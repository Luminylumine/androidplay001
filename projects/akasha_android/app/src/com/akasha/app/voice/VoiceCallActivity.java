package com.akasha.app.voice;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import com.akasha.app.CpLog;
import com.akasha.app.Prefs;
import com.akasha.app.R;

/**
 * Voice call screen (Task 8 / FR-7.5, §17 status-card style).
 * Read-only observer of VoiceCallService: big state card + Server/Mic/TTS
 * GREEN-YELLOW-RED-GRAY + partial/final transcript + brain answer + task
 * progress. Connection failures show an explicit reason (TR-8.2).
 */
public class VoiceCallActivity extends Activity {

    private static final int C_GREEN = 0xFF2E7D32;
    private static final int C_YELLOW = 0xFFB26A00;
    private static final int C_RED = 0xFFC0392B;
    private static final int C_GRAY = 0xFF999999;
    private static final int C_BLUE = 0xFF0A66C2;

    private TextView tvCallState, tvServerState, tvMicState, tvTtsState, tvLatency;
    private TextView tvPartial, tvFinal, tvBrainText, tvTaskProgress, tvError;
    private ScrollView svTaskProgress;
    private Button btnRedial;

    private VoiceCallService svc;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String pendingError = "";

    private final VoiceCallService.StateListener listener =
            new VoiceCallService.StateListener() {
                @Override
                public void onCallState(String state) {
                    updateCallState(state);
                }

                @Override
                public void onPartial(String text) {
                    tvPartial.setText(text);
                }

                @Override
                public void onFinal(String text) {
                    tvFinal.setText(text);
                    tvBrainText.setText(""); // new turn: brain answer restarts
                }

                @Override
                public void onBrainText(String text) {
                    String cur = tvBrainText.getText().toString();
                    tvBrainText.setText(cur.isEmpty() ? text : cur + text);
                }

                @Override
                public void onTaskGoal(String taskId, String goal, String akashaSessionId) {
                    updateCallState("task");
                    appendTask("任务: " + goal + " (" + taskId.substring(0, Math.min(8, taskId.length())) + ")");
                }

                @Override
                public void onError(String code, String message) {
                    pendingError = code + ": " + message;
                    tvError.setText(pendingError);
                    CpLog.w("VoiceCall", "server error " + code + ": " + message);
                }

                @Override
                public void onFirstAudioLatency(long ms) {
                    tvLatency.setText("首包延迟: " + ms + " ms（final → 第一帧 TTS 音频）");
                }

                @Override
                public void onCallEnded() {
                    updateCallState("ended");
                    btnRedial.setVisibility(View.VISIBLE);
                }
            };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refreshIndicators();
            main.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_call);
        tvCallState = (TextView) findViewById(R.id.tvCallState);
        tvServerState = (TextView) findViewById(R.id.tvServerState);
        tvMicState = (TextView) findViewById(R.id.tvMicState);
        tvTtsState = (TextView) findViewById(R.id.tvTtsState);
        tvLatency = (TextView) findViewById(R.id.tvLatency);
        tvPartial = (TextView) findViewById(R.id.tvPartial);
        tvFinal = (TextView) findViewById(R.id.tvFinal);
        tvBrainText = (TextView) findViewById(R.id.tvBrainText);
        tvTaskProgress = (TextView) findViewById(R.id.tvTaskProgress);
        svTaskProgress = (ScrollView) findViewById(R.id.svTaskProgress);
        tvError = (TextView) findViewById(R.id.tvError);
        btnRedial = (Button) findViewById(R.id.btnRedial);

        findViewById(R.id.btnHangup).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { hangUp(); }
        });
        btnRedial.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { redial(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        svc = VoiceCallService.get();
        if (svc != null) {
            svc.addStateListener(listener);
            btnRedial.setVisibility(svc.isHungUp() ? View.VISIBLE : View.GONE);
        } else {
            updateCallState("ended");
            btnRedial.setVisibility(View.VISIBLE);
            tvError.setText("当前没有进行中的通话");
        }
        main.removeCallbacks(poll);
        main.post(poll);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(poll);
        if (svc != null) svc.removeStateListener(listener);
        super.onPause();
    }

    private void refreshIndicators() {
        VoiceCallService s = svc;
        if (s == null || !VoiceCallService.active()) {
            // service died (killed / finished) while we are here
            svc = null;
            setInd(tvServerState, "GRAY NOT TESTED", C_GRAY);
            setInd(tvMicState, "GRAY NOT TESTED", C_GRAY);
            setInd(tvTtsState, "GRAY NOT TESTED", C_GRAY);
            updateCallState("ended");
            btnRedial.setVisibility(View.VISIBLE);
            return;
        }
        boolean serverOk = s.serverOk();
        setInd(tvServerState, serverOk ? "GREEN PASS" : "RED FAIL",
                serverOk ? C_GREEN : C_RED);
        if (!serverOk && !s.isHungUp() && pendingError.isEmpty()) {
            tvError.setText("连不上后端（正在自动重连，退避 1s→10s 封顶）。"
                    + "请确认后端已启动、地址/Token 正确（全局 → 语音通话 → 测试连接）。");
        }
        boolean micOk = s.micOk();
        setInd(tvMicState, micOk ? "GREEN PASS" : (s.isHungUp()
                ? "GRAY NOT TESTED" : "RED FAIL"),
                micOk ? C_GREEN : (s.isHungUp() ? C_GRAY : C_RED));
        setInd(tvTtsState, s.ttsOk() ? "GREEN PASS" : "GRAY 已关闭",
                s.ttsOk() ? C_GREEN : C_GRAY);
    }

    private void setInd(TextView tv, String text, int color) {
        tv.setText(text);
        tv.setTextColor(color);
    }

    private void updateCallState(String state) {
        String label;
        int color;
        switch (state) {
            case "listening":
                label = "LISTENING";
                color = C_GREEN;
                break;
            case "thinking":
                label = "THINKING";
                color = C_YELLOW;
                break;
            case "speaking":
                label = "SPEAKING";
                color = C_BLUE;
                break;
            case "task":
                label = "TASK";
                color = C_YELLOW;
                break;
            case "ended":
                label = "ENDED";
                color = C_GRAY;
                break;
            default:
                label = "CONNECTING";
                color = C_GRAY;
        }
        tvCallState.setText(label);
        tvCallState.setTextColor(color);
    }

    private void appendTask(String line) {
        String cur = tvTaskProgress.getText().toString();
        tvTaskProgress.setText(cur.isEmpty() ? line : cur + "\n" + line);
        svTaskProgress.post(new Runnable() {
            @Override
            public void run() {
                svTaskProgress.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void hangUp() {
        VoiceCallService s = svc;
        if (s == null) return;
        Intent i = new Intent(this, VoiceCallService.class);
        i.setAction(VoiceCallService.ACTION_HANGUP);
        startService(i);
    }

    private void redial() {
        Prefs prefs = new Prefs(this);
        Intent i = new Intent(this, VoiceCallService.class);
        i.setAction(VoiceCallService.ACTION_START);
        i.putExtra(VoiceCallService.EXTRA_HOST, prefs.voiceBackendHost());
        i.putExtra(VoiceCallService.EXTRA_TOKEN, prefs.voiceToken());
        startService(i);
        tvError.setText("");
        pendingError = "";
        tvPartial.setText("");
        tvFinal.setText("");
        tvBrainText.setText("");
        tvTaskProgress.setText("");
        tvLatency.setText("首包延迟: —（final → 第一帧 TTS 音频）");
        btnRedial.setVisibility(View.GONE);
        if (svc != null) svc.removeStateListener(listener);
        svc = VoiceCallService.get();
        if (svc != null) svc.addStateListener(listener);
        updateCallState("idle");
    }
}
