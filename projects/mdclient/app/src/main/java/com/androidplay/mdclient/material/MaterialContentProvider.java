package com.androidplay.mdclient.material;

import android.graphics.Bitmap;
import android.net.Uri;

import java.io.IOException;

/** A small, UI-independent contract for material pages. Page indexes are zero-based. */
public interface MaterialContentProvider {
    String EVENT_PAGE_CHANGED = "PAGE_CHANGED";

    void open(Uri uri) throws IOException;

    void close();

    boolean isOpen();

    int pageCount();

    int currentPage();

    void next();

    void previous();

    void jump(int pageIndex);

    /** The returned bitmap belongs to the caller and must be recycled when no longer used. */
    Bitmap renderPageBitmap(int pageIndex, int width, int height) throws IOException;

    Bitmap renderPageBitmap(int width, int height) throws IOException;

    /** PdfRenderer has no text extraction API; implementations may return an empty string. */
    String extractPageText(int pageIndex) throws IOException;

    boolean needsOcr(int pageIndex);

    boolean needsOcr();

    void setPageChangedCallback(PageChangedCallback callback);

    interface PageChangedCallback {
        void onPageChanged(int pageIndex, int pageCount);
    }
}
