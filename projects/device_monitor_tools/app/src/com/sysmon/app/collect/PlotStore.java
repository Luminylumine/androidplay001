package com.sysmon.app.collect;

import android.content.Context;

import com.sysmon.app.SysLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 单个绘图子选项的历史数据存储（时间戳 ms + 数值 float，升序）。
 * 双约束裁剪：
 *  1) 保留采样时间：丢弃超过时间窗的最旧数据（时间窗缩短）；
 *  2) 最多保留采样点数目：超限时对整段数据做真值等间隔降采样
 *     （每 step 个保留 1 个，从最新端开始取，保留时间跨度不变）。
 * 持久化到 app 私有目录 plot_{key}.bin，重启恢复。
 * 全部方法 synchronized，采集线程与 UI 线程共享。
 */
public final class PlotStore {

    private static final int MAGIC = 0x53504C31; // "SPL1"

    private final String key;
    private final File file;

    private long[] ts = new long[1024];
    private float[] val = new float[1024];
    private int n = 0;
    private boolean loaded = false;
    private boolean dirty = false;

    public PlotStore(Context ctx, String key) {
        this.key = key;
        this.file = new File(ctx.getFilesDir(), "plot_" + key + ".bin");
    }

    public String key() { return key; }

    /** 追加一个有效采样点（调用方已保证数据合法）。 */
    public synchronized void append(long tMs, float v) {
        load();
        if (n == ts.length) {
            int cap = ts.length * 2;
            long[] nts = new long[cap];
            float[] nv = new float[cap];
            System.arraycopy(ts, 0, nts, 0, n);
            System.arraycopy(val, 0, nv, 0, n);
            ts = nts;
            val = nv;
        }
        ts[n] = tMs;
        val[n] = v;
        n++;
        dirty = true;
    }

    /** 双约束裁剪：时间窗 + 最大点数（等间隔降采样）。 */
    public synchronized void trim(long nowMs, long retentionSec, int maxPoints) {
        // 1) 时间窗
        if (retentionSec > 0 && n > 0) {
            long limit = nowMs - retentionSec * 1000L;
            int cut = 0;
            while (cut < n && ts[cut] < limit) cut++;
            if (cut > 0) {
                System.arraycopy(ts, cut, ts, 0, n - cut);
                System.arraycopy(val, cut, val, 0, n - cut);
                n -= cut;
            }
        }
        // 2) 最大点数：每 step 个保留 1 个（从最新端取，保持跨度）
        if (maxPoints > 0 && n > maxPoints) {
            int step = (int) Math.ceil((double) n / maxPoints);
            int m = 0;
            long[] nts = new long[maxPoints];
            float[] nv = new float[maxPoints];
            for (int i = n - 1; i >= 0; i -= step) {
                nts[m] = ts[i];
                nv[m] = val[i];
                m++;
            }
            // 反转为升序
            for (int i = 0, j = m - 1; i < j; i++, j--) {
                long tt = nts[i]; nts[i] = nts[j]; nts[j] = tt;
                float vv = nv[i]; nv[i] = nv[j]; nv[j] = vv;
            }
            ts = nts;
            val = nv;
            n = m;
        }
    }

    public synchronized void clear() {
        n = 0;
        loaded = true;
        dirty = false;
        if (file.exists() && !file.delete()) {
            SysLog.w("plotstore: delete failed " + key);
        }
    }

    public synchronized int count() { return n; }

    /** 把数据拷入调用方缓冲，返回点数（<= buf 容量）。 */
    public synchronized int snapshot(long[] tsBuf, float[] valBuf) {
        int c = Math.min(n, Math.min(tsBuf.length, valBuf.length));
        System.arraycopy(ts, 0, tsBuf, 0, c);
        System.arraycopy(val, 0, valBuf, 0, c);
        return c;
    }

    // ---------- 持久化 ----------

    public synchronized void load() {
        if (loaded) return;
        loaded = true;
        if (!file.exists()) return;
        DataInputStream in = null;
        try {
            in = new DataInputStream(new FileInputStream(file));
            if (in.readInt() != MAGIC) throw new IOException("bad magic");
            int m = in.readInt();
            if (m < 0 || m > 5_000_000) throw new IOException("bad count");
            long[] nts = new long[m];
            float[] nv = new float[m];
            for (int i = 0; i < m; i++) {
                nts[i] = in.readLong();
                nv[i] = in.readFloat();
            }
            ts = nts;
            val = nv;
            n = m;
        } catch (Throwable t) {
            SysLog.w("plotstore load " + key + ": " + t);
            n = 0;
        } finally {
            close(in);
        }
    }

    public synchronized void save() {
        if (!dirty) return;
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new FileOutputStream(tmp));
            out.writeInt(MAGIC);
            out.writeInt(n);
            for (int i = 0; i < n; i++) {
                out.writeLong(ts[i]);
                out.writeFloat(val[i]);
            }
            out.flush();
            if (file.exists() && !file.delete()) {
                SysLog.w("plotstore save: delete old failed " + key);
            }
            if (!tmp.renameTo(file)) {
                SysLog.w("plotstore save: rename failed " + key);
            } else {
                dirty = false;
            }
        } catch (Throwable t) {
            SysLog.w("plotstore save " + key + ": " + t);
            tmp.delete();
        } finally {
            close(out);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (IOException ignored) {}
        }
    }
}
