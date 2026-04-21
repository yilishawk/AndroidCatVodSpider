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

    /**
     * 外部調用的關鍵方法：獲取當前代理端口
     * 解決 ProxyVideo.java:38 報錯的關鍵就在這裡
     */
    public static int getPort() {
        adjustPort();
        return port;
    }

    public static Object[] proxy(Map<String, String> params) throws Exception {
        String action = params.get("do");
        if (action == null) return null;

        switch (action) {
            case "ck":
                return new Object[]{200, "text/plain; charset=utf-8", new ByteArrayInputStream("ok".getBytes("UTF-8"))};
            
            // --- 凱哥專屬：實時日誌查看端口 ---
            case "kaige_debug":
                String html = "<html><head><meta charset='utf-8'><meta http-equiv='refresh' content='2'>" +
                        "<title>KaiGe Debug Port</title>" +
                        "<style>body{background:#f0f2f5;padding:20px;font-family:sans-serif;} " +
                        ".card{background:#fff;padding:15px;border-radius:4px;box-shadow:0 1px 3px rgba(0,0,0,0.1);}</style>" +
                        "</head><body>" +
                        "<h3>🚀 實時日誌監控 (2秒自刷)</h3>" +
                        "<div class='card'>" + SpiderDebug.getLogs() + "</div>" +
                        "</body></html>";
                return new Object[]{200, "text/html; charset=utf-8", new ByteArrayInputStream(html.getBytes("UTF-8"))};
            
            // --- 原有業務邏輯 ---
            case "ali": return Ali.proxy(params);
            case "quark": return Quark.proxy(params);
            case "uc": return UC.proxy(params);
            case "proxy": return commonProxy(params);
            default: return null;
        }
    }

    private static Object[] commonProxy(Map<String, String> params) throws Exception {
        String url = Util.base64Decode(params.get("url"));
        Map<String, String> header = new Gson().fromJson(Util.base64Decode(params.get("header")), Map.class);
        if (header == null) header = new HashMap<>();
        return ProxyVideo.proxyMultiThread(url, header);
    }

    // 自動偵測並鎖定可用端口
    static void adjustPort() {
        if (Proxy.port > 0) return;
        int p = 9978;
        while (p < 10000) {
            try {
                // 測試本地端口是否通暢
                String resp = OkHttp.string("http://127.0.0.1:" + p + "/proxy?do=ck", null);
                if ("ok".equals(resp)) {
                    Proxy.port = p;
                    break;
                }
            } catch (Exception ignored) {}
            p++;
        }
        // 如果都沒找到，給個默認值防止報錯
        if (Proxy.port == -1) Proxy.port = 9978;
    }
}
