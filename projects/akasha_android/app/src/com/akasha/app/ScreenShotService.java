package com.akasha.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * Holds a MediaProjection session and lets the agent grab the current screen
 * on demand. One user consent (system dialog) per start.
 */
public class ScreenShotService extends Service {

    private final MediaProjection.Callback cb = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            cleanup();
        }
    };

    public static final String ACTION_GRAB = "com.akasha.app.GRAB";
    public static final String ACTION_STOP = "com.akasha.app.STOP_CAPTURE";

    private static volatile ScreenShotService inst = null;

    private MediaProjection proj;
    private ImageReader reader;
    private VirtualDisplay vdisp;
    private int w, h, dpi;

    public static boolean active() {
        ScreenShotService s = inst;
        return s != null && s.proj != null;
    }

    public static Bitmap grab() {
        ScreenShotService s = inst;
        if (s == null || s.reader == null) return null;
        Image img = s.reader.acquireLatestImage();
        if (img == null) return null;
        try {
            Bitmap bmp = Bitmap.createBitmap(s.w, s.h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(img.getPlanes()[0].getBuffer());
            return bmp;
        } catch (Exception e) {
            return null;
        } finally {
            img.close();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel("capture",
                "Akasha 屏幕捕获", NotificationManager.IMPORTANCE_MIN);
        nm.createNotificationChannel(ch);
    }

    /**
     * HarmonyOS (even on Android 10/12) and Android 14+ require an FGS of
     * type mediaProjection before getMediaProjection() succeeds.
     */
    private void promoteToForeground() {
        Notification n = new Notification.Builder(this, "capture")
                .setContentTitle("Akasha")
                .setContentText("屏幕捕获已就绪")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
        CpLog.i("Akasha", "ScreenShotService promoted to foreground (mediaProjection)");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        CpLog.i("Akasha", "ScreenShotService onStartCommand action="
                + (intent != null ? intent.getAction() : "null"));
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            cleanup();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_GRAB.equals(intent.getAction())) {
            Intent consent = intent.getParcelableExtra("consent");
            android.net.Uri u = intent.getData();
            if (consent != null) {
                startGrab(consent);
            } else if (u != null) {
                startGrab(new Intent().setData(u));
            } else {
                CpLog.w("Akasha", "GRAB without consent intent or data");
            }
        }
        return START_NOT_STICKY;
    }

    private void startGrab(Intent consent) {
        try {
            promoteToForeground();
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            CpLog.i("Akasha", "startGrab: getMediaProjection, consent data="
                    + (consent != null ? consent.getData() : "null"));
            proj = mpm.getMediaProjection(Activity.RESULT_OK, consent);
            if (proj == null) {
                CpLog.w("Akasha", "getMediaProjection returned null");
                return;
            }
            proj.registerCallback(cb, null);

            DisplayMetrics dm = new DisplayMetrics();
            ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay().getMetrics(dm);
            w = dm.widthPixels;
            h = dm.heightPixels;
            dpi = dm.densityDpi;
            CpLog.i("Akasha", "startGrab: " + w + "x" + h + "@" + dpi + "dpi");

            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            vdisp = proj.createVirtualDisplay("akasha", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                    reader.getSurface(), null, null);
            CpLog.i("Akasha", "startGrab: virtual display created");
        } catch (Exception e) {
            CpLog.e("Akasha", "startGrab failed: " + e);
            cleanup();
        }
    }

    private void cleanup() {
        try { if (vdisp != null) vdisp.release(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (proj != null) proj.stop(); } catch (Exception ignored) {}
        vdisp = null;
        reader = null;
        proj = null;
        if (inst == this) inst = null;
        stopSelf();
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
