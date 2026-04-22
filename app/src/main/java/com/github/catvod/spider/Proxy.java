package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

public class Proxy extends Spider {
    private static StringBuilder sb = new StringBuilder("--- 凱哥極速日誌監聽啟動 ---\n");
    private static boolean isServerRunning = false;
    private static final int MY_LOG_PORT = 10086;

    // 🚀 保留這兩個方法，防止 AliYun.java 等文件編譯報錯
    public static int getPort() { return 9978; }
    public static String getUrl() { return "http://127.0.0.1:9978/proxy"; }

    public static void log(String msg) {
        if (msg == null) return;
        if (sb.length() > 150000) sb.delete(0, 75000);
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        // 🚀 過濾掉 HTML 標籤，輸出純文本
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
                        String data = sb.toString();
                        // 🚀 使用純文本協議頭，並加上 1 秒自動刷新
                        String resp = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/plain; charset=utf-8\r\n" +
                                "Refresh: 1\r\n" + 
                                "Connection: close\r\n\r\n" + data;
                        out.write(resp.getBytes("UTF-8"));
                        out.flush();
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) { isServerRunning = false; }
        }).start();
    }

    // 🚀 關鍵修復：去掉 @Override 和那些報錯的調用
    // 讓這個類只負責定義 getUrl，不強行攔截請求
    public Object[] proxy(Map<String, String> params) {
        return null;
    }
}
