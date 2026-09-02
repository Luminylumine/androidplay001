package com.akasha.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Global "全局" tab content (req 5 / refactor 2026-08-23):
 *  - 权限与通道状态 (always visible)
 *  - 开机自启总门控 (always visible, gates per-agent autoStart)
 *  - 设置子菜单 (collapsible: 展开后才看到 默认API/运行参数/经验池策略)
 *
 * Per-agent Base URL / API Key / autoStart / permissions / prompt live in
 * ModelSettingsActivity (通讯录 → 某 Agent).
 */
public class SettingsPanel {

    private final Activity act;
    private final View root;
    private final Prefs prefs;
    private final boolean saveFinishes;

    private EditText etGoal, etUrl, etKey, etMax, etInterval, etHistory, etExpDays, etExpMax;
    private EditText etVoiceHost, etVoiceToken;
    private CheckBox cbVoiceTts;
    private Button btnKeyVis, btnTest, btnSave, btnVoiceTest;
    private TextView tvVoiceTestResult;
    private Spinner spModel;
    private CheckBox cbAutoStart;
    private TextView tvShellStatus, tvDhizukuStatus, tvShizukuStatus, tvShotStatus,
            tvA11yStatus, tvBatteryStatus, tvLogPath;
    private LinearLayout llSettingsToggle;
    private LinearLayout llSettingsPanel;
    private TextView tvSettingsArrow;

    private boolean settingsOpen = false;
    private boolean keyVisible = false;
    private List<ModelInfo> models = new ArrayList<>();

    public static SettingsPanel attach(Activity act, View searchRoot, boolean saveFinishes) {
        return new SettingsPanel(act, searchRoot, saveFinishes);
    }

    private SettingsPanel(Activity act, View searchRoot, boolean saveFinishes) {
        this.act = act;
        this.root = searchRoot;
        this.prefs = new Prefs(act);
        this.saveFinishes = saveFinishes;

        // ---- 状态区 ----
        tvShellStatus = (TextView) root.findViewById(R.id.tvShellStatus);
        tvDhizukuStatus = (TextView) root.findViewById(R.id.tvDhizukuStatus);
        tvShizukuStatus = (TextView) root.findViewById(R.id.tvShizukuStatus);
        tvShotStatus = (TextView) root.findViewById(R.id.tvShotStatus);
        tvA11yStatus = (TextView) root.findViewById(R.id.tvA11yStatus);
        tvBatteryStatus = (TextView) root.findViewById(R.id.tvBatteryStatus);
        tvLogPath = (TextView) root.findViewById(R.id.tvLogPath);

        root.findViewById(R.id.btnShellRecheck).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ShellChannel.hardReset();
                ShellChannel.ensure();
                refreshStatuses();
            }
        });

        root.findViewById(R.id.btnDhizuku).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ShellChannel.dhizukuStatus() == -1) {
                    Toast.makeText(act, "未安装 Dhizuku（可选；提供 Device Owner 能力）", Toast.LENGTH_SHORT).show();
                    return;
                }
                ShellChannel.requestDhizukuPermission();
                ShellChannel.openDhizuku();
            }
        });

        root.findViewById(R.id.btnShizuku).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ShellChannel.shizukuStatus() == -1) {
                    Toast.makeText(act, "未安装 Shizuku", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (ShellChannel.shizukuStatus() == 0) {
                    ShellChannel.requestShizukuPermission();
                } else {
                    ShellChannel.openShizuku();
                }
            }
        });

        root.findViewById(R.id.btnWireless).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    ShellChannel.openWirelessDebugging();
                } else {
                    new AlertDialog.Builder(act)
                            .setTitle("adb 无线端口启动 Shizuku")
                            .setMessage("当前系统无「无线调试」配对界面，用 adb 无线端口 5555（无需配对码）:\n1. USB 连着手机时，电脑执行 adb tcpip 5555 开启无线端口\n2. 手机与电脑连同一 WiFi，执行 adb connect 手机IP:5555\n3. 电脑执行: adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh\n   （若报 no such file，先打开一次 Shizuku App 让它生成脚本）\n4. 回本页面点\"重新检测\"\n(手机 IP 见 设置→WLAN→当前网络)\n注意: 重启后需重做步骤 2-3")
                            .setPositiveButton("知道了", null)
                            .show();
                }
            }
        });

        root.findViewById(R.id.btnBattery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    act.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception e) {
                    act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS));
                }
            }
        });

        // ---- 开机自启总门控 (直接实时保存) ----
        cbAutoStart = (CheckBox) root.findViewById(R.id.cbAutoStart);
        cbAutoStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.autoStart(cbAutoStart.isChecked());
                Toast.makeText(act, cbAutoStart.isChecked()
                        ? "已开启总门控：勾选了自启的 Agent 会在开机后被尝试唤起"
                        : "已关闭总门控：所有 Agent 的开机自启都不会生效", Toast.LENGTH_SHORT).show();
            }
        });

        // ---- 设置子菜单 (可展开) ----
        llSettingsToggle = (LinearLayout) root.findViewById(R.id.llSettingsToggle);
        llSettingsPanel = (LinearLayout) root.findViewById(R.id.llSettingsPanel);
        tvSettingsArrow = (TextView) root.findViewById(R.id.tvSettingsArrow);
        llSettingsToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingsOpen = !settingsOpen;
                llSettingsPanel.setVisibility(settingsOpen ? View.VISIBLE : View.GONE);
                tvSettingsArrow.setText(settingsOpen ? "▾" : "▸");
            }
        });

        etGoal = (EditText) root.findViewById(R.id.etGoal);
        etUrl = (EditText) root.findViewById(R.id.etUrl);
        etKey = (EditText) root.findViewById(R.id.etKey);
        etMax = (EditText) root.findViewById(R.id.etMax);
        etInterval = (EditText) root.findViewById(R.id.etInterval);
        etHistory = (EditText) root.findViewById(R.id.etHistory);
        etExpDays = (EditText) root.findViewById(R.id.etExpDays);
        etExpMax = (EditText) root.findViewById(R.id.etExpMax);
        btnKeyVis = (Button) root.findViewById(R.id.btnKeyVis);
        btnTest = (Button) root.findViewById(R.id.btnTest);
        btnSave = (Button) root.findViewById(R.id.btnSave);
        spModel = (Spinner) root.findViewById(R.id.spModel);

        btnKeyVis.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                keyVisible = !keyVisible;
                etKey.setInputType(keyVisible
                        ? android.text.InputType.TYPE_CLASS_TEXT
                        : android.text.InputType.TYPE_CLASS_TEXT
                          | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                etKey.setSelection(etKey.getText().length());
                btnKeyVis.setText(keyVisible ? "隐藏" : "显示");
            }
        });

        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collect();
                final String url = prefs.baseUrl();
                final String key = prefs.apiKey();
                final String model = prefs.model();
                Toast.makeText(act, "正在测试 " + url + " ...", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String res = LlmClient.preflight(url, key, model);
                        act.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                AgentService.log("连接测试: " + res);
                                new AlertDialog.Builder(act)
                                        .setTitle(res.startsWith("OK") ? "连接正常" : "连接失败")
                                        .setMessage(res + (res.startsWith("OK")
                                                ? "\n\n模型 " + model + " 可用。"
                                                : "\n\n请检查 Base URL / API Key / 网络。常见原因:\n· 405: URL 少了 api. 前缀或路径不对\n· 401/403: Key 错误或无权限\n· UnknownHost/timeout: 手机当前网络不通"))
                                        .setPositiveButton("知道了", null)
                                        .show();
                            }
                        });
                    }
                }).start();
            }
        });

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveFields();
                Toast.makeText(act, "已保存", Toast.LENGTH_SHORT).show();
                if (saveFinishes) act.finish();
            }
        });

        // ---- 语音通话 (Task 8) ----
        etVoiceHost = (EditText) root.findViewById(R.id.etVoiceHost);
        etVoiceToken = (EditText) root.findViewById(R.id.etVoiceToken);
        cbVoiceTts = (CheckBox) root.findViewById(R.id.cbVoiceTts);
        btnVoiceTest = (Button) root.findViewById(R.id.btnVoiceTest);
        tvVoiceTestResult = (TextView) root.findViewById(R.id.tvVoiceTestResult);

        btnVoiceTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveVoiceFields();
                final String host = prefs.voiceBackendHost();
                tvVoiceTestResult.setText("正在测试 " + host + " ...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String res = VoiceHealth.test(host);
                        act.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                tvVoiceTestResult.setText(res);
                            }
                        });
                    }
                }).start();
            }
        });

        onResume();
    }

    public void onResume() {
        loadFields();
        loadModels();
        refreshStatuses();
    }

    // ---------------- statuses ----------------

    private void refreshStatuses() {
        String src = ShellChannel.source();
        String shellTxt;
        if (ShellChannel.available()) {
            String uid = ShellChannel.svcUid();
            shellTxt = src + " ✓" + (uid == null ? "" : ("2000".equals(uid)
                    ? " (shell uid 2000)" : " (uid " + uid + "，仅 app 级)"));
        } else if (ShellChannel.pendingSource() != null) {
            shellTxt = "经 " + ShellChannel.pendingSource() + " 建立中…";
        } else {
            shellTxt = "无（点击右侧重新检测）";
        }
        tvShellStatus.setText("Shell 通道: " + shellTxt);

        int d = ShellChannel.dhizukuStatus();
        tvDhizukuStatus.setText("Dhizuku(Device Owner): " + (d == -1 ? "未安装"
                : (d == 0 ? "已安装，未激活/未授权" : "已授权 ✓（不提供 shell）")));

        int s = ShellChannel.shizukuStatus();
        tvShizukuStatus.setText("Shizuku(shell 通道): " + (s == -1 ? "未安装"
                : (s == 0 ? "未运行或未授权" : "运行中且已授权 ✓")));

        tvShotStatus.setText("屏幕捕获(mediaProjection): "
                + (ScreenShotService.active() ? "已授权 ✓" : "未授权（首次运行 Agent 时弹授权）"));
        tvA11yStatus.setText("无障碍服务: "
                + (ControlService.ready() ? "已启用 ✓" : "未启用（无 shell 通道时用于点击/输入/读屏）"));

        String bat = "电池优化: ";
        try {
            PowerManager pm = (PowerManager) act.getSystemService(Context.POWER_SERVICE);
            bat += pm.isIgnoringBatteryOptimizations(act.getPackageName()) ? "已忽略 ✓" : "未忽略（建议忽略以防被杀）";
        } catch (Exception e) {
            bat += "未知";
        }
        tvBatteryStatus.setText(bat);

        String lp = CpLog.path();
        tvLogPath.setText("日志文件: " + (lp == null ? "不可用（需授权存储/文件目录）" : lp)
                + "\nShell 侧日志: /sdcard/akasha_shell.log");
    }

    // ---------------- fields ----------------

    private void loadFields() {
        cbAutoStart.setChecked(prefs.autoStart());
        etGoal.setText(prefs.goal());
        etUrl.setText(prefs.baseUrl());
        etKey.setText(prefs.apiKey());
        etMax.setText(String.valueOf(prefs.maxTokens()));
        etInterval.setText(String.valueOf(prefs.intervalMs() / 1000));
        etHistory.setText(String.valueOf(prefs.historyRounds()));
        etExpDays.setText(String.valueOf(prefs.expRetainDays()));
        etExpMax.setText(String.valueOf(prefs.expRetainMax()));
        etVoiceHost.setText(prefs.voiceBackendHost());
        etVoiceToken.setText(prefs.voiceToken());
        cbVoiceTts.setChecked(prefs.voiceTtsOn());
    }

    private void saveFields() {
        prefs.model(selectedModel());
        prefs.goal(etGoal.getText().toString().trim());
        String url = etUrl.getText().toString().trim();
        prefs.baseUrl(url.isEmpty() ? Prefs.DEF_BASE_URL : url);
        prefs.apiKey(etKey.getText().toString().trim());
        applyNums();
        prefs.autoStart(cbAutoStart.isChecked());
        saveVoiceFields();
    }

    /** 语音通话字段单独保存（"测试连接"点击时也要先落盘）。 */
    private void saveVoiceFields() {
        String h = etVoiceHost.getText().toString().trim();
        prefs.voiceBackendHost(h.isEmpty() ? Prefs.DEF_VOICE_HOST : h);
        prefs.voiceToken(etVoiceToken.getText().toString().trim());
        prefs.voiceTtsOn(cbVoiceTts.isChecked());
    }

    /** Apply model spinner + numeric fields to prefs (used before "test"). */
    private void collect() {
        saveFields();
    }

    private void applyNums() {
        try { prefs.maxTokens(Integer.parseInt(etMax.getText().toString().trim())); }
        catch (Exception ignored) {}
        try { prefs.intervalMs(Integer.parseInt(etInterval.getText().toString().trim()) * 1000); }
        catch (Exception ignored) {}
        try { prefs.historyRounds(Integer.parseInt(etHistory.getText().toString().trim())); }
        catch (Exception ignored) {}
        try { prefs.expRetainDays(Integer.parseInt(etExpDays.getText().toString().trim())); }
        catch (Exception ignored) {}
        try { prefs.expRetainMax(Integer.parseInt(etExpMax.getText().toString().trim())); }
        catch (Exception ignored) {}
    }

    private void loadModels() {
        models = prefs.models();
        List<String> ids = new ArrayList<>();
        for (ModelInfo m : models) ids.add(m.id);
        spModel.setAdapter(new ArrayAdapter<String>(act,
                android.R.layout.simple_spinner_item, ids));
        int idx = ids.indexOf(prefs.model());
        spModel.setSelection(idx < 0 ? 0 : idx);
    }

    private String selectedModel() {
        if (spModel.getSelectedItem() == null) return prefs.model();
        return spModel.getSelectedItem().toString();
    }
}
