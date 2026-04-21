package com.github.catvod.crawler;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SpiderDebug {
    private static final StringBuilder logBuffer = new StringBuilder();
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public static void log(Throwable e) {
        if (e != null) log("❌ 錯誤: " + e.getMessage());
    }

    public static void log(String msg) {
        if (msg == null) return;
        String time = sdf.format(new Date());
        Log.d("SpiderDebug", "[" + time + "] " + msg);
        synchronized (logBuffer) {
            logBuffer.append("<div style='margin-bottom:5px;border-bottom:1px solid #eee;'>")
                     .append("<span style='color:green;'>[").append(time).append("]</span> ")
                     .append(msg.replace("\n", "<br>"))
                     .append("</div>");
            if (logBuffer.length() > 20000) logBuffer.delete(0, 5000);
        }
    }

    public static String getLogs() {
        synchronized (logBuffer) {
            return logBuffer.toString();
        }
    }
}
