package com.androidplay.mdclient.markdown;

import android.text.Html;
import android.text.Spanned;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared markdown projection boundary for Notes and whiteboard stickers. */
public final class MarkdownRenderEngine {
    private MarkdownRenderEngine() {}

    public static Spanned render(String source) {
        String html = source == null ? "" : source.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        html = html.replaceAll("(?m)^### (.+)$", "<h3>$1</h3>")
                .replaceAll("(?m)^## (.+)$", "<h2>$1</h2>")
                .replaceAll("(?m)^# (.+)$", "<h1>$1</h1>")
                .replaceAll("(?m)^[-*] (.+)$", "• $1<br>")
                .replaceAll("(?m)^\\d+\\. (.+)$", "$1<br>")
                .replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>")
                .replaceAll("(?<!\\*)\\*([^*]+)\\*", "<i>$1</i>")
                .replaceAll("`([^`]+)`", "<tt>$1</tt>")
                .replace("\n\n", "<br><br>")
                .replace("\n", "<br>");
        html = replaceMath(html, "\\$\\$(.+?)\\$\\$", "<blockquote><tt>", "</tt></blockquote>");
        html = replaceMath(html, "\\$([^$]+)\\$", "<tt>", "</tt>");
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
    }

    private static String replaceMath(String source, String expression, String open, String close) {
        Matcher matcher = Pattern.compile(expression).matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(open + mathToHtml(matcher.group(1)) + close));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String mathToHtml(String value) {
        return value.replace("\\dot{x}", "<i>x</i>&#x307;")
                .replace("\\operatorname{rank}", "<b>rank</b>")
                .replace("\\mathbf{A}", "<b>A</b>")
                .replace("\\mathbf{B}", "<b>B</b>")
                .replace("\\omega", "ω")
                .replace("\\int", "∫")
                .replaceAll("\\^\\{([^}]*)\\}", "<sup>$1</sup>")
                .replaceAll("_\\{([^}]*)\\}", "<sub>$1</sub>")
                .replace("\\ ", " ");
    }
}
