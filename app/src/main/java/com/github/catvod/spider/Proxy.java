package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.ProxyVideo;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

public class Proxy extends Spider {
    private static int port = -1;

    public static Object[] proxy(Map<String, String> params) throws Exception {
        String action = params.get("do");
        
        // 1. 心跳檢測
        if ("ck".equals(action)) {
            return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("ok".getBytes("UTF-8"))};
        }
        
        // 2. 凱哥專屬日誌查看接口
        if ("kaige_log".equals(action)) {
            String html = "<html><head><meta charset='utf-8'><meta http-equiv='refresh' content='2'>" +
                    "<title>KaiGe Real-time Logs</title>" +
                    "<style>body{background:#f8f9fa;padding:20px;font-family:sans-serif;} .card{background:#fff;padding:15px;border-radius:8px;box-shadow:0 2px 4px rgba(0,0,0,0.1);}</style>" +
                    "</head><body>" +
                    "<h2>🚀 凱哥實時日誌監控</h2>" +
                    "<div class='card'>" + SpiderDebug.getLogs() + "</div>" +
                    "</body></html>";
            return new Object[]{200, "text/html; charset=utf-8", new ByteArrayInputStream(html.getBytes("UTF-8"))};
        }
        
        // 3. 原有代理邏輯
        if ("proxy".equals(action)) return commonProxy(params);
        return null;
    }

    private static Object[] commonProxy(Map<String, String> params) throws Exception {
        String url = Util.base64Decode(params.get("url"));
        Map<String, String> header = new Gson().fromJson(Util.base64Decode(params.get("header")), Map.class);
        return ProxyVideo.proxyMultiThread(url, header == null ? new HashMap<>() : header);
    }

    public static String getUrl() {
        adjustPort();
        return "http://127.0.0.1:" + port + "/proxy";
    }

    static void adjustPort() {
        if (Proxy.port > 0) return;
        int p = 9978;
        while (p < 10000) {
            try {
                String resp = OkHttp.string("http://127.0.0.1:" + p + "/proxy?do=ck", null);
                if ("ok".equals(resp)) { Proxy.port = p; break; }
            } catch (Exception ignored) {}
            p++;
        }
    }
}
