package com.akasha.app;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import org.json.JSONObject;

/**
 * Backend /health probe for the settings "测试连接" button (voice-bridge §18
 * Test 1 interaction). Returns a human-readable multiline result; runs on a
 * worker thread only.
 */
public final class VoiceHealth {

    private static final int TIMEOUT_MS = 5000;

    private VoiceHealth() {}

    public static String test(String host) {
        if (host == null || host.isEmpty()) host = Prefs.DEF_VOICE_HOST;
        String base = host.startsWith("http") ? host : "http://" + host;
        String urlStr = base.replaceAll("/+$", "") + "/health";
        HttpURLConnection c = null;
        long t0 = System.currentTimeMillis();
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            long ms = System.currentTimeMillis() - t0;
            StringBuilder sb = new StringBuilder();
            sb.append("URL: ").append(urlStr).append('\n');
            sb.append("HTTP status: ").append(code).append('\n');
            if (code == 200) {
                String body = readAll(c.getInputStream());
                JSONObject o = new JSONObject(body);
                sb.append("backend version: ").append(o.optString("version", "?")).append('\n');
                sb.append("往返延迟: ").append(ms).append(" ms").append('\n');
                sb.append("ASR loaded: ").append(o.optBoolean("asr_loaded")).append('\n');
                sb.append("TTS loaded: ").append(o.optBoolean("tts_loaded")).append('\n');
                sb.append("CUDA: ").append(o.optBoolean("cuda")).append('\n');
                sb.append(o.optBoolean("ok") ? "→ 连接正常" : "→ 后端报告不健康");
            } else {
                sb.append("往返: ").append(ms).append(" ms\n→ 连接失败（HTTP ").append(code).append("）");
            }
            return sb.toString();
        } catch (Exception e) {
            return "URL: " + urlStr + "\n→ 连接失败: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage())
                    + "\n（后端未启动 / 地址错误 / 手机与后端不在同一网络）";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }
}
