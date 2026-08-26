package com.akasha.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 全局设置主页（按键→子页面索引模式，重构 2026-08-26）。
 * 主页只列条目 + 当前值摘要，点击任一条目进入对应子页面完成设置:
 *   - 权限与通道状态 → SettingsDetailActivity(SEC_STATUS)
 *   - 开机自启（总门控）→ SettingsDetailActivity(SEC_BOOT)
 *   - 默认 API 与模型   → SettingsDetailActivity(SEC_API)
 *   - 运行参数          → SettingsDetailActivity(SEC_PARAMS)
 *   - 经验池保留策略    → SettingsDetailActivity(SEC_POOL)
 *   - 系统提示词        → PromptEditorActivity(SCOPE_SYSTEM)
 * 主页由 activity_settings 的 ScrollView 包裹，子页面内部也各自可滚动，
 * 后期增加设置项不影响布局。
 *
 * 提示词三级回退: 会话提示词(对话→设置) → 模型提示词(通讯录→Agent) → 系统提示词(本面板)。
 * 单 Agent 的 API/权限/模型级 Prompt 仍在 ModelSettingsActivity（通讯录 → 某 Agent）。
 */
public class SettingsPanel {

    private final Activity act;
    private final LinearLayout container;
    private final Prefs prefs;

    public static SettingsPanel attach(Activity act, View searchRoot) {
        return new SettingsPanel(act, searchRoot);
    }

    private SettingsPanel(Activity act, View searchRoot) {
        this.act = act;
        this.prefs = new Prefs(act);
        this.container = (LinearLayout) searchRoot.findViewById(R.id.llSettingsIndex);
        onResume();
    }

    public void onResume() {
        container.removeAllViews();
        addRow("🔐 权限与通道状态", statusSummary(),
                new SectionLauncher(SettingsDetailActivity.SEC_STATUS));
        addRow("🚀 开机自启（总门控）",
                prefs.autoStart() ? "已开启（勾选自启的 Agent 会在开机后被唤起）"
                                  : "已关闭（所有 Agent 的开机自启都不生效）",
                new SectionLauncher(SettingsDetailActivity.SEC_BOOT));
        addRow("🌐 默认 API 与模型",
                prefs.baseUrl() + " / " + prefs.model(),
                new SectionLauncher(SettingsDetailActivity.SEC_API));
        addRow("⚙️ 运行参数",
                "输出上限 " + prefs.maxTokens() + " · 间隔 " + prefs.intervalMs() / 1000 + "s · 历史 "
                        + prefs.historyRounds() + " 轮",
                new SectionLauncher(SettingsDetailActivity.SEC_PARAMS));
        addRow("📦 经验池保留策略",
                "保留 " + prefs.expRetainDays() + " 天 / " + prefs.expRetainMax() + " 条（0=不限）",
                new SectionLauncher(SettingsDetailActivity.SEC_POOL));
        addRow("💬 系统提示词",
                AgentPrompts.systemPromptSet(act)
                        ? "已自定义（回退链最底层，可在此编辑/恢复内置默认）"
                        : "使用内置默认（可在此自定义；会话/模型提示词为空时生效）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        act.startActivity(new Intent(act, PromptEditorActivity.class)
                                .putExtra(PromptEditorActivity.EXTRA_SCOPE,
                                        PromptEditorActivity.SCOPE_SYSTEM));
                    }
                });
    }

    /** 通道状态一行摘要（详情在子页面）。 */
    private String statusSummary() {
        String shell = ShellChannel.available() ? "Shell✓" : "Shell✗";
        String sz = ShellChannel.shizukuStatus() == 2 ? "Shizuku✓" : "Shizuku✗";
        String a11y = ControlService.ready() ? "无障碍✓" : "无障碍✗";
        String shot = ScreenShotService.active() ? "屏幕捕获✓" : "屏幕捕获✗";
        return shell + " · " + sz + " · " + a11y + " · " + shot;
    }

    /** Index row: title bold / sub gray / arrow / clickable。 */
    private void addRow(String title, String sub, View.OnClickListener onClick) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        r.setPadding(pad, pad, pad, pad);
        r.setBackgroundColor(Color.WHITE);
        r.setClickable(true);
        r.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        r.setLayoutParams(lp);

        LinearLayout hl = new LinearLayout(act);
        hl.setOrientation(LinearLayout.HORIZONTAL);
        hl.setGravity(android.view.Gravity.CENTER_VERTICAL);
        hl.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView t = new TextView(act);
        t.setText(title);
        t.setTextColor(Color.BLACK);
        t.setTextSize(16);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = new TextView(act);
        arrow.setText("›");
        arrow.setTextColor(0xFF999999);
        arrow.setTextSize(20);
        hl.addView(t);
        hl.addView(arrow);
        r.addView(hl);

        TextView s = new TextView(act);
        s.setText(sub == null ? "" : sub);
        s.setTextColor(0xFF666666);
        s.setTextSize(13);
        r.addView(s);

        r.setOnClickListener(onClick);
        container.addView(r);
    }

    /** 便捷构造: 点击后打开 SettingsDetailActivity 对应区块。 */
    public static class SectionLauncher implements View.OnClickListener {
        private final String section;
        public SectionLauncher(String section) { this.section = section; }
        @Override
        public void onClick(View v) {
            Context c = v.getContext();
            c.startActivity(new Intent(c, SettingsDetailActivity.class)
                    .putExtra(SettingsDetailActivity.EXTRA_SECTION, section));
        }
    }

    private int dp(int v) {
        float d = act.getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }
}
