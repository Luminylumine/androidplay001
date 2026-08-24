package com.sysmon.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.Calendar;
import java.util.Locale;

/**
 * XY 绘图视图（时间序列）。
 * - 绘图区近正方形（宽≈高）
 * - 图名（含单位）标在图的下方
 * - 数据范围映射到绘图区 [5%, 95%]（x/y 均不延伸到坐标轴）
 * - X=时间（单位自适应 秒/分/时/天），Y=数据（自动定标）
 * - 轴刻度标签倾斜 45°（向右上），右端锚定在对应格点处
 * - 显示点降采样：按可用像素宽度，每 step 个点绘制 1 个（不影响曲线形态）
 * - 相邻点时间间隔 > 10×采样周期 → 断线（数据缺失不画 0）
 */
public class PlotView extends View {

    private static final int C_AXIS  = 0xFF888899;
    private static final int C_GRID  = 0xFF23232E;
    private static final int C_CROSS = 0xFF3A3A48;
    private static final int C_LINE  = 0xFF4DD0E1;
    private static final int C_TXT   = 0xFFAAAAAA;
    private static final int C_TITLE = 0xFFD0D0D0;

    private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCross = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pPoint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTitle = new Paint(Paint.ANTI_ALIAS_FLAG);

    // 数据
    private long[] ts = new long[0];
    private float[] val = new float[0];
    private int n = 0;
    private int nominalMs = 1000;

    // 样式（独立 x/y）
    private String title = "";
    private boolean xTicks, yTicks, xGrid, yGrid, xCross, yCross;
    private int axisW = 4, pointSz = 3, gridW = 2;

    private final int mLeft, mRight, mTop, mLabelBottom, mTitleBottom;

    public PlotView(Context ctx) {
        super(ctx);
        float d = getResources().getDisplayMetrics().density;
        mLeft = (int) (58 * d);      // y 标签（45° 倾斜后）
        mRight = (int) (10 * d);
        mTop = (int) (10 * d);
        mLabelBottom = (int) (64 * d);  // x 标签（45° 倾斜后）
        mTitleBottom = (int) (26 * d);  // 图名

        pGrid.setStyle(Paint.Style.STROKE);
        pAxis.setStyle(Paint.Style.STROKE);
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(2f * d);
        pPoint.setStyle(Paint.Style.FILL);
        pText.setTextSize(11f * d);
        pText.setTypeface(android.graphics.Typeface.MONOSPACE);
        pTitle.setTextSize(12f * d);
        pTitle.setTypeface(android.graphics.Typeface.MONOSPACE);
        pTitle.setTextAlign(Paint.Align.CENTER);
        pText.setTextAlign(Paint.Align.RIGHT);
    }

    /** 更新数据与样式（调用方保证已在主线程）。 */
    public void setData(long[] ts, float[] val, int n, int nominalMs) {
        this.ts = ts;
        this.val = val;
        this.n = n;
        this.nominalMs = nominalMs > 0 ? nominalMs : 1000;
    }

    public void setStyle(String title,
                         boolean xTicks, boolean yTicks,
                         boolean xGrid, boolean yGrid,
                         boolean xCross, boolean yCross,
                         int axisW, int pointSz, int gridW) {
        this.title = title;
        this.xTicks = xTicks; this.yTicks = yTicks;
        this.xGrid = xGrid; this.yGrid = yGrid;
        this.xCross = xCross; this.yCross = yCross;
        this.axisW = Math.max(1, axisW);
        this.pointSz = Math.max(1, pointSz);
        this.gridW = Math.max(1, gridW);
    }

    private int plotSize() {
        return getWidth() - mLeft - mRight;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int plot = Math.max(100, w - mLeft - mRight);
        setMeasuredDimension(w, mTop + plot + mLabelBottom + mTitleBottom);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float L = mLeft, T = mTop, S = plotSize();
        if (S <= 0) return;
        float B = T + S;

        pGrid.setStrokeWidth(gridW);
        pGrid.setColor(C_GRID);
        pAxis.setStrokeWidth(axisW);
        pAxis.setColor(C_AXIS);
        pCross.setColor(C_CROSS);
        pLine.setColor(C_LINE);
        pPoint.setColor(C_LINE);
        pText.setColor(C_TXT);
        pTitle.setColor(C_TITLE);

        // 图名（含单位）在图下方
        canvas.drawText(title, L + S / 2f, B + mLabelBottom + mTitleBottom - 6, pTitle);

        if (n == 0) {
            // 空数据：只画坐标轴 + 提示
            drawAxis(canvas, L, T, S);
            Paint tmp = new Paint(pText);
            tmp.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("无数据", L + S / 2f, T + S / 2f, tmp);
            return;
        }

        // ---- 数据范围 ----
        long t0 = ts[0], t1 = ts[n - 1];
        if (t1 <= t0) t1 = t0 + 1000;
        float ymin = Float.MAX_VALUE, ymax = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (val[i] < ymin) ymin = val[i];
            if (val[i] > ymax) ymax = val[i];
        }
        if (ymax - ymin < 1e-6f) {
            float pad = Math.max(1f, Math.abs(ymin) * 0.05f);
            ymin -= pad;
            ymax += pad;
        }
        final float PAD = 0.05f; // 数据占绘图区 5%~95%

        // ---- 坐标刻度 ----
        long stepX = pickTimeStep(t1 - t0);
        long[] xTicksV = new long[16];
        int nx = 0;
        for (long tk = (t0 / stepX + 1) * stepX; tk <= t1 && nx < 16; tk += stepX) xTicksV[nx++] = tk;

        float rangeY = ymax - ymin;
        float stepY = niceStep(rangeY / 5f);
        float[] yTicksV = new float[16];
        int ny = 0;
        for (float tk = (float) Math.ceil(ymin / stepY) * stepY; tk <= ymax && ny < 16; tk += stepY) yTicksV[ny++] = tk;

        // ---- 网格 ----
        for (int i = 0; i < nx; i++) {
            float x = xmap(t0, t1, xTicksV[i], L, S, PAD);
            if (xGrid) canvas.drawLine(x, T, x, B, pGrid);
        }
        for (int i = 0; i < ny; i++) {
            float y = ymap(ymin, ymax, yTicksV[i], T, S, PAD);
            if (yGrid) canvas.drawLine(L, y, L + S, y, pGrid);
        }
        // ---- 格点（网格交叉点），x/y 独立 ----
        float cr = gridW * 1.2f;
        if (xCross) {
            for (int i = 0; i < nx; i++) {
                float x = xmap(t0, t1, xTicksV[i], L, S, PAD);
                for (int j = 0; j < ny; j++) {
                    float y = ymap(ymin, ymax, yTicksV[j], T, S, PAD);
                    canvas.drawRect(x - cr, y - cr, x + cr, y + cr, pCross);
                }
            }
        }
        if (yCross) {
            for (int i = 0; i < ny; i++) {
                float y = ymap(ymin, ymax, yTicksV[i], T, S, PAD);
                for (int j = 0; j < nx; j++) {
                    float x = xmap(t0, t1, xTicksV[j], L, S, PAD);
                    canvas.drawRect(x - cr, y - cr, x + cr, y + cr, pCross);
                }
            }
        }

        // ---- 坐标轴（左 + 底） ----
        drawAxis(canvas, L, T, S);

        // ---- 刻度打点 + 45° 倾斜标签 ----
        float tickLen = axisW + 4f;
        for (int i = 0; i < nx; i++) {
            float x = xmap(t0, t1, xTicksV[i], L, S, PAD);
            if (xTicks) canvas.drawLine(x, B, x, B + tickLen, pAxis);
            drawRotatedLabel(canvas, fmtTime(xTicksV[i], stepX), x + 5, B + 16);
        }
        for (int i = 0; i < ny; i++) {
            float y = ymap(ymin, ymax, yTicksV[i], T, S, PAD);
            if (yTicks) canvas.drawLine(L - tickLen, y, L, y, pAxis);
            drawRotatedLabel(canvas, fmtNum(yTicksV[i]), L - 6, y + 10);
        }

        // ---- 数据：按像素宽度降采样 + 断线 ----
        int pxAvail = (int) (S * (1 - 2 * PAD));
        int step = Math.max(1, (int) Math.ceil(n / (float) Math.max(1, pxAvail)));

        boolean hasPrev = false;
        long prevT = 0;
        float prevX = 0, prevY = 0;
        for (int i = 0; i < n; i++) {
            if (i % step != 0 && i != n - 1) continue; // 真值等间隔 + 像素门控
            float x = xmap(t0, t1, ts[i], L, S, PAD);
            float y = ymap(ymin, ymax, val[i], T, S, PAD);
            if (hasPrev) {
                // 时间间隔 > 10×采样周期 → 数据缺失，断线
                if (ts[i] - prevT <= nominalMs * 10) {
                    canvas.drawLine(prevX, prevY, x, y, pLine);
                }
            }
            float h = pointSz / 2f;
            canvas.drawRect(x - h, y - h, x + h, y + h, pPoint);
            hasPrev = true;
            prevT = ts[i];
            prevX = x;
            prevY = y;
        }
    }

    private void drawAxis(Canvas canvas, float L, float T, float S) {
        canvas.drawLine(L, T, L, T + S, pAxis);
        canvas.drawLine(L, T + S, L + S, T + S, pAxis);
    }

    /** 45° 倾斜（向右上）标签：右端锚定在锚点，文本向左下延伸。 */
    private void drawRotatedLabel(Canvas canvas, String s, float ax, float ay) {
        canvas.save();
        canvas.rotate(-45, ax, ay);
        canvas.drawText(s, ax, ay, pText);
        canvas.restore();
    }

    private float xmap(long t0, long t1, long t, float L, float S, float pad) {
        return L + S * (pad + (t - t0) * (1 - 2 * pad) / (t1 - t0));
    }

    private float ymap(float ymin, float ymax, float v, float T, float S, float pad) {
        return T + S - S * (pad + (v - ymin) * (1 - 2 * pad) / (ymax - ymin));
    }

    /** 时间步长：按跨度选 秒/分/时/天 中合适档位。 */
    private static long pickTimeStep(long spanMs) {
        long[] cands = {
                1000, 2000, 5000, 10000, 15000, 30000,
                60000, 120000, 300000, 600000, 900000, 1800000, 3600000,
                7200000L, 10800000L, 21600000L, 43200000L,
                86400000L, 172800000L, 604800000L, 2592000000L
        };
        for (long c : cands) {
            if (spanMs / c <= 7) return c;
        }
        return cands[cands.length - 1];
    }

    private static String fmtTime(long ms, long step) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        int H = c.get(Calendar.HOUR_OF_DAY);
        int M = c.get(Calendar.MINUTE);
        int S = c.get(Calendar.SECOND);
        int MO = c.get(Calendar.MONTH) + 1;
        int D = c.get(Calendar.DAY_OF_MONTH);
        if (step < 60000) return String.format(Locale.US, "%02d:%02d:%02d", H, M, S);
        if (step < 86400000L) return String.format(Locale.US, "%02d-%02d %02d:00", MO, D, H);
        return String.format(Locale.US, "%02d-%02d", MO, D);
    }

    /** Y 轴"漂亮"步长：1/2/5 × 10^k。 */
    private static float niceStep(float raw) {
        if (raw <= 0) return 1f;
        float mag = (float) Math.pow(10, Math.floor(Math.log10(raw)));
        float[] mults = {1f, 2f, 5f, 10f};
        for (float m : mults) {
            if (raw / mag <= m) return m * mag;
        }
        return 10f * mag;
    }

    private static String fmtNum(float v) {
        float a = Math.abs(v);
        if (a >= 100) return String.format(Locale.US, "%.0f", v);
        if (a >= 10) return String.format(Locale.US, "%.1f", v);
        return String.format(Locale.US, "%.2f", v);
    }
}
