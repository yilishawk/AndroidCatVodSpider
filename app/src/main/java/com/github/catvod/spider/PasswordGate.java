package com.github.catvod.spider;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 通用密码门禁工具类。
 * 在爬虫的 init() 里调用 PasswordGate.ensureUnlocked(context, "标识名")，
 * 只有密码正确才会返回 true，爬虫才继续往下走。
 */
public class PasswordGate {

    // 密码只存 SHA-256 哈希，不直接存明文，防止反编译直接看到密码原文。
    // 如需修改密码：把新密码明文跑一遍 sha256()，把结果替换到这里。
    private static final String PASSWORD_HASH = sha256("123456789");

    // 本次 App 运行期间验证一次即可，不用每次点开该源都重新输入。
    // 如果想"每次点开都要输入"，把 volatile boolean 换成不持久化的局部判断即可（见下方注释）。
    private static volatile boolean unlocked = false;

    /**
     * 确认是否已解锁。会阻塞调用线程，直到用户输入正确密码或取消。
     * @param context 爬虫 init() 里传入的 Context
     * @return true=密码正确可以继续运行，false=用户取消或超时未操作
     */
    public static boolean ensureUnlocked(Context context) {
        if (unlocked) return true; // 已经验证过，直接放行

        CountDownLatch latch = new CountDownLatch(1);
        boolean[] result = {false};

        new Handler(Looper.getMainLooper()).post(() -> promptPassword(context, latch, result));

        try {
            latch.await(5, TimeUnit.MINUTES); // 最多等5分钟，避免用户长时间不操作导致线程卡死
        } catch (InterruptedException ignored) {
        }

        unlocked = result[0];
        return unlocked;
    }

    private static void promptPassword(Context context, CountDownLatch latch, boolean[] result) {
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("请输入访问密码");

        new AlertDialog.Builder(context)
                .setTitle("该源需要密码")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("确定", (dialog, which) -> {
                    String pwd = input.getText().toString();
                    if (sha256(pwd).equals(PASSWORD_HASH)) {
                        result[0] = true;
                        latch.countDown();
                    } else {
                        Toast.makeText(context, "密码错误，请重试", Toast.LENGTH_SHORT).show();
                        promptPassword(context, latch, result); // 密码错了重新弹一次
                    }
                })
                .setNegativeButton("取消", (dialog, which) -> latch.countDown()) // 取消则放行失败
                .show();
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
