package com.akasha.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局设置的子页面（按键→子页面模式）。
 * 主页面（SettingsPanel 索引）只列条目，具体设置全部在这里完成:
 *   - SEC_STATUS : 权限与通道状态（状态展示 + 授予/重检按钮）
 *   - SEC_BOOT   : 开机自启总门控（勾选实时保存）
 *   - SEC_API    : 默认 API 与模型（目标/URL/Key/默认模型 + 连接测试）
 *   - SEC_PARAMS : 运行参数（最大输出/轮次间隔/历史轮数）
 *   - SEC_POOL   : 全局经验池保留策略
 * 页面整体 ScrollView 包裹，后期加设置项不影响布局。
 */
public class SettingsDetailActivity extends Activity {

    public static final String EXTRA_SECTION = "section";
    public static final String SEC_STATUS = "status";
    public static final String SEC_BOOT = "boot";
    public static final String SEC_API = "api";
    public static final String SEC_PARAMS = "params";
    public static final String SEC_POOL = "pool";

    private Prefs prefs;
    private String section = SEC_STATUS;

    private EditText etGoal, etUrl, etKey, etMax, etInterval, etHistory, etExpDays, etExpMax;
    private Spinner spModel;
    private CheckBox cbAutoStart;
    private TextView tvShellStatus, tvDhizukuStatus, tvShizukuStatus, tvShotStatus,
            tvA11yStatus, tvBatteryStatus, tvLogPath;
    private boolean keyVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_detail);
        prefs = new Prefs(this);

        section = getIntent().getStringExtra(EXTRA_SECTION);
        if (section == null) section = SEC_STATUS;

        TextView title = (TextView) findViewById(R.id.tvSdTitle);
        findViewById(R.id.btnSdBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        View bkStatus = findViewById(R.id.bkStatus);
        View bkBoot = findViewById(R.id.bkBoot);
        View bkApi = findViewById(R.id.bkApi);
        View bkParams = findViewById(R.id.bkParams);
        View bkPool = findViewById(R.id.bkPool);
        View[] blocks = {bkStatus, bkBoot, bkApi, bkParams, bkPool};
        for (View b : blocks) b.setVisibility(View.GONE);

        switch (section) {
            case SEC_BOOT:
                title.setText("开机自启（总门控）");
                bkBoot.setVisibility(View.VISIBLE);
                setupBoot();
                break;
            case SEC_API:
                title.setText("默认 API 与模型");
                bkApi.setVisibility(View.VISIBLE);
                setupApi();
                break;
            case SEC_PARAMS:
                title.setText("运行参数");
                bkParams.setVisibility(View.VISIBLE);
                setupParams();
                break;
            case SEC_POOL:
                title.setText("经验池保留策略");
                bkPool.setVisibility(View.VISIBLE);
                setupPool();
                break;
            case SEC_STATUS:
            default:
                title.setText("权限与通道状态");
                bkStatus.setVisibility(View.VISIBLE);
                setupStatus();
                break;
        }
    }

    // ---------------- 权限与通道状态 ----------------

    private void setupStatus() {
        tvShellStatus = (TextView) findViewById(R.id.tvShellStatus);
        tvDhizukuStatus = (TextView) findViewById(R.id.tvDhizukuStatus);
        tvShizukuStatus = (TextView) findViewById(R.id.tvShizukuStatus);
        tvShotStatus = (TextView) findViewById(R.id.tvShotStatus);
        tvA11yStatus = (TextView) findViewById(R.id.tvA11yStatus);
        tvBatteryStatus = (TextView) findViewById(R.id.tvBatteryStatus);
        tvLogPath = (TextView) findViewById(R.id.tvLogPath);

        findViewById(R.id.btnShellRecheck).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ShellChannel.hardReset();
                ShellChannel.ensure();
                refreshStatuses();
            }
        });

        findViewById(R.id.btnDhizuku).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ShellChannel.dhizukuStatus() == -1) {
                    Toast.makeText(SettingsDetailActivity.this,
                            "未安装 Dhizuku（可选；提供 Device Owner 能力）", Toast.LENGTH_SHORT).show();
                    return;
                }
                ShellChannel.requestDhizukuPermission();
                ShellChannel.openDhizuku();
            }
        });

        findViewById(R.id.btnShizuku).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ShellChannel.shizukuStatus() == -1) {
                    Toast.makeText(SettingsDetailActivity.this, "未安装 Shizuku", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (ShellChannel.shizukuStatus() == 0) {
                    ShellChannel.requestShizukuPermission();
                } else {
                    ShellChannel.openShizuku();
                }
            }
        });

        findViewById(R.id.btnWireless).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 30) {
                    ShellChannel.openWirelessDebugging();
                } else {
                    new AlertDialog.Builder(SettingsDetailActivity.this)
                            .setTitle("adb 无线端口启动 Shizuku")
                            .setMessage("当前系统无「无线调试」配对界面，用 adb 无线端口 5555（无需配对码）:\n1. USB 连着手机时，电脑执行 adb tcpip 5555 开启无线端口\n2. 手机与电脑连同一 WiFi，执行 adb connect 手机IP:5555\n3. 电脑执行: adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh\n   （若报 no such file，先打开一次 Shizuku App 让它生成脚本）\n4. 回本页面点\"重新检测\"\n(手机 IP 见 设置→WLAN→当前网络)\n注意: 重启后需重做步骤 2-3")
                            .setPositiveButton("知道了", null)
                            .show();
                }
            }
        });

        findViewById(R.id.btnBattery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS));
                }
            }
        });

        refreshStatuses();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (SEC_STATUS.equals(section)) refreshStatuses();
    }

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
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            bat += pm.isIgnoringBatteryOptimizations(getPackageName()) ? "已忽略 ✓" : "未忽略（建议忽略以防被杀）";
        } catch (Exception e) {
            bat += "未知";
        }
        tvBatteryStatus.setText(bat);

        String lp = CpLog.path();
        tvLogPath.setText("日志文件: " + (lp == null ? "不可用（需授权存储/文件目录）" : lp)
                + "\nShell 侧日志: /sdcard/akasha_shell.log");
    }

    // ---------------- 开机自启 ----------------

    private void setupBoot() {
        cbAutoStart = (CheckBox) findViewById(R.id.cbAutoStart);
        cbAutoStart.setChecked(prefs.autoStart());
        cbAutoStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.autoStart(cbAutoStart.isChecked());
                Toast.makeText(SettingsDetailActivity.this, cbAutoStart.isChecked()
                        ? "已开启总门控：勾选了自启的 Agent 会在开机后被尝试唤起"
                        : "已关闭总门控：所有 Agent 的开机自启都不会生效", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------------- 默认 API 与模型 ----------------

    private void setupApi() {
        etGoal = (EditText) findViewById(R.id.etGoal);
        etUrl = (EditText) findViewById(R.id.etUrl);
        etKey = (EditText) findViewById(R.id.etKey);
        spModel = (Spinner) findViewById(R.id.spModel);
        Button btnKeyVis = (Button) findViewById(R.id.btnKeyVis);

        etGoal.setText(prefs.goal());
        etUrl.setText(prefs.baseUrl());
        etKey.setText(prefs.apiKey());
        loadModels();

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

        findViewById(R.id.btnTest).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveApi();
                final String url = prefs.baseUrl();
                final String key = prefs.apiKey();
                final String model = prefs.model();
                Toast.makeText(SettingsDetailActivity.this, "正在测试 " + url + " ...",
                        Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String res = LlmClient.preflight(url, key, model);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                AgentService.log("连接测试: " + res);
                                new AlertDialog.Builder(SettingsDetailActivity.this)
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

        ((Button) findViewById(R.id.btnSaveApi)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveApi();
                Toast.makeText(SettingsDetailActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            }
        });
    }

    private void saveApi() {
        prefs.model(selectedModel());
        prefs.goal(etGoal.getText().toString().trim());
        String url = etUrl.getText().toString().trim();
        prefs.baseUrl(url.isEmpty() ? Prefs.DEF_BASE_URL : url);
        prefs.apiKey(etKey.getText().toString().trim());
    }

    private void loadModels() {
        List<ModelInfo> models = prefs.models();
        List<String> ids = new ArrayList<>();
        for (ModelInfo m : models) ids.add(m.id);
        spModel.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, ids));
        int idx = ids.indexOf(prefs.model());
        spModel.setSelection(idx < 0 ? 0 : idx);
    }

    private String selectedModel() {
        if (spModel.getSelectedItem() == null) return prefs.model();
        return spModel.getSelectedItem().toString();
    }

    // ---------------- 运行参数 ----------------

    private void setupParams() {
        etMax = (EditText) findViewById(R.id.etMax);
        etInterval = (EditText) findViewById(R.id.etInterval);
        etHistory = (EditText) findViewById(R.id.etHistory);

        etMax.setText(String.valueOf(prefs.maxTokens()));
        etInterval.setText(String.valueOf(prefs.intervalMs() / 1000));
        etHistory.setText(String.valueOf(prefs.historyRounds()));

        ((Button) findViewById(R.id.btnSaveParams)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyNums();
                Toast.makeText(SettingsDetailActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            }
        });
    }

    private void applyNums() {
        try { prefs.maxTokens(Integer.parseInt(etMax.getText().toString().trim())); }
        catch (Exception ignored) {}
        try { prefs.intervalMs(Integer.parseInt(etInterval.getText().toString().trim()) * 1000); }
        catch (Exception ignored) {}
        try { prefs.historyRounds(Integer.parseInt(etHistory.getText().toString().trim())); }
        catch (Exception ignored) {}
    }

    // ---------------- 经验池保留策略 ----------------

    private void setupPool() {
        etExpDays = (EditText) findViewById(R.id.etExpDays);
        etExpMax = (EditText) findViewById(R.id.etExpMax);

        etExpDays.setText(String.valueOf(prefs.expRetainDays()));
        etExpMax.setText(String.valueOf(prefs.expRetainMax()));

        ((Button) findViewById(R.id.btnSavePool)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try { prefs.expRetainDays(Integer.parseInt(etExpDays.getText().toString().trim())); }
                catch (Exception ignored) {}
                try { prefs.expRetainMax(Integer.parseInt(etExpMax.getText().toString().trim())); }
                catch (Exception ignored) {}
                Toast.makeText(SettingsDetailActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            }
        });
    }
}
