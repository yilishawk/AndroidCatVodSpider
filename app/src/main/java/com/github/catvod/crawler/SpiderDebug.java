package com.github.catvod.crawler;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SpiderDebug {
    private static final String TAG = "SpiderDebug";
    private static final StringBuilder logBuffer = new StringBuilder();
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public static void log(Throwable e) {
        if (e != null) log("❌ 異常: " + e.getMessage());
    }

    public static void log(String msg) {
        if (msg == null) return;
        String time = sdf.format(new Date());
        // 打印到系統日誌
        Log.d(TAG, "[" + time + "] " + msg);
        // 存入內存供網頁端調用
        synchronized (logBuffer) {
            logBuffer.append("<div style='padding:5px;border-bottom:1px solid #eee;font-family:monospace;'>")
                     .append("<span style='color:#28a745;'>[").append(time).append("]</span> ")
                     .append(msg.replace("\n", "<br>"))
                     .append("</div>");
            if (logBuffer.length() > 25000) logBuffer.delete(0, 5000);
        }
    }

    public static String getLogs() {
        synchronized (logBuffer) {
            return logBuffer.toString();
        }
    }
}
