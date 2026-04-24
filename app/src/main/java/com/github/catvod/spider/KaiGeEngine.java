package com.github.catvod.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URLDecoder;

/**
 * 凱哥標準規則引擎 1.0 (終極版)
 * 集成工具：&&, @, *, +, [n], [包含], [不包含], [reg], [full], [z], >, [base64], [url_decode]
 */
public class KaiGeEngine {

    // 原生判斷空值，不依賴外部包
    private static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }

    /**
     * 提取入口
     * @param html 原始源碼
     * @param rule 規則字符串
     * @param host 域名（用於補全）
     */
    public static ExtractionResult doExtract(String html, String rule, String host) {
        ExtractionResult result = new ExtractionResult();
        if (isEmpty(html) || isEmpty(rule)) return result;

        // 1. 拆分指令掛載 (;; 分隔)
        String[] segments = rule.split(";;");
        String coreLogic = segments[0].trim();

        for (int i = 1; i < segments.length; i++) {
            String tag = segments[i].trim();
            if (tag.equalsIgnoreCase("[z]")) result.isDirect = true;
            if (tag.equalsIgnoreCase("[full]")) result.shouldFull = true;
            
            // 處理 [n] 索引定位
            if (tag.matches("\\[\\d+\\]")) {
                result.index = Integer.parseInt(tag.replaceAll("[\\[\\]]", ""));
            }
            // 處理 [包含/不包含] 過濾
            if (tag.startsWith("[包含:")) result.includeKey = tag.substring(4, tag.length() - 1);
            if (tag.startsWith("[不包含:")) result.excludeKey = tag.substring(5, tag.length() - 1);
        }

        // 2. 處理核心邏輯 (支持管道符 > 鏈式加工)
        String finalValue = "";
        if (coreLogic.contains(" > ")) {
            String[] steps = coreLogic.split(" > ");
            finalValue = html; 
            for (String step : steps) {
                finalValue = processStep(finalValue, step.trim(), host);
            }
        } else {
            finalValue = processStep(html, coreLogic, host);
        }

        // 3. 執行過濾檢查 (不符合條件則清空結果)
        if (!isEmpty(result.includeKey) && !finalValue.contains(result.includeKey)) finalValue = "";
        if (!isEmpty(result.excludeKey) && finalValue.contains(result.excludeKey)) finalValue = "";

        // 4. 執行自動補全
        if (result.shouldFull && !isEmpty(finalValue)) {
            finalValue = autoFullUrl(finalValue, host);
        }

        result.value = finalValue;
        return result;
    }

    private static String processStep(String content, String step, String host) {
        // 工具：Base64 解碼
        if (step.equalsIgnoreCase("[base64]")) return Util.base64Decode(content); 
        
        // 工具：URL 解碼
        if (step.equalsIgnoreCase("[url_decode]")) {
            try { return URLDecoder.decode(content, "UTF-8"); } catch (Exception e) { return content; }
        }
        
        // 工具：正則提取 [reg:...]
        if (step.startsWith("[reg:")) return matchReg(content, step.substring(5, step.length() - 1));
        
        // 工具：加號拼接
        if (step.contains(" + ")) return handleCombination(content, step);
        
        // 工具：基礎提取 (@ 或 &&)
        return executeSingleRule(content, step);
    }

    private static String executeSingleRule(String html, String rule) {
        String normRule = rule.replace("@", "&&");
        if (normRule.contains("&&")) {
            String[] parts = normRule.split("&&");
            String start = parts[0].trim();
            String end = parts.length > 1 ? parts[1].trim() : "";
            // 支持 * 通配穿透
            return start.contains("*") ? cutWithWildcard(html, start, end) : simpleCut(html, start, end);
        }
        return html; 
    }

    private static String handleCombination(String html, String logic) {
        String[] parts = logic.split("\\+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String item = p.trim().replace("\"", "");
            if (item.contains("@") || item.contains("&&")) {
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
            Pattern pattern = Pattern.compile(fullRegex, Pattern.DOTALL);
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) return matcher.group(1).trim();
        } catch (Exception ignored) {}
        return "";
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
        } catch (Exception ignored) {}
        return "";
    }

    private static String matchReg(String content, String reg) {
        try {
            Matcher m = Pattern.compile(reg).matcher(content);
            if (m.find()) return m.group(1).trim();
        } catch (Exception ignored) {}
        return "";
    }

    private static String autoFullUrl(String url, String host) {
        if (url.startsWith("http") || isEmpty(host)) return url;
        String base = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return base + url;
        return base + "/" + url;
    }

    public static class ExtractionResult {
        public String value = "";
        public boolean isDirect = false; 
        public boolean shouldFull = false;
        public int index = -1;
        public String includeKey = "";
        public String excludeKey = "";
    }
}
