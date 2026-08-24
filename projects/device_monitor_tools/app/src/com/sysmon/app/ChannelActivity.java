package com.sysmon.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sysmon.app.adb.AdbWireless;

/** 权限通道子页：Shizuku / Dhizuku / ADB 授权管理。 */
public class ChannelActivity extends Activity {

    private static final int FG = 0xFFD0D0D0;
    private static final int ACCENT = 0xFF4DD0E1;
    private static final int DIM = 0xFF888888;
    private static final int GREEN = 0xFF00E676;
    private static final int YELLOW = 0xFFFFD54F;

    private LinearLayout ll;
    private TextView tvShizuku, tvDhizuku, tvAdb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundColor(0xFF0F0F14);
        ll.setPadding(dp(12), dp(12), dp(12), dp(16));
        setContentView(ll);

        addSection("── 权限通道 ──");

        tvShizuku = addTextRow("Shizuku");
        Button btnShizuku = addButtonRow("授权/打开");
        btnShizuku.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Privilege.shizukuInstalled()) {
                    if (Privilege.shizukuStatus() == 0) Privilege.requestShizukuPermission();
                    else Privilege.openShizuku();
                } else {
                    Privilege.openShizuku();
                }
            }
        });

        tvDhizuku = addTextRow("Dhizuku");
        Button btnDhizuku = addButtonRow("授权/打开");
        btnDhizuku.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Privilege.dhizukuInstalled()) {
                    if (Privilege.dhizukuStatus() == 0) Privilege.requestDhizukuPermission();
                    else Privilege.openDhizuku();
                } else {
                    Privilege.openDhizuku();
                }
            }
        });

        tvAdb = addTextRow("ADB");
        Button btnAdb = addButtonRow("扫描端口");
        btnAdb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AdbWireless.scan();
                refresh();
            }
        });

        Button btnWireless = addButtonRow("打开无线调试设置");
        btnWireless.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Privilege.openWirelessDebugging();
            }
        });

        TextView note = new TextView(this);
        note.setText("说明：Shizuku 提供 shell(uid 2000) 通道，数据最全。Dhizuku 为 Device Owner 降级通道（非 shell）。ADB 为设备内自连本机 adbd（先试 5555，可手动扫描无线调试随机端口）。无任何通道时仅显示无权限可读数据（内存/电池/网络）。");
        note.setTextColor(DIM);
        note.setTextSize(11);
        note.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams lp = lp();
        lp.topMargin = dp(10);
        ll.addView(note, lp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Privilege.reset();
        Privilege.ensure();
        refresh();
    }

    private void refresh() {
        int ss = Privilege.shizukuStatus();
        tvShizuku.setText(ss == 1 ? "已授权 ✓" : (ss == 0 ? "未授权/未运行" : "未安装"));
        tvShizuku.setTextColor(ss == 1 ? GREEN : YELLOW);
        int ds = Privilege.dhizukuStatus();
        tvDhizuku.setText(ds == 1 ? "已授权 ✓" : (ds == 0 ? "未授权" : "未安装"));
        tvDhizuku.setTextColor(ds == 1 ? GREEN : YELLOW);
        tvAdb.setText(AdbWireless.status());
        tvAdb.setTextColor(AdbWireless.isConnected() ? GREEN : YELLOW);
    }

    private void addSection(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ACCENT);
        t.setTextSize(13);
        t.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = lp();
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(4);
        ll.addView(t, lp);
    }

    private TextView addTextRow(String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextColor(DIM);
        lab.setTextSize(12);
        lab.setTypeface(android.graphics.Typeface.MONOSPACE);
        lab.setWidth(dp(52));

        TextView val = new TextView(this);
        val.setTextColor(FG);
        val.setTextSize(12);
        val.setTypeface(android.graphics.Typeface.MONOSPACE);

        row.addView(lab);
        row.addView(val);
        ll.addView(row, lp());
        return val;
    }

    private Button addButtonRow(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(52), 1));
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setMinWidth(0);
        b.setAllCaps(false);
        row.addView(b);
        ll.addView(row, lp());
        return b;
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
