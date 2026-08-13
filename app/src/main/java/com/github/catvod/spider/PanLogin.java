package com.github.catvod.spider;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.github.catvod.api.AliYun;
import com.github.catvod.api.BaiDuYunHandler;
import com.github.catvod.api.BaiduDrive;
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
 * 网盘登录管理 (重构版 - 极简卡片式操作)
 * 配置：api = "csp_PanLogin"
 */
public class PanLogin extends Spider {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AlertDialog currentDialog;

    private void logger(String msg) {
        try {
            Proxy.log(msg);
        } catch (Exception ignored) {
        }
    }

    private void runOnUI(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }

    @Override
    public void init(Context context, String extend) {
        logger("🚀 [网盘登录] 初始化 (卡片模式)");
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONArray classes = new JSONArray();
            classes.put(makeClass("网盘管理", "pan_mgr"));

            JSONArray list = new JSONArray();
            list.put(makePanVod("quark", "夸克网盘", loginStatus("quark")));
            list.put(makePanVod("uc", "UC网盘", loginStatus("uc")));
            list.put(makePanVod("ali", "阿里云盘", loginStatus("ali")));
            list.put(makePanVod("baidu", "百度网盘", loginStatus("baidu")));
            list.put(makePanVod("pan123", "123网盘", loginStatus("pan123")));
            list.put(makePanVod("tianyi", "天翼云盘", loginStatus("tianyi")));
            list.put(makePanVod("yun139", "139云盘", loginStatus("yun139")));

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

    private JSONObject makePanVod(String id, String name, String status) throws Exception {
        JSONObject o = new JSONObject();
        o.put("vod_id", id);
        o.put("vod_name", name);
        o.put("vod_remarks", status);
        o.put("vod_pic", "https://img.alicdn.com/imgextra/i4/O1CN01Z5paLz1O0egCGvGck_!!6000000001644-55-tps-83-82.svg");
        return o;
    }

    private String loginStatus(String type) {
        try {
            switch (type) {
                case "quark": {
                    String s = Path.read(QuarkApi.get().getCache());
                    if (TextUtils.isEmpty(s) || "{}".equals(s)) return "❌ 未登录";
                    JSONObject obj = new JSONObject(s);
                    String cookie = obj.optJSONObject("user") != null ? obj.optJSONObject("user").optString("cookie", "") : "";
                    return TextUtils.isEmpty(cookie) ? "❌ 未登录" : "✅ 已登录";
                }
                case "uc": {
                    String s = Path.read(UCApi.get().getCache());
                    if (TextUtils.isEmpty(s) || "{}".equals(s)) return "❌ 未登录";
                    JSONObject obj = new JSONObject(s);
                    String cookie = obj.optJSONObject("user") != null ? obj.optJSONObject("user").optString("cookie", "") : "";
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
                        String access = user.optString("access_token", "");
                        String refresh = user.optString("refresh_token", "");
                        return (TextUtils.isEmpty(access) && TextUtils.isEmpty(refresh)) ? "❌ 未登录" : "✅ 已登录";
                    }
                    return "❌ 未登录";
                }
                case "baidu": {
                    String t = BaiDuYunHandler.get().getToken();
                    return TextUtils.isEmpty(t) ? "❌ 未登录" : "✅ 已登录";
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
                    if (TextUtils.isEmpty(s) || "{}".equals(s)) return "❌ 未登录";
                    JSONObject obj = new JSONObject(s);
                    String cookie = obj.optString("cookie", "");
                    if (TextUtils.isEmpty(cookie) && obj.has("user")) {
                        cookie = obj.optJSONObject("user").optString("cookie", "");
                    }
                    return TextUtils.isEmpty(cookie) ? "❌ 未登录" : "✅ 已登录";
                }
                case "yun139": {
                    String t = YunTokenHandler.get().getToken();
                    return TextUtils.isEmpty(t) ? "❌ 未登录" : "✅ 已登录";
                }
            }
        } catch (Exception ignored) {
        }
        return "❌ 未登录";
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return "{\"list\":[]}";
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = ids.get(0);
        Init.execute(() -> showOperationDialog(id));

        try {
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", "请在弹窗中操作");
            vod.put("vod_content", "已拉起操作面板");
            vod.put("vod_play_from", "操作提示");
            vod.put("vod_play_url", "完成$0");
            JSONObject result = new JSONObject();
            result.put("list", new JSONArray().put(vod));
            return result.toString();
        } catch (Exception e) {
            return "{\"list\":[]}";
        }
    }

    private void showOperationDialog(String panId) {
        runOnUI(() -> {
            String title = getPanName(panId) + " 管理";
            CharSequence[] options = {"📱 扫码/触发登录", "🍪 粘贴 Cookie/Token", "🚪 清除登录信息"};

            new AlertDialog.Builder(Init.getActivity())
                    .setTitle(title)
                    .setItems(options, (dialog, which) -> {
                        switch (which) {
                            case 0:
                                triggerLogin(panId);
                                break;
                            case 1:
                                triggerPasteCookie(panId);
                                break;
                            case 2:
                                triggerClear(panId);
                                break;
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    private String getPanName(String id) {
        switch (id) {
            case "quark": return "夸克网盘";
            case "uc": return "UC网盘";
            case "ali": return "阿里云盘";
            case "baidu": return "百度网盘";
            case "pan123": return "123网盘";
            case "tianyi": return "天翼云盘";
            case "yun139": return "139云盘";
            default: return "网盘";
        }
    }

    private void triggerLogin(String panId) {
        Init.execute(() -> {
            try {
                switch (panId) {
                    case "quark":
                        QuarkApi.get().setCookie("");
                        QuarkApi.get().getShareData("https://pan.quark.cn/s/invalid");
                        Notify.show("正在拉起夸克登录面板...");
                        break;
                    case "uc":
                        new UCTokenHandler().startUC_TOKENScan();
                        break;
                    case "ali":
                        Path.write(AliYun.get().getCache(), "{}");
                        AliYun.get().getVod("https://www.aliyundrive.com/s/invalid", "invalid", "");
                        Notify.show("正在拉起阿里登录面板...");
                        break;
                    case "baidu":
                        BaiDuYunHandler.get().startScan();
                        break;
                    case "pan123":
                        showDoubleInput("123网盘登录", "账号", "密码", (user, pass) -> Init.execute(() -> {
                            try {
                                Pan123Handler.INSTANCE.loginWithPassword(user, pass);
                            } catch (Exception e) {
                                Notify.show("❌ 失败: " + e.getMessage());
                            }
                        }));
                        break;
                    case "tianyi":
                        TianYiHandler.get().startScan();
                        break;
                    case "yun139":
                        Notify.show("139云盘请使用 Cookie 粘贴方式");
                        break;
                }
            } catch (Exception e) {
                Notify.show("触发失败: " + e.getMessage());
            }
        });
    }

    private void triggerPasteCookie(String panId) {
        Init.execute(() -> {
            switch (panId) {
                case "quark":
                    showSingleInput("夸克 Cookie", "请粘贴含 __pus 的 Cookie", cookie -> {
                        try { QuarkApi.get().setCookie(cookie); Notify.show("✅ 成功"); } catch (Exception e) {}
                    });
                    break;
                case "uc":
                    showSingleInput("UC Cookie", "请粘贴含 __pus 的 Cookie", cookie -> {
                        try { UCApi.get().setCookie(cookie); Notify.show("✅ 成功"); } catch (Exception e) {}
                    });
                    break;
                case "ali":
                    showSingleInput("阿里 RefreshToken", "请粘贴 refresh_token", token -> {
                        try {
                            AliYun.get().setRefreshToken(token);
                            JSONObject cache = new JSONObject().put("user", new JSONObject().put("refresh_token", token));
                            Path.write(AliYun.get().getCache(), cache.toString());
                            Notify.show("✅ 成功");
                        } catch (Exception e) {}
                    });
                    break;
                case "baidu":
                    showSingleInput("百度 Cookie", "请粘贴完整 Cookie (含 BDUSS)", cookie -> {
                        try {
                            JSONObject cache = new JSONObject().put("user", new JSONObject().put("cookie", cookie));
                            Path.write(BaiDuYunHandler.get().getCache(), cache.toString());
                            BaiduDrive.INSTANCE.setCookie(cookie);
                            Notify.show("✅ 成功");
                        } catch (Exception e) {}
                    });
                    break;
                case "pan123":
                    showSingleInput("123 Token", "请粘贴 token", token -> {
                        try {
                            JSONObject user = new JSONObject().put("cookie", token).put("userName", "").put("password", "").put("expire", 0);
                            Path.write(Pan123Handler.INSTANCE.getCache(), new JSONObject().put("user", user).toString());
                            Notify.show("✅ 成功");
                        } catch (Exception e) {}
                    });
                    break;
                case "tianyi":
                    showDoubleInput("天翼云盘密码登录", "用户名/手机号", "密码", (user, pass) -> Init.execute(() -> {
                        try { TianYiHandler.get().loginWithPassword(user, pass); } catch (Exception e) { Notify.show("❌ 失败: " + e.getMessage()); }
                    }));
                    break;
                case "yun139":
                    showSingleInput("139 Token", "Base64: pc:手机号:xxxxx", token -> {
                        try {
                            Path.write(YunTokenHandler.get().getCache(), new JSONObject().put("user", new JSONObject().put("cookie", token)).toString());
                            Notify.show("✅ 成功");
                        } catch (Exception e) {}
                    });
                    break;
            }
        });
    }

    private void triggerClear(String panId) {
        Init.execute(() -> {
            try {
                switch (panId) {
                    case "quark": Path.write(QuarkApi.get().getCache(), "{}"); QuarkApi.get().setCookie(""); break;
                    case "uc": Path.write(UCApi.get().getCache(), "{}"); try { Path.write(new UCTokenHandler().getCache(), "{}"); } catch (Exception e){} break;
                    case "ali": Path.write(AliYun.get().getCache(), "{}"); break;
                    case "baidu": Path.write(BaiDuYunHandler.get().getCache(), "{}"); BaiduDrive.INSTANCE.setCookie(""); break;
                    case "pan123": Path.write(Pan123Handler.INSTANCE.getCache(), "{}"); break;
                    case "tianyi": TianYiHandler.get().cleanCookie(); Path.write(TianYiHandler.get().getCache(), "{}"); break;
                    case "yun139": Path.write(YunTokenHandler.get().getCache(), "{}"); break;
                }
                Notify.show("✅ 清除成功，请刷新或重新进入");
            } catch (Exception e) {
                Notify.show("❌ 清除失败");
            }
        });
    }

    private void showSingleInput(String title, String hint, InputCallback callback) {
        runOnUI(() -> {
            try {
                int margin = ResUtil.dp2px(16);
                FrameLayout frame = new FrameLayout(Init.context());
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
                Notify.show("弹窗失败");
            }
        });
    }

    private void showDoubleInput(String title, String hint1, String hint2, DoubleInputCallback callback) {
        runOnUI(() -> {
            try {
                int margin = ResUtil.dp2px(16);
                LinearLayout layout = new LinearLayout(Init.context());
                layout.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(margin, margin / 2, margin, margin / 2);

                EditText etUser = new EditText(Init.context());
                etUser.setHint(hint1);
                EditText etPass = new EditText(Init.context());
                etPass.setHint(hint2);
                etPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

                layout.addView(etUser, lp);
                layout.addView(etPass, lp);

                currentDialog = new AlertDialog.Builder(Init.getActivity())
                        .setTitle(title)
                        .setView(layout)
                        .setPositiveButton("登录", (d, w) -> {
                            String u = etUser.getText().toString().trim();
                            String p = etPass.getText().toString().trim();
                            if (!TextUtils.isEmpty(u) && !TextUtils.isEmpty(p)) callback.onInput(u, p);
                            else Notify.show("账号密码不能为空");
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } catch (Exception e) {
                Notify.show("弹窗失败");
            }
        });
    }

    private interface InputCallback { void onInput(String text); }
    private interface DoubleInputCallback { void onInput(String a, String b); }

    @Override public String playerContent(String flag, String id, List<String> vipFlags) { return "{}"; }
    @Override public String searchContent(String key, boolean quick) { return "{\"list\":[]}"; }
}

