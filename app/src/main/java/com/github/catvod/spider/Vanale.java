package com.github.catvod.spider;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Vanale extends Spider {

    private static final String HOST = "https://a.v-anale.best";
    private static boolean isVerified = false; // 密码验证状 态全局缓存

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, George) Chrome/114.0.0.0 Safari/537.36");
        headers.put("Referer", HOST + "/");
        return headers;
    }

    // 全局动态尝试获取合法的 Activity
    private Activity getActivity() {
        try {
            return com.github.catvod.spider.Init.getActivity();
        } catch (Throwable e) {
            try {
                java.lang.reflect.Method method = Class.forName("com.github.catvod.utils.Utils").getMethod("getTopActivity");
                return (Activity) method.invoke(null);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    @Override
    public void init(final Context context, String extend) throws Exception {
        super.init(context, extend);
    }

    private synchronized void checkPasswordWithWebView() {
        if (isVerified) {
            return;
        }

        final Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }

        // 创建锁，初始计数为 1
        final CountDownLatch latch = new CountDownLatch(1);

        // 强行切到 Android UI 主线程进行 WebView 渲染
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface"})
            @Override
            public void run() {
                try {
                    final Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                    final WebView webView = new WebView(activity);
                    
                    webView.getSettings().setJavaScriptEnabled(true);
                    webView.setBackgroundColor(Color.parseColor("#121212")); // 适配暗黑背景

                    // 构建交互用的 HTML 页面内容（纯前端 UI，不依赖外部网络，秒开）
                    String html = "<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>"
                            + "<style>"
                            + "body { background-color: #121212; color: #ffffff; font-family: sans-serif; display: flex; flex-direction: column; justify-content: center; align-items: center; height: 100vh; margin: 0; }"
                            + ".box { background: #1e1e1e; padding: 30px; border-radius: 12px; box-shadow: 0 4px 15px rgba(0,0,0,0.5); text-align: center; width: 80%; max-width: 360px; }"
                            + "h3 { margin-bottom: 20px; color: #e0e0e0; font-weight: normal; }"
                            + "input { width: 100%; padding: 12px; margin-bottom: 20px; border: 1px solid #333; background: #2c2c2c; color: #fff; border-radius: 6px; box-sizing: border-box; font-size: 16px; text-align: center; }"
                            + "button { width: 100%; padding: 12px; border: none; background: #ff4757; color: #fff; border-radius: 6px; font-size: 16px; cursor: pointer; font-weight: bold; }"
                            + "button:active { background: #e84118; }"
                            + "</style></head><body>"
                            + "<div class='box'>"
                            + "<h3>请输入安全验证密码</h3>"
                            + "<input type='password' id='pwd' placeholder='请输入密码' autofocus>"
                            + "<button onclick='submit()'>确 定</button>"
                            + "</div>"
                            + "<script>"
                            + "function submit() {"
                            + "    var p = document.getElementById('pwd').value;"
                            + "    if(p) { window.AndroidBridge.onPasswordSubmit(p); }"
                            + "}"
                            + "</script></body></html>";

                    // 注入 Javascript 桥梁接口
                    webView.addJavascriptInterface(new Object() {
                        @JavascriptInterface
                        public void onPasswordSubmit(String password) {
                            if ("123456789".equals(password)) {
                                isVerified = true;
                            }
                            // 收到密码并验证后，通知主线程关闭 Dialog 并释放 Latch
                            new Handler(Looper.getMainLooper()).post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        dialog.dismiss();
                                    } catch (Exception e) {
                                        SpiderDebug.log(e);
                                    }
                                    latch.countDown(); // 减计数，释放爬虫阻塞状态
                                }
                            });
                        }
                    }, "AndroidBridge");

                    webView.setWebViewClient(new WebViewClient());
                    String encodedHtml = Base64.encodeToString(html.getBytes("UTF-8"), Base64.NO_PADDING);
                    webView.loadData(encodedHtml, "text/html", "base64");

                    dialog.setContentView(webView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    dialog.setCancelable(false); // 强制要求用户必须输入，不可滑走
                    dialog.show();

                } catch (Exception e) {
                    SpiderDebug.log(e);
                    latch.countDown();
                }
            }
        });

        // 核心阻塞：让爬虫的核心提取线程在非 UI 线程挂起，等待用户在 WebView 输入完成
        try {
            latch.await();
        } catch (InterruptedException e) {
            SpiderDebug.log(e);
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        // 进入首页时唤醒 WebView
        checkPasswordWithWebView();

        List<Class> classes = new ArrayList<Class>();
        if (!isVerified) {
            return Result.error("验证失败，安全限制无法访问该爬虫源！");
        }

        classes.add(new Class("asian", "亚洲"));
        classes.add(new Class("rimming", "毒龙/舔肛"));
        classes.add(new Class("granny", "熟女/祖母"));
        classes.add(new Class("bondage", "捆绑/BDSM"));
        classes.add(new Class("pregnant", "孕妇"));
        classes.add(new Class("blonde", "金发女郎"));
        classes.add(new Class("big-ass", "大屁股"));
        classes.add(new Class("big-tits", "大胸"));
        classes.add(new Class("big-dick", "大器粗活"));
        classes.add(new Class("brazzers", "Brazers"));
        classes.add(new Class("brunette", "黑发"));
        classes.add(new Class("stockings", "丝袜"));
        classes.add(new Class("hairy", "多毛"));
        classes.add(new Class("threesome", "三人行"));
        classes.add(new Class("deep-anal", "深度肛交"));
        classes.add(new Class("rough", "粗暴"));
        classes.add(new Class("gangbang", "群交"));
        classes.add(new Class("double-anal", "双洞肛交"));
        classes.add(new Class("dildo", "假阳具"));
        classes.add(new Class("homemade", "自拍自制"));
        classes.add(new Class("hardcore", "硬核"));
        classes.add(new Class("mature", "熟女"));
        classes.add(new Class("toys", "性玩具"));
        classes.add(new Class("ass-to-mouth", "肛转口"));
        classes.add(new Class("cuckold", "绿帽"));
        classes.add(new Class("anal-creampie", "肛内射精"));
        classes.add(new Class("cosplay", "角色扮演"));
        classes.add(new Class("beautiful", "唯美"));
        classes.add(new Class("babe", "美女"));
        classes.add(new Class("pussy-licking", "舔阴"));
        classes.add(new Class("latina", "拉丁"));
        classes.add(new Class("lesbian", "拉拉"));
        classes.add(new Class("milf", "人妻熟女"));
        classes.add(new Class("massage", "按摩"));
        classes.add(new Class("masturbation", "自慰"));
        classes.add(new Class("stepmom", "继母"));
        classes.add(new Class("blowjob", "口交"));
        classes.add(new Class("young", "年轻"));
        classes.add(new Class("outdoor", "户外"));
        classes.add(new Class("ebony", "黑人女"));
        classes.add(new Class("pov", "第一视角"));
        classes.add(new Class("first-anal", "首次尝试"));
        classes.add(new Class("russian", "俄罗斯"));
        classes.add(new Class("wife", "妻子"));
        classes.add(new Class("bbc", "巨根黑人"));
        classes.add(new Class("sister", "姐妹"));
        classes.add(new Class("family", "乱伦家庭"));
        classes.add(new Class("squirt", "潮吹"));
        classes.add(new Class("solo", "独奏"));
        classes.add(new Class("student", "学生妹"));
        classes.add(new Class("tiny", "娇小玲珑"));
        classes.add(new Class("amateur", "业余"));
        classes.add(new Class("teen", "少女"));
        classes.add(new Class("japanese", "日本"));

        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        List<Vod> list = new ArrayList<Vod>();
        if (!isVerified) return Result.get().vod(list).string();

        int page = Integer.parseInt(pg);
        String url = HOST + "/" + tid + "?page=" + page;
        String html = OkHttp.string(url, getHeaders());

        if (TextUtils.isEmpty(html)) {
            return Result.get().vod(list).page(page, 0, 0, 0).string();
        }

        Pattern pVideo = Pattern.compile("<div class=\"video\">([\\s\\S]*?)</div>\\s*</div>", Pattern.CASE_INSENSITIVE);
        Matcher mVideo = pVideo.matcher(html);

        while (mVideo.find()) {
            String block = mVideo.group(1);

            Pattern pId = Pattern.compile("data-id=\"(\\d+)\"", Pattern.CASE_INSENSITIVE);
            Matcher mId = pId.matcher(block);
            if (!mId.find()) continue;
            String id = mId.group(1);

            Pattern pImg = Pattern.compile("<img[^>]+src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher mImg = pImg.matcher(block);
            String img = mImg.find() ? mImg.group(1) : "";
            if (img.startsWith("/")) {
                img = HOST + img;
            }

            Pattern pTitle = Pattern.compile("<img[^>]+alt=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher mTitle = pTitle.matcher(block);
            String title = mTitle.find() ? mTitle.group(1) : "Video " + id;

            Pattern pDur = Pattern.compile("<span class=\"duration\">[^<]*</span>\\s*([^<]+)</span>", Pattern.CASE_INSENSITIVE);
            Matcher mDur = pDur.matcher(block);
            String duration = mDur.find() ? mDur.group(1).trim() : "";

            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(title);
            vod.setVodPic(img);
            vod.setVodRemarks(duration);

            list.add(vod);
        }

        int nextPg = page + 1;
        int totalPage = html.contains("page=" + nextPg) ? nextPg : page;

        return Result.get().vod(list).page(page, totalPage, 20, 2000).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (!isVerified) return Result.get().vod(new Vod()).string();

        String id = ids.get(0);
        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName("视频详情 #" + id);
        vod.setVodPic(HOST + "/images/" + id + ".jpg");
        vod.setVodPlayFrom("Vanale高速线");
        vod.setVodPlayUrl("播放播放$" + id); 

        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        List<Vod> list = new ArrayList<Vod>();
        return Result.get().vod(list).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (!isVerified) return Result.get().url("").string();

        String targetUrl = HOST + "/get/" + id;
        String m3u8MainUrl = OkHttp.string(targetUrl, getHeaders());

        if (TextUtils.isEmpty(m3u8MainUrl)) {
            return Result.get().url("").string();
        }
        m3u8MainUrl = m3u8MainUrl.trim();

        String m3u8Content = OkHttp.string(m3u8MainUrl, getHeaders());
        if (TextUtils.isEmpty(m3u8Content)) {
            return Result.get().url(m3u8MainUrl).header(getHeaders()).string();
        }

        String baseUrl = m3u8MainUrl.substring(0, m3u8MainUrl.lastIndexOf("/") + 1);
        
        String p1080 = findSubM3u8(m3u8Content, "1080p");
        String p720 = findSubM3u8(m3u8Content, "720p");
        String p480 = findSubM3u8(m3u8Content, "480p");
        String p360 = findSubM3u8(m3u8Content, "360p");
        String p250 = findSubM3u8(m3u8Content, "250p");

        String finalPlayUrl = m3u8MainUrl;
        
        if (!p1080.isEmpty()) {
            finalPlayUrl = p1080.startsWith("http") ? p1080 : baseUrl + p1080;
        } else if (!p720.isEmpty()) {
            finalPlayUrl = p720.startsWith("http") ? p720 : baseUrl + p720;
        } else if (!p480.isEmpty()) {
            finalPlayUrl = p480.startsWith("http") ? p480 : baseUrl + p480;
        } else if (!p360.isEmpty()) {
            finalPlayUrl = p360.startsWith("http") ? p360 : baseUrl + p360;
        } else if (!p250.isEmpty()) {
            finalPlayUrl = p250.startsWith("http") ? p250 : baseUrl + p250;
        }

        return Result.get().url(finalPlayUrl).header(getHeaders()).string();
    }

    private String findSubM3u8(String content, String resolutionName) {
        try {
            Pattern pattern = Pattern.compile("RESOLUTION=[^,\\n\\r]+,NAME=\"" + resolutionName + "\"\\s*\\n?([^\\#\\n\\r]+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (Exception e) {
            // 优雅抓取
        }
        return "";
    }
}
