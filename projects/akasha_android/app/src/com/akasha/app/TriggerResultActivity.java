package com.akasha.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 触发结果选择页:
 *  - 发送指定信息(排队 / 打断并发送 / 引导 + 内容) 或 发送「继续」
 *  - 顶栏: 返回(红, 不改动) / 清空重选(黄, 恢复默认) / 确定(蓝, 返回 JSON)
 */
public class TriggerResultActivity extends Activity {

    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_AGENT_NAME = "agentName";
    public static final String EXTRA_RESULT = "result"; // 入参(当前值) / 出参 JSON

    private RadioGroup rgType, rgMode;
    private RadioButton rbMsg, rbContinue, rbQueue, rbInterrupt, rbGuide;
    private EditText etMsg;
    private LinearLayout llMsg;
    private String event = "task_done";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trigger_result);
        BackgroundHelper.apply(this, findViewById(R.id.resultRoot), BackgroundHelper.PAGE_MODEL);

        event = getIntent().getStringExtra(EXTRA_EVENT);
        if (event == null) event = "task_done";
        String agentName = getIntent().getStringExtra(EXTRA_AGENT_NAME);

        rgType = (RadioGroup) findViewById(R.id.rgTrType);
        rgMode = (RadioGroup) findViewById(R.id.rgTrMode);
        rbMsg = (RadioButton) findViewById(R.id.rbTrMsg);
        rbContinue = (RadioButton) findViewById(R.id.rbTrContinue);
        rbQueue = (RadioButton) findViewById(R.id.rbTrQueue);
        rbInterrupt = (RadioButton) findViewById(R.id.rbTrInterrupt);
        rbGuide = (RadioButton) findViewById(R.id.rbTrGuide);
        etMsg = (EditText) findViewById(R.id.etTrMsg);
        llMsg = (LinearLayout) findViewById(R.id.llTrMsg);

        TextView title = (TextView) findViewById(R.id.tvTrTitle);
        title.setText(eventLabel() + " · 触发结果" + (agentName == null || agentName.isEmpty() ? "" : "（" + agentName + "）"));

        // 载入当前值
        TimerConfig.TriggerResult cur = null;
        try {
            String j = getIntent().getStringExtra(EXTRA_RESULT);
            if (j != null && !j.isEmpty()) {
                JSONObject o = new JSONObject(j);
                cur = new TimerConfig.TriggerResult();
                cur.type = o.optInt("type", TimerConfig.TRIG_SEND_CONTINUE);
                cur.sendMode = o.optInt("sendMode", TimerConfig.MODE_QUEUE);
                cur.message = o.optString("message", "");
            }
        } catch (Exception ignored) {}
        applyResult(cur == null ? TimerConfig.TriggerResult.def() : cur);

        final Runnable syncMsgVis = new Runnable() {
            @Override
            public void run() {
                llMsg.setVisibility(rbMsg.isChecked() ? View.VISIBLE : View.GONE);
            }
        };
        rbMsg.setOnClickListener(v -> syncMsgVis.run());
        rbContinue.setOnClickListener(v -> syncMsgVis.run());

        findViewById(R.id.btnTrBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); } // 返回 = 不改动
        });
        findViewById(R.id.btnTrReset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { applyResult(TimerConfig.TriggerResult.def()); }
        });
        findViewById(R.id.btnTrOk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TimerConfig.TriggerResult t = new TimerConfig.TriggerResult();
                t.type = rbContinue.isChecked() ? TimerConfig.TRIG_SEND_CONTINUE : TimerConfig.TRIG_SEND_MESSAGE;
                if (rbQueue.isChecked()) t.sendMode = TimerConfig.MODE_QUEUE;
                else if (rbInterrupt.isChecked()) t.sendMode = TimerConfig.MODE_INTERRUPT;
                else t.sendMode = TimerConfig.MODE_GUIDE;
                t.message = etMsg.getText().toString().trim();
                try {
                    JSONObject o = new JSONObject();
                    o.put("type", t.type);
                    o.put("sendMode", t.sendMode);
                    o.put("message", t.message);
                    Intent data = new Intent().putExtra(EXTRA_RESULT, o.toString());
                    setResult(RESULT_OK, data);
                    finish();
                    return;
                } catch (Exception e) {
                    finish();
                }
            }
        });
    }

    private void applyResult(TimerConfig.TriggerResult t) {
        if (t.type == TimerConfig.TRIG_SEND_CONTINUE) rbContinue.setChecked(true);
        else rbMsg.setChecked(true);
        if (t.sendMode == TimerConfig.MODE_INTERRUPT) rbInterrupt.setChecked(true);
        else if (t.sendMode == TimerConfig.MODE_GUIDE) rbGuide.setChecked(true);
        else rbQueue.setChecked(true);
        etMsg.setText(t.message == null ? "" : t.message);
        llMsg.setVisibility(rbMsg.isChecked() ? View.VISIBLE : View.GONE);
    }

    private String eventLabel() {
        if ("task_done".equals(event)) return "任务完成";
        if ("app_open".equals(event)) return "打开软件";
        if ("alarm".equals(event)) return "闹钟";
        if ("countdown".equals(event)) return "倒计时";
        return "触发";
    }
}
