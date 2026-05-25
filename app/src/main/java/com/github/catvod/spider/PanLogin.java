package com.github.catvod.spider;

import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.github.catvod.api.AliYun;
import com.github.catvod.api.BaiDuYunHandler;
import com.github.catvod.api.Pan123Api;
import com.github.catvod.api.Pan123Handler;
import com.github.catvod.api.QuarkApi;
import com.github.catvod.api.UCApi;
import com.github.catvod.api.UCTokenHandler;
import com.github.catvod.api.YunTokenHandler;
import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.Notify;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.ResUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;

/**
 * 网盘登录管理 Spider
 * 仓库配置：api = "com.github.catvod.spider.PanLogin"
 */
public class PanLogin extends Spider {

    private AlertDialog currentDialog;

    private void logger(String msg) {
        try { Proxy.log(msg); } catch (Exception ignored) {}
    }

    // ──────────────────────────────────────────────
    // 生命周期
    // ──────────────────────────────────────────────

    @Override
    public void init(Context context, String extend) {
        logger("🚀 [网盘登录] 初始化");
    }

    // ──────────────────────────────────────────────
    // 首页：各网盘登录状态
    // ──────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONArray classes = new JSONArray();
            classes.put(makeClass("夸克网盘", "quark"));
            classes.put(makeClass("UC网盘",   "uc"));
            classes.put(makeClass("阿里云盘", "ali"));
            classes.put(makeClass("百度网盘", "baidu"));
            classes.put(makeClass("123网盘",  "pan123"));
            classes.put(makeClass("139云盘",  "yun139"));

            JSONArray list = new JSONArray();
            list.put(makeStatusVod("quark",  "夸克网盘",  loginStatus("quark")));
            list.put(makeStatusVod("uc",     "UC网盘",    loginStatus("uc")));
            list.put(makeStatusVod("ali",    "阿里云盘",  loginStatus("ali")));
            list.put(makeStatusVod("baidu",  "百度网盘",  loginStatus("baidu")));
            list.put(makeStatusVod("pan123", "123网盘",   loginStatus("pan123")));
            list.put(makeStatusVod("yun139", "139云盘",   loginStatus("yun139")));

            JSONObject result = new JSONObject();
            result.put("class", classes);
            result.put("list",  list);
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private JSONObject makeClass(String name, String id) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type_name", name);
        o.put("type_id",   id);
        return o;
    }

    /**
     * 登录状态显示：已登录显示用户信息，未登录显示"未登录"
     */
    private String loginStatus(String type) {
        try {
            switch (type) {
                case "quark": {
                    String s = Path.read(QuarkApi.get().getCache());
                    if (TextUtils.isEmpty(s) || s.equals("{}")) return "❌ 未登录";
                    JSONObject obj = new JSONObject(s);
                    String cookie = obj.optJSONObject("user") != null
                        ? obj.optJSONObject("user").optString("cookie", "") : "";
                    return TextUtils.isEmpty(cookie) ? "❌ 未登录" : "✅ 已登录";
                }
                case "uc": {
                    String s = Path.read(UCApi.get().getCache());
                    if (TextUtils.isEmpty(s) || s.equals("{}")) return "❌ 未登录";
                    JSONObject obj = new JSONObject(s);
                    String cookie = obj.optJSONObject("user") != null
                        ? obj.optJSONObject("user").optString("cookie", "") : "";
                    return TextUtils.isEmpty(cookie) ? "❌ 未登录" : "✅ 已登录";
                }
                case "ali": {
                    String s = Path.read(AliYun.get().getCache());
                    if (TextUtils.isEmpty(s) || s.equals("{}")) return "❌ 未登录";
                    JSONObject obj = new JSONObject(s);
                    // 优先显示用户昵称
                    JSONObject user = obj.optJSONObject("user");
                    if (user != null) {
                        String nick = user.optString("nick_name", "");
                        if (!TextUtils.isEmpty(nick)) return "✅ " + nick;
                    }
                    String accessToken = user != null ? user.optString("access_token", "") : "";
                    return TextUtils.isEmpty(accessToken) ? "❌ 未登录" : "✅ 已登录";
                }
                case "baidu": {
                    String token = BaiDuYunHandler.get().getToken();
                    return TextUtils.isEmpty(token) ? "❌ 未登录" : "✅ 已登录";
                }
                case "pan123": {
                    String s = Path.read(Pan123Handler.INSTANCE.getCache());
                    if (TextUtils.isEmpty(s) || s.equals("{}")) return "❌ 未登录";
                    JSONObject obj = new JSONObject(s);
                    JSONObject user = obj.optJSONObject("user");
                    String cookie = user != null ? user.optString("cookie", "") : "";
                    // 尝试显示用户名
                    String uname = user != null ? user.optString("userName", "") : "";
                    if (!TextUtils.isEmpty(uname)) return "✅ " + uname;
                    return TextUtils.isEmpty(cookie) ? "❌ 未登录" : "✅ 已登录";
                }
                case "yun139": {
                    String token = YunTokenHandler.get().getToken();
                    return TextUtils.isEmpty(token) ? "❌ 未登录" : "✅ 已登录";
                }
            }
        } catch (Exception ignored) {}
        return "❌ 未登录";
    }

    private JSONObject makeStatusVod(String id, String name, String status) throws Exception {
        JSONObject o = new JSONObject();
        o.put("vod_id",      id);
        o.put("vod_name",    name);
        o.put("vod_remarks", status);
        o.put("vod_pic",     "");
        return o;
    }

    // ──────────────────────────────────────────────
    // 分类：某网盘的操作列表
    // ──────────────────────────────────────────────

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            JSONArray list = new JSONArray();
            switch (tid) {
                case "quark":
                    list.put(makeActionVod("quark_login",  "📱 登录（弹窗自带扫码/Cookie）", loginStatus("quark")));
                    list.put(makeActionVod("quark_cookie", "🍪 直接粘贴Cookie",               "手动输入"));
                    list.put(makeActionVod("quark_clear",  "🚪 清除登录状态",                 "点击清除"));
                    break;
                case "uc":
                    list.put(makeActionVod("uc_scan",   "📱 Token扫码登录", loginStatus("uc")));
                    list.put(makeActionVod("uc_cookie", "🍪 直接粘贴Cookie", "手动输入"));
                    list.put(makeActionVod("uc_clear",  "🚪 清除登录状态",  "点击清除"));
                    break;
                case "ali":
                    list.put(makeActionVod("ali_login", "📱 登录（弹窗自带扫码/Token）", loginStatus("ali")));
                    list.put(makeActionVod("ali_token", "🔑 直接粘贴RefreshToken",       "手动输入"));
                    list.put(makeActionVod("ali_clear", "🚪 清除登录状态",               "点击清除"));
                    break;
                case "baidu":
                    list.put(makeActionVod("baidu_scan",  "📱 扫码登录",  loginStatus("baidu")));
                    list.put(makeActionVod("baidu_clear", "🚪 退出说明",  "点击查看"));
                    break;
                case "pan123":
                    list.put(makeActionVod("pan123_login", "🔑 账号密码登录", loginStatus("pan123")));
                    list.put(makeActionVod("pan123_clear", "🚪 退出说明",     "点击查看"));
                    break;
                case "yun139":
                    list.put(makeActionVod("yun139_token", "🔑 粘贴Token", loginStatus("yun139")));
                    list.put(makeActionVod("yun139_clear", "🚪 清除Token", "点击清除"));
                    break;
            }
            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("page", 1);
            result.put("pages", 1);  
            return result.toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    private JSONObject makeActionVod(String id, String name, String remarks) throws Exception {
        JSONObject o = new JSONObject();
        o.put("vod_id",      id);
        o.put("vod_name",    name);
        o.put("vod_remarks", remarks);
        o.put("vod_pic",     "");
        return o;
    }

    // ──────────────────────────────────────────────
    // 详情：点击触发对应操作，直接返回空（参考 Config.java）
    // ──────────────────────────────────────────────

    @Override
    public String detailContent(List<String> ids) {
        String action = ids.get(0);
        logger("🔑 [网盘登录] 触发: " + action);
        Init.run(() -> handleAction(action));
        return "";
    }

    // ──────────────────────────────────────────────
    // 操作分发
    // ──────────────────────────────────────────────

    private void handleAction(String action) {
        switch (action) {

            // ════════════════ 夸克 ════════════════
            case "quark_login":
                // setCookie("") 触发 QuarkApi 内部弹窗（含扫码+Cookie输入）
                Init.execute(() -> {
                    try { QuarkApi.get().setCookie(""); }
                    catch (Exception ignored) {}
                });
                break;

            case "quark_cookie":
                showSingleInput("夸克Cookie",
                    "浏览器访问 pan.quark.cn\nF12 → Network → 任意请求 → 复制 Cookie 头",
                    cookie -> Init.execute(() -> {
                        try {
                            QuarkApi.get().setCookie(cookie);
                            Notify.show("✅ 夸克Cookie设置成功");
                        } catch (Exception e) {
                            Notify.show("❌ 失败: " + e.getMessage());
                        }
                    }));
                break;

            case "quark_clear":
                Init.execute(() -> {
                    try {
                        Path.write(QuarkApi.get().getCache(), "{}");
                        Notify.show("✅ 夸克登录状态已清除，重启生效");
                    } catch (Exception ignored) {}
                });
                break;

            // ════════════════ UC ════════════════
            case "uc_scan":
                Init.execute(() -> {
                    try {
                        new UCTokenHandler().startUC_TOKENScan();
                    } catch (Exception e) {
                        Notify.show("UC扫码失败: " + e.getMessage());
                    }
                });
                break;

            case "uc_cookie":
                showSingleInput("UC Cookie",
                    "浏览器访问 drive.uc.cn\nF12 → Network → 任意请求 → 复制 Cookie 头",
                    cookie -> Init.execute(() -> {
                        try {
                            UCApi.get().setCookie(cookie);
                            Notify.show("✅ UC Cookie设置成功");
                        } catch (Exception e) {
                            Notify.show("❌ 失败: " + e.getMessage());
                        }
                    }));
                break;

            case "uc_clear":
                Init.execute(() -> {
                    try {
                        Path.write(UCApi.get().getCache(), "{}");
                        Notify.show("✅ UC登录状态已清除，重启生效");
                    } catch (Exception ignored) {}
                });
                break;

            // ════════════════ 阿里云盘 ════════════════
            case "ali_login":
                // 清除缓存后，下次访问内容时 AliYun 内部自动弹出登录窗口
                Init.execute(() -> {
                    AliYun.get().setRefreshToken("");
                    Path.write(AliYun.get().getCache(), "{}");
                    Notify.show("已清除，请进入任意阿里云盘内容触发登录弹窗");
                });
                break;

            case "ali_token":
                showSingleInput("阿里云盘 RefreshToken",
                    "阿里云盘官网 F12\n→ Application → Local Storage\n→ token 字段",
                    token -> Init.execute(() -> {
                        AliYun.get().setRefreshToken(token);
                        Notify.show("✅ 阿里Token已设置，下次访问内容时生效");
                    }));
                break;

            case "ali_clear":
                Init.execute(() -> {
                    AliYun.get().setRefreshToken("");
                    Path.write(AliYun.get().getCache(), "{}");
                    Notify.show("✅ 阿里云盘登录状态已清除");
                });
                break;

            // ════════════════ 百度网盘 ════════════════
            case "baidu_scan":
                Init.execute(() -> {
                    try {
                        BaiDuYunHandler.get().startScan();
                    } catch (Exception e) {
                        Notify.show("百度扫码失败: " + e.getMessage());
                    }
                });
                break;

            case "baidu_clear":
                Notify.show("百度网盘暂不支持主动退出，请清除App数据或重装");
                break;

            // ════════════════ 123网盘 ════════════════
            case "pan123_login":
                showDoubleInput("123网盘登录", "账号（手机号/邮箱）", "密码",
                    (user, pass) -> Init.execute(() -> {
                        try {
                            Pan123Api.INSTANCE.login(user, pass);
                            Notify.show("✅ 123网盘登录成功");
                        } catch (Exception e) {
                            Notify.show("❌ 登录失败: " + e.getMessage());
                        }
                    }));
                break;

            case "pan123_clear":
                Notify.show("123网盘退出请重启App");
                break;

            // ════════════════ 139云盘 ════════════════
            case "yun139_token":
                showSingleInput("139云盘 Token",
                    "格式：pc:手机号:xxxxx（Base64编码）\n139云盘官网 F12 → Network → Authorization 头",
                    token -> Init.execute(() -> {
                        try {
                            JSONObject userJson = new JSONObject();
                            userJson.put("cookie", token);
                            JSONObject cacheJson = new JSONObject();
                            cacheJson.put("user", userJson);
                            Path.write(YunTokenHandler.get().getCache(), cacheJson.toString());
                            Notify.show("✅ 139Token已设置，重启后生效");
                        } catch (Exception e) {
                            Notify.show("❌ 失败: " + e.getMessage());
                        }
                    }));
                break;

            case "yun139_clear":
                Init.execute(() -> {
                    try {
                        Path.write(YunTokenHandler.get().getCache(), "{}");
                        Notify.show("✅ 139云盘Token已清除");
                    } catch (Exception ignored) {}
                });
                break;
        }
    }

    // ──────────────────────────────────────────────
    // 通用弹窗：单输入框
    // ──────────────────────────────────────────────

    private void showSingleInput(String title, String hint, InputCallback callback) {
        try {
            int margin = ResUtil.dp2px(16);
            FrameLayout frame = new FrameLayout(Init.context());
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(margin, margin, margin, margin);
            EditText input = new EditText(Init.context());
            input.setHint(hint);
            input.setMinLines(2);
            frame.addView(input, lp);

            currentDialog = new AlertDialog.Builder(Init.getActivity())
                .setTitle(title)
                .setView(frame)
                .setPositiveButton("确定", (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(text)) callback.onInput(text);
                    else Notify.show("内容不能为空");
                })
                .setNegativeButton("取消", null)
                .show();
        } catch (Exception e) {
            Notify.show("弹窗失败: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    // 通用弹窗：双输入框（账号 + 密码）
    // ──────────────────────────────────────────────

    private void showDoubleInput(String title, String hint1, String hint2, DoubleInputCallback callback) {
        try {
            int margin = ResUtil.dp2px(16);
            LinearLayout layout = new LinearLayout(Init.context());
            layout.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(margin, margin / 2, margin, margin / 2);

            EditText etUser = new EditText(Init.context());
            etUser.setHint(hint1);
            EditText etPass = new EditText(Init.context());
            etPass.setHint(hint2);
            etPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

            layout.addView(etUser, lp);
            layout.addView(etPass, lp);

            currentDialog = new AlertDialog.Builder(Init.getActivity())
                .setTitle(title)
                .setView(layout)
                .setPositiveButton("登录", (d, w) -> {
                    String u = etUser.getText().toString().trim();
                    String p = etPass.getText().toString().trim();
                    if (!TextUtils.isEmpty(u) && !TextUtils.isEmpty(p))
                        callback.onInput(u, p);
                    else Notify.show("账号密码不能为空");
                })
                .setNegativeButton("取消", null)
                .show();
        } catch (Exception e) {
            Notify.show("弹窗失败: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    // 回调接口
    // ──────────────────────────────────────────────

    private interface InputCallback {
        void onInput(String text);
    }

    private interface DoubleInputCallback {
        void onInput(String a, String b);
    }

    // ──────────────────────────────────────────────
    // 不需要的方法
    // ──────────────────────────────────────────────

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return "{}";
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return "{\"list\":[]}";
    }
}
