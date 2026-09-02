package com.akasha.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Full-screen log detail behind a long-pressed system line (req 5):
 *  - model raw output / system raw error / app interpretation
 *  - 关闭 (top-left), 导出 to /sdcard/Download (top-right)
 *  - red 打断并告知模型: feeds the error + tool usage hint back to the model
 *    (as guide if running in this session, else queued until next start).
 *    Stays on this screen; the chat shows a short system line afterwards.
 */
public class LogDetailActivity extends Activity {

    private String sessionId = null;
    private String text = "";
    private String raw = "";
    private String err = "";
    private String hint = "";
    private String tool = null;
    private boolean told = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_detail);
        sessionId = getIntent().getStringExtra("sessionId");
        text = getIntent().getStringExtra("text");
        String meta = getIntent().getStringExtra("meta");
        if (meta != null) {
            try {
                JSONObject o = new JSONObject(meta);
                raw = o.optString("raw", "");
                err = o.optString("err", "");
                hint = o.optString("hint", "");
                tool = o.optString("tool", null);
            } catch (Exception ignored) {}
        }

        TextView tvRaw = (TextView) findViewById(R.id.tvLogRaw);
        TextView tvErr = (TextView) findViewById(R.id.tvLogErr);
        TextView tvHint = (TextView) findViewById(R.id.tvLogHint);
        tvRaw.setText(raw.isEmpty() ? "(无)" : raw);
        tvErr.setText(err.isEmpty() ? "(无)" : err);
        tvHint.setText(hint.isEmpty() ? "(无)" : hint);

        tvRaw.setOnLongClickListener(v -> copy("模型原始输出", tvRaw.getText().toString()));
        tvErr.setOnLongClickListener(v -> copy("系统报错", tvErr.getText().toString()));
        tvHint.setOnLongClickListener(v -> copy("系统解释", tvHint.getText().toString()));

        findViewById(R.id.btnLogClose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        ((TextView) findViewById(R.id.btnLogExport)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export();
            }
        });

        Button btnTell = (Button) findViewById(R.id.btnTellModel);
        btnTell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tellModel();
            }
        });
    }

    private boolean copy(String what, String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(what, s));
            Toast.makeText(this, "已复制: " + what, Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private String buildText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Akasha 日志详情\n");
        sb.append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        sb.append("系统提示: ").append(text == null ? "" : text).append('\n');
        sb.append("\n── 模型原始输出 ──\n").append(raw.isEmpty() ? "(无)" : raw).append('\n');
        sb.append("\n── 系统原始报错/输出 ──\n").append(err.isEmpty() ? "(无)" : err).append('\n');
        sb.append("\n── 系统解释/反馈 ──\n").append(hint.isEmpty() ? "(无)" : hint).append('\n');
        return sb.toString();
    }

    /** Default target: /sdcard/Download (req 5). */
    private void export() {
        try {
            File dir = new File(android.os.Environment.getExternalStorageDirectory(), "Download");
            if (!dir.isDirectory()) dir = getExternalFilesDir(null);
            if (dir == null) {
                Toast.makeText(this, "无可用导出目录", Toast.LENGTH_SHORT).show();
                return;
            }
            String name = "akasha_log_"
                    + new SimpleDateFormat("MMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
            File f = new File(dir, name);
            FileOutputStream os = new FileOutputStream(f);
            os.write(buildText().getBytes("UTF-8"));
            os.close();
            Toast.makeText(this, "已导出: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e, Toast.LENGTH_LONG).show();
        }
    }

    private void tellModel() {
        if (told) {
            Toast.makeText(this, "已告知，下次启动 Agent 时自动生效", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder tell = new StringBuilder();
        tell.append("你上一次的调用出错了。");
        if (tool != null) tell.append("工具/动作: ").append(tool).append("。");
        if (!err.isEmpty()) tell.append("错误: ").append(err).append("。");
        if (!hint.isEmpty()) tell.append("正确用法提示: ").append(hint).append(" ");
        tell.append("请修正后重试，并用 say 向用户说明原因。");
        final String tellText = tell.toString();

        boolean sameSessionRunning = AgentService.isSessionRunning(sessionId);
        if (sameSessionRunning) {
            AgentService.addGuideToSession(tellText, sessionId);
        } else if (sessionId != null) {
            new SessionStore(this).appendNote(sessionId, tellText); // next task start
        }

        // short system line back in this session's chat
        String shortSum = (tool == null ? "错误" : tool + " 错误")
                + (err.isEmpty() ? "" : ": " + firstWords(err, 30));
        String line = "已打断并告知模型: " + shortSum;
        SessionStore st = new SessionStore(this);
        if (sameSessionRunning) {
            AgentService.logToSession(sessionId, line);
        } else {
            st.appendChat(sessionId, "system", line, System.currentTimeMillis(), null);
        }

        told = true;
        Button btnTell = (Button) findViewById(R.id.btnTellModel);
        btnTell.setText("已告知模型 ✓（关闭后在对话中可见）");
        btnTell.setEnabled(false);
        Toast.makeText(this,
                sameSessionRunning ? "已注入，下一轮生效" : "已记录，下次启动该会话任务时生效",
                Toast.LENGTH_SHORT).show();
        // stays on this screen; user closes manually (req 5)
    }

    private static String firstWords(String s, int n) {
        s = s.replace('\n', ' ').trim();
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
