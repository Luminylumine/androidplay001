package com.akasha.app;

import android.app.Activity;
import android.os.Bundle;

/** 全局设置页（索引 → 子页面模式; SettingsPanel 动态生成索引行）。 */
public class SettingsActivity extends Activity {

    private SettingsPanel panel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        panel = SettingsPanel.attach(this, findViewById(android.R.id.content));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (panel != null) panel.onResume();
    }
}
