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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Tvbyun extends Spider {

    private final String host = "http://www.viptvb08.com";

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

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.104 Mobile Safari/537.36");
        headers.put("Referer", host + "/");
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
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

            String html = OkHttp.string(sb.toString(), getHeaders());
            Document doc = Jsoup.parse(html);

            List<Vod> list = new ArrayList<>();
            Elements items = doc.select(".myui-vodlist li");
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
            return Result.string(list);
        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
    String detailUrl = ids.get(0).startsWith("http") ? ids.get(0) : host + ids.get(0);
    String html = OkHttp.string(detailUrl, getHeaders());
    Document doc = Jsoup.parse(html);

    Vod vod = new Vod();
    vod.setVodId(ids.get(0));

    // 1. 定位详情核心区域（减少全屏扫描）
    Element detailBox = doc.selectFirst(".myui-content__detail");
    if (detailBox == null) detailBox = doc.selectFirst(".stui-content__detail");

    if (detailBox != null) {
        // 标题
        Element titleElem = detailBox.selectFirst(".title");
        vod.setVodName(titleElem != null ? titleElem.text().trim() : "未知标题");

        // 一次性循环解析所有 p.data 标签，彻底抛弃低效的 :contains() 和 getInfo 循环
        Elements datas = detailBox.select("p.data");
        for (Element p : datas) {
            String text = p.text().trim();
            if (text.startsWith("分类：")) {
                // 如果需要分类可以解析
            } else if (text.contains("地区：")) {
                // 提取地区：由于有隐藏标签，直接拿对应 a 标签文本最快
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
        vod.setVodName("未知标题");
    }

    // 2. 图片提取
    Element thumbImg = doc.selectFirst(".myui-content__thumb img, .stui-content__thumb img");
    if (thumbImg != null) {
        String vodPic = thumbImg.hasAttr("data-original") ? thumbImg.attr("data-original") : thumbImg.attr("src");
        vod.setVodPic(vodPic);
    }

    // 3. 简介提取
    Element desc = doc.selectFirst("#desc .col-pd .data p"); // 精准定位隐藏的高清完整简介
    if (desc == null) desc = doc.selectFirst("#desc .sketch.content");
    vod.setVodContent(desc != null ? desc.text().trim() : "");

    // 4. 播放线路与列表解析（基于你提供的源码极致优化）
    List<String> fromList = new ArrayList<>();
    List<String> urlList  = new ArrayList<>();

    // 根据源码，每一个播放列表都是一个单独的 .myui-panel，里面包含 h3.title 和 ul.myui-content__list
    Elements playlistPanels = doc.select(".myui-panel");
    for (Element panel : playlistPanels) {
        Element headTitle = panel.selectFirst(".myui-panel__head h3.title");
        if (headTitle == null) continue;

        String fromName = headTitle.text().trim();
        // 过滤非播放列表区域
        if (fromName.contains("剧情") || fromName.contains("猜你") || fromName.isEmpty()) continue;

        Elements links = panel.select("ul.myui-content__list a");
        if (links.isEmpty()) continue;

        List<String> episodeList = new ArrayList<>();
        int max = Math.min(links.size(), 150); // 电视端限制集数防止内存溢出
        for (int j = 0; j < max; j++) {
            Element a = links.get(j);
            String epName = a.text().trim();
            String epUrl  = a.attr("href");
            if (!epUrl.startsWith("http")) epUrl = host + epUrl;
            episodeList.add(epName + "$" + epUrl);
        }

        fromList.add(fromName);
        urlList.add(TextUtils.join("#", episodeList));
    }

    vod.setVodPlayFrom(TextUtils.join("$$$", fromList));
    vod.setVodPlayUrl(TextUtils.join("$$$", urlList));

    return Result.get().vod(vod).string();
}


    private String getInfo(Document doc, String key) {
        Element el = doc.selectFirst("p.data:contains(" + key + ")");
        if (el == null) return "";
        String text = el.text();
        return text.replace(key + "：", "").replace(key + ":", "").trim();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
    String playUrl = id.startsWith("http") ? id : host + id;
    HashMap<String, String> currentHeaders = getHeaders();

    try {
        String html = OkHttp.string(playUrl, currentHeaders);

        // 提取播放器配置 JSON 
        String marker = "var player_data=";
        int start = html.indexOf(marker) + marker.length();
        if (start < marker.length()) {
            return Result.get().url(playUrl).parse(1).header(currentHeaders).string();
        }

        int end = html.indexOf("</script>", start);
        String jsonStr = html.substring(start, end).trim();
        JsonObject playerData = JsonParser.parseString(jsonStr).getAsJsonObject();
        String rawUrl = playerData.get("url").getAsString();
        String from   = playerData.get("from").getAsString();

        // 1. 如果有对应的解析接口，必须走解析去广告
        if (jiexiUrlMap.containsKey(from)) {
            try {
                String fullApiUrl = jiexiUrlMap.get(from) + URLEncoder.encode(rawUrl, "UTF-8");
                String apiResponse = OkHttp.string(fullApiUrl, currentHeaders);
                if (apiResponse != null && !apiResponse.trim().isEmpty()) {
                    JsonObject resJson = JsonParser.parseString(apiResponse).getAsJsonObject();
                    if (resJson.has("code") && resJson.get("code").getAsInt() == 200) {
                        String realUrl = resJson.get("url").getAsString();
                        if (realUrl != null && !realUrl.isEmpty() && realUrl.startsWith("http")) {
                            Map<String, String> pureHeaders = new HashMap<>();
                            pureHeaders.put("User-Agent", currentHeaders.get("User-Agent"));
                            // 解析成功，直接推送解析后的直链 (parse(0))
                            return Result.get().url(realUrl).parse(0).header(pureHeaders).string();
                        }
                    }
                }
            } catch (Exception ignored) {}
            
            // 如果该线路有解析接口但因网络等原因解析失败了，降级交给壳子嗅探播放
            return Result.get().url(playUrl).parse(1).header(currentHeaders).string();
        }

        // 2. 如果没有对应的解析接口，直接把提取出来的原始直链推给壳子播放
        if (rawUrl.startsWith("http")) {
            return Result.get().url(rawUrl).parse(0).header(currentHeaders).string();
        }

        // 兜底逻辑
        return Result.get().url(playUrl).parse(1).header(currentHeaders).string();

    } catch (Exception e) {
        return Result.get().url(playUrl).parse(1).header(currentHeaders).string();
    }
}


    @Override
    public String searchContent(String key, boolean quick) throws Exception {
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
        } catch (Exception ignored) {}
        return Result.string(list);
    }
}
