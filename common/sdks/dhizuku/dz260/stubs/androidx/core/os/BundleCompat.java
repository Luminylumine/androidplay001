package androidx.core.os;

import android.os.Bundle;

/** Compile/runtime stub replacing androidx.core (not bundled in our APK). */
public class BundleCompat {
    @SuppressWarnings("deprecation")
    public static <T extends android.os.Parcelable> T getParcelable(Bundle bundle, String key, Class<T> clazz) {
        if (bundle == null) return null;
        return bundle.getParcelable(key);
    }
}
