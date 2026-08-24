package com.sysmon.app;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * 无障碍服务：检测前台窗口是否全屏（边界≈屏幕），供悬浮窗"全屏应用时隐藏"使用。
 * 仅当设置开启时才有意义；未开启时本服务基本无开销。
 */
public class SysAccessibilityService extends AccessibilityService {

    private boolean lastFullscreen = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }
        boolean fullscreen = detectFullscreen();
        if (fullscreen != lastFullscreen) {
            lastFullscreen = fullscreen;
            OverlayService svc = OverlayService.instance;
            if (svc != null) {
                svc.onFullscreenChanged(fullscreen);
            }
        }
    }

    private boolean detectFullscreen() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null || windows.isEmpty()) return false;
            DisplayMetrics dm = getResources().getDisplayMetrics();
            for (AccessibilityWindowInfo w : windows) {
                if (!w.isFocused() && !w.isActive()) continue;
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                Rect bounds = new Rect();
                w.getBoundsInScreen(bounds);
                // 全屏应用窗口通常覆盖整个屏幕（含状态栏区域）
                return bounds.width() >= dm.widthPixels && bounds.height() >= dm.heightPixels;
            }
        } catch (Throwable t) {
            SysLog.w("detectFullscreen: " + t);
        }
        return false;
    }

    @Override
    public void onInterrupt() {
    }
}
