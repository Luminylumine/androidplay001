package com.akasha.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen multi-image viewer.
 *
 * Gesture contract (fixed this round):
 *   - State machine mode ∈ {NONE, DRAG, ZOOM}.
 *   - DRAG / ZOOM both mutate the same Matrix using **relative post-ops**
 *     (postTranslate / postScale with pivot).  We never rebuild the matrix
 *     from (scale, transX, transY) primitives because the two coordinate
 *     systems don't line up (that caused "pinch jitters" and "drag does
 *     nothing" in the previous version — see Experience 616567 / 665122).
 *   - After each gesture we also clamp scale back into [fitScale, 6*fitScale]
 *     and re-center inside the viewport if the zoomed bitmap would leave
 *     blank edges (otherwise the user can pan the image out of view).
 *
 * UI spec (as requested):
 *   1. Default: image fit-full-screen to the viewport.
 *      Two-finger pinch scales between fit → 6× fit.
 *   2. No ✕ close button — a quick single-tap closes.
 *   3. At fit-scale only, a quick horizontal swipe switches the image
 *      (left-swipe → next, right-swipe → previous).  Once zoomed in the
 *      same one-finger motion does free pan instead.
 */
public class ImageViewerActivity extends Activity {

    public static final String KEY_FILES = "files";
    public static final String KEY_INDEX = "index";

    private static final int MODE_NONE = 0;
    private static final int MODE_DRAG = 1;
    private static final int MODE_ZOOM = 2;

    private ImageView iv;
    private final Matrix matrix = new Matrix();

    private float fitScale = 1f;          // scale at which bitmap fits viewport
    private float minScale = 1f;          // == fitScale (updated per image)
    private float maxScale = 6f;          // 6 * fitScale (updated per image)

    private int mode = MODE_NONE;
    private float lastX, lastY;           // DRAG mode reference
    private float oldDist;                // ZOOM mode reference
    private float midX, midY;             // ZOOM mode pivot reference

    private List<String> files = new ArrayList<>();
    private int idx = 0;
    private Bitmap currentBmp;
    private int bmpW, bmpH;
    private int viewW, viewH;

    // ---- single-tap detection ----
    private long downT;
    private float startX, startY;
    private boolean tapMoved;
    private static final long TAP_MS = 300;
    private static final float TAP_PX = 16f;

    // ---- swipe-to-next detection (meaningful only at fit scale) ----
    private static final float SWIPE_PX = 60f;          // lowered for sensitivity
    private static final float SWIPE_QUICK_PX = 40f;    // short-quick-fling threshold
    private static final long SWIPE_QUICK_MS = 250L;    // duration for "quick"
    private static final float SWIPE_RATIO = 1.1f;      // horizontal dominance (lowered)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);
        iv = (ImageView) findViewById(R.id.ivViewer);

        if (getIntent() != null) {
            ArrayList<String> f = getIntent().getStringArrayListExtra(KEY_FILES);
            if (f != null && !f.isEmpty()) {
                files = f;
                idx = Math.max(0, Math.min(files.size() - 1,
                        getIntent().getIntExtra(KEY_INDEX, 0)));
            } else {
                String single = getIntent().getStringExtra("path");
                if (single != null) {
                    files = new ArrayList<>();
                    files.add(single);
                    idx = 0;
                }
            }
        }

        iv.post(new Runnable() {
            @Override
            public void run() {
                viewW = iv.getWidth();
                viewH = iv.getHeight();
                show(idx);
            }
        });

        iv.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        mode = MODE_DRAG;
                        lastX = ev.getX();
                        lastY = ev.getY();
                        startX = ev.getX();
                        startY = ev.getY();
                        downT = System.currentTimeMillis();
                        tapMoved = false;
                        return true;
                    }
                    case MotionEvent.ACTION_POINTER_DOWN: {
                        // second finger landed → switch into ZOOM
                        if (ev.getPointerCount() >= 2) {
                            mode = MODE_ZOOM;
                            oldDist = spacing(ev);
                            midpoint(ev);
                        }
                        tapMoved = true;
                        return true;
                    }
                    case MotionEvent.ACTION_POINTER_UP: {
                        // one finger lifted; if exactly one remains, fall back to DRAG
                        // and reset drag anchor to that finger so we don't compute
                        // delta from the old two-finger frame.
                        int remain = ev.getPointerCount() - 1;
                        if (remain == 1) {
                            mode = MODE_DRAG;
                            int kept = 0;
                            int upIdx = ev.getActionIndex();
                            for (int i = 0; i < ev.getPointerCount(); i++) {
                                if (i != upIdx) { kept = i; break; }
                            }
                            lastX = ev.getX(kept);
                            lastY = ev.getY(kept);
                        } else if (remain >= 2) {
                            mode = MODE_ZOOM;
                            oldDist = spacing(ev);
                            midpoint(ev);
                        } else {
                            mode = MODE_NONE;
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        if (mode == MODE_ZOOM && ev.getPointerCount() >= 2) {
                            float newDist = spacing(ev);
                            if (newDist > 10f) {
                                float factor = newDist / oldDist;
                                if (Float.isNaN(factor) || Float.isInfinite(factor)) factor = 1f;
                                // clamp factor per-frame so sensor noise doesn't
                                // blow up the scale.
                                factor = Math.max(0.85f, Math.min(1.18f, factor));
                                // apply matrix-relative scale around the two-finger midpoint.
                                matrix.postScale(factor, factor, midX, midY);
                                applyMatrix();
                                oldDist = newDist;
                                midpoint(ev);
                            }
                            tapMoved = true;
                            return true;
                        }
                        if (mode == MODE_DRAG && ev.getPointerCount() == 1) {
                            float dx = ev.getX() - lastX;
                            float dy = ev.getY() - lastY;
                            if (Math.abs(dx) > 0 || Math.abs(dy) > 0) {
                                matrix.postTranslate(dx, dy);
                                applyMatrix();
                            }
                            lastX = ev.getX();
                            lastY = ev.getY();
                            if (Math.abs(ev.getX() - startX) > TAP_PX
                                    || Math.abs(ev.getY() - startY) > TAP_PX) {
                                tapMoved = true;
                            }
                            // NOTE: swipe-to-next is intentionally evaluated only on
                            // ACTION_UP (finger-up).  Triggering during ACTION_MOVE
                            // caused "two pages skip in one swipe" because the new
                            // bitmap is also at fit-scale and the remaining drag
                            // distance still fires the threshold a second time.
                            return true;
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        // after gesture, clamp scale to [minScale, maxScale] and
                        // re-center (keeps zoomed bitmap within the viewport and
                        // avoids "scale jumps" when gestures end).
                        boolean wasAtFit = clampScaleAndRecenter();
                        long dt = System.currentTimeMillis() - downT;
                        float totalDx = ev.getX() - startX;
                        float totalDy = ev.getY() - startY;
                        if (!tapMoved && dt < TAP_MS
                                && Math.hypot(totalDx, totalDy) < TAP_PX * 2) {
                            finish(); // single tap closes (req 2)
                            return true;
                        }
                        // swipe to next/prev only when at fit-scale (req 3)
                        if (wasAtFit && mode != MODE_ZOOM) {
                            boolean horizontal = Math.abs(totalDx) > SWIPE_RATIO * Math.abs(totalDy);
                            boolean reached = false;
                            if (dt <= SWIPE_QUICK_MS && Math.abs(totalDx) >= SWIPE_QUICK_PX) {
                                reached = true;
                            } else if (Math.abs(totalDx) >= SWIPE_PX) {
                                reached = true;
                            }
                            if (horizontal && reached) {
                                if (totalDx < 0 && idx < files.size() - 1) {
                                    show(idx + 1);
                                } else if (totalDx > 0 && idx > 0) {
                                    show(idx - 1);
                                }
                            }
                        }
                        mode = MODE_NONE;
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        mode = MODE_NONE;
                        clampScaleAndRecenter();
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentBmp != null && !currentBmp.isRecycled()) currentBmp.recycle();
    }

    // -------- image loading --------

    private void show(int newIdx) {
        if (newIdx < 0 || newIdx >= files.size()) return;
        idx = newIdx;
        if (currentBmp != null && !currentBmp.isRecycled()) currentBmp.recycle();
        currentBmp = null;

        String path = files.get(idx);
        int targetPx = Math.max(viewW, viewH);
        if (targetPx <= 0) targetPx = 2048;
        Bitmap b = ExperiencePoolActivity.decodeSampled(path, targetPx);
        if (b == null) {
            Toast.makeText(this, "无法打开图片", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        currentBmp = b;
        bmpW = b.getWidth();
        bmpH = b.getHeight();
        iv.setImageBitmap(b);

        // Fit-center the bitmap into the viewport.
        float sx = viewW > 0 ? (float) viewW / bmpW : 1f;
        float sy = viewH > 0 ? (float) viewH / bmpH : 1f;
        fitScale = Math.min(sx, sy);
        minScale = fitScale;
        maxScale = fitScale * 6f;
        matrix.reset();
        matrix.postScale(fitScale, fitScale);
        float scaledW = bmpW * fitScale;
        float scaledH = bmpH * fitScale;
        matrix.postTranslate((viewW - scaledW) * 0.5f, (viewH - scaledH) * 0.5f);
        iv.setImageMatrix(matrix);
        mode = MODE_NONE;
    }

    private void applyMatrix() {
        iv.setImageMatrix(matrix);
    }

    /**
     * Reconciles the matrix with our scale bounds and viewport.
     *   - If current effective scale < minScale → zoom back up to fit.
     *   - If > maxScale → shrink back to maxScale (keeping center stable).
     *   - When the bitmap is smaller than the viewport in either axis,
     *     translate it back to centered so there's no "drifting" whitespace.
     *   - When zoomed in and the bitmap would leave blank edges, pull it back
     *     into the viewport so the user can't pan into a black void.
     *
     * @return true if, after clamping, the bitmap is still at fit-scale
     *         (used by ACTION_UP to decide whether to allow swipe-to-next).
     */
    private boolean clampScaleAndRecenter() {
        float[] v = new float[9];
        matrix.getValues(v);
        float s = v[Matrix.MSCALE_X];
        if (Float.compare(s, 0f) == 0) s = fitScale;

        if (s < minScale) {
            // under-scale: snap back to fit-center exactly.
            matrix.reset();
            matrix.postScale(fitScale, fitScale);
            float sw = bmpW * fitScale;
            float sh = bmpH * fitScale;
            matrix.postTranslate((viewW - sw) * 0.5f, (viewH - sh) * 0.5f);
            applyMatrix();
            return true;
        }
        if (s > maxScale) {
            float k = maxScale / s;
            matrix.postScale(k, k, viewW * 0.5f, viewH * 0.5f);
        }

        // re-read after any scale adjustment
        matrix.getValues(v);
        s = v[Matrix.MSCALE_X];
        float tx = v[Matrix.MTRANS_X];
        float ty = v[Matrix.MTRANS_Y];
        float scaledW = bmpW * s;
        float scaledH = bmpH * s;

        if (scaledW <= viewW) {
            // bitmap fits horizontally → center it (no drift)
            float wantX = (viewW - scaledW) * 0.5f;
            if (Math.abs(wantX - tx) > 0.5f) {
                matrix.postTranslate(wantX - tx, 0);
            }
        } else {
            // zoomed wider than viewport: clamp so neither left nor right edge
            // leaves a blank strip
            if (tx > 0) matrix.postTranslate(-tx, 0);
            else if (tx + scaledW < viewW) matrix.postTranslate(viewW - (tx + scaledW), 0);
        }
        if (scaledH <= viewH) {
            float wantY = (viewH - scaledH) * 0.5f;
            if (Math.abs(wantY - ty) > 0.5f) {
                matrix.postTranslate(0, wantY - ty);
            }
        } else {
            if (ty > 0) matrix.postTranslate(0, -ty);
            else if (ty + scaledH < viewH) matrix.postTranslate(0, viewH - (ty + scaledH));
        }

        applyMatrix();
        // "at fit" means effective scale is essentially still fitScale
        return Math.abs(s - fitScale) / Math.max(0.0001f, fitScale) < 0.04f;
    }

    /**
     * Reads the current matrix scale without mutating it and decides whether
     * the bitmap is effectively at the "fit-full-screen" size.
     * Used during ACTION_MOVE to decide if a drag is a page swipe vs a pan.
     */
    private boolean isAtFitScaleNow() {
        float[] v = new float[9];
        matrix.getValues(v);
        float s = v[Matrix.MSCALE_X];
        if (Float.compare(s, 0f) == 0) s = fitScale;
        return Math.abs(s - fitScale) / Math.max(0.0001f, fitScale) < 0.04f;
    }

    // -------- gesture math helpers --------

    private float spacing(MotionEvent ev) {
        float dx = ev.getX(0) - ev.getX(1);
        float dy = ev.getY(0) - ev.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private void midpoint(MotionEvent ev) {
        midX = (ev.getX(0) + ev.getX(1)) * 0.5f;
        midY = (ev.getY(0) + ev.getY(1)) * 0.5f;
    }
}
