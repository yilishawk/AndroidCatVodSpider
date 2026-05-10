package com.github.catvod.spider;

import android.text.TextUtils;
import android.util.Base64;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 凱哥標準規則引擎 2.1（增强JSON路径支持）
 */
public class KaiGeEngine {

    private static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }

    public static ExtractionResult doExtract(String html, String rule, String host) {
        ExtractionResult result = new ExtractionResult();
        if (isEmpty(html) || isEmpty(rule)) return result;

        String[] segments = rule.split("\\s*;;\\s*");
        String coreLogic = segments[0].trim();

        for (int i = 1; i < segments.length; i++) {
            String tag = segments[i].trim();
            if (tag.equalsIgnoreCase("[full]")) result.shouldFull = true;
            if (tag.matches("\\[\\d+\\]")) {
                result.index = Integer.parseInt(tag.replaceAll("[\\[\\]]", ""));
            }
            if (tag.startsWith("[包含:")) result.includeKey = tag.substring(4, tag.length() - 1);
            if (tag.startsWith("[排除:")) result.excludeKey = tag.substring(4, tag.length() - 1);
        }

        String finalValue = "";
        if (coreLogic.contains(">")) {
            String[] steps = coreLogic.split("\\s*>\\s*");
            finalValue = html;
            for (String step : steps) {
                finalValue = processStep(finalValue, step.trim(), host);
            }
        } else {
            finalValue = processStep(html, coreLogic, host);
        }

        if (!isEmpty(result.includeKey) && !finalValue.contains(result.includeKey)) finalValue = "";
        if (!isEmpty(result.excludeKey) && finalValue.contains(result.excludeKey)) finalValue = "";

        if (result.shouldFull && !isEmpty(finalValue)) finalValue = autoFullUrl(finalValue, host);

        result.value = finalValue;
        return result;
    }

    private static String processStep(String content, String step, String host) {
        if (isEmpty(step)) return content;

        if (step.contains("json:")) {
            return extractJsonPath(content, step);
        }
        if (step.equalsIgnoreCase("[base64]")) {
            try { return new String(Base64.decode(content, Base64.DEFAULT)); } catch (Exception e) { return content; }
        }
        if (step.equalsIgnoreCase("[url_decode]")) {
            try { return URLDecoder.decode(content, "UTF-8"); } catch (Exception e) { return content; }
        }
        if (step.startsWith("[reg:")) {
            Matcher m = Pattern.compile(step.substring(5, step.length() - 1)).matcher(content);
            return m.find() ? m.group(1).trim() : "";
        }
        if (step.startsWith("[提取:") && step.endsWith("]")) {
            return executeSingleRule(content, step.substring(4, step.length() - 1));
        }
        if (step.equalsIgnoreCase("[time]")) return String.valueOf(System.currentTimeMillis() / 1000);
        if (step.equalsIgnoreCase("[time13]")) return String.valueOf(System.currentTimeMillis());
        if (step.equalsIgnoreCase("[sort_params]")) return com.github.catvod.utils.Util.sortQueryString(content);
        if (step.equalsIgnoreCase("[md5]")) return com.github.catvod.utils.Util.MD5(content);
        if (step.equalsIgnoreCase("[sha1]")) {
            try { return com.github.catvod.utils.Util.sha1Hex(content); } catch (Exception e) { return ""; }
        }
        if (step.startsWith("[aes_cbc:") && step.endsWith("]")) {
            try {
                String paramsStr = step.substring(9, step.length() - 1);
                String[] p = paramsStr.split(",");
                String key = p[0].trim();
                String iv = p.length > 1 ? p[1].trim() : "";
                return com.github.catvod.utils.AESEncryption.decrypt(content, key, iv, com.github.catvod.utils.AESEncryption.CBC_PKCS_7_PADDING);
            } catch (Exception e) { return ""; }
        }
        if (step.startsWith("[aes_ecb:") && step.endsWith("]")) {
            try {
                String key = step.substring(9, step.length() - 1).trim();
                return com.github.catvod.utils.AESEncryption.decrypt(content, key, "", com.github.catvod.utils.AESEncryption.ECB_PKCS_7_PADDING);
            } catch (Exception e) { return ""; }
        }

        if (step.contains("+")) {
            return handleCombination(content, step, host);
        }

        return executeSingleRule(content, step);
    }

    private static String extractJsonPath(String content, String step) {
        String rawPath = step.replace("[", "").replace("]", "").replace("json:", "").trim();
        try {
            String cleanContent = content.replace("\\/", "/");
            Object current = new JSONObject(cleanContent);
            String[] segments = rawPath.split("\\.");

            for (String seg : segments) {
                if (seg.contains("[")) {
                    String key = seg.substring(0, seg.indexOf("["));
                    int index = Integer.parseInt(seg.substring(seg.indexOf("[") + 1, seg.indexOf("]")));
                    if (key.isEmpty() && current instanceof JSONArray) {
                        current = ((JSONArray) current).get(index);
                    } else if (current instanceof JSONObject) {
                        current = ((JSONObject) current).getJSONArray(key).get(index);
                    }
                } else {
                    if (current instanceof JSONObject) {
                        current = ((JSONObject) current).opt(seg);
                    } else if (current instanceof JSONArray) {
                        current = ((JSONArray) current).opt(Integer.parseInt(seg));
                    }
                }
                if (current == null) return "";
            }
            return current instanceof String ? (String) current : current.toString();
        } catch (Exception e) {
            // 保底正则
            try {
                String keyName = rawPath.contains(".") ? rawPath.substring(rawPath.lastIndexOf(".") + 1) : rawPath;
                Matcher m = Pattern.compile("\"" + keyName + "\"\\s*:\\s*\"(.*?)\"").matcher(content);
                return m.find() ? m.group(1).replace("\\/", "/") : "";
            } catch (Exception ignored) {}
            return "";
        }
    }

    private static String executeSingleRule(String html, String rule) {
        if (rule.contains("@")) {
            String[] parts = rule.split("@");
            String attrName = parts[parts.length - 1].trim();
            Pattern p = Pattern.compile(attrName + "\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(html);
            return m.find() ? m.group(1).trim() : "";
        }
        if (rule.contains("&&")) {
            String[] parts = rule.split("&&");
            String start = parts[0].trim();
            String end = parts.length > 1 ? parts[1].trim() : "";
            return start.contains("*") ? cutWithWildcard(html, start, end) : simpleCut(html, start, end);
        }
        return html;
    }

    private static String handleCombination(String html, String logic, String host) {
        String[] parts = logic.split("\\s*\\+\\s*");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String item = p.trim();
            if (item.startsWith("\"") && item.endsWith("\"") && item.length() >= 2) {
                sb.append(item.substring(1, item.length() - 1));
            } else if (item.contains("@") || item.contains("&&")) {
                sb.append(executeSingleRule(html, item));
            } else {
                sb.append(item);
            }
        }
        return sb.toString();
    }

    private static String cutWithWildcard(String html, String startRule, String end) {
        try {
            String regexStart = Pattern.quote(startRule).replace("*", "\\E.*?\\Q");
            String fullRegex = regexStart + "(.*?)" + (isEmpty(end) ? "$" : Pattern.quote(end));
            Matcher matcher = Pattern.compile(fullRegex, Pattern.DOTALL).matcher(html);
            return matcher.find() ? matcher.group(1).trim() : "";
        } catch (Exception e) { return ""; }
    }

    private static String simpleCut(String html, String start, String end) {
        try {
            int s = html.indexOf(start);
            if (s > -1) {
                s += start.length();
                if (isEmpty(end)) return html.substring(s).trim();
                int e = html.indexOf(end, s);
                if (e > -1) return html.substring(s, e).trim();
            }
        } catch (Exception e) { }
        return "";
    }

    private static String autoFullUrl(String path, String host) {
        if (isEmpty(path) || path.startsWith("http")) return path;
        if (isEmpty(host)) return path;
        if (path.startsWith("//")) return "https:" + path;
        if (path.startsWith("/")) return host.endsWith("/") ? host + path.substring(1) : host + path;
        return host + (host.endsWith("/") ? "" : "/") + path;
    }

    public static class ExtractionResult {
        public String value = "";
        public boolean shouldFull = false;
        public int index = 0;
        public String includeKey = "";
        public String excludeKey = "";
    }
}