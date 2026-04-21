package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import java.io.ByteArrayInputStream;
import java.util.Map;

public class Proxy extends Spider {
    // 端口我們固定一個，避開 9978
    private static final int MY_PORT = 12345;

    public static String getUrl() {
        return "http://127.0.0.1:" + MY_PORT + "/proxy";
    }

    public static int getPort() {
        return MY_PORT;
    }

    /**
     * 核心修理目標：徹底解決 Attempt to read from null array
     */
    public static Object[] proxy(Map<String, String> params) throws Exception {
        try {
            // 1. 極致防禦：如果 params 本身就是空的
            if (params == null || params.isEmpty()) {
                return textResponse("Error: Params is null or empty");
            }

            // 2. 核心診斷：處理可能是 String[] 的情況
            Object doObj = params.get("do");
            if (doObj == null) {
                return textResponse("Error: 'do' parameter is missing");
            }

            String action;
            if (doObj instanceof String[]) {
                String[] arr = (String[]) doObj;
                action = (arr.length > 0) ? arr[0] : "";
            } else {
                action = String.valueOf(doObj);
            }

            // 3. 只保留日誌查看功能，確保它絕對優先且簡單
            if ("kaige_debug".equals(action)) {
                String logs = SpiderDebug.getLogs();
                if (logs == null || logs.isEmpty()) logs = "尚未有日誌產生，請在 App 內操作分類或搜索。";
                
                String html = "<html><head><meta charset='utf-8'><meta http-equiv='refresh' content='2'></head>" +
                             "<body style='background:#000;color:#0f0;padding:20px;font-family:monospace;line-height:1.5;'>" +
                             "<h2 style='color:#fff;border-bottom:1px solid #333;'>🚀 凱哥日誌控制台 (Port: " + MY_PORT + ")</h2>" +
                             "<div>" + logs + "</div>" +
                             "</body></html>";
                
                return new Object[]{200, "text/html; charset=utf-8", new ByteArrayInputStream(html.getBytes("UTF-8"))};
            }

            // 4. 其他請求（如 ali, quark）暫時做空處理或簡單轉發
            // 防止因為其他業務邏輯報錯影響日誌查看
            return textResponse("Action '" + action + "' received, but log-only mode is ON.");

        } catch (Throwable t) {
            // 5. 終極捕獲：如果代碼出錯，把錯誤堆棧直接噴在網頁上
            String stackTrace = t.toString();
            return new Object[]{200, "text/html", new ByteArrayInputStream(("🚨 Crash: " + stackTrace).getBytes("UTF-8"))};
        }
    }

    private static Object[] textResponse(String txt) throws Exception {
        return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream(txt.getBytes("UTF-8"))};
    }
}
