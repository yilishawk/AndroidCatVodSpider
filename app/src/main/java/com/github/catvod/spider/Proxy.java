package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.ByteArrayInputStream;
import java.util.Map;

public class Proxy extends Spider {
    private static StringBuilder sb = new StringBuilder("--- 凱哥全流程日誌系統啟動 ---<br>");

    public static void log(String msg) {
        synchronized (sb) {
            if (sb.length() > 80000) sb.setLength(0);
            String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
            sb.append("[").append(time).append("] ").append(msg).append("<br>");
        }
    }

    public static Object[] proxy(Map<String, String> params) throws Exception {
        try {
            if (params == null) return null;
            Object doObj = params.get("do");
            String action = (doObj instanceof String[]) ? ((String[]) doObj)[0] : String.valueOf(doObj);
            if ("kaige_debug".equals(action)) {
                String html = "<html><head><meta charset='utf-8'><meta http-equiv='refresh' content='3'></head>" +
                        "<body style='background:#121212;color:#00ff00;padding:20px;font-family:monospace;'>" +
                        "<h2>🚀 凱哥實時解析控制台</h2><hr>" + sb.toString() + "</body></html>";
                return new Object[]{200, "text/html; charset=utf-8", new ByteArrayInputStream(html.getBytes("UTF-8"))};
            }
        } catch (Throwable t) {}
        return null;
    }
}
