package com.akasha.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-compatible chat completions client (supports image content parts).
 * Uses framework HttpURLConnection so it follows the Android system proxy.
 */
public class LlmClient {

    public static class Msg {
        public final String role;
        public final Object content; // String or JSONArray (for multimodal)

        public Msg(String role, Object content) {
            this.role = role;
            this.content = content;
        }
    }

    public static String chat(String baseUrl, String apiKey, String model,
                              int maxTokens, List<Msg> msgs) throws Exception {
        JSONArray arr = new JSONArray();
        for (Msg m : msgs) {
            arr.put(new JSONObject().put("role", m.role).put("content", m.content));
        }
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", arr);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.2);

        String url = buildUrl(baseUrl);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
        }

        byte[] data = body.toString().getBytes("UTF-8");
        OutputStream os = c.getOutputStream();
        os.write(data);
        os.flush();
        os.close();

        int code = c.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String resp = readAll(is);
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + " url=" + url + " : " + truncate(resp, 300));
        }

        JSONObject jo = new JSONObject(resp);
        JSONObject msg = jo.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        String content = msg.optString("content", "");
        if (content.isEmpty()) {
            content = msg.optString("reasoning_content", "");
        }
        return content;
    }

    /**
     * Accepts a bare host, a base URL with or without /v1, or the full
     * /chat/completions endpoint, and always produces the full endpoint.
     */
    public static String buildUrl(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/chat/completions")) return u;
        // bare host (no path) -> assume OpenAI layout with /v1
        if (u.matches("https?://[^/]+")) return u + "/v1/chat/completions";
        return u + "/chat/completions";
    }

    /**
     * Lightweight check before starting the agent: minimal chat request to the
     * configured endpoint. Returns a human-readable verdict ("OK: ..." or the
     * error reason), never throws.
     */
    public static String preflight(String baseUrl, String apiKey, String model) {
        try {
            List<Msg> msgs = new ArrayList<>();
            msgs.add(new Msg("user", "ping"));
            String reply = chat(baseUrl, apiKey, model, 8, msgs);
            return "OK: " + truncate(reply, 60);
        } catch (Exception e) {
            String m = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
            m = m.replace('\n', ' ');
            if (m.length() > 300) m = m.substring(0, 300);
            return m;
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            is.close();
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }
}
