package com.github.catvod.spider;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 注视影视 gazes.host
 * WebView 过验证拿 Cookie，再带 Cookie POST /filter_movielist
 */
public class Gaze extends Spider {

    private static final String SITE = "https://gazes.host";
    private static final String FILTER_URL = SITE + "/filter";
    private static final String LIST_API = SITE + "/filter_movielist";

    private Context context;
    private String cookie = "";

    @Override
    public void init(Context context, String extend) throws Exception {
        this.context = context;
        if (!TextUtils.isEmpty(extend)) {
            if (extend.contains("cf_clearance") || extend.contains("PHPSESSID")) {
                cookie = extend.trim();
            }
        }
    }

    private Map<String, String> getHeader() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36");
        h.put("Origin", SITE);
        h.put("Referer", FILTER_URL);
        h.put("Accept", "*/*");
        h.put("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        h.put("X-Requested-With", "XMLHttpRequest");
        if (!TextUtils.isEmpty(cookie)) {
            h.put("Cookie", cookie);
        }
        return h;
    }

    /**
     * WebView 打开 /filter，等待加载结束，取出 Cookie
     * @return 是否拿到疑似有效 Cookie（含 cf_clearance 或 PHPSESSID）
     */
    private boolean refreshCookieByWebView() {
        if (context == null) return false;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> cookieRef = new AtomicReference<>("");

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                WebView webView = new WebView(context);
                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setUserAgentString(
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"
                );

                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                // 如需每次强制重过验证，可先清站内 Cookie：
                // cm.setCookie(SITE, "");

                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        try {
                            String c = cm.getCookie(SITE);
                            if (TextUtils.isEmpty(c)) {
                                c = cm.getCookie(FILTER_URL);
                            }
                            if (!TextUtils.isEmpty(c)) {
                                cookieRef.set(c);
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            try {
                                view.stopLoading();
                                view.destroy();
                            } catch (Throwable ignored) {
                            }
                            latch.countDown();
                        }
                    }
                });
                webView.loadUrl(FILTER_URL);
            } catch (Throwable e) {
                latch.countDown();
            }
        });

        try {
            // 最多等 20 秒
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String c = cookieRef.get();
        if (!TextUtils.isEmpty(c) && (c.contains("cf_clearance") || c.contains("PHPSESSID"))) {
            cookie = c;
            return true;
        }
        return false;
    }

    private String postMovieList(String mform, String page) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("mform", TextUtils.isEmpty(mform) ? "all" : mform);
        body.put("mcountry", "all");
        body.put("genre_arr", "all");
        body.put("page", TextUtils.isEmpty(page) ? "1" : page);
        body.put("sort", "updatetime");
        body.put("album", "all");
        body.put("title", "");
        body.put("years", "all");

        OkResult result = OkHttp.post(LIST_API, body, getHeader());
        return result == null ? "" : result.getBody();
    }

    private List<Vod> parseList(String json, int[] pageInfo) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(json)) return list;
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.optInt("code") != 1) return list;

            pageInfo[0] = obj.optInt("pages", 1);   // pagecount
            pageInfo[1] = obj.optInt("total", 0);  // total

            JSONArray mlist = obj.optJSONArray("mlist");
            if (mlist == null) return list;

            for (int i = 0; i < mlist.length(); i++) {
                JSONObject it = mlist.getJSONObject(i);
                String mid = it.optString("mid");
                if (TextUtils.isEmpty(mid)) continue;

                Vod vod = new Vod();
                vod.setVodId(mid);
                vod.setVodName(it.optString("title"));
                vod.setVodPic(it.optString("cover_img"));
                String grade = it.optString("grade");
                vod.setVodRemarks(TextUtils.isEmpty(grade) || "0".equals(grade) ? "" : "豆瓣 " + grade);
                list.add(vod);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("all", "全部"));
        // 按站点实际 mform 再补
        return Result.string(classes, new ArrayList<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String page = TextUtils.isEmpty(pg) ? "1" : pg;
        String mform = TextUtils.isEmpty(tid) ? "all" : tid;

        // 1. 先带现有 Cookie 请求
        String json = postMovieList(mform, page);
        int[] pageInfo = new int[]{1, 0};
        List<Vod> list = parseList(json, pageInfo);

        // 2. 失败则 WebView 刷新 Cookie 后再请求一次
        if (list.isEmpty()) {
            boolean ok = refreshCookieByWebView();
            if (ok) {
                json = postMovieList(mform, page);
                list = parseList(json, pageInfo);
            }
        }

        if (list.isEmpty()) {
            return Result.error("分类获取失败：WebView 未拿到有效 Cookie 或接口仍被拦截");
        }

        int pageNum = Integer.parseInt(page);
        int pagecount = Math.max(pageInfo[0], pageNum);
        int total = pageInfo[1] > 0 ? pageInfo[1] : list.size();
        return Result.string(pageNum, pagecount, 36, total, list);
    }

    @Override
    public String action(String action) {
        if (!TextUtils.isEmpty(action) && (action.contains("cf_clearance") || action.contains("PHPSESSID"))) {
            cookie = action.trim();
            return Result.notify("Cookie 已手动更新");
        }
        // action=refresh 时强制 WebView 刷新
        if ("refresh".equalsIgnoreCase(action)) {
            boolean ok = refreshCookieByWebView();
            return Result.notify(ok ? "WebView Cookie 刷新成功" : "WebView Cookie 刷新失败");
        }
        return Result.notify("支持：传入 Cookie 字符串，或 action=refresh");
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        return Result.error("播放解析暂未实现");
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return Result.error("播放解析暂未实现");
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return Result.string(new ArrayList<>());
    }
}
