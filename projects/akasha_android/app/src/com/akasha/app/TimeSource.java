package com.akasha.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Time source abstraction (future timer / wakeup / event phases need to read
 * system time). Inject a fake clock in tests; production uses {@link SystemTime}.
 */
public interface TimeSource {

    long now();

    /** Human-readable "yyyy-MM-dd HH:mm:ss" for a timestamp. */
    String format(long millis);

    /** Production implementation backed by the system clock. */
    class SystemTime implements TimeSource {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public String format(long millis) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(millis));
        }
    }
}
