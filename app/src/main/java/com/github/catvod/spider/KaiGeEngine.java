package com.github.catvod.spider;

import android.text.TextUtils;
import android.util.Base64; 
import android.util.Log;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URLDecoder;
import com.github.catvod.utils.Util;
import com.github.catvod.utils.AESEncryption;

/**
 * 凱哥標準規則引擎 2.0 (空格自由版) - 【带详细调试日志版】
 */
public class KaiGeEngine {

    private static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }

    public static ExtractionResult doExtract(String html, String rule, String host) {
        ExtractionResult result = new ExtractionResult();
        
        Log.i("KaiGeEngine", "🔍 doExtract 开始执行");
        Log.i("KaiGeEngine", "   rule = " + rule);
        Log.i("KaiGeEngine", "   host = " + host);
        Log.i("KaiGeEngine", "   html长度 = " + (html == null ? 0 : html.length()));

        if (isEmpty(html) || isEmpty(rule)) {
            Log.w("KaiGeEngine", "⚠️ html或rule为空，返回空结果");
            return result;
        }

        // 1. 指令拆分 (;; 分隔)
        String[] segments = rule.split("\\s*;;\\s*");
        String coreLogic = segments[0].trim();

        Log.i("KaiGeEngine", "📌 核心逻辑: " + coreLogic);

        for (int i = 1; i < segments.length; i++) {
            String tag = segments[i].trim();
            Log.i("KaiGeEngine", "   检测指令: " + tag);
            
            if (tag.equalsIgnoreCase("[full]")) {
                result.shouldFull = true;
                Log.i("KaiGeEngine", "   → 启用 [full] 自动补全");
            }
            if (tag.matches("\\[\\d+\\]")) {
                result.index = Integer.parseInt(tag.replaceAll("[\\[\\]]", ""));
                Log.i("KaiGeEngine", "   → 索引 [n] = " + result.index);
            }
            if (tag.startsWith("[包含:")) {
                result.includeKey = tag.substring(4, tag.length() - 1);
                Log.i("KaiGeEngine", "   → 包含过滤: " + result.includeKey);
            }
            if (tag.startsWith("[排除:")) {
                result.excludeKey = tag.substring(4, tag.length() - 1);
                Log.i("KaiGeEngine", "   → 排除过滤: " + result.excludeKey);
            }
        }

        // 2. 处理核心逻辑
        String finalValue = "";
        if (coreLogic.contains(">")) {
            String[] steps = coreLogic.split("\\s*>\\s*");
            finalValue = html; 
            Log.i("KaiGeEngine", "🔀 进入管道模式，共 " + steps.length + " 步");
            for (String step : steps) {
                Log.i("KaiGeEngine", "   → 管道步骤: " + step.trim());
                finalValue = processStep(finalValue, step.trim(), host);
            }
        } else {
            finalValue = processStep(html, coreLogic, host);
        }

        Log.i("KaiGeEngine", "📊 核心处理后结果: " + finalValue);

        // 过滤
        if (!isEmpty(result.includeKey) && !finalValue.contains(result.includeKey)) {
            finalValue = "";
            Log.i("KaiGeEngine", "🚫 包含过滤未通过，清空结果");
        }
        if (!isEmpty(result.excludeKey) && finalValue.contains(result.excludeKey)) {
            finalValue = "";
            Log.i("KaiGeEngine", "🚫 排除过滤触发，清空结果");
        }

        if (result.shouldFull && !isEmpty(finalValue)) {
            finalValue = autoFullUrl(finalValue, host);
            Log.i("KaiGeEngine", "🔗 [full] 补全后: " + finalValue);
        }

        result.value = finalValue;
        Log.i("KaiGeEngine", "✅ doExtract 最终返回: " + finalValue);
        return result;
    }

    private static String processStep(String content, String step, String host) {
        Log.i("KaiGeEngine", "🔧 processStep | step = " + step);

        if (isEmpty(step)) {
            Log.w("KaiGeEngine", "   step为空，返回原内容");
            return content;
        }

        // JSON 处理
        if (step.contains("json:")) {
            Log.i("KaiGeEngine", "📊 进入 JSON 提取模式");
            try {
                String path = step.replace("[", "").replace("]", "").replace("json:", "").trim();
                Log.i("KaiGeEngine", "   JSON路径: " + path);

                String cleanContent = content.replace("\\/", "/");
                org.json.JSONObject obj = new org.json.JSONObject(cleanContent);

                if (path.contains(".")) {
                    String[] keys = path.split("\\.");
                    Object current = obj;
                    for (int i = 0; i < keys.length; i++) {
                        if (i == keys.length - 1) {
                            String val = ((org.json.JSONObject) current).optString(keys[i], "");
                            Log.i("KaiGeEngine", "   JSON提取成功: " + val);
                            return val;
                        } else {
                            current = ((org.json.JSONObject) current).optJSONObject(keys[i]);
                            if (current == null) {
                                Log.w("KaiGeEngine", "   JSON路径中断于: " + keys[i]);
                                return "";
                            }
                        }
                    }
                }
                return obj.optString(path, "");
            } catch (Exception e) {
                Log.e("KaiGeEngine", "❌ JSON解析失败: " + e.getMessage());
            }
        }

        // + 拼接（重点日志）
        if (step.contains("+")) {
            Log.i("KaiGeEngine", "🔗 检测到 + 拼接，进入 handleCombination");
            return handleCombination(content, step, host);
        }

        return executeSingleRule(content, step);
    }

    private static String handleCombination(String html, String logic, String host) {
        Log.i("KaiGeEngine", "🔗 handleCombination 开始 | logic = " + logic);

        String[] parts = logic.split("\\s*\\+\\s*");
        StringBuilder sb = new StringBuilder();

        for (String p : parts) {
            String item = p.trim();
            Log.i("KaiGeEngine", "   拼接片段: [" + item + "]");

            if (item.startsWith("\"") && item.endsWith("\"") && item.length() >= 2) {
                String str = item.substring(1, item.length() - 1);
                sb.append(str);
                Log.i("KaiGeEngine", "     → 字符串常量: " + str);
            } 
            else if (item.contains("@") || item.contains("&&")) {
                String extracted = executeSingleRule(html, item);
                sb.append(extracted);
                Log.i("KaiGeEngine", "     → 提取结果: " + extracted);
            } 
            else {
                sb.append(item);
                Log.i("KaiGeEngine", "     → 普通字符串: " + item);
            }
        }

        String result = sb.toString();
        Log.i("KaiGeEngine", "🔗 拼接完成: " + result);
        return result;
    }

    private static String executeSingleRule(String html, String rule) {
        Log.i("KaiGeEngine", "   executeSingleRule | rule = " + rule);
        // 原有实现保持不变
        if (rule.contains("@")) {
            String[] parts = rule.split("@");
            String attrName = parts[parts.length - 1].trim(); 
            Pattern p = Pattern.compile(attrName + "\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(html);
            if (m.find()) {
                String val = m.group(1).trim();
                Log.i("KaiGeEngine", "     → @属性提取成功: " + val);
                return val;
            }
            Log.w("KaiGeEngine", "     → @属性提取失败");
            return ""; 
        }

        if (rule.contains("&&")) {
            String[] parts = rule.split("&&");
            String start = parts[0].trim();
            String end = parts.length > 1 ? parts[1].trim() : "";
            String val = start.contains("*") ? cutWithWildcard(html, start, end) : simpleCut(html, start, end);
            Log.i("KaiGeEngine", "     → &&提取结果: " + val);
            return val;
        }
        return html; 
    }

    private static String cutWithWildcard(String html, String startRule, String end) {
        try {
            String regexStart = Pattern.quote(startRule).replace("*", "\\E.*?\\Q");
            String fullRegex = regexStart + "(.*?)" + (isEmpty(end) ? "$" : Pattern.quote(end));
            Pattern pattern = Pattern.compile(fullRegex, Pattern.DOTALL);
            Matcher matcher = pattern.matcher(html);
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
        } catch (Exception e) { return ""; }
        return "";
    }

    private static String autoFullUrl(String path, String host) {
        if (isEmpty(path) || path.startsWith("http")) return path;
        if (isEmpty(host)) return path;
        if (path.startsWith("//")) return "https:" + path;
        if (path.startsWith("/")) {
            if (host.endsWith("/")) return host + path.substring(1);
            return host + path;
        }
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
