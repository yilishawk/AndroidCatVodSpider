package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.ByteArrayInputStream;
import java.util.Map;

public class Proxy extends Spider {
    private static StringBuilder sb = new StringBuilder("--- 凱哥全流程日誌系統啟動 ---<br>");

    public static int getPort() {
        return 9978;
    }

    public static String getUrl() {
        return "http://127.0.0.1:9978/proxy";
    }

    public static void log(String msg) {
        synchronized (sb) {
            if (sb.length() > 150000) sb.setLength(0); // 增加緩衝區到 150KB
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
                        "<body style='background:#0d1117;color:#58a6ff;padding:20px;font-family:monospace;line-height:1.6;'>" +
                        "<h2 style='color:#f0f6fc;border-bottom:1px solid #30363d;padding-bottom:10px;'>🚀 凱哥實時解析日誌控制台</h2>" +
                        "<div style='background:#161b22;padding:15px;border-radius:6px;border:1px solid #30363d;'>" + sb.toString() + "</div>" +
                        "</body></html>";
                return new Object[]{200, "text/html; charset=utf-8", new ByteArrayInputStream(html.getBytes("UTF-8"))};
            }
        } catch (Throwable t) {
            return new Object[]{200, "text/plain", new ByteArrayInputStream(t.toString().getBytes())};
        }
        return null;
    }
}
