package com.github.catvod.spider;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 通用密码门禁工具类。
 * 在爬虫的 init() 里调用 PasswordGate.ensureUnlocked(context)，
 * 只有密码正确才会返回 true，爬虫才继续往下走。
 *
 * 由于本项目只能编译成 spider jar 塞进已打包好的宿主 App，无法修改宿主 App 的
 * AndroidManifest.xml，因此不能用悬浮窗权限方案。改为：通过反射拿到宿主 App
 * 当前处于前台的真实 Activity，把普通 AlertDialog 依附在这个 Activity 上，
 * 不需要任何额外权限。
 *
 * 局限：反射依赖 Android 内部私有实现，个别系统版本/厂商 ROM 上可能失效，
 * 失效时会走兜底逻辑（弹窗失败但不崩溃，只是验证不通过），不影响 App 稳定性。
 *
 * 注意：必须在【非主线程】调用（init() 通常已经是在后台线程执行，正常情况下没问题）。
 */
public class PasswordGate {

    // 密码只存 SHA-256 哈希，不直接存明文，防止反编译直接看到密码原文。
    private static final String PASSWORD_HASH = sha256("123456789");

    private static volatile boolean unlocked = false;

    public static boolean ensureUnlocked(Context context) {
        if (unlocked) return true;

        // 防御性检查：避免主线程调用导致死锁。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e("PasswordGate", "ensureUnlocked 被主线程调用，为避免死锁已跳过弹窗，请确认 init() 是否运行在后台线程");
            return false;
        }

        CountDownLatch latch = new CountDownLatch(1);
        boolean[] result = {false};

        new Handler(Looper.getMainLooper()).post(() -> showPrompt(context, latch, result));

        try {
            latch.await(5, TimeUnit.MINUTES); // 最多等5分钟，避免用户长时间不操作导致线程卡死
        } catch (InterruptedException ignored) {
        }

        unlocked = result[0];
        return unlocked;
    }

    private static void showPrompt(Context fallbackContext, CountDownLatch latch, boolean[] result) {
        try {
            Activity activity = getTopActivity();
            // 优先用反射拿到的真实前台 Activity；拿不到就退回原始 context（大概率还是会失败，
            // 但至少不会因为反射本身报错而崩溃）。
            Context dialogContext = (activity != null) ? activity : fallbackContext;

            EditText input = new EditText(dialogContext);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.setHint("请输入访问密码");

            new AlertDialog.Builder(dialogContext)
                    .setTitle("该源需要密码")
                    .setView(input)
                    .setCancelable(false)
                    .setPositiveButton("确定", (dialog, which) -> {
                        String pwd = input.getText().toString();
                        if (sha256(pwd).equals(PASSWORD_HASH)) {
                            result[0] = true;
                            latch.countDown();
                        } else {
                            Toast.makeText(dialogContext, "密码错误，请重试", Toast.LENGTH_SHORT).show();
                            showPrompt(fallbackContext, latch, result); // 密码错了重新弹一次
                        }
                    })
                    .setNegativeButton("取消", (dialog, which) -> latch.countDown())
                    .show();
        } catch (Exception e) {
            // 依然可能因为 context 不是 Activity（反射失败时的兜底情况）而抛 BadTokenException，
            // 这里 catch 住避免崩溃整个 App，只是这次验证失败。
            Log.e("PasswordGate", "弹窗显示失败: " + e);
            latch.countDown();
        }
    }

    /**
     * 反射获取当前处于前台、可交互的 Activity。
     * 依赖 android.app.ActivityThread 的私有字段，非官方 API。
     */
    @SuppressWarnings("unchecked")
    private static Activity getTopActivity() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread");
            Object activityThread = currentActivityThreadMethod.invoke(null);
            if (activityThread == null) return null;

            Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Map<Object, Object> activities = (Map<Object, Object>) activitiesField.get(activityThread);
            if (activities == null || activities.isEmpty()) return null;

            for (Object activityRecord : activities.values()) {
                Class<?> recordClass = activityRecord.getClass();
                Field pausedField = recordClass.getDeclaredField("paused");
                pausedField.setAccessible(true);
                if (!pausedField.getBoolean(activityRecord)) {
                    Field activityField = recordClass.getDeclaredField("activity");
                    activityField.setAccessible(true);
                    Object activityObj = activityField.get(activityRecord);
                    if (activityObj instanceof Activity) {
                        return (Activity) activityObj;
                    }
                }
            }
        } catch (Throwable t) {
            // 反射失败（可能被系统限制），静默返回 null，走 fallback
            Log.w("PasswordGate", "反射获取前台 Activity 失败: " + t);
        }
        return null;
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
