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

    /** JS stringtoHex：每个字符 Unicode + 1 拼接 */
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

    /**
     * 仅在明确验证页时返回 true，避免 huadong_/slider 误判正常页
     */
    private boolean isSliderPage(String html) {
        if (html == null || html.isEmpty()) return false;
        if (html.contains("myui-vodlist") || html.contains("stui-vodlist")
                || html.contains("myui-content__detail") || html.contains("stui-content__detail")
                || html.contains("myui-panel") || html.contains("player_data")) {
            return false;
        }
        return html.contains("滑动验证") || html.contains("人机身份验证");
    }

    private synchronized boolean ensureCookie() {
        String exist = currentCookie.get();
        if (!TextUtils.isEmpty(exist)) {
            log("复用已有 Cookie: " + clip(exist, 60));
            return true;
        }
        try {
            log("开始滑块验证...");
            HashMap<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36");
            headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9");

            String sessionCookie = null;
            String html = "";
            for (int i = 0; i < 3 && sessionCookie == null; i++) {
                log("第1步 访问首页 try=" + (i + 1));
                okhttp3.Response resp1 = OkHttp.newCall(host + "/", headers);
                if (resp1 == null) {
                    log("首页响应 null");
                    continue;
                }
                sessionCookie = extractCookie(resp1, "server_name_session");
                List<String> allSc = resp1.headers("Set-Cookie");
                log("Set-Cookie 数量=" + (allSc == null ? 0 : allSc.size()) + " raw=" + clip(String.valueOf(allSc), 120));
                html = resp1.body() != null ? resp1.body().string() : "";
                resp1.close();
                log("首页 htmlLen=" + html.length() + " slider=" + isSliderPage(html) + " head=" + clip(html, 80));
                if (sessionCookie == null) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            if (sessionCookie == null) {
                log("未获取到 server_name_session");
                return false;
            }
            log("sessionCookie=" + sessionCookie);

            String jsUrl = extractJsUrlFromHtml(html);
            if (jsUrl == null) {
                log("未提取到 huadong JS，html 中 huadong 位置相关=" + clip(html, 200));
                return false;
            }
            log("JS地址=" + jsUrl);

            HashMap<String, String> jsHeaders = new HashMap<>(headers);
            jsHeaders.put("Referer", host + "/");
            jsHeaders.put("Cookie", sessionCookie);
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
                log("JS 无 key/value head=" + clip(jsContent, 150));
                return false;
            }
            log("key=" + key + " value=" + value);

            Map<String, String> verifyParams = extractVerifyParams(jsContent);
            if (verifyParams.isEmpty()) verifyParams = extractVerifyParams(html);
            String verifyPath = verifyParams.get("verify_path");
            String type = verifyParams.get("type");
            if (TextUtils.isEmpty(verifyPath) || TextUtils.isEmpty(type)) {
                log("未提取验证路径，降级固定路径");
                verifyPath = "/a20be899_96a6_40b2_88ba_32f1f75f1552_yanzheng_huadong.php";
                type = "ad82060c2e67cc7e2cc47552a4fc1242";
                Matcher m2 = Pattern.compile("/[a-f0-9_]+_yanzheng_huadong\\.php").matcher(html);
                if (m2.find()) {
                    verifyPath = m2.group();
                    log("从 HTML 降级路径=" + verifyPath);
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

            HashMap<String, String> verifyHeaders = new HashMap<>(headers);
            verifyHeaders.put("Referer", host + "/");
            verifyHeaders.put("Cookie", sessionCookie);
            okhttp3.Response verifyResp = OkHttp.newCall(verifyUrl, verifyHeaders);
            if (verifyResp == null) {
                log("验证请求 null");
                return false;
            }
            String secondCookie = extractOtherCookie(verifyResp);
            String verifyBody = verifyResp.body() != null ? verifyResp.body().string() : "";
            List<String> vsc = verifyResp.headers("Set-Cookie");
            log("验证 status 相关 Set-Cookie=" + clip(String.valueOf(vsc), 150) + " body=" + clip(verifyBody, 100));
            verifyResp.close();

            if (secondCookie == null) {
                log("验证未返回第二 Cookie");
                return false;
            }

            String fullCookie = sessionCookie + "; " + secondCookie;
            currentCookie.set(fullCookie);
            log("验证成功 fullCookie=" + fullCookie);
            return true;
        } catch (Exception e) {
            log("验证异常: " + e.getClass().getSimpleName() + " " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private HashMap<String, String> getHeaders() {
        if (TextUtils.isEmpty(currentCookie.get())) {
            ensureCookie();
        }
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.7727.56 Safari/537.36");
        headers.put("Referer", host + "/");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Cache-Control", "max-age=0");
        headers.put("Upgrade-Insecure-Requests", "1");
        headers.put("sec-ch-ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"147\", \"Google Chrome\";v=\"147\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        String cookie = currentCookie.get();
        if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
        log("请求头 Cookie=" + (TextUtils.isEmpty(cookie) ? "(空)" : clip(cookie, 50)));
        return headers;
    }

    /** 拉页面：若真滑块则清 Cookie 再验一次 */
    private String fetchHtml(String url, String tag) {
        HashMap<String, String> headers = getHeaders();
        log(tag + " GET " + url);
        String html = OkHttp.string(url, headers);
        log(tag + " htmlLen=" + (html == null ? 0 : html.length())
                + " slider=" + isSliderPage(html)
                + " hasList=" + (html != null && (html.contains("myui-vodlist") || html.contains("stui-vodlist")))
                + " head=" + clip(html, 100));

        if (TextUtils.isEmpty(html)) return html;

        if (isSliderPage(html)) {
            log(tag + " 判定为滑块页，清空 Cookie 重验");
            currentCookie.set("");
            if (!ensureCookie()) {
                log(tag + " 重验失败");
                return html;
            }
            headers = getHeaders();
            html = OkHttp.string(url, headers);
            log(tag + " 重试后 htmlLen=" + (html == null ? 0 : html.length())
                    + " slider=" + isSliderPage(html)
                    + " hasList=" + (html != null && (html.contains("myui-vodlist") || html.contains("stui-vodlist")))
                    + " head=" + clip(html, 100));
        }
        return html;
    }

    @Override
    public void init(android.content.Context context, String extend) {
        log("init host=" + host + " extend=" + extend);
        if (!TextUtils.isEmpty(extend) && extend.startsWith("http")) {
            host = extend.replaceAll("/$", "");
            log("host 改为 " + host);
        }
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
            log("category tid=" + tid + " pg=" + pg + " id=" + id);

            String html = fetchHtml(url, "category");
            if (TextUtils.isEmpty(html)) {
                log("category 空响应");
                return Result.string(new ArrayList<>());
            }
            if (isSliderPage(html)) {
                log("category 仍是滑块，放弃");
                return Result.string(new ArrayList<>());
            }

            Document doc = Jsoup.parse(html);
            List<Vod> list = new ArrayList<>();
            Elements items = doc.select(".myui-vodlist li");
            if (items.isEmpty()) items = doc.select(".stui-vodlist li");
            log("category 条目选择器命中=" + items.size());
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
            log("category 异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String detailUrl = ids.get(0).startsWith("http") ? ids.get(0) : host + ids.get(0);
        log("detail id=" + ids.get(0));

        String html = fetchHtml(detailUrl, "detail");
        if (TextUtils.isEmpty(html) || isSliderPage(html)) {
            log("detail 失败 html空或滑块");
            return Result.get().vod(new Vod()).string();
        }

        Document doc = Jsoup.parse(html);
        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        String vodName = "未知标题";
        Element detailBox = doc.selectFirst(".myui-content__detail");
        if (detailBox == null) detailBox = doc.selectFirst(".stui-content__detail");
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
                    List<String> actorList = new ArrayList<>();
                    for (Element a : p.select("a")) {
                        String n = a.text().trim();
                        if (!n.isEmpty()) actorList.add(n);
                    }
                    vod.setVodActor(TextUtils.join(", ", actorList));
                } else if (text.startsWith("导演：")) {
                    vod.setVodDirector(text.replace("导演：", "").trim());
                }
            }
        } else {
            vod.setVodName(vodName);
            log("detail 未找到 detail 区块");
        }

        Element thumbImg = doc.selectFirst(".myui-content__thumb img, .stui-content__thumb img");
        if (thumbImg != null) {
            vod.setVodPic(thumbImg.hasAttr("data-original") ? thumbImg.attr("data-original") : thumbImg.attr("src"));
        }
        Element desc = doc.selectFirst("#desc .col-pd .data p");
        if (desc == null) desc = doc.selectFirst("#desc .sketch.content");
        vod.setVodContent(desc != null ? desc.text().trim() : "");

        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();
        Elements playlistPanels = doc.select(".myui-panel");
        for (Element panel : playlistPanels) {
            Element headTitle = panel.selectFirst(".myui-panel__head h3.title");
            if (headTitle == null) continue;
            String fromName = headTitle.text().trim();
            if (fromName.contains("剧情") || fromName.contains("猜你") || fromName.isEmpty()) continue;
            Elements links = panel.select("ul.myui-content__list a");
            if (links.isEmpty()) continue;
            List<String> episodeList = new ArrayList<>();
            int max = Math.min(links.size(), 150);
            for (int j = 0; j < max; j++) {
                Element a = links.get(j);
                String epUrl = a.attr("href");
                if (!epUrl.startsWith("http")) epUrl = host + epUrl;
                episodeList.add(a.text().trim() + "$" + epUrl);
            }
            fromList.add(fromName);
            urlList.add(TextUtils.join("#", episodeList));
        }
        vod.setVodPlayFrom(TextUtils.join("$$$", fromList));
        vod.setVodPlayUrl(TextUtils.join("$$$", urlList));
        log("detail 完成 name=" + vodName + " 线路=" + fromList.size());
        return Result.get().vod(vod).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : host + id;
        log("player flag=" + flag + " id=" + id);
        HashMap<String, String> currentHeaders = getHeaders();
        try {
            String html = fetchHtml(playUrl, "player");
            if (TextUtils.isEmpty(html) || isSliderPage(html)) {
                log("player 页失败，降级嗅探");
                return Result.get().url(playUrl).parse(1).header(currentHeaders).string();
            }

            String marker = "var player_data=";
            int start = html.indexOf(marker);
            if (start < 0) {
                log("无 player_data，降级嗅探");
                return Result.get().url(playUrl).parse(1).header(currentHeaders).string();
            }
            start += marker.length();
            int end = html.indexOf("</script>", start);
            if (end < 0) end = Math.min(start + 2000, html.length());
            String jsonStr = html.substring(start, end).trim();
            log("player_data 片段=" + clip(jsonStr, 120));

            JsonObject playerData = JsonParser.parseString(jsonStr).getAsJsonObject();
            String rawUrl = playerData.has("url") ? playerData.get("url").getAsString() : "";
            String from = playerData.has("from") ? playerData.get("from").getAsString() : "";
            log("from=" + from + " rawUrl=" + clip(rawUrl, 80));

            Map<String, String> pureHeaders = new HashMap<>();
            pureHeaders.put("User-Agent", currentHeaders.get("User-Agent"));

            if (jiexiUrlMap.containsKey(from)) {
                try {
                    String fullApiUrl = jiexiUrlMap.get(from) + URLEncoder.encode(rawUrl, "UTF-8");
                    log("解析接口 " + fullApiUrl);
                    String apiResponse = OkHttp.string(fullApiUrl, currentHeaders);
                    log("解析响应 " + clip(apiResponse, 150));
                    if (!TextUtils.isEmpty(apiResponse)) {
                        JsonObject resJson = JsonParser.parseString(apiResponse).getAsJsonObject();
                        if (resJson.has("code") && resJson.get("code").getAsInt() == 200) {
                            String realUrl = resJson.get("url").getAsString();
                            if (!TextUtils.isEmpty(realUrl) && realUrl.startsWith("http")) {
                                log("解析成功 " + clip(realUrl, 100));
                                return Result.get().url(realUrl).parse(0).header(pureHeaders).string();
                            }
                        }
                    }
                } catch (Exception e) {
                    log("解析异常 " + e.getMessage());
                }
                return Result.get().url(playUrl).parse(1).header(currentHeaders).string();
            }

            if (rawUrl.startsWith("http")) {
                log("直链返回");
                return Result.get().url(rawUrl).parse(0).header(pureHeaders).string();
            }
            return Result.get().url(playUrl).parse(1).header(currentHeaders).string();
        } catch (Exception e) {
            log("player 异常 " + e.getMessage());
            return Result.get().url(playUrl).parse(1).header(currentHeaders).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        log("search key=" + key);
        String searchUrl = host + "/index.php/ajax/suggest.html?mid=1&wd=" + URLEncoder.encode(key, "UTF-8");
        String jsonResult = OkHttp.string(searchUrl, getHeaders());
        log("search 响应 " + clip(jsonResult, 150));
        List<Vod> list = new ArrayList<>();
        try {
            JsonObject response = JsonParser.parseString(jsonResult).getAsJsonObject();
            if (response.has("code") && response.get("code").getAsInt() == 1) {
                JsonArray jsonArray = response.getAsJsonArray("list");
                for (JsonElement element : jsonArray) {
                    JsonObject item = element.getAsJsonObject();
                    Vod vod = new Vod();
                    vod.setVodId("/vod/detail/id/" + item.get("id").getAsInt() + ".html");
                    vod.setVodName(item.get("name").getAsString());
                    vod.setVodPic(item.get("pic").getAsString());
                    list.add(vod);
                }
            }
            log("search 结果数=" + list.size());
        } catch (Exception e) {
            log("search 异常 " + e.getMessage());
        }
        return Result.string(list);
    }
}
