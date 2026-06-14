package com.github.catvod.spider;

import android.content.Context;
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
import java.util.List;
import java.util.Map;

public class FkTv extends Spider {

    private final String siteUrl = "https://fktv.me";
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

    @Override
    public void init(Context context, String extend) throws Exception {
    }

    private List<Vod> parseList(String html) {
        List<Vod> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Elements items = doc.select("div.item-wrap.vertical");

        for (Element item : items) {
            Element a = item.selectFirst("a[href^=/movie/detail]");
            if (a == null) continue;

            String href = siteUrl + a.attr("href");
            String name = a.attr("title");
            String remark = item.selectFirst(".category") != null
                    ? item.selectFirst(".category").text() : "";

            Vod vod = new Vod();
            vod.setVodId(href);
            vod.setVodName(name);
            vod.setVodRemarks(remark);

            // 只使用本地代理图片
            String proxyPic = Proxy.getUrl() + "?do=getPoster&title=" + encodeUrl(name);
            vod.setVodPic(proxyPic);

            list.add(vod);
        }
        return list;
    }

    // 封装 URLEncoder，避免异常
    private String encodeUrl(String str) {
        try {
            return URLEncoder.encode(str, "UTF-8");
        } catch (Exception e) {
            return str.replace(" ", "%20");
        }
    }

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

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String url = siteUrl + "/channel?page=" + pg + "&cat_id=" + tid + "&tag_id=&order=new&page_size=32";
        String html = OkHttp.string(url, getHeader());
        return Result.string(parseList(html));
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        try {
            String url = siteUrl + "/channel?keywords=" + encodeUrl(key);
            String html = OkHttp.string(url, getHeader());
            return Result.string(parseList(html));
        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String detailUrl = ids.get(0).startsWith("http") ? ids.get(0) : siteUrl + ids.get(0);
        String html = OkHttp.string(detailUrl, getHeader());
        Document doc = Jsoup.parse(html);

        Vod vod = new Vod();
        vod.setVodId(detailUrl);

        Element nameEl = doc.selectFirst(".name");
        vod.setVodName(nameEl != null ? nameEl.text().trim() : "未知標題");

        Element desc = doc.selectFirst(".desc");
        vod.setVodContent(desc != null ? desc.text().trim() : "");

        List<String> linkIds = extractLinkIds(html);
        if (linkIds.isEmpty()) {
            return Result.get().vod(vod).string();
        }

        List<String> playLines = getPlayUrls(detailUrl, linkIds.get(0));
        if (playLines.isEmpty()) {
            return Result.get().vod(vod).string();
        }

        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();

        for (String line : playLines) {
            String[] parts = line.split("\\$", 2);
            if (parts.length != 2) continue;

            fromList.add(parts[0]);

            List<String> episodes = new ArrayList<>();
            int limit = Math.min(linkIds.size(), 120);
            for (int i = 0; i < limit; i++) {
                String episodeName = "第" + (i + 1) + "集";
                episodes.add(episodeName + "$" + detailUrl + "|" + linkIds.get(i) + "|" + playLines.indexOf(line));
            }
            urlList.add(String.join("#", episodes));
        }

        vod.setVodPlayFrom(String.join("$$$", fromList));
        vod.setVodPlayUrl(String.join("$$$", urlList));

        return Result.get().vod(vod).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // FkTv 只做图片代理，播放直接返回原始地址
        if (id.contains("|")) {
            String[] parts = id.split("\\|");
            if (parts.length >= 3) {
                String detailUrl = parts[0];
                String linkId = parts[1];
                int lineIndex = 0;
                try { lineIndex = Integer.parseInt(parts[2]); } catch (Exception ignored) {}

                List<String> playList = getPlayUrls(detailUrl, linkId);
                if (lineIndex < playList.size()) {
                    String[] arr = playList.get(lineIndex).split("\\$", 2);
                    if (arr.length == 2) {
                        return Result.get().url(arr[1]).string();
                    }
                }
            }
        }
        return Result.get().url(id).string();
    }

    // ==================== 工具方法 ====================
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
                    String lid = arr.getJSONObject(i).optString("id");
                    if (!lid.isEmpty()) linkIds.add(lid);
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

    private String extractRegex(String text, String regex) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
        java.util.regex.Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : "";
    }
}
