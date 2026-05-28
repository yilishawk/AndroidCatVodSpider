package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FkTv extends Spider {

    private final String siteUrl = "https://fktv.me";
    private final String imageSearchUrl = "https://hongniuzy.tv/index.php/ajax/suggest.html?mid=1&wd=";

    private final String UA = "Mozilla/5.0 (Linux; Android 15; 23054RA19C Build/AP3A.240905.015.A2; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/147.0.7727.137 Mobile Safari/537.36";
    private final String COOKIE = "_did=wEdXiQxa07zJ15hm0AsNjxsc4rZRSKzb; _device=pc";

    private Map<String, String> getHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", siteUrl + "/");
        headers.put("Accept", "application/json, text/javascript, */*; q=0.01");
        headers.put("Cookie", COOKIE);
        return headers;
    }

    // ==================== 快速解析列表 + 异步补图 ====================
    private List<Vod> parseList(String html) {
        List<Vod> list = new ArrayList<>();
        Map<Vod, String> titleMap = new HashMap<>();   // 用于异步补图时保存标题

        Document doc = Jsoup.parse(html);
        Elements items = doc.select("div.item-wrap.vertical");

        for (Element item : items) {
            Element a = item.selectFirst("a[href^=/movie/detail]");
            if (a == null) continue;

            String href = siteUrl + a.attr("href");
            String name = a.attr("title");
            String remark = item.selectFirst(".category") != null ? item.selectFirst(".category").text() : "";

            Element img = item.selectFirst("div.lazy-load");
            String pic = img != null ? img.attr("data-src") : "";

            Vod vod = new Vod();
            vod.setVodId(href);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodRemarks(remark);

            list.add(vod);
            titleMap.put(vod, name);   // 保存对应标题
        }

        // 启动异步补图
        if (!list.isEmpty()) {
            asyncLoadBetterImages(titleMap);
        }

        return list;
    }

    // 异步补高清图（关键修复）
    private void asyncLoadBetterImages(Map<Vod, String> titleMap) {
        ExecutorService executor = Executors.newFixedThreadPool(4);  // 限制并发
        for (Map.Entry<Vod, String> entry : titleMap.entrySet()) {
            Vod vod = entry.getKey();
            String title = entry.getValue();

            executor.execute(() -> {
                try {
                    String betterPic = getBetterImage(title);
                    if (!TextUtils.isEmpty(betterPic)) {
                        vod.setVodPic(betterPic);   // 更新图片
                    }
                } catch (Exception ignored) {}
            });
        }
        executor.shutdown(); // 不等待完成
    }

    private String getBetterImage(String title) {
        if (TextUtils.isEmpty(title)) return "";
        try {
            String url = imageSearchUrl + URLEncoder.encode(title, "UTF-8");
            String json = OkHttp.string(url);
            JSONObject obj = new JSONObject(json);
            JSONArray arr = obj.optJSONArray("list");
            if (arr != null && arr.length() > 0) {
                String pic = arr.getJSONObject(0).optString("pic");
                if (pic.startsWith("http")) return pic;
            }
        } catch (Exception ignored) {}
        return "";
    }

    @Override
    public void init(Context context, String extend) throws Exception {
    }

    // ==================== 首页 ====================
    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("1", "电影"));
        classes.add(new Class("2", "电视剧"));
        classes.add(new Class("3", "综艺"));
        classes.add(new Class("8", "短剧"));

        String html = OkHttp.string(siteUrl + "/channel?cat_id=1&page=1&page_size=32", getHeader());
        List<Vod> list = parseList(html);
        return Result.string(classes, list);
    }

    // ==================== 分类页 ====================
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String url = siteUrl + "/channel?page=" + pg + "&cat_id=" + tid + "&tag_id=&order=new&page_size=32";
        String html = OkHttp.string(url, getHeader());
        List<Vod> list = parseList(html);
        return Result.string(list);
    }

    // ==================== 搜索 ====================
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        try {
            String url = siteUrl + "/channel?keywords=" + URLEncoder.encode(key, "UTF-8");
            String html = OkHttp.string(url, getHeader());
            List<Vod> list = parseList(html);
            return Result.string(list);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.string(new ArrayList<>());
        }
    }

    // ==================== 详情页 ====================
    @Override
    public String detailContent(List<String> ids) throws Exception {
    String detailUrl = ids.get(0).startsWith("http") ? ids.get(0) : host + ids.get(0);
    String html = OkHttp.string(detailUrl, getHeaders());
    Document doc = Jsoup.parse(html);

    Vod vod = new Vod();
    vod.setVodId(detailUrl);

    // 標題
    Element nameEl = doc.selectFirst(".name");
    vod.setVodName(nameEl != null ? nameEl.text().trim() : "未知標題");

    // 簡介
    Element desc = doc.selectFirst(".desc");
    vod.setVodContent(desc != null ? desc.text().trim() : "");

    // 圖片
    String pic = getBetterImage(vod.getVodName() != null ? vod.getVodName() : "");
    vod.setVodPic(pic);

    // 提取 linkIds
    List<String> linkIds = extractLinkIds(html);
    if (linkIds.isEmpty()) {
        return Result.get().vod(vod).string();
    }

    // 獲取播放線路樣本
    List<String> playLines = getPlayUrls(detailUrl, linkIds.get(0));

    List<String> fromList = new ArrayList<>();
    List<String> urlList = new ArrayList<>();

    for (String line : playLines) {
        String[] parts = line.split("\\$");
        if (parts.length == 2) {
            String lineName = parts[0];
            fromList.add(lineName);

            // 生成各集（限制集數）
            List<String> episodes = new ArrayList<>();
            for (int i = 0; i < linkIds.size(); i++) {
                if (i >= 120) break;   // 重要：限制集數，避免電視端卡頓
                String episodeName = "第" + (i + 1) + "集";
                String playId = detailUrl + "|" + linkIds.get(i);
                episodes.add(episodeName + "$" + playId);
            }
            urlList.add(String.join("#", episodes));
        }
    }

    vod.setVodPlayFrom(String.join("$$$", fromList));
    vod.setVodPlayUrl(String.join("$$$", urlList));

    return Result.get().vod(vod).string();
}

        Vod vod = new Vod();
        vod.setVodId(detailUrl);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setVodContent(content);
        vod.setVodPlayFrom(String.join("$$$", lineNames));
        vod.setVodPlayUrl(String.join("$$$",
                lineMap.values().stream().map(list -> String.join("#", list)).toList()));

        return Result.string(vod);
    }

    private List<String> extractLinkIds(String html) {
        List<String> linkIds = new ArrayList<>();
        Elements scripts = Jsoup.parse(html).select("script");
        String scriptText = "";
        for (Element s : scripts) {
            if (s.html().contains("var links")) {
                scriptText = s.html();
                break;
            }
        }
        String linksStr = extractRegex(scriptText, "var links\\s*=\\s*(\\[.*?\\]);");
        if (!linksStr.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(linksStr);
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.getJSONObject(i).optString("id");
                    if (!id.isEmpty()) linkIds.add(id);
                }
            } catch (Exception ignored) {}
        }
        if (linkIds.isEmpty()) {
            String defaultId = extractRegex(scriptText, "linkId\\s*=\\s*['\"](.*?)['\"]");
            if (!defaultId.isEmpty()) linkIds.add(defaultId);
        }
        return linkIds;
    }

    private List<String> getPlayUrls(String detailUrl, String linkId) {
        List<String> urls = new ArrayList<>();
        try {
            Map<String, String> postData = new HashMap<>();
            postData.put("link_id", linkId);
            postData.put("is_switch", "1");

            Map<String, String> postHeader = new HashMap<>(getHeader());
            postHeader.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            postHeader.put("X-Requested-With", "XMLHttpRequest");
            postHeader.put("Origin", siteUrl);

            OkResult res = OkHttp.post(detailUrl, postData, postHeader);
            JSONObject json = new JSONObject(res.getBody());

            if ("y".equals(json.optString("status"))) {
                JSONArray playLinks = json.getJSONObject("data").getJSONArray("play_links");
                for (int i = 0; i < playLinks.length(); i++) {
                    JSONObject line = playLinks.getJSONObject(i);
                    String m3u8 = line.optString("m3u8_url");
                    if (m3u8.startsWith("/")) m3u8 = siteUrl + m3u8;
                    urls.add("线路" + (i + 1) + "$" + m3u8);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return urls;
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (id.contains("|")) {
            String[] parts = id.split("\\|");
            if (parts.length == 2) {
                String detailUrl = parts[0];
                String linkId = parts[1];
                List<String> playList = getPlayUrls(detailUrl, linkId);
                if (!playList.isEmpty()) {
                    String[] arr = playList.get(0).split("\\$");
                    if (arr.length == 2) {
                        return Result.get().url(arr[1]).string();
                    }
                }
            }
        }
        return Result.get().url(id).string();
    }

    private String extractRegex(String text, String regex) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
        java.util.regex.Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : "";
    }
}
