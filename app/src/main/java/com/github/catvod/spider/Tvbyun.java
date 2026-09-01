package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Tvbyun extends Spider {

    private String host = "http://www.tvyun05.com";
    private final AtomicReference<String> currentCookie = new AtomicReference<>("");

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    private static final Map<String, String> jiexiUrlMap = new HashMap<>();

    static {
        jiexiUrlMap.put("lzm3u8", "http://111.229.219.148:808/xun3.php?url=");
        jiexiUrlMap.put("bfzym3u8", "http://111.229.219.148:808/xun3.php?url=");
        jiexiUrlMap.put("mytvb", "http://111.229.219.148:808/index.php?url=");
        jiexiUrlMap.put("YYNB", "http://111.229.219.148:808/index.php?url=");
        jiexiUrlMap.put("ffm3u8", "http://111.229.219.148:808/xun3.php?url=");
        jiexiUrlMap.put("1080zyk", "http://111.229.219.148:808/xun3.php?url=");
        jiexiUrlMap.put("mytv", "http://111.229.219.148:808/index.php?url=");
    }

    private void log(String msg) {
        try {
            Proxy.log("[Tvbyun] " + msg);
        } catch (Exception ignored) {
        }
    }

    private String clip(String s, int max) {
        if (s == null) return "null";
        s = s.replace("\n", " ").replace("\r", " ");
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String stringToHex(String str) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) sb.append((int) str.charAt(i) + 1);
        return sb.toString();
    }

    private String extractJsUrlFromHtml(String html) {
        if (TextUtils.isEmpty(html)) return null;
        Matcher m = Pattern.compile("src=[\"'](/huadong_[^\"']+\\.js[^\"']*)[\"']").matcher(html);
        return m.find() ? host + m.group(1) : null;
    }

    private Map<String, String> extractKeyValueFromJs(String jsContent) {
        Map<String, String> result = new HashMap<>();
        if (TextUtils.isEmpty(jsContent)) return result;
        Matcher keyM = Pattern.compile("key\\s*=\\s*[\"']([0-9a-f]{32})[\"']").matcher(jsContent);
        if (keyM.find()) result.put("key", keyM.group(1));
        Matcher valM = Pattern.compile("value\\s*=\\s*[\"']([0-9a-f]{32})[\"']").matcher(jsContent);
        if (valM.find()) result.put("value", valM.group(1));
        return result;
    }

    private Map<String, String> extractVerifyParams(String content) {
        Map<String, String> result = new HashMap<>();
        if (TextUtils.isEmpty(content)) return result;
        Matcher m = Pattern.compile("c\\.get\\(\"([^\"]+\\?type=([0-9a-f]+))&key=").matcher(content);
        if (m.find()) {
            result.put("verify_path", m.group(1));
            result.put("type", m.group(2));
        }
        return result;
    }

    private String extractCookie(okhttp3.Response resp, String cookieName) {
        if (resp == null) return null;
        List<String> setCookies = resp.headers("Set-Cookie");
        if (setCookies == null) return null;
        for (String sc : setCookies) {
            if (sc.startsWith(cookieName + "=")) return sc.split(";")[0];
        }
        return null;
    }

    private String extractOtherCookie(okhttp3.Response resp) {
        if (resp == null) return null;
        List<String> setCookies = resp.headers("Set-Cookie");
        if (setCookies == null) return null;
        for (String sc : setCookies) {
            if (sc.contains("=") && !sc.startsWith("server_name_session=")) {
                return sc.split(";")[0];
            }
        }
        return null;
    }

    /** 有列表/详情/播放特征则不算滑块 */
    private boolean isSliderPage(String html) {
        if (html == null || html.isEmpty()) return false;
        if (html.contains("myui-vodlist") || html.contains("stui-vodlist")
                || html.contains("myui-content__detail") || html.contains("stui-content__detail")
                || html.contains("myui-panel") || html.contains("player_data")
                || html.contains("myui-vodlist__thumb")) {
            return false;
        }
        return html.contains("滑动验证") || html.contains("人机身份验证")
                || (html.contains("<title>滑动验证</title>"));
    }

    private HashMap<String, String> baseBrowserHeaders() {
        HashMap<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        h.put("Accept-Language", "zh-CN,zh;q=0.9");
        h.put("Upgrade-Insecure-Requests", "1");
        h.put("Cache-Control", "max-age=0");
        h.put("sec-ch-ua", "\"Chromium\";v=\"151\", \"Not A(Brand\";v=\"24\", \"Google Chrome\";v=\"151\"");
        h.put("sec-ch-ua-mobile", "?0");
        h.put("sec-ch-ua-platform", "\"Windows\"");
        return h;
    }

    private HashMap<String, String> getHeaders(String referer) {
        if (TextUtils.isEmpty(currentCookie.get())) {
            ensureCookie();
        }
        HashMap<String, String> h = baseBrowserHeaders();
        h.put("Referer", TextUtils.isEmpty(referer) ? host + "/" : referer);
        String cookie = currentCookie.get();
        if (!TextUtils.isEmpty(cookie)) {
            h.put("Cookie", cookie);
            log("headers Cookie=" + clip(cookie, 70) + " Referer=" + h.get("Referer"));
        } else {
            log("headers 无 Cookie Referer=" + h.get("Referer"));
        }
        return h;
    }

    private static class FetchResult {
        int code;
        String body;

        FetchResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    private FetchResult fetch(String url, HashMap<String, String> headers) {
        try {
            okhttp3.Response resp = OkHttp.newCall(url, headers);
            if (resp == null) {
                log("fetch null url=" + url);
                return new FetchResult(-1, "");
            }
            int code = resp.code();
            String body = resp.body() != null ? resp.body().string() : "";
            resp.close();
            return new FetchResult(code, body);
        } catch (Exception e) {
            log("fetch 异常 " + e.getMessage() + " url=" + url);
            return new FetchResult(-1, "");
        }
    }

    private synchronized boolean ensureCookie() {
        String exist = currentCookie.get();
        if (!TextUtils.isEmpty(exist)) {
            log("复用 Cookie " + clip(exist, 60));
            return true;
        }
        try {
            log("开始滑块验证 host=" + host);
            HashMap<String, String> headers = baseBrowserHeaders();
            headers.put("Referer", host + "/");

            String sessionCookie = null;
            String html = "";
            for (int i = 0; i < 3 && sessionCookie == null; i++) {
                log("第1步 首页 try=" + (i + 1));
                FetchResult fr = fetch(host + "/", headers);
                log("首页 code=" + fr.code + " len=" + fr.body.length() + " head=" + clip(fr.body, 80));
                // 从响应头取 session：再发一次 newCall 只为 Set-Cookie（上面 fetch 已消费 body）
                okhttp3.Response resp1 = OkHttp.newCall(host + "/", headers);
                if (resp1 != null) {
                    sessionCookie = extractCookie(resp1, "server_name_session");
                    List<String> all = resp1.headers("Set-Cookie");
                    log("Set-Cookie=" + clip(String.valueOf(all), 160));
                    if (TextUtils.isEmpty(html)) {
                        html = resp1.body() != null ? resp1.body().string() : "";
                    } else if (resp1.body() != null) {
                        resp1.body().close();
                    }
                    resp1.close();
                }
                if (sessionCookie == null && !TextUtils.isEmpty(fr.body)) html = fr.body;
                if (sessionCookie == null) {
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            if (sessionCookie == null) {
                log("未拿到 server_name_session");
                return false;
            }
            log("sessionCookie=" + sessionCookie);

            // 若首页不是滑块页，也可能没有 js；用一次滑块页保底
            if (extractJsUrlFromHtml(html) == null) {
                log("首页无 huadong JS，请求分类页诱导滑块");
                FetchResult fr2 = fetch(host + "/vod/show/id/13/page/1.html", headers);
                log("诱导页 code=" + fr2.code + " slider=" + isSliderPage(fr2.body));
                if (!TextUtils.isEmpty(fr2.body)) html = fr2.body;
                okhttp3.Response r2 = OkHttp.newCall(host + "/vod/show/id/13/page/1.html", headers);
                if (r2 != null) {
                    String s2 = extractCookie(r2, "server_name_session");
                    if (s2 != null) sessionCookie = s2;
                    if (r2.body() != null) {
                        String b = r2.body().string();
                        if (extractJsUrlFromHtml(b) != null) html = b;
                    }
                    r2.close();
                }
            }

            String jsUrl = extractJsUrlFromHtml(html);
            if (jsUrl == null) {
                log("仍无 huadong JS head=" + clip(html, 200));
                return false;
            }
            log("JS=" + jsUrl);

            HashMap<String, String> jsHeaders = baseBrowserHeaders();
            jsHeaders.put("Referer", host + "/");
            jsHeaders.put("Cookie", sessionCookie);
            jsHeaders.put("Accept", "*/*");
            String jsContent = OkHttp.string(jsUrl, jsHeaders);
            log("JS len=" + (jsContent == null ? 0 : jsContent.length()));
            if (TextUtils.isEmpty(jsContent)) {
                log("JS 下载失败");
                return false;
            }

            Map<String, String> kv = extractKeyValueFromJs(jsContent);
            String key = kv.get("key");
            String value = kv.get("value");
            if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
                log("JS 无 key/value " + clip(jsContent, 120));
                return false;
            }
            log("key=" + key + " value=" + value);

            Map<String, String> verifyParams = extractVerifyParams(jsContent);
            if (verifyParams.isEmpty()) verifyParams = extractVerifyParams(html);
            String verifyPath = verifyParams.get("verify_path");
            String type = verifyParams.get("type");
            if (TextUtils.isEmpty(verifyPath) || TextUtils.isEmpty(type)) {
                log("验证路径降级");
                verifyPath = "/a20be899_96a6_40b2_88ba_32f1f75f1552_yanzheng_huadong.php";
                type = "ad82060c2e67cc7e2cc47552a4fc1242";
                Matcher m2 = Pattern.compile("/[a-f0-9_]+_yanzheng_huadong\\.php").matcher(html + jsContent);
                if (m2.find()) {
                    verifyPath = m2.group();
                    log("路径=" + verifyPath);
                }
            }
            log("verifyPath=" + verifyPath + " type=" + type);

            String md5Value = md5(stringToHex(value));
            String verifyUrl;
            if (verifyPath.contains("?")) {
                verifyUrl = host + verifyPath + "&key=" + key + "&value=" + md5Value;
            } else {
                verifyUrl = host + verifyPath + "?type=" + type + "&key=" + key + "&value=" + md5Value;
            }
            log("验证URL=" + verifyUrl);

            HashMap<String, String> verifyHeaders = baseBrowserHeaders();
            verifyHeaders.put("Referer", host + "/");
            verifyHeaders.put("Cookie", sessionCookie);
            verifyHeaders.put("Accept", "*/*");
            verifyHeaders.put("X-Requested-With", "XMLHttpRequest");

            okhttp3.Response verifyResp = OkHttp.newCall(verifyUrl, verifyHeaders);
            if (verifyResp == null) {
                log("验证响应 null");
                return false;
            }
            int vcode = verifyResp.code();
            String secondCookie = extractOtherCookie(verifyResp);
            String verifyBody = verifyResp.body() != null ? verifyResp.body().string() : "";
            List<String> vsc = verifyResp.headers("Set-Cookie");
            log("验证 code=" + vcode + " Set-Cookie=" + clip(String.valueOf(vsc), 160) + " body=" + clip(verifyBody, 80));
            verifyResp.close();

            if (secondCookie == null) {
                log("未拿到第二段 Cookie");
                return false;
            }

            String fullCookie = sessionCookie + "; " + secondCookie;
            currentCookie.set(fullCookie);
            log("验证写入 Cookie=" + fullCookie);

            // 探测分类页是否真的通过
            String testUrl = host + "/vod/show/id/13/page/1.html";
            String testRef = host + "/vod/show/id/13.html";
            HashMap<String, String> testH = baseBrowserHeaders();
            testH.put("Referer", testRef);
            testH.put("Cookie", fullCookie);
            FetchResult probe = fetch(testUrl, testH);
            boolean hasList = probe.body.contains("myui-vodlist") || probe.body.contains("stui-vodlist");
            boolean slider = isSliderPage(probe.body);
            log("验证后探测 code=" + probe.code + " slider=" + slider + " hasList=" + hasList + " head=" + clip(probe.body, 80));

            if (probe.code == 403 || slider || !hasList) {
                log("探测失败，Cookie 无效，清空");
                currentCookie.set("");
                return false;
            }
            log("探测通过，Cookie 可用");
            return true;
        } catch (Exception e) {
            log("验证异常 " + e.getClass().getSimpleName() + " " + e.getMessage());
            e.printStackTrace();
            currentCookie.set("");
            return false;
        }
    }

    /** 拉 HTML：403/滑块则清 Cookie 重验一次 */
    private FetchResult fetchHtml(String url, String referer, String tag) {
        HashMap<String, String> headers = getHeaders(referer);
        FetchResult fr = fetch(url, headers);
        boolean slider = isSliderPage(fr.body);
        boolean hasList = fr.body.contains("myui-vodlist") || fr.body.contains("stui-vodlist")
                || fr.body.contains("myui-content__detail") || fr.body.contains("player_data");
        log(tag + " code=" + fr.code + " len=" + fr.body.length()
                + " slider=" + slider + " hasList=" + hasList + " head=" + clip(fr.body, 90));

        if (fr.code == 403 || slider) {
            log(tag + " 需重新验证");
            currentCookie.set("");
            if (!ensureCookie()) {
                log(tag + " 重验失败");
                return fr;
            }
            headers = getHeaders(referer);
            fr = fetch(url, headers);
            slider = isSliderPage(fr.body);
            hasList = fr.body.contains("myui-vodlist") || fr.body.contains("stui-vodlist")
                    || fr.body.contains("myui-content__detail") || fr.body.contains("player_data");
            log(tag + " 重试 code=" + fr.code + " slider=" + slider + " hasList=" + hasList + " head=" + clip(fr.body, 90));
        }
        return fr;
    }

    @Override
    public void init(android.content.Context context, String extend) {
        log("init extend=" + extend);
        if (!TextUtils.isEmpty(extend) && extend.startsWith("http")) {
            host = extend.replaceAll("/$", "");
        }
        log("host=" + host);
        ensureCookie();
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        log("homeContent filter=" + filter);
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("13", "国产剧"));
        classes.add(new Class("2", "電視劇"));
        classes.add(new Class("1", "電影"));
        classes.add(new Class("3", "綜藝"));
        classes.add(new Class("5", "短劇"));
        Result result = new Result().classes(classes);
        if (filter) result.filters(getFilterConfig());
        return result.toString();
    }

    protected LinkedHashMap<String, List<Filter>> getFilterConfig() {
        LinkedHashMap<String, List<Filter>> filterConfig = new LinkedHashMap<>();
        List<Filter> guochanFilters = new ArrayList<>();
        guochanFilters.add(new Filter("class", "劇情", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("古裝", "古裝"),
                new Filter.Value("戰爭", "戰爭"), new Filter.Value("青春偶像", "青春偶像"),
                new Filter.Value("喜劇", "喜劇"), new Filter.Value("家庭", "家庭"),
                new Filter.Value("犯罪", "犯罪"), new Filter.Value("動作", "動作"),
                new Filter.Value("奇幻", "奇幻"), new Filter.Value("劇情", "劇情"),
                new Filter.Value("歷史", "歷史"), new Filter.Value("經典", "經典")
        )));
        guochanFilters.add(new Filter("area", "地區", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("大陸", "大陸")
        )));
        guochanFilters.add(new Filter("year", "年份", getYearValues()));
        guochanFilters.add(new Filter("by", "排序", getSortValues()));
        filterConfig.put("13", guochanFilters);

        List<Filter> tvFilters = new ArrayList<>();
        tvFilters.add(new Filter("id", "類型", Arrays.asList(
                new Filter.Value("全部", "2"), new Filter.Value("港台劇", "14"),
                new Filter.Value("日韓劇", "15"), new Filter.Value("歐美劇", "16"),
                new Filter.Value("海外劇", "20")
        )));
        tvFilters.add(new Filter("class", "劇情", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("古裝", "古裝"), new Filter.Value("戰爭", "戰爭"),
                new Filter.Value("青春偶像", "青春偶像"), new Filter.Value("喜劇", "喜劇"), new Filter.Value("家庭", "家庭"),
                new Filter.Value("犯罪", "犯罪"), new Filter.Value("動作", "動作"), new Filter.Value("奇幻", "奇幻"),
                new Filter.Value("劇情", "劇情"), new Filter.Value("歷史", "歷史"), new Filter.Value("經典", "經典")
        )));
        tvFilters.add(new Filter("area", "地區", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("香港", "香港"),
                new Filter.Value("韓國", "韓國"), new Filter.Value("台灣", "台灣"), new Filter.Value("日本", "日本"),
                new Filter.Value("美國", "美國"), new Filter.Value("泰國", "泰國"), new Filter.Value("英國", "英國"),
                new Filter.Value("新加坡", "新加坡"), new Filter.Value("其他", "其他")
        )));
        tvFilters.add(new Filter("year", "年份", getYearValues()));
        tvFilters.add(new Filter("by", "排序", getSortValues()));
        filterConfig.put("2", tvFilters);

        List<Filter> movieFilters = new ArrayList<>();
        movieFilters.add(new Filter("id", "類型", Arrays.asList(
                new Filter.Value("全部", "1"), new Filter.Value("動作片", "6"), new Filter.Value("喜劇片", "7"),
                new Filter.Value("愛情片", "8"), new Filter.Value("科幻片", "9"), new Filter.Value("劇情片", "10"),
                new Filter.Value("恐怖片", "11"), new Filter.Value("戰爭片", "12")
        )));
        movieFilters.add(new Filter("class", "劇情", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("喜劇", "喜劇"), new Filter.Value("愛情", "愛情"),
                new Filter.Value("恐怖", "恐怖"), new Filter.Value("動作", "動作"), new Filter.Value("科幻", "科幻"),
                new Filter.Value("劇情", "劇情"), new Filter.Value("戰爭", "戰爭"), new Filter.Value("警匪", "警匪"),
                new Filter.Value("犯罪", "犯罪"), new Filter.Value("動畫", "動畫"), new Filter.Value("奇幻", "奇幻"),
                new Filter.Value("武俠", "武俠"), new Filter.Value("冒險", "冒險"), new Filter.Value("懸疑", "懸疑")
        )));
        movieFilters.add(new Filter("area", "地區", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("大陸", "大陸"), new Filter.Value("香港", "香港"),
                new Filter.Value("台灣", "台灣"), new Filter.Value("美國", "美國"), new Filter.Value("日本", "日本"),
                new Filter.Value("韓國", "韓國"), new Filter.Value("英國", "英國"), new Filter.Value("泰國", "泰國")
        )));
        movieFilters.add(new Filter("year", "年份", getYearValues()));
        movieFilters.add(new Filter("by", "排序", getSortValues()));
        filterConfig.put("1", movieFilters);

        List<Filter> varietyFilters = new ArrayList<>();
        varietyFilters.add(new Filter("id", "類型", Arrays.asList(
                new Filter.Value("全部", "3"), new Filter.Value("大陸綜藝", "21"),
                new Filter.Value("香港綜藝", "22"), new Filter.Value("日韓綜藝", "23"),
                new Filter.Value("歐美綜藝", "24")
        )));
        varietyFilters.add(new Filter("class", "劇情", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("選秀", "選秀"), new Filter.Value("情感", "情感"),
                new Filter.Value("訪談", "訪談"), new Filter.Value("旅遊", "旅遊"), new Filter.Value("音樂", "音樂"),
                new Filter.Value("美食", "美食"), new Filter.Value("紀實", "紀實"), new Filter.Value("遊戲", "遊戲")
        )));
        varietyFilters.add(new Filter("year", "年份", getYearValues()));
        varietyFilters.add(new Filter("by", "排序", getSortValues()));
        filterConfig.put("3", varietyFilters);

        List<Filter> shortFilters = new ArrayList<>();
        shortFilters.add(new Filter("class", "劇情", Arrays.asList(
                new Filter.Value("全部", ""), new Filter.Value("喜劇", "喜劇"), new Filter.Value("愛情", "愛情"),
                new Filter.Value("動作", "動作"), new Filter.Value("古裝", "古裝"), new Filter.Value("都市", "都市"),
                new Filter.Value("懸疑", "懸疑"), new Filter.Value("玄幻", "玄幻")
        )));
        shortFilters.add(new Filter("year", "年份", getYearValues()));
        shortFilters.add(new Filter("by", "排序", getSortValues()));
        filterConfig.put("5", shortFilters);
        return filterConfig;
    }

    private List<Filter.Value> getYearValues() {
        List<Filter.Value> values = new ArrayList<>();
        values.add(new Filter.Value("全部", ""));
        for (int i = 2026; i >= 2001; i--) {
            values.add(new Filter.Value(String.valueOf(i), String.valueOf(i)));
        }
        return values;
    }

    private List<Filter.Value> getSortValues() {
        return Arrays.asList(
                new Filter.Value("時間", "time"),
                new Filter.Value("人氣", "hits"),
                new Filter.Value("評分", "score")
        );
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String id = (extend != null && extend.containsKey("id") && !TextUtils.isEmpty(extend.get("id")))
                    ? extend.get("id") : tid;
            StringBuilder sb = new StringBuilder(host + "/vod/show");
            String[] keys = {"area", "by", "class", "lang", "year"};
            if (extend != null) {
                for (String key : keys) {
                    if (extend.containsKey(key) && !TextUtils.isEmpty(extend.get(key))) {
                        sb.append("/").append(key).append("/").append(URLEncoder.encode(extend.get(key), "UTF-8"));
                    }
                }
            }
            sb.append("/id/").append(id).append("/page/").append(pg).append(".html");
            String url = sb.toString();
            // 与浏览器抓包一致
            String referer = host + "/vod/show/id/" + id + ".html";
            log("category tid=" + tid + " pg=" + pg + " id=" + id + " url=" + url);

            FetchResult fr = fetchHtml(url, referer, "category");
            if (TextUtils.isEmpty(fr.body) || isSliderPage(fr.body) || fr.code == 403) {
                log("category 失败 code=" + fr.code);
                return Result.string(new ArrayList<>());
            }

            Document doc = Jsoup.parse(fr.body);
            List<Vod> list = new ArrayList<>();
            Elements items = doc.select(".myui-vodlist li");
            if (items.isEmpty()) items = doc.select(".stui-vodlist li");
            log("category 节点数=" + items.size());
            for (Element item : items) {
                Element a = item.selectFirst("a.myui-vodlist__thumb, a.stui-vodlist__thumb, a[href*=/vod/detail]");
                if (a == null) continue;
                Vod vod = new Vod();
                vod.setVodId(a.attr("href"));
                vod.setVodName(TextUtils.isEmpty(a.attr("title")) ? a.text() : a.attr("title"));
                String pic = a.hasAttr("data-original") ? a.attr("data-original") : a.attr("src");
                vod.setVodPic(pic);
                Element tag = item.selectFirst(".pic-tag, .pic-text");
                vod.setVodRemarks(tag != null ? tag.text() : "");
                list.add(vod);
            }
            log("category 返回 " + list.size() + " 条");
            return Result.string(list);
        } catch (Exception e) {
            log("category 异常 " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String path = ids.get(0);
        String detailUrl = path.startsWith("http") ? path : host + path;
        log("detail " + detailUrl);
        FetchResult fr = fetchHtml(detailUrl, host + "/", "detail");
        if (TextUtils.isEmpty(fr.body) || isSliderPage(fr.body) || fr.code == 403) {
            return Result.get().vod(new Vod()).string();
        }

        Document doc = Jsoup.parse(fr.body);
        Vod vod = new Vod();
        vod.setVodId(path);
        String vodName = "未知标题";
        Element detailBox = doc.selectFirst(".myui-content__detail, .stui-content__detail");
        if (detailBox != null) {
            Element titleElem = detailBox.selectFirst(".title");
            if (titleElem != null) vodName = titleElem.text().trim();
            vod.setVodName(vodName);
            for (Element p : detailBox.select("p.data")) {
                String text = p.text().trim();
                if (text.contains("地区：")) {
                    Element areaA = p.selectFirst("a[href*=/area/]");
                    vod.setVodArea(areaA != null ? areaA.text().trim() : "");
                } else if (text.contains("年份：")) {
                    Element yearA = p.selectFirst("a[href*=/year/]");
                    vod.setVodYear(yearA != null ? yearA.text().trim() : "");
                } else if (text.startsWith("更新：")) {
                    vod.setVodRemarks(text.replace("更新：", "").trim());
                } else if (text.startsWith("主演：")) {
                    List<String> actors = new ArrayList<>();
                    for (Element a : p.select("a")) {
                        String n = a.text().trim();
                        if (!n.isEmpty()) actors.add(n);
                    }
                    vod.setVodActor(TextUtils.join(", ", actors));
                } else if (text.startsWith("导演：")) {
                    vod.setVodDirector(text.replace("导演：", "").trim());
                }
            }
        } else {
            vod.setVodName(vodName);
        }

        Element thumbImg = doc.selectFirst(".myui-content__thumb img, .stui-content__thumb img");
        if (thumbImg != null) {
            vod.setVodPic(thumbImg.hasAttr("data-original") ? thumbImg.attr("data-original") : thumbImg.attr("src"));
        }
        Element desc = doc.selectFirst("#desc .col-pd .data p, #desc .sketch.content");
        vod.setVodContent(desc != null ? desc.text().trim() : "");

        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();
        for (Element panel : doc.select(".myui-panel")) {
            Element headTitle = panel.selectFirst(".myui-panel__head h3.title");
            if (headTitle == null) continue;
            String fromName = headTitle.text().trim();
            if (fromName.contains("剧情") || fromName.contains("猜你") || fromName.isEmpty()) continue;
            Elements links = panel.select("ul.myui-content__list a");
            if (links.isEmpty()) continue;
            List<String> eps = new ArrayList<>();
            int max = Math.min(links.size(), 150);
            for (int j = 0; j < max; j++) {
                Element a = links.get(j);
                String epUrl = a.attr("href");
                if (!epUrl.startsWith("http")) epUrl = host + epUrl;
                eps.add(a.text().trim() + "$" + epUrl);
            }
            fromList.add(fromName);
            urlList.add(TextUtils.join("#", eps));
        }
        vod.setVodPlayFrom(TextUtils.join("$$$", fromList));
        vod.setVodPlayUrl(TextUtils.join("$$$", urlList));
        log("detail 完成 " + vodName + " 线路=" + fromList.size());
        return Result.get().vod(vod).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : host + id;
        log("player flag=" + flag + " url=" + playUrl);
        HashMap<String, String> headers = getHeaders(host + "/");
        try {
            FetchResult fr = fetchHtml(playUrl, host + "/", "player");
            if (TextUtils.isEmpty(fr.body) || isSliderPage(fr.body) || fr.code == 403) {
                return Result.get().url(playUrl).parse(1).header(headers).string();
            }
            String html = fr.body;
            String marker = "var player_data=";
            int start = html.indexOf(marker);
            if (start < 0) {
                log("无 player_data");
                return Result.get().url(playUrl).parse(1).header(headers).string();
            }
            start += marker.length();
            int end = html.indexOf("</script>", start);
            if (end < 0) end = Math.min(start + 2000, html.length());
            String jsonStr = html.substring(start, end).trim();
            log("player_data " + clip(jsonStr, 120));

            JsonObject playerData = JsonParser.parseString(jsonStr).getAsJsonObject();
            String rawUrl = playerData.has("url") ? playerData.get("url").getAsString() : "";
            String from = playerData.has("from") ? playerData.get("from").getAsString() : "";
            log("from=" + from + " rawUrl=" + clip(rawUrl, 80));

            Map<String, String> pure = new HashMap<>();
            pure.put("User-Agent", UA);

            if (jiexiUrlMap.containsKey(from)) {
                try {
                    String api = jiexiUrlMap.get(from) + URLEncoder.encode(rawUrl, "UTF-8");
                    log("jiexi " + api);
                    String apiResponse = OkHttp.string(api, headers);
                    log("jiexi resp " + clip(apiResponse, 120));
                    if (!TextUtils.isEmpty(apiResponse)) {
                        JsonObject res = JsonParser.parseString(apiResponse).getAsJsonObject();
                        if (res.has("code") && res.get("code").getAsInt() == 200) {
                            String real = res.get("url").getAsString();
                            if (!TextUtils.isEmpty(real) && real.startsWith("http")) {
                                log("jiexi ok");
                                return Result.get().url(real).parse(0).header(pure).string();
                            }
                        }
                    }
                } catch (Exception e) {
                    log("jiexi 异常 " + e.getMessage());
                }
                return Result.get().url(playUrl).parse(1).header(headers).string();
            }
            if (rawUrl.startsWith("http")) {
                return Result.get().url(rawUrl).parse(0).header(pure).string();
            }
            return Result.get().url(playUrl).parse(1).header(headers).string();
        } catch (Exception e) {
            log("player 异常 " + e.getMessage());
            return Result.get().url(playUrl).parse(1).header(headers).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        log("search " + key);
        String searchUrl = host + "/index.php/ajax/suggest.html?mid=1&wd=" + URLEncoder.encode(key, "UTF-8");
        String jsonResult = OkHttp.string(searchUrl, getHeaders(host + "/"));
        log("search resp " + clip(jsonResult, 120));
        List<Vod> list = new ArrayList<>();
        try {
            JsonObject response = JsonParser.parseString(jsonResult).getAsJsonObject();
            if (response.has("code") && response.get("code").getAsInt() == 1) {
                for (JsonElement el : response.getAsJsonArray("list")) {
                    JsonObject item = el.getAsJsonObject();
                    Vod vod = new Vod();
                    vod.setVodId("/vod/detail/id/" + item.get("id").getAsInt() + ".html");
                    vod.setVodName(item.get("name").getAsString());
                    vod.setVodPic(item.get("pic").getAsString());
                    list.add(vod);
                }
            }
            log("search 条数=" + list.size());
        } catch (Exception e) {
            log("search 异常 " + e.getMessage());
        }
        return Result.string(list);
    }
}
