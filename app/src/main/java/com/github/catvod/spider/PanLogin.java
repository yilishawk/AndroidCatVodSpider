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
import com.github.catvod.api.TianYiHandler;
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
 * 网盘登录管理
 * 配置：api = "csp_PanLogin"
 * 每个网盘统一三项：📱登录 / 🍪粘贴Cookie / 🚪清除
 */
public class PanLogin extends Spider {

    private AlertDialog currentDialog;

    private void logger(String msg) {
        try { Proxy.log(msg); } catch (Exception ignored) {}
    }

    @Override
    public void init(Context context, String extend) {
        logger("🚀 [网盘登录] 初始化");
    }

    // ──────────────────────────────────────────────
    // 首页
    // ──────────────────────────────────────────────
    @Override
    public String homeContent(boolean filter) {
        try {
            JSONArray classes = new JSONArray();
            classes.put(makeClass("夸克网盘", "quark"));
            classes.put(makeClass("UC网盘", "uc"));
            classes.put(makeClass("阿里云盘", "ali"));
            classes.put(makeClass("百度网盘", "baidu"));
            classes.put(makeClass("123网盘", "pan123"));
            classes.put(makeClass("天翼云盘", "tianyi"));
            classes.put(makeClass("139云盘", "yun139"));

            JSONArray list = new JSONArray();
            list.put(makeStatusVod("quark", "夸克网盘", loginStatus("quark")));
            list.put(makeStatusVod("uc", "UC网盘", loginStatus("uc")));
            list.put(makeStatusVod("ali", "阿里云盘", loginStatus("ali")));
            list.put(makeStatusVod("baidu", "百度网盘", loginStatus("baidu")));
            list.put(makeStatusVod("pan123", "123网盘", loginStatus("pan123")));
            list.put(makeStatusVod("tianyi", "天翼云盘", loginStatus("tianyi")));
            list.put(makeStatusVod("yun139", "139云盘", loginStatus("yun139")));

            JSONObject result = new JSONObject();
            result.put("class", classes);
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            return "{\"class\":[],\"list\":[]}";
        }
    }

    private JSONObject makeClass(String name, String id) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type_id", id);
        o.put("type_name", name);
        return o;
    }

    private JSONObject makeStatusVod(String id, String name, String status) throws Exception {
        JSONObject o = new JSONObject();
        o.put("vod_id", id);
        o.put("vod_name", name);
        o.put("vod_remarks", status);
        o.put("vod_pic", "");
        return o;
    }

    private String loginStatus(String type) {
        try {
            switch (type) {
                case "quark": {
                    String s = Path.read(QuarkApi.get().getCache());
                    if (TextUtils.isEmpty(s) || "{}".equals(s)) return "❌ 未登录";
                    JSONObject obj = new JSONObject(s);
                    String cookie = obj.optJSONObject("user") != null
                            ? obj.optJSONObject("user").optString("cookie", "") : "";
                    return TextUtils.isEmpty(cookie) ? "❌ 未登录" : "✅ 已登录";
                }
                case "uc": {
                    String s = Path.read(UCApi.get().getCache());
                    if (TextUtils.isEmpty(s) || "{}".equals(s)) return "❌ 未登录";
                    JSONObject obj = new JSONObject(s);
                    String cookie = obj.optJSONObject("user") != null
                            ? obj.optJSONObject("user").optString("cookie", "") : "";
                    return TextUtils.isEmpty(cookie) ? "❌ 未登录" : "✅ 已登录";
                }
                case "ali": {
                    String s = Path.read(AliYun.get().getCache());
                    if (TextUtils.isEmpty(s) || "{}".equals(s)) return "❌ 未登录";
                    JSONObject obj = new JSONObject(s);
                    JSONObject user = obj.optJSONObject("user");
                    if (user != null) {
                        String nick = user.optString("nick_name", "");
                        if (!TextUtils.isEmpty(nick)) return "✅ " + nick;
                        return TextUtils.isEmpty(user.optString("access_token", "")) ? "❌ 未登录" : "✅ 已登录";
                    }
                    return "❌ 未登录";
                }
                case "baidu": {
                    return TextUtils.isEmpty(BaiDuYunHandler.get().getToken()) ? "❌ 未登录" : "✅ 已登录";
                }
                case "pan123": {
                    String s = Path.read(Pan123Handler.INSTANCE.getCache());
                    if (TextUtils.isEmpty(s) || "{}".equals(s)) return "❌ 未登录";
                    JSONObject obj = new JSONObject(s);
                    JSONObject user = obj.optJSONObject("user");
                    if (user != null) {
                        String uname = user.optString("userName", "");
                        if (!TextUtils.isEmpty(uname)) return "✅ " + uname;
                        return TextUtils.isEmpty(user.optString("cookie", "")) ? "❌ 未登录" : "✅ 已登录";
                    }
                    return "❌ 未登录";
                }
                case "tianyi": {
                    String s = Path.read(TianYiHandler.get().getCache());
                    return (TextUtils.isEmpty(s) || "{}".equals(s)) ? "❌ 未登录" : "✅ 已登录";
                }
                case "yun139": {
                    return TextUtils.isEmpty(YunTokenHandler.get().getToken()) ? "❌ 未登录" : "✅ 已登录";
                }
            }
        } catch (Exception ignored) {}
        return "❌ 未登录";
    }

    // ──────────────────────────────────────────────
    // 分类页：统一三项
    // ──────────────────────────────────────────────
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            JSONArray list = new JSONArray();
            String status = loginStatus(tid);

            list.put(makeActionVod(tid + "_login",  "📱 登录",        status));
            list.put(makeActionVod(tid + "_cookie", "🍪 粘贴Cookie",  "手动输入"));
            list.put(makeActionVod(tid + "_clear",  "🚪 清除",        "点击清除"));

            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("page", 1);
            result.put("pagecount", 1);
            result.put("limit", 20);
            result.put("total", 3);
            return result.toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    private JSONObject makeActionVod(String id, String name, String remarks) throws Exception {
        JSONObject o = new JSONObject();
        o.put("vod_id", id);
        o.put("vod_name", name);
        o.put("vod_remarks", remarks);
        o.put("vod_pic", "");
        return o;
    }

    // ──────────────────────────────────────────────
    // 点击操作
    // ──────────────────────────────────────────────
    @Override
    public String detailContent(List<String> ids) {
        String id = ids.get(0);
        Init.execute(() -> handleAction(id));
        try {
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", "操作已触发");
            vod.put("vod_content", "请查看弹窗或提示");
            vod.put("vod_play_from", "操作");
            vod.put("vod_play_url", "完成$0");
            JSONObject result = new JSONObject();
            result.put("list", new JSONArray().put(vod));
            return result.toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    private void handleAction(String id) {
        try {
            switch (id) {

                // ══════════ 夸克 ══════════
                case "quark_login":
                    QuarkApi.get().setCookie(""); // 触发自带扫码/登录弹窗
                    break;
                case "quark_cookie":
                    showSingleInput("夸克 Cookie", "请粘贴夸克 Cookie", cookie -> Init.execute(() -> {
                        try {
                            QuarkApi.get().setCookie(cookie);
                            Notify.show("✅ 夸克 Cookie 已设置");
                        } catch (Exception e) {
                            Notify.show("❌ 失败: " + e.getMessage());
                        }
                    }));
                    break;
                case "quark_clear":
                    Path.write(QuarkApi.get().getCache(), "{}");
                    Notify.show("✅ 夸克已清除");
                    break;

                // ══════════ UC ══════════
                case "uc_login":
                    try {
                        UCTokenHandler.get().startScan();
                    } catch (Exception e) {
                        Notify.show("UC扫码失败: " + e.getMessage());
                    }
                    break;
                case "uc_cookie":
                    showSingleInput("UC Cookie", "请粘贴 UC Cookie", cookie -> Init.execute(() -> {
                        try {
                            UCApi.get().setCookie(cookie);
                            Notify.show("✅ UC Cookie 已设置");
                        } catch (Exception e) {
                            Notify.show("❌ 失败: " + e.getMessage());
                        }
                    }));
                    break;
                case "uc_clear":
                    Path.write(UCApi.get().getCache(), "{}");
                    Notify.show("✅ UC已清除");
                    break;

                // ══════════ 阿里 ══════════
                case "ali_login":
                    Path.write(AliYun.get().getCache(), "{}");
                    Notify.show("已准备登录，请进入任意阿里内容触发登录");
                    break;
                case "ali_cookie":
                    showSingleInput("阿里 RefreshToken", "请粘贴 refresh_token", token -> Init.execute(() -> {
                        try {
                            AliYun.get().setToken(token);
                            Notify.show("✅ 阿里 Token 已设置");
                        } catch (Exception e) {
                            Notify.show("❌ 失败: " + e.getMessage());
                        }
                    }));
                    break;
                case "ali_clear":
                    Path.write(AliYun.get().getCache(), "{}");
                    Notify.show("✅ 阿里已清除");
                    break;

                // ══════════ 百度 ══════════
                case "baidu_login":
                    try {
                        BaiDuYunHandler.get().startScan();
                    } catch (Exception e) {
                        Notify.show("百度扫码失败: " + e.getMessage());
                    }
                    break;
                case "baidu_cookie":
                    showSingleInput("百度 Cookie/Token", "请粘贴百度 Cookie 或 Token", token -> Init.execute(() -> {
                        try {
                            // 按你项目里百度实际接口调整
                            BaiDuYunHandler.get().setToken(token);
                            Notify.show("✅ 百度已设置");
                        } catch (Exception e) {
                            Notify.show("❌ 失败: " + e.getMessage());
                        }
                    }));
                    break;
                case "baidu_clear":
                    Notify.show("百度请清除App数据或重装以退出");
                    break;

                // ══════════ 123 ══════════
                case "pan123_login":
                    showDoubleInput("123网盘登录", "账号", "密码", (user, pass) -> Init.execute(() -> {
                        try {
                            Pan123Api.INSTANCE.login(user, pass);
                            Notify.show("✅ 123登录成功");
                        } catch (Exception e) {
                            Notify.show("❌ 失败: " + e.getMessage());
                        }
                    }));
                    break;
                case "pan123_cookie":
                    showSingleInput("123 Cookie", "请粘贴 123 Cookie", cookie -> Init.execute(() -> {
                        try {
                            JSONObject userJson = new JSONObject();
                            userJson.put("cookie", cookie);
                            JSONObject cacheJson = new JSONObject();
                            cacheJson.put("user", userJson);
                            Path.write(Pan123Handler.INSTANCE.getCache(), cacheJson.toString());
                            Notify.show("✅ 123 Cookie 已设置");
                        } catch (Exception e) {
                            Notify.show("❌ 失败: " + e.getMessage());
                        }
                    }));
                    break;
                case "pan123_clear":
                    Path.write(Pan123Handler.INSTANCE.getCache(), "{}");
                    Notify.show("✅ 123已清除");
                    break;

                // ══════════ 天翼 ══════════
                case "tianyi_login":
                    showDoubleInput("天翼云盘登录", "用户名/手机号", "密码", (user, pass) -> Init.execute(() -> {
                        try {
                            TianYiHandler.get().loginWithPassword(user, pass);
                            Notify.show("✅ 天翼登录成功");
                        } catch (Exception e) {
                            Notify.show("❌ 失败: " + e.getMessage());
                        }
                    }));
                    break;
                case "tianyi_cookie":
                    showSingleInput("天翼 Cookie", "请粘贴天翼 Cookie（可选）", cookie -> Init.execute(() -> {
                        try {
                            // 简单写入缓存，具体格式按你项目 Cache 结构调整
                            Path.write(TianYiHandler.get().getCache(), cookie);
                            Notify.show("✅ 天翼 Cookie 已写入");
                        } catch (Exception e) {
                            Notify.show("❌ 失败: " + e.getMessage());
                        }
                    }));
                    break;
                case "tianyi_clear":
                    TianYiHandler.get().cleanCookie();
                    Path.write(TianYiHandler.get().getCache(), "{}");
                    Notify.show("✅ 天翼已清除");
                    break;

                // ══════════ 139 ══════════
                case "yun139_login":
                case "yun139_cookie":
                    showSingleInput("139 Token/Cookie",
                            "格式示例：pc:手机号:xxxxx\n从网页 F12 → Authorization 复制",
                            token -> Init.execute(() -> {
                                try {
                                    JSONObject userJson = new JSONObject();
                                    userJson.put("cookie", token);
                                    JSONObject cacheJson = new JSONObject();
                                    cacheJson.put("user", userJson);
                                    Path.write(YunTokenHandler.get().getCache(), cacheJson.toString());
                                    Notify.show("✅ 139 已设置");
                                } catch (Exception e) {
                                    Notify.show("❌ 失败: " + e.getMessage());
                                }
                            }));
                    break;
                case "yun139_clear":
                    Path.write(YunTokenHandler.get().getCache(), "{}");
                    Notify.show("✅ 139已清除");
                    break;

                default:
                    Notify.show("未知操作: " + id);
                    break;
            }
        } catch (Exception e) {
            Notify.show("操作失败: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    // 弹窗
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

    private interface InputCallback {
        void onInput(String text);
    }

    private interface DoubleInputCallback {
        void onInput(String a, String b);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return "{}";
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return "{\"list\":[]}";
    }
}
