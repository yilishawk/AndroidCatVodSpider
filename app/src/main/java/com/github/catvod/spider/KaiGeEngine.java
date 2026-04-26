package com.github.catvod.spider;

import android.text.TextUtils;
import android.util.Base64; 
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URLDecoder;

/**
 * 凱哥標準規則引擎 1.0 (正式版)
 * 修復：a@href 導致的屬性提取錯位及閃退問題
 * 功能：支持 &&, @, *, +, [n], [包含], [full], >, [base64]
 */
public class KaiGeEngine {

    private static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }

    public static ExtractionResult doExtract(String html, String rule, String host) {
        ExtractionResult result = new ExtractionResult();
        if (isEmpty(html) || isEmpty(rule)) return result;

        // 1. 拆分指令 (;; 分隔)
        String[] segments = rule.split(";;");
        String coreLogic = segments[0].trim();

        for (int i = 1; i < segments.length; i++) {
            String tag = segments[i].trim();
            if (tag.equalsIgnoreCase("[full]")) result.shouldFull = true;
            if (tag.matches("\\[\\d+\\]")) {
                result.index = Integer.parseInt(tag.replaceAll("[\\[\\]]", ""));
            }
            if (tag.startsWith("[包含:")) result.includeKey = tag.substring(4, tag.length() - 1);
        }

        // 2. 處理核心邏輯 (支持管道符 >)
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

        // 3. 過濾與補全
        if (!isEmpty(result.includeKey) && !finalValue.contains(result.includeKey)) finalValue = "";
        if (result.shouldFull && !isEmpty(finalValue)) finalValue = autoFullUrl(finalValue, host);

        result.value = finalValue;
        return result;
    }

    private static String processStep(String content, String step, String host) {
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
        if (step.contains(" + ")) return handleCombination(content, step, host);

        return executeSingleRule(content, step);
    }

    private static String executeSingleRule(String html, String rule) {
        // 🚀 核心改進：精確處理 @ 屬性提取，不再轉為 &&
        if (rule.contains("@")) {
            String[] parts = rule.split("@");
            String attrName = parts[parts.length - 1].trim(); // 拿到 href 或 data-src
            // 精確匹配屬性值，防止拿到 class
            Pattern p = Pattern.compile(attrName + "\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(html);
            if (m.find()) return m.group(1).trim();
            return ""; 
        }

        // 傳統 && 切分邏輯
        if (rule.contains("&&")) {
            String[] parts = rule.split("&&");
            String start = parts[0].trim();
            String end = parts.length > 1 ? parts[1].trim() : "";
            return start.contains("*") ? cutWithWildcard(html, start, end) : simpleCut(html, start, end);
        }
        return html; 
    }

    private static String handleCombination(String html, String logic, String host) {
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
      