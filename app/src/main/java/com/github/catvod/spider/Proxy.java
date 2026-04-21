package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.ByteArrayInputStream;
import java.util.Map;

public class Proxy extends Spider {
    // 凱哥日誌緩衝區
    private static StringBuilder sb = new StringBuilder("--- 凱哥全流程日誌系統啟動 ---<br>");

    // 1. 補回丟失的核心方法：獲取本地代理 URL
    public static String getUrl() {
        return "http://127.0.0.1:9978/proxy";
    }

    // 2. 凱哥專屬日誌接口
    public static void log(String msg) {
        synchronized (sb) {
            if (sb.length() > 100000) sb.setLength(0);
            String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
            sb.append("[").append(time).append("] ").append(msg).append("<br>");
        }
    }

    @Override
    public static Object[] proxy(Map<String, String> params) throws Exception {
        try {
            if (params == null) return null;
            Object doObj = params.get("do");
            String action = (doObj instanceof String[]) ? ((String[]) doObj)[0] : String.valueOf(doObj);

            // 凱哥日誌查看地址：http://手機IP:9978/proxy?do=kaige_debug
            if ("kaige_debug".equals(action)) {
                String html = "<html><head><meta charset='utf-8'><meta http-equiv='refresh' content='3'></head>" +
                        "<body style='background:#121212;color:#00ff00;padding:20px;font-family:monospace;line-height:1.5;'>" +
                        "<h2 style='color:#fff;border-bottom:1px solid #333;'>🚀 凱哥實時解析日誌控制台</h2>" +
                        "<div>" + sb.toString() + "</div></body></html>";
                return new Object[]{200, "text/html; charset=utf-8", new ByteArrayInputStream(html.getBytes("UTF-8"))};
            }
            
            // 這裡可以根據需要擴展其他的 do 邏輯（如 bili, ali 等）
            
        } catch (Throwable t) {
            return new Object[]{200, "text/plain", new ByteArrayInputStream(t.toString().getBytes())};
        }
        return null;
    }
}
