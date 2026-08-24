package com.akasha.app;

import android.app.Activity;
import android.os.Bundle;

/** Standalone settings screen (deep link). The home "设置" tab embeds the
 *  same SettingsPanel inside an in-app view. */
public class SettingsActivity extends Activity {

    private SettingsPanel panel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        panel = SettingsPanel.attach(this, findViewById(android.R.id.content), true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (panel != null) panel.onResume();
    }
}
