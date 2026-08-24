package com.akasha.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Executes screen actions through the AccessibilityService (global gestures,
 * text injection into the focused field, global keys) and can dump the
 * current screen's text tree (a11y) so the agent can read the UI without
 * spending screenshot tokens.
 */
public class ControlService extends AccessibilityService {

    private static volatile ControlService inst = null;
    private static Context appCtx = null;
    private static final String OUR_COMP = "com.akasha.app/com.akasha.app.ControlService";
    /** Centers of the nodes from the last dumpText(), in output order. */
    private static final List<Point> lastNodes = new ArrayList<>();

    public static boolean ready() {
        return inst != null;
    }

    @Override
    protected void onServiceConnected() {
        inst = this;
        appCtx = getApplicationContext();
    }

    /**
     * The a11y service can die while the system still shows it as enabled
     * (Huawei kills our process; AMS does not rebind automatically).
     * Force a rebind by toggling our component out and back into the
     * enabled_accessibility_services secure setting. Prefers the shell
     * channel (uid 2000 can write secure settings); falls back to a direct
     * putString (needs WRITE_SECURE_SETTINGS, usually fails silently).
     * Returns true if a rebind was attempted.
     */
    public static boolean rebind() {
        Context c = appCtx != null ? appCtx : (inst == null ? null : inst.getApplicationContext());
        if (c == null) {
            CpLog.w("ControlService", "rebind skipped: no context");
            return false;
        }
        try {
            String current = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current == null || !current.contains(OUR_COMP)) {
                CpLog.w("ControlService", "rebind skipped: not in enabled list. current=" + current);
                return false;
            }
            List<String> parts = new ArrayList<>(Arrays.asList(current.split(":")));
            parts.remove(OUR_COMP);
            String without = join(parts);
            String with = without.isEmpty() ? OUR_COMP : without + ":" + OUR_COMP;
            CpLog.i("ControlService", "rebind attempt: " + current);

            // 1) via shell (most reliable)
            String sh1 = ShellChannel.exec("settings put secure enabled_accessibility_services '" + without + "'");
            if (sh1 != null && sh1.startsWith("rc=0")) {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                String sh2 = ShellChannel.exec("settings put secure enabled_accessibility_services '" + with + "'");
                CpLog.i("ControlService", "rebind via shell rc1=" + firstLine(sh1) + " rc2=" + firstLine(sh2));
                return sh2 != null && sh2.startsWith("rc=0");
            }
            CpLog.w("ControlService", "rebind: shell unavailable (" + firstLine(sh1) + "), trying putString");

            // 2) direct putString fallback
            try {
                Settings.Secure.putString(c.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without);
                Thread.sleep(300);
                Settings.Secure.putString(c.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, with);
                CpLog.i("ControlService", "rebind via putString done");
                return true;
            } catch (Throwable t) {
                CpLog.w("ControlService", "rebind putString failed: " + t);
                return false;
            }
        } catch (Throwable t) {
            CpLog.w("ControlService", "rebind: " + t);
            return false;
        }
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(':');
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private static String firstLine(String s) {
        if (s == null) return "null";
        int n = s.indexOf('\n');
        return n < 0 ? s : s.substring(0, n);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 前台窗口变化 → 定时器「打开软件」事件唤醒
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence p = event.getPackageName();
            if (p != null) TimerEngine.onAppForeground(p.toString());
        }
    }

    @Override
    public void onInterrupt() {
        // not used
    }

    @Override
    public void onDestroy() {
        if (inst == this) inst = null;
        super.onDestroy();
    }

    private static boolean gesture(Path path, long durationMs) {
        final AccessibilityService s = inst;
        if (s == null) return false;
        try {
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs));
            final boolean[] ok = {false};
            CountDownLatch latch = new CountDownLatch(1);
            s.dispatchGesture(b.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {
                    ok[0] = true;
                    latch.countDown();
                }

                @Override
                public void onCancelled(GestureDescription g) {
                    latch.countDown();
                }
            }, null);
            latch.await(3, TimeUnit.SECONDS);
            return ok[0];
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean tapPx(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        return gesture(p, 80);
    }

    public static boolean doubleTapPx(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        boolean r1 = gesture(p, 60);
        try { Thread.sleep(120); } catch (InterruptedException ignored) {}
        return r1 && gesture(p, 60);
    }

    public static boolean swipePx(int x1, int y1, int x2, int y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return gesture(p, Math.max(80, ms));
    }

    public static boolean typeText(String text) {
        final AccessibilityService s = inst;
        if (s == null || text == null) return false;
        try {
            List<AccessibilityWindowInfo> wins = s.getWindows();
            for (AccessibilityWindowInfo wi : wins) {
                if (!wi.isFocused()) continue;
                AccessibilityNodeInfo root = wi.getRoot();
                if (root == null) continue;
                AccessibilityNodeInfo n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (n == null) n = root;
                Bundle b = new Bundle();
                b.putCharSequence("text", text); // AccessibilityNodeInfo.ACTION_ARGUMENT_TEXT
                return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Dump the active window's text tree. Output lines look like:
     *   [0] clickable TextView "设置" (360,210)
     * Only nodes with text/contentDescription or clickable are listed.
     * Node centers are cached so tapIdx() can hit them.
     */
    public static String dumpText(int maxLines) {
        final AccessibilityService s = inst;
        if (s == null) return "(无障碍服务未启用)";
        synchronized (lastNodes) {
            lastNodes.clear();
            StringBuilder sb = new StringBuilder();
            int[] idx = {0};
            try {
                AccessibilityNodeInfo root = s.getRootInActiveWindow();
                if (root == null) return "(取不到当前窗口)";
                walk(root, sb, idx, maxLines);
            } catch (Exception e) {
                return "(dump 异常: " + e + ")";
            }
            return sb.length() == 0 ? "(屏幕无可读文本节点 — 建议 look 截图)" : sb.toString();
        }
    }

    private static void walk(AccessibilityNodeInfo n, StringBuilder sb, int[] idx, int max) {
        if (n == null || idx[0] >= max) return;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        boolean hasText = (t != null && t.length() > 0) || (d != null && d.length() > 0);
        boolean clickable = n.isClickable();
        if (hasText || clickable) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            int cx = r.centerX(), cy = r.centerY();
            synchronized (lastNodes) {
                lastNodes.add(new Point(cx, cy));
            }
            String label = t != null && t.length() > 0 ? t.toString() : (d != null ? d.toString() : "");
            label = label.replace('\n', ' ').trim();
            if (label.length() > 60) label = label.substring(0, 60) + "…";
            sb.append('[').append(idx[0]).append("] ")
              .append(clickable ? "C" : "-").append(' ')
              .append(label).append(" @(").append(cx).append(',').append(cy).append(")\n");
            idx[0]++;
        }
        for (int i = 0; i < n.getChildCount() && idx[0] < max; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) {
                try {
                    walk(c, sb, idx, max);
                } finally {
                    c.recycle();
                }
            }
        }
    }

    /** Tap node #i from the most recent dumpText(). */
    public static String tapIdx(int i) {
        Point p;
        synchronized (lastNodes) {
            if (i < 0 || i >= lastNodes.size()) return "idx 越界(0.."
                    + Math.max(0, lastNodes.size() - 1) + ")，先执行 a11y_text";
            p = new Point(lastNodes.get(i));
        }
        return tapPx(p.x, p.y) ? "tap idx=" + i + " @(" + p.x + "," + p.y + ")" : "tap idx 失败";
    }

    /** Find a node whose text/contentDescription contains `query` and tap it. */
    public static String tapText(String query) {
        final AccessibilityService s = inst;
        if (s == null) return "无障碍服务未启用";
        if (query == null || query.trim().isEmpty()) return "query 为空";
        final String[] hit = {null};
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return "取不到当前窗口";
            findText(root, query.trim(), hit);
        } catch (Exception e) {
            return "查找异常: " + e;
        }
        if (hit[0] == null) return "未找到含\"" + query + "\"的控件（可 a11y_text 查看）";
        return hit[0];
    }

    private static void findText(AccessibilityNodeInfo n, String q, final String[] hit) {
        if (n == null || hit[0] != null) return;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        String match = null;
        if (t != null && t.toString().contains(q)) match = t.toString();
        else if (d != null && d.toString().contains(q)) match = d.toString();
        if (match != null) {
            boolean ok;
            if (n.isClickable()) {
                try {
                    ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                } catch (Exception e) {
                    ok = false;
                }
                if (!ok) {
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    ok = tapPx(r.centerX(), r.centerY());
                }
            } else {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                ok = tapPx(r.centerX(), r.centerY());
            }
            hit[0] = (ok ? "tap_text \"" + q + "\" 命中\"" + clip(match) + "\""
                         : "tap_text 点击失败");
            return;
        }
        for (int i = 0; i < n.getChildCount() && hit[0] == null; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) {
                try {
                    findText(c, q, hit);
                } finally {
                    c.recycle();
                }
            }
        }
    }

    private static String clip(String s) {
        s = s.replace('\n', ' ').trim();
        return s.length() <= 30 ? s : s.substring(0, 30) + "…";
    }

    public static boolean globalKey(String key) {
        final AccessibilityService s = inst;
        if (s == null || key == null) return false;
        try {
            // AccessibilityService global action codes
            if ("back".equals(key)) return s.performGlobalAction(1);   // GLOBAL_BACK
            if ("home".equals(key)) return s.performGlobalAction(2);   // GLOBAL_HOME
            if ("recents".equals(key)) return s.performGlobalAction(3); // GLOBAL_RECENTS
        } catch (Exception ignored) {}
        return false;
    }
}
