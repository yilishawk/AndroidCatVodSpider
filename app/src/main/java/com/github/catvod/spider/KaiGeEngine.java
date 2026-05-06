package com.github.catvod.spider;

import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.utils.Util;
import com.github.catvod.utils.AESEncryption;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URLDecoder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KaiGeEngine {

    public static boolean DEBUG = false;

    // 🚀 正则缓存（性能优化）
    private static final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    private static Pattern getPattern(String regex) {
        return patternCache.computeIfAbsent(regex, Pattern::compile);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.length() == 0;
    }

    // 🚀 主入口
    public static ExtractionResult doExtract(String html, String rule, String host) {

        ExtractionResult result = new ExtractionResult();
        if (isEmpty(html) || isEmpty(rule)) return result;

        String[] segments = rule.split("\\s*;;\\s*");
        String core = segments[0];

        for (int i = 1; i < segments.length; i++) {
            String tag = segments[i].trim();

            if (tag.equalsIgnoreCase("[full]")) result.shouldFull = true;
            if (tag.matches("\\[\\d+]")) {
                result.index = Integer.parseInt(tag.replaceAll("[\\[\\]]", ""));
            }
            if (tag.startsWith("[包含:")) result.includeKey = tag.substring(4, tag.length() - 1);
            if (tag.startsWith("[排除:")) result.excludeKey = tag.substring(4, tag.length() - 1);
        }

        String value = html;

        String[] steps = core.split("\\s*>\\s*");

        for (int i = 0; i < steps.length; i++) {
            value = processStep(value, steps[i].trim(), host);

            if (DEBUG) System.out.println("Step" + i + " => " + value);
        }

        // include
        if (!isEmpty(result.includeKey) && !value.contains(result.includeKey)) value = "";

        // exclude
        if (!isEmpty(result.excludeKey) && value.contains(result.excludeKey)) value = "";

        // full url
        if (result.shouldFull) value = autoFullUrl(value, host);

        result.value = value;
        return result;
    }

    // 🚀 核心处理
    private static String processStep(String content, String step, String host) {

        if (isEmpty(step)) return content;

        step = applyVars(step, content, host);

        // JSON
        if (step.startsWith("json:") || step.startsWith("[json:")) {
            String path = step.replace("[", "").replace("]", "").replace("json:", "").trim();
            return parseJson(content, path);
        }

        // base64
        if (step.equalsIgnoreCase("[base64]")) {
            try {
                return new String(Base64.decode(content, Base64.DEFAULT));
            } catch (Exception e) {
                return content;
            }
        }

        // url decode
        if (step.equalsIgnoreCase("[url_decode]")) {
            try {
                return URLDecoder.decode(content, "UTF-8");
            } catch (Exception e) {
                return content;
            }
        }

        // md5
        if (step.equalsIgnoreCase("[md5]")) {
            return Util.MD5(content);
        }

        // sha1
        if (step.equalsIgnoreCase("[sha1]")) {
            try {
                return Util.sha1Hex(content);
            } catch (Exception e) {
                return "";
            }
        }

        // aes
        if (step.startsWith("[aes_cbc:")) {
            try {
                String[] p = step.substring(9, step.length() - 1).split(",");
                return AESEncryption.decrypt(content, p[0], p.length > 1 ? p[1] : "", AESEncryption.CBC_PKCS_7_PADDING);
            } catch (Exception e) {
                return "";
            }
        }

        if (step.startsWith("[aes_ecb:")) {
            try {
                String key = step.substring(9, step.length() - 1);
                return AESEncryption.decrypt(content, key, "", AESEncryption.ECB_PKCS_7_PADDING);
            } catch (Exception e) {
                return "";
            }
        }

        // 正则
        if (step.startsWith("[reg:")) {
            Matcher m = getPattern(step.substring(5, step.length() - 1)).matcher(content);
            return m.find() ? m.group(1).trim() : "";
        }

        // 提取
        if (step.startsWith("[提取:")) {
            return executeSingleRule(content, step.substring(4, step.length() - 1));
        }

        // 时间
        if (step.equalsIgnoreCase("[time]")) {
            return String.valueOf(System.currentTimeMillis() / 1000);
        }

        if (step.equalsIgnoreCase("[time13]")) {
            return String.valueOf(System.currentTimeMillis());
        }

        // 拼接
        if (step.contains("+")) {
            return handlePlus(content, step);
        }

        return executeSingleRule(content, step);
    }

    // 🚀 变量系统
    private static String applyVars(String input, String content, String host) {
        if (input == null) return "";
        return input
                .replace("${host}", host == null ? "" : host)
                .replace("${input}", content == null ? "" : content)
                .replace("${time}", String.valueOf(System.currentTimeMillis() / 1000))
                .replace("${time13}", String.valueOf(System.currentTimeMillis()));
    }

    // 🚀 JSON解析（支持数组）
    private static String parseJson(String content, String path) {
        try {
            Object json = new JSONTokener(content).nextValue();

            String[] keys = path.split("\\.");
            Object cur = json;

            for (String k : keys) {

                if (k.contains("[")) {
                    String key = k.substring(0, k.indexOf("["));
                    int idx = Integer.parseInt(k.replaceAll(".*\\[(\\d+)]", "$1"));

                    cur = ((JSONObject) cur).optJSONArray(key);
                    if (cur == null) return "";
                    cur = ((JSONArray) cur).opt(idx);
                } else {
                    if (cur instanceof JSONObject) {
                        cur = ((JSONObject) cur).opt(k);
                    }
                }

                if (cur == null) return "";
            }

            return String.valueOf(cur);

        } catch (Exception e) {
            return "";
        }
    }

    // 🚀 拼接
    private static String handlePlus(String content, String step) {
        String[] parts = step.split("\\s*\\+\\s*");
        StringBuilder sb = new StringBuilder();

        for (String p : parts) {
            p = p.trim();

            if (p.startsWith("\"") && p.endsWith("\"")) {
                sb.append(p.substring(1, p.length() - 1));
            } else if (p.contains("@") || p.contains("&&")) {
                sb.append(executeSingleRule(content, p));
            } else {
                sb.append(p);
            }
        }

        return sb.toString();
    }

    // 🚀 单规则
    private static String executeSingleRule(String html, String rule) {

        if (rule.contains("@")) {
            Pattern p = getPattern(rule.split("@")[1] + "\\s*=\\s*[\"']([^\"']*)[\"']");
            Matcher m = p.matcher(html);
            return m.find() ? m.group(1) : "";
        }

        if (rule.contains("&&")) {
            String[] arr = rule.split("&&");
            return simpleCut(html, arr[0], arr.length > 1 ? arr[1] : "");
        }

        return html;
    }

    private static String simpleCut(String html, String start, String end) {
        int s = html.indexOf(start);
        if (s == -1) return "";
        s += start.length();
        if (isEmpty(end)) return html.substring(s);
        int e = html.indexOf(end, s);
        return e == -1 ? "" : html.substring(s, e);
    }

    // 🚀 自动补全 URL
    private static String autoFullUrl(String path, String host) {
        if (isEmpty(path) || path.startsWith("http")) return path;
        if (isEmpty(host)) return path;
        if (path.startsWith("//")) return "https:" + path;
        if (path.startsWith("/")) return host + path;
        return host + "/" + path;
    }

    // 🚀 返回结构
    public static class ExtractionResult {
        public String value = "";
        public boolean shouldFull = false;
        public int index = 0;
        public String includeKey = "";
        public String excludeKey = "";
    }
}