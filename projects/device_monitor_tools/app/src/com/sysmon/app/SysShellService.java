package com.sysmon.app;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.sysmon.aidl.ISysShell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * 由 Shizuku / Dhizuku 绑定并在其特权进程内运行。
 * - exec(): 执行 shell 命令（dumpsys 等）
 * - readFiles(): 直接 FileInputStream 批量读文件（避免 spawn 进程）
 */
public class SysShellService extends Service {

    private static final String TAG = "SysMon-shell";

    private ISysShell.Stub binder;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            binder = new ISysShell.Stub() {
                @Override
                public String exec(String cmd) {
                    return SysShellService.this.execCmd(cmd);
                }

                @Override
                public String readFiles(String[] paths) {
                    return SysShellService.this.readFiles(paths);
                }
            };
            Log.i(TAG, "SysShellService onCreate uid=" + android.os.Process.myUid()
                    + " pid=" + android.os.Process.myPid() + " binder=" + binder);
        } catch (Throwable t) {
            Log.e(TAG, "SysShellService onCreate failed", t);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind binder=" + binder);
        return binder;
    }

    private String execCmd(String cmd) {
        if (cmd == null) return "rc=-1\nnull";
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            StringBuilder out = new StringBuilder();
            BufferedReader b = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            int lines = 0;
            while ((line = b.readLine()) != null && lines < 600) {
                out.append(line).append('\n');
                lines++;
            }
            BufferedReader e = new BufferedReader(new InputStreamReader(p.getErrorStream(), "UTF-8"));
            while ((line = e.readLine()) != null && out.length() < 40000) {
                out.append("[e] ").append(line).append('\n');
            }
            int rc = p.waitFor();
            String s = out.toString();
            if (s.length() > 40000) s = s.substring(0, 40000) + "\n…(truncated)";
            return "rc=" + rc + "\n" + s;
        } catch (Exception ex) {
            return "rc=-1\nexec 异常: " + ex;
        }
    }

    /** 批量读文件，返回 "=== path ===\n<content>\n" 拼接；不可读的路径跳过。 */
    private String readFiles(String[] paths) {
        if (paths == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            sb.append("=== ").append(path).append(" ===\n");
            try {
                File f = new File(path);
                if (!f.exists() || !f.canRead()) continue;
                FileInputStream in = new FileInputStream(f);
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(buf)) > 0 && total < 60000) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                    total += n;
                }
                in.close();
            } catch (Throwable ignored) {}
            if (sb.length() > 200000) break;
        }
        return sb.toString();
    }
}
