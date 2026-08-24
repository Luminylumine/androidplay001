package com.sysmon.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * htop 风格色块条：按占用率分色（绿→黄→红），块数固定。
 */
public class BarView extends View {

    private static final int BLOCKS = 24;
    private static final int COLOR_LOW = 0xFF00E676;
    private static final int COLOR_MID = 0xFFFFD54F;
    private static final int COLOR_HIGH = 0xFFFF5252;
    private static final int COLOR_BG = 0xFF1A1A22;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float value = 0f; // 0-100

    public BarView(Context context) {
        super(context);
    }

    public void setValue(float v) {
        float nv = Float.isNaN(v) ? 0f : Math.max(0, Math.min(100, v));
        if (nv != value) {
            value = nv;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = dp(10);
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float gap = dp(1.5f);
        float bw = (w - gap * (BLOCKS - 1)) / BLOCKS;
        int filled = Math.round(value / 100f * BLOCKS);

        RectF r = new RectF();
        for (int i = 0; i < BLOCKS; i++) {
            float x = i * (bw + gap);
            r.set(x, 0, x + bw, h);
            if (i < filled) {
                float pct = (i + 1) / (float) BLOCKS * 100f;
                paint.setColor(pct <= 50 ? COLOR_LOW : (pct <= 80 ? COLOR_MID : COLOR_HIGH));
            } else {
                paint.setColor(COLOR_BG);
            }
            canvas.drawRoundRect(r, dp(1), dp(1), paint);
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
