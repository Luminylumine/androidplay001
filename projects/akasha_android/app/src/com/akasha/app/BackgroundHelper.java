package com.akasha.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Environment;
import android.view.View;

import java.io.File;

/**
 * Per-page background hook (req 1: every page may later switch from white to
 * arbitrary backgrounds, even user-provided images).
 *
 * Spec string stored per page key in SharedPreferences:
 *   ""            -> default (no override)
 *   "#RRGGBB"     -> solid color
 *   "/abs/path"   -> image file (bitmap)
 *
 * No UI exposes this yet; it is the interface future settings will drive.
 */
public class BackgroundHelper {

    public static final String PAGE_HOME = "home";
    public static final String PAGE_CHAT = "chat";
    public static final String PAGE_CONTACTS = "contacts";
    public static final String PAGE_DISCOVER = "discover";
    public static final String PAGE_SETTINGS = "settings";
    public static final String PAGE_MODEL = "model";

    public static String get(Context ctx, String page) {
        return new Prefs(ctx).raw().getString("bg_" + page, "");
    }

    public static void set(Context ctx, String page, String spec) {
        new Prefs(ctx).raw().edit().putString("bg_" + page, spec == null ? "" : spec).apply();
    }

    /** Apply the stored spec to a root view; no-op when spec is empty/invalid. */
    public static void apply(Context ctx, View root, String page) {
        if (root == null) return;
        String spec = get(ctx, page);
        if (spec == null || spec.isEmpty()) return;
        try {
            if (spec.startsWith("#")) {
                root.setBackgroundColor(android.graphics.Color.parseColor(spec));
                return;
            }
            // image file: absolute path, or a bare name in the app's external dir
            File f = new File(spec);
            if (!f.isAbsolute()) {
                File ext = ctx.getExternalFilesDir(null);
                if (ext != null) f = new File(ext, spec);
            }
            if (!f.isFile()) {
                // also allow a name on shared storage root
                File sd = new File(Environment.getExternalStorageDirectory(), spec);
                if (sd.isFile()) f = sd;
            }
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp != null) {
                root.setBackground(new BitmapDrawable(ctx.getResources(), bmp));
            }
        } catch (Throwable t) {
            CpLog.w("Akasha", "background apply failed (" + page + "): " + t);
        }
    }
}
