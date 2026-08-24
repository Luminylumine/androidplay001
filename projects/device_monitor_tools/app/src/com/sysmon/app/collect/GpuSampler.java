package com.sysmon.app.collect;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import com.sysmon.app.SysLog;

/**
 * GPU 数据采样器。
 * 支持：
 * - devfreq 路径读取 GPU 频率（Mali/Adreno 通用）
 * - KGSL 路径读取 GPU 使用率（Adreno 专用）
 * - 通过频率/最大频率估算相对占用率
 */
public class GpuSampler {

    // 候选路径列表，按优先级排序
    private static final String[] GpuFreqPaths = {
            "/sys/class/devfreq/gpufreq/cur_freq",
            "/sys/class/devfreq/ff9a0000.gpu/cur_freq",
            "/sys/class/devfreq/13000000.mali/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
    };

    private static final String[] GpuMaxFreqPaths = {
            "/sys/class/devfreq/gpufreq/max_freq",
            "/sys/class/devfreq/ff9a0000.gpu/max_freq",
            "/sys/class/devfreq/13000000.mali/max_freq",
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
    };

    private static final String[] GpuBusyPaths = {
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
    };

    private static final String[] GpuBusyTotalPaths = {
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
    };

    // 已探测到的路径（缓存）
    private String freqPath = null;
    private String maxFreqPath = null;
    private String busyPath = null;
    private String busyTotalPath = null;
    private long maxFreqHz = 0;
    private boolean probed = false;

    public GpuSampler() {}

    /** 采样 GPU 数据，写入 SysData。 */
    public void sample(SysData d) {
        if (!probed) probe();

        // 1. 读取 GPU 频率
        Long freq = readFreq();
        if (freq != null && freq > 0) {
            d.gpuFreq = (int) (freq / 1_000_000); // Hz → MHz
        }

        // 2. 读取 GPU 使用率（优先使用硬件 busy 统计）
        Float usage = readGpuUsage();
        if (usage != null && !Float.isNaN(usage)) {
            d.gpuUtil = usage;
        } else if (freq != null && maxFreqHz > 0) {
            // 3. 回退：通过频率估算相对占用率
            d.gpuUtil = freq * 100f / maxFreqHz;
        }
    }

    /** 探测可用的 GPU 路径。 */
    private void probe() {
        probed = true;

        // 探测频率路径
        for (String p : GpuFreqPaths) {
            if (fileReadable(p)) {
                freqPath = p;
                SysLog.w("GpuSampler: found freq path=" + p);
                break;
            }
        }

        // 探测最大频率路径
        for (String p : GpuMaxFreqPaths) {
            if (fileReadable(p)) {
                maxFreqPath = p;
                try {
                    maxFreqHz = readLong(p);
                } catch (Exception ignored) {}
                SysLog.w("GpuSampler: found max_freq path=" + p + " value=" + maxFreqHz);
                break;
            }
        }

        // 如果没找到 max_freq，尝试从 freq 路径同目录推断
        if (maxFreqHz == 0 && freqPath != null) {
            String dir = freqPath.substring(0, freqPath.lastIndexOf('/'));
            String candidate = dir + "/max_freq";
            if (fileReadable(candidate)) {
                maxFreqPath = candidate;
                try {
                    maxFreqHz = readLong(candidate);
                } catch (Exception ignored) {}
            }
        }

        // 探测 KGSL busy 百分比
        for (String p : GpuBusyPaths) {
            if (fileReadable(p)) {
                busyPath = p;
                SysLog.w("GpuSampler: found busy path=" + p);
                break;
            }
        }

        // 探测 KGSL gpubusy (busy total counter)
        for (String p : GpuBusyTotalPaths) {
            if (fileReadable(p)) {
                busyTotalPath = p;
                SysLog.w("GpuSampler: found busy_total path=" + p);
                break;
            }
        }

        if (freqPath == null) {
            SysLog.w("GpuSampler: no GPU freq path found");
        }
    }

    private Long readFreq() {
        if (freqPath == null) return null;
        try {
            long v = readLong(freqPath);
            return v > 0 ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Float readGpuUsage() {
        // 优先使用 gpu_busy_percentage
        if (busyPath != null) {
            try {
                String raw = readText(busyPath);
                if (raw != null) {
                    raw = raw.replace("%", "").trim();
                    float v = Float.parseFloat(raw);
                    if (v >= 0 && v <= 100) return v;
                }
            } catch (Exception ignored) {}
        }

        // 使用 gpubusy (busy total)
        if (busyTotalPath != null) {
            try {
                String raw = readText(busyTotalPath);
                if (raw != null) {
                    String[] p = raw.trim().split("\\s+");
                    if (p.length >= 2) {
                        long busy = Long.parseLong(p[0]);
                        long total = Long.parseLong(p[1]);
                        if (busy >= 0 && total > 0 && busy <= total) {
                            return busy * 100f / total;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        return Float.NaN;
    }

    private boolean fileReadable(String path) {
        File f = new File(path);
        return f.isFile() && f.canRead();
    }

    private long readLong(String path) throws Exception {
        String s = readText(path);
        if (s == null) throw new Exception("read failed: " + path);
        return Long.parseLong(s.trim());
    }

    private String readText(String path) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(path));
            String line = r.readLine();
            r.close();
            return line;
        } catch (Exception e) {
            return null;
        }
    }
}
