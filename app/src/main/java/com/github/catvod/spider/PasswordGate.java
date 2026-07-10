package com.github.catvod.spider;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 通用密码门禁工具类（WebView + 系统悬浮窗实现）。
 * 在爬虫的 init() 里调用 PasswordGate.ensureUnlocked(context)，
 * 只有密码正确才会返回 true，爬虫才继续往下走。
 *
 * 用系统悬浮窗（TYPE_APPLICATION_OVERLAY）挂载 WebView，不依赖 Activity Context，
 * 因此不会出现 AlertDialog 那种 BadTokenException 崩溃问题。
 *
 * 前提条件：App 需要已获得"显示在其他应用上层"权限（SYSTEM_ALERT_WINDOW），
 * 需要在 AndroidManifest.xml 声明该权限，并引导用户在系统设置里手动开启。
 *
 * 注意：必须在【非主线程】调用（init() 通常已经是在后台线程执行，正常情况下没问题）。
 */
public class PasswordGate {

    // 密码只存 SHA-256 哈希，不直接存明文，防止反编译直接看到密码原文。
    private static final String PASSWORD_HASH = sha256("123456789");

    private static volatile boolean unlocked = false;

    public static boolean ensureUnlocked(Context context) {
        if (unlocked) return true;

        // 防御性检查：避免主线程调用导致死锁（post到主线程弹窗 + 当前线程阻塞等待会互相卡死）。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e("PasswordGate", "ensureUnlocked 被主线程调用，为避免死锁已跳过弹窗，请确认 init() 是否运行在后台线程");
            return false;
        }

        CountDownLatch latch = new CountDownLatch(1);
        boolean[] result = {false};

        Context appContext = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> showWebViewPrompt(appContext, latch, result));

        try {
            latch.await(5, TimeUnit.MINUTES); // 最多等5分钟，避免用户长时间不操作导致线程卡死
        } catch (InterruptedException ignored) {
        }

        unlocked = result[0];
        return unlocked;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void showWebViewPrompt(Context appContext, CountDownLatch latch, boolean[] result) {
        try {
            WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) throw new IllegalStateException("拿不到 WindowManager");

            WebView webView = new WebView(appContext);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.setBackgroundColor(Color.parseColor("#CC000000")); // 半透明黑色遮罩，铺满全屏

            int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY // Android 8.0+ 用这个类型
                    : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;       // 老版本兼容

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, // 不加 NOT_FOCUSABLE，保证能接收键盘/遥控器输入
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.CENTER;

            // JS 回调桥接：密码框提交/取消都通过这个接口传回 Java 层
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void submit(String pwd) {
                    if (sha256(pwd).equals(PASSWORD_HASH)) {
                        result[0] = true;
                        closeOverlay(wm, webView);
                        latch.countDown();
                    } else {
                        webView.post(() -> webView.evaluateJavascript("showError()", null));
                    }
                }

                @JavascriptInterface
                public void cancel() {
                    closeOverlay(wm, webView);
                    latch.countDown();
                }
            }, "Android");

            webView.loadDataWithBaseURL(null, buildHtml(), "text/html", "utf-8", null);

            wm.addView(webView, params);
        } catch (Exception e) {
            // 常见于没有"显示在其他应用上层"权限，addView 会抛异常。
            // 弹窗失败时不能让异常往外抛导致整个 App 崩溃，这里改为验证失败处理。
            Log.e("PasswordGate", "WebView 悬浮窗显示失败，请检查是否已授予悬浮窗权限: " + e);
            latch.countDown();
        }
    }

    private static void closeOverlay(WindowManager wm, WebView webView) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                wm.removeView(webView);
            } catch (Exception ignored) {
            }
        });
    }

    private static String buildHtml() {
        return "<html><body style='margin:0;display:flex;align-items:center;justify-content:center;height:100vh;'>"
                + "<div style='background:#222;padding:30px 40px;border-radius:12px;text-align:center;font-family:sans-serif;'>"
                + "<div style='color:#fff;font-size:18px;margin-bottom:16px;'>该源需要密码</div>"
                + "<input id='pwd' type='password' placeholder='请输入访问密码' autofocus "
                + "style='font-size:18px;padding:10px;width:220px;border-radius:6px;border:none;box-sizing:border-box;'/>"
                + "<div id='err' style='color:#ff6b6b;margin-top:8px;display:none;'>密码错误，请重试</div>"
                + "<div style='margin-top:16px;'>"
                + "<button onclick='doSubmit()' style='font-size:16px;padding:8px 24px;margin-right:12px;border-radius:6px;border:none;background:#4CAF50;color:#fff;'>确定</button>"
                + "<button onclick='Android.cancel()' style='font-size:16px;padding:8px 24px;border-radius:6px;border:none;background:#666;color:#fff;'>取消</button>"
                + "</div></div>"
                + "<script>"
                + "function doSubmit(){Android.submit(document.getElementById('pwd').value);}"
                + "function showError(){document.getElementById('err').style.display='block';document.getElementById('pwd').value='';document.getElementById('pwd').focus();}"
                + "document.getElementById('pwd').addEventListener('keyup',function(e){if(e.key==='Enter'){doSubmit();}});"
                + "</script>"
                + "</body></html>";
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
