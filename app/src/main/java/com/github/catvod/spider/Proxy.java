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
     * 解決 AliYun、QuarkApi、UCApi 的 11 個報錯關鍵
     * 返回完整的代理基礎 URL
     */
    public static String getUrl() {
        return "http://127.0.0.1:" + getPort() + "/proxy";
    }

    /**
     * 獲取端口號
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
        String headerStr = params.get("header");
        Map<String, String> header = new HashMap<>();
        if (headerStr != null) {
            header = new Gson().fromJson(Util.base64Decode(headerStr), Map.class);
        }
        return ProxyVideo.proxyMultiThread(url, header);
    }

    // 自動偵測可用端口
    static void adjustPort() {
        if (Proxy.port > 0) return;
        int p = 9978;
        while (p < 10000) {
            try {
                // 注意：這裡直接使用 127.0.0.1 測試
                String resp = OkHttp.string("http://127.0.0.1:" + p + "/proxy?do=ck", null);
                if ("ok".equals(resp)) {
                    Proxy.port = p;
                    break;
                }
            } catch (Exception ignored) {}
            p++;
        }
        if (Proxy.port == -1) Proxy.port = 9978;
    }
}
