package com.androidplay.mdclient.material;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.IOException;

/** Material provider backed exclusively by Android's platform PdfRenderer. */
public final class PdfMaterialController implements MaterialContentProvider {
    private static final String PREFS_NAME = "mdclient_material";
    private static final String URI_KEY = "pdf_uri";

    private final Context context;
    private final SharedPreferences preferences;
    private ParcelFileDescriptor fileDescriptor;
    private PdfRenderer renderer;
    private int currentPage = -1;
    private PageChangedCallback pageChangedCallback;
    private final java.util.Map<Integer, String> pageTextCache = new java.util.HashMap<>();

    public PdfMaterialController(Context context) {
        if (context == null) throw new NullPointerException("context");
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Opens a URI previously returned by ACTION_OPEN_DOCUMENT. */
    @Override
    public synchronized void open(Uri uri) throws IOException {
        if (uri == null) throw new NullPointerException("uri");
        if ("content".equals(uri.getScheme())) {
            try {
                context.getContentResolver().takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Some providers allow reading but do not offer persistable permissions.
            }
        }

        ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r");
        if (descriptor == null) throw new IOException("Unable to open PDF: " + uri);
        PdfRenderer newRenderer;
        try {
            newRenderer = new PdfRenderer(descriptor);
        } catch (RuntimeException e) {
            try { descriptor.close(); } catch (IOException ignored) {}
            throw new IOException("Unable to read PDF: " + uri, e);
        }

        close();
        fileDescriptor = descriptor;
        renderer = newRenderer;
        pageTextCache.clear();
        currentPage = renderer.getPageCount() == 0 ? -1 : 0;
        preferences.edit().putString(URI_KEY, uri.toString()).apply();
        notifyPageChanged();
    }

    /** Restores and opens the last persisted SAF URI, if there is one. */
    public synchronized boolean openPersisted() throws IOException {
        String value = preferences.getString(URI_KEY, null);
        if (value == null) return false;
        open(Uri.parse(value));
        return true;
    }

    /** Removes the remembered URI; this does not revoke the OS permission. */
    public synchronized void forgetPersistedUri() {
        preferences.edit().remove(URI_KEY).apply();
    }

    @Override
    public synchronized void close() {
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
        if (fileDescriptor != null) {
            try { fileDescriptor.close(); } catch (IOException ignored) {}
            fileDescriptor = null;
        }
        currentPage = -1;
        pageTextCache.clear();
    }

    @Override public synchronized boolean isOpen() { return renderer != null; }

    @Override public synchronized int pageCount() { return renderer == null ? 0 : renderer.getPageCount(); }

    @Override public synchronized int currentPage() { return currentPage; }

    @Override public synchronized void next() {
        requireOpen();
        if (currentPage + 1 < renderer.getPageCount()) {
            currentPage++;
            notifyPageChanged();
        }
    }

    @Override public synchronized void previous() {
        requireOpen();
        if (currentPage > 0) {
            currentPage--;
            notifyPageChanged();
        }
    }

    @Override public synchronized void jump(int pageIndex) {
        requireOpen();
        checkPage(pageIndex);
        if (currentPage != pageIndex) {
            currentPage = pageIndex;
            notifyPageChanged();
        }
    }

    @Override public synchronized Bitmap renderPageBitmap(int width, int height) throws IOException {
        requireOpen();
        return renderPageBitmap(currentPage, width, height);
    }

    @Override public synchronized Bitmap renderPageBitmap(int pageIndex, int width, int height)
            throws IOException {
        requireOpen();
        checkPage(pageIndex);
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Bitmap dimensions must be positive");
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        PdfRenderer.Page page = null;
        try {
            page = renderer.openPage(pageIndex);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        } catch (RuntimeException e) {
            bitmap.recycle();
            throw new IOException("Unable to render PDF page " + pageIndex, e);
        } finally {
            if (page != null) page.close();
        }
        return bitmap;
    }

    @Override public synchronized String extractPageText(int pageIndex) throws IOException {
        requireOpen();
        checkPage(pageIndex);
        if (pageTextCache.containsKey(pageIndex)) return pageTextCache.get(pageIndex);
        String text = AndroidxPdfTextExtractor.extract(context, rendererUri(), pageIndex);
        pageTextCache.put(pageIndex, text == null ? "" : text);
        return pageTextCache.get(pageIndex);
    }

    @Override public synchronized boolean needsOcr(int pageIndex) {
        requireOpen();
        checkPage(pageIndex);
        try { return extractPageText(pageIndex).trim().isEmpty(); }
        catch (IOException ignored) { return true; }
    }

    @Override public synchronized boolean needsOcr() { return renderer != null && renderer.getPageCount() > 0; }

    @Override public synchronized void setPageChangedCallback(PageChangedCallback callback) {
        pageChangedCallback = callback;
    }

    private void requireOpen() {
        if (renderer == null) throw new IllegalStateException("No PDF is open");
    }

    private void checkPage(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) {
            throw new IndexOutOfBoundsException("pageIndex=" + pageIndex);
        }
    }

    private void notifyPageChanged() {
        if (pageChangedCallback != null && currentPage >= 0) {
            pageChangedCallback.onPageChanged(currentPage, renderer.getPageCount());
        }
    }

    private Uri rendererUri() {
        String value = preferences.getString(URI_KEY, null);
        return value == null ? null : Uri.parse(value);
    }
}
