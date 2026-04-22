package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

public class Proxy extends Spider {
    private static StringBuilder sb = new StringBuilder("--- 凱哥高速純文本監聽已啟動 ---\n");
    private static boolean isServerRunning = false;
    private static final int MY_LOG_PORT = 10086;

    public static int getPort() { return 9978; }
    public static String getUrl() { return "http://127.0.0.1:9978/proxy"; }

    public static void log(String msg) {
        if (msg == null) return;
        if (sb.length() > 150000) sb.delete(0, 75000);
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        // 🚀 去掉 HTML 標籤，純文本輸出
        sb.append("[").append(time).append("] ").append(msg.replaceAll("<[^>]*>", "")).append("\n");
        if (!isServerRunning) startLegacyServer();
    }

    private static void startLegacyServer() {
        if (isServerRunning) return;
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(MY_LOG_PORT)) {
                server.setReuseAddress(true);
                isServerRunning = true;
                while (true) {
                    try (Socket client = server.accept(); OutputStream out = client.getOutputStream()) {
                        String content = sb.toString();
                        // 🚀 關鍵：Content-Type 改為 text/plain，並加入自動刷新 Header
                        String response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/plain; charset=utf-8\r\n" +
                                "Refresh: 1\r\n" + // 🚀 協議級別刷新，比 HTML 標籤快得多
                                "Connection: close\r\n\r\n" + content;
                        out.write(response.getBytes("UTF-8"));
                        out.flush();
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) { isServerRunning = false; }
        }).start();
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        String type = params.get("do");
        if ("ali".equals(type)) return com.github.catvod.api.AliYun.proxy(params);
        if ("quark".equals(type)) return com.github.catvod.api.QuarkApi.proxy(params);
        if ("uc".equals(type)) return com.github.catvod.api.UCApi.proxy(params);
        if ("bili".equals(type)) return com.github.catvod.spider.Bili.proxy(params);
        return null;
    }
}
