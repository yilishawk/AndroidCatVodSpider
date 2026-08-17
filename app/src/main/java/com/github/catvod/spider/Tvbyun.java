package com.github.catvod.spider;

import android.text.TextUtils;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.Filter;
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

    private String host = "http://www.tvyun03.com";

    private static final String VERIFY_TYPE = "ad82060c2e67cc7e2cc47552a4fc1242";
    private static final String VERIFY_PATH = "/a20be899_96a6_40b2_88ba_32f1f75f1552_yanzheng_huadong.php";

    private final AtomicReference<String> currentCookie = new AtomicReference<>("");

    private static final Map<String, String> jiexiUrlMap = new HashMap<>();
    static {
        jiexiUrlMap.put("lzm3u8",   "http://111.229.219.148:808/xun3.php?url=");
        jiexiUrlMap.put("bfzym3u8", "http://111.229.219.148:808/xun3.php?url=");
        jiexiUrlMap.put("mytvb",    "http://111.229.219.148:808/index.php?url=");
        jiexiUrlMap.put("YYNB",     "http://111.229.219.148:808/index.php?url=");
        jiexiUrlMap.put("ffm3u8",   "http://111.229.219.148:808/xun3.php?url=");
        jiexiUrlMap.put("1080zyk",  "http://111.229.219.148:808/xun3.php?url=");
        jiexiUrlMap.put("mytv",     "http://111.229.219.148:808/index.php?url=");
    }

    private void log(String msg) {
        try {
            Proxy.log("[Tvbyun] " + msg);
        } catch (Exception ignored) {
        }
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** JS 里的 stringtoHex */
    private String stringToHex(String str) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            sb.append((int) str.charAt(i) + 1);
        }
        return sb.toString();
    }

    /**
     * 自动过滑块验证（动态提取 key / value）
     */
    private synchronized boolean ensureCookie() {
        if (!TextUtils.isEmpty(currentCookie.get())) {
            return true;
        }

        try {
            log("开始自动过滑块验证...");

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.7727.56 Safari/537.36");
            headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9");
            headers.put("Referer", "http://hktvyb.cc/");

            // 1. 访问首页，拿 server_name_session + 滑块页 HTML
            okhttp3.Response resp = OkHttp.newCall(host + "/", headers);
            if (resp == null) {
                log("❌ 首页请求失败");
                return false;
            }

            String sessionCookie = null;
            List<String> setCookies = resp.headers("Set-Cookie");
            if (setCookies != null) {
                for (String sc : setCookies) {
                    if (sc.startsWith("server_name_session=")) {
                        sessionCookie = sc.split(";")[0];
                        break;
                    }
                }
            }

            String html = resp.body() != null ? resp.body().string() : "";
            resp.close();

            if (sessionCookie == null) {
                log("❌ 未获取到 server_name_session");
                return false;
            }
            log("获取到 session: " + sessionCookie);

            // 2. 从 HTML 提取 huadong JS 地址
            Pattern jsPattern = Pattern.compile("src=[\"'](/huadong_[^\"']+\\.js[^\"']*)[\"']");
            Matcher jsMatcher = jsPattern.matcher(html);
            if (!jsMatcher.find()) {
                log("❌ 未在滑块页找到 huadong JS");
                return false;
            }
            String jsUrl = host + jsMatcher.group(1);
            log("提取到 JS 地址: " + jsUrl);

            // 3. 下载 JS，提取 key 和 value
            Map<String, String> jsHeaders = new HashMap<>(headers);
            jsHeaders.put("Referer", host + "/");
            jsHeaders.put("Cookie", sessionCookie);

            String jsContent = OkHttp.string(jsUrl, jsHeaders);
            if (TextUtils.isEmpty(jsContent)) {
                log("❌ JS 下载失败");
                return false;
            }

            Pattern keyPat = Pattern.compile("key\\s*=\\s*[\"']([0-9a-f]{32})[\"']");
            Pattern valPat = Pattern.compile("value\\s*=\\s*[\"']([0-9a-f]{32})[\"']");

            Matcher keyMatcher = keyPat.matcher(jsContent);
            Matcher valMatcher = valPat.matcher(jsContent);

            if (!keyMatcher.find() || !valMatcher.find()) {
                log("❌ JS 中未提取到 key 或 value");
                return false;
            }

            String key = keyMatcher.group(1);
            String value = valMatcher.group(1);
            log("提取到 key=" + key + "  value=" + value);

            // 4. 计算验证参数
            String verifyValue = md5(stringToHex(value));
            String verifyUrl = host + VERIFY_PATH
                    + "?type=" + VERIFY_TYPE
                    + "&key=" + key
                    + "&value=" + verifyValue;

            log("请求验证接口: " + verifyUrl);

            Map<String, String> verifyHeaders = new HashMap<>();
            verifyHeaders.put("User-Agent", headers.get("User-Agent"));
            verifyHeaders.put("Accept", "*/*");
            verifyHeaders.put("Referer", host + "/");
            verifyHeaders.put("Cookie", sessionCookie);

            okhttp3.Response verifyResp = OkHttp.newCall(verifyUrl, verifyHeaders);
            if (verifyResp == null) {
                log("❌ 验证接口请求失败");
                return false;
            }

            String secondCookie = null;
            List<String> verifySetCookies = verifyResp.headers("Set-Cookie");
            if (verifySetCookies != null) {
                for (String sc : verifySetCookies) {
                    // 排除 server_name_session，取另一个
                    if (sc.contains("=") && !sc.startsWith("server_name_session=")) {
                        secondCookie = sc.split(";")[0];
                        break;
                    }
                }
            }

            String body = verifyResp.body() != null ? verifyResp.body().string() : "";
            verifyResp.close();

            if (secondCookie == null) {
                log("❌ 未从验证接口获取到第二个 Cookie，body=" + body);
                return false;
            }

            String fullCookie = sessionCookie + "; " + secondCookie;
            currentCookie.set(fullCookie);
            log("✅ 自动验证成功 → " + fullCookie);
            return true;

        } catch (Exception e) {
            log("自动验证异常: " + e.getMessage());
            return false;
        }
    }

    private HashMap<String, String> getHeaders() {
        ensureCookie();

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
        if (!TextUtils.isEmpty(cookie)) {
            headers.put("Cookie", cookie);
            log("请求携带 Cookie: " + cookie.substring(0, Math.min(50, cookie.length())) + "...");
        } else {
            log("⚠️ 当前没有可用 Cookie");
        }
        return headers;
    }

    private boolean isSliderPage(String html) {
        return html != null && (html.contains("滑动验证") || html.contains("人机身份验证") || html.contains("huadong_"));
    }

    @Override
    public void init(android.content.Context context, String extend) {
        log("🚀 Tvbyun 初始化，域名: " + host);
        ensureCookie();
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        log("进入 homeContent");
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("13", "国产剧"));
        classes.add(new Class("2",  "電視劇"));
        classes.add(new Class("1",  "電影"));
        classes.add(new Class("3",  "綜藝"));
        classes.add(new Class("5",  "短劇"));

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
            String id = extend.containsKey("id") ? extend.get("id") : tid;
            StringBuilder sb = new StringBuilder(host + "/vod/show");

            String[] keys = {"area", "by", "class", "lang", "year"};
            for (String key : keys) {
                if (extend.containsKey(key) && !extend.get(key).isEmpty()) {
                    sb.append("/").append(key).append("/").append(URLEncoder.encode(extend.get(key), "UTF-8"));
                }
            }
            sb.append("/id/").append(id).append("/page/").append(pg).append(".html");

            String url = sb.toString();
            log("分类请求 → " + url);
            String html = OkHttp.string(url, getHeaders());

            if (TextUtils.isEmpty(html)) {
                log("❌ 分类页返回空");
                return Result.string(new ArrayList<>());
            }

            if (isSliderPage(html)) {
                log("⚠️ 分类页命中滑块，重新验证");
                currentCookie.set("");
                ensureCookie();
                html = OkHttp.string(url, getHeaders());
                if (isSliderPage(html)) {
                    log("❌ 重新验证后仍失败");
                    return Result.string(new ArrayList<>());
                }
            }

            Document doc = Jsoup.parse(html);
            List<Vod> list = new ArrayList<>();
            Elements items = doc.select(".myui-vodlist li");
            log("找到列表条目数: " + items.size());

            for (Element item : items) {
                Vod vod = new Vod();
                Element a = item.selectFirst("a.myui-vodlist__thumb");
                if (a != null) {
                    vod.setVodId(a.attr("href"));
                    vod.setVodName(a.attr("title"));
                    vod.setVodPic(a.attr("data-original"));
                    vod.setVodRemarks(item.selectFirst(".pic-tag") != null ? item.selectFirst(".pic-tag").text() : "");
                    list.add(vod);
                }
            }
            log("分类解析完成，返回 " + list.size() + " 条");
            return Result.string(list);
        } catch (Exception e) {
            log("分类异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String detailUrl = ids.get(0).startsWith("http") ? ids.get(0) : host + ids.get(0);
        log("详情页请求 → " + detailUrl);

        String html = OkHttp.string(detailUrl, getHeaders());
        if (TextUtils.isEmpty(html)) {
            log("❌ 详情页返回空");
            return Result.get().vod(new Vod()).string();
        }

        if (isSliderPage(html)) {
            log("⚠️ 详情页命中滑块，重新验证");
            currentCookie.set("");
            ensureCookie();
            html = OkHttp.string(detailUrl, getHeaders());
            if (isSliderPage(html)) {
                return Result.get().vod(new Vod()).string();
            }
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

            Elements datas = detailBox.select("p.data");
            for (Element p : datas) {
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
                        String actorName = a.text().trim();
                        if (!actorName.isEmpty()) actorList.add(actorName);
                    }
                    vod.setVodActor(TextUtils.join(", ", actorList));
                } else if (text.startsWith("导演：")) {
                    vod.setVodDirector(text.replace("导演：", "").trim());
                }
            }
        } else {
            vod.setVodName(vodName);
        }

        Element thumbImg = doc.selectFirst(".myui-content__thumb img, .stui-content__thumb img");
        if (thumbImg != null) {
            String vodPic = thumbImg.hasAttr("data-original") ? thumbImg.attr("data-original") : thumbImg.attr("src");
            vod.setVodPic(vodPic);
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
                String epName = a.text().trim();
                String epUrl = a.attr("href");
                if (!epUrl.startsWith("http")) epUrl = host + epUrl;
                episodeList.add(epName + "$" + epUrl);
            }

            fromList.add(fromName);
            urlList.add(TextUtils.join("#", episodeList));
        }

        vod.setVodPlayFrom(TextUtils.join("$$$", fromList));
        vod.setVodPlayUrl(TextUtils.join("$$$", urlList));

        log("详情解析完成: " + vodName + "，线路数=" + fromList.size());
        return Result.get().vod(vod).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : host + id;
        HashMap<String, String> currentHeaders = getHeaders();

        log("开始解析播放页: " + playUrl + "  flag=" + flag);

        try {
            String html = OkHttp.string(playUrl, currentHeaders);

            if (TextUtils.isEmpty(html)) {
                log("❌ 播放页返回空");
                return Result.get().url(playUrl).parse(1).header(currentHeaders).string();
            }

            if (isSliderPage(html)) {
                log("⚠️ 播放页命中滑块，重新验证");
                currentCookie.set("");
                ensureCookie();
                currentHeaders = getHeaders();
                html = OkHttp.string(playUrl, currentHeaders);
                if (isSliderPage(html)) {
                    return Result.get().url(playUrl).parse(1).header(currentHeaders).string();
                }
            }

            String marker = "var player_data=";
            int start = html.indexOf(marker) + marker.length();
            if (start < marker.length()) {
                log("未找到 player_data，降级嗅探");
                return Result.get().url(playUrl).parse(1).header(currentHeaders).string();
            }

            int end = html.indexOf("</script>", start);
            String jsonStr = html.substring(start, end).trim();
            JsonObject playerData = JsonParser.parseString(jsonStr).getAsJsonObject();
            String rawUrl = playerData.get("url").getAsString();
            String from = playerData.get("from").getAsString();

            log("提取到 from=" + from + "  rawUrl=" + rawUrl);

            Map<String, String> pureHeaders = new HashMap<>();
            pureHeaders.put("User-Agent", currentHeaders.get("User-Agent"));

            if (jiexiUrlMap.containsKey(from)) {
                try {
                    String fullApiUrl = jiexiUrlMap.get(from) + URLEncoder.encode(rawUrl, "UTF-8");
                    log("请求解析接口: " + fullApiUrl);
                    String apiResponse = OkHttp.string(fullApiUrl, currentHeaders);
                    if (!TextUtils.isEmpty(apiResponse)) {
                        JsonObject resJson = JsonParser.parseString(apiResponse).getAsJsonObject();
                        if (resJson.has("code") && resJson.get("code").getAsInt() == 200) {
                            String realUrl = resJson.get("url").getAsString();
                            if (!TextUtils.isEmpty(realUrl) && realUrl.startsWith("http")) {
                                log("✅ 解析成功: " + realUrl);
                                return Result.get().url(realUrl).parse(0).header(pureHeaders).string();
                            }
                        }
                    }
                } catch (Exception e) {
                    log("解析接口异常: " + e.getMessage());
                }
                return Result.get().url(playUrl).parse(1).header(currentHeaders).string();
            }

            if (rawUrl.startsWith("http")) {
                log("✅ 直接返回原始直链: " + rawUrl);
                return Result.get().url(rawUrl).parse(0).header(pureHeaders).string();
            }

            return Result.get().url(playUrl).parse(1).header(currentHeaders).string();

        } catch (Exception e) {
            log("playerContent 异常: " + e.getMessage());
            return Result.get().url(playUrl).parse(1).header(currentHeaders).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        log("搜索: " + key);
        String searchUrl = host + "/index.php/ajax/suggest.html?mid=1&wd=" + URLEncoder.encode(key, "UTF-8");
        String jsonResult = OkHttp.string(searchUrl, getHeaders());
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
            log("搜索完成，结果数: " + list.size());
        } catch (Exception e) {
            log("搜索异常: " + e.getMessage());
        }
        return Result.string(list);
    }
}
