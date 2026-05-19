package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import com.github.catvod.utils.Json;

import org.json.JSONArray;
import org.json.JSONObject;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Qkys extends Spider {

    private String host = "https://www.qkw1.com";
    private String jxHost = "https://zyz-omtcqq-com-oss-cn-hangzhou-shanghai-yys-valipl-vip-cp11.xmsu8.top";
    private Map<String, String> headers;

    public Qkys() {
        headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Accept-Encoding", "gzip, deflate, br, zstd");
        headers.put("Connection", "keep-alive");
    }

    @Override
    public void init(Context context, String extend) {
        if (!TextUtils.isEmpty(extend)) {
            try {
                JSONObject cfg = new JSONObject(extend);
                if (cfg.has("host")) host = cfg.optString("host");
                if (cfg.has("jxHost")) jxHost = cfg.optString("jxHost");
            } catch (Exception e) {
                SpiderDebug.log("[Qkys] parse extend error: " + e.getMessage());
            }
        }
    }

    // ==================== 工具方法 ====================

    private String fetch(String url) throws Exception {
        return OkHttp.string(url, headers);
    }

    private String fetch(String url, Map<String, String> extraHeaders) throws Exception {
        Map<String, String> allHeaders = new HashMap<>(headers);
        if (extraHeaders != null) allHeaders.putAll(extraHeaders);
        return OkHttp.string(url, allHeaders);
    }

    // ==================== 首页 ====================

    @Override
    public String homeContent(boolean filter) {
        List<com.github.catvod.bean.Class> classes = Arrays.asList(
                new com.github.catvod.bean.Class("guochan", "国产剧"),
                new com.github.catvod.bean.Class("2", "连续剧"),
                new com.github.catvod.bean.Class("1", "电影"),
                new com.github.catvod.bean.Class("3", "综艺"),
                new com.github.catvod.bean.Class("4", "动漫")
        );
        return Result.string(classes, new LinkedHashMap<>());
    }

    // ==================== 分类列表 ====================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String url = host + "/qkwshow/" + tid + "--------" + pg + "---.html";
            String html = fetch(url);
            Document doc = Jsoup.parse(html);
            
            List<Vod> videos = new ArrayList<>();
            Elements items = doc.select("li.stui-vodlist__item");
            
            for (Element item : items) {
                Element thumb = item.selectFirst(".stui-vodlist__thumb");
                if (thumb == null) continue;
                
                String href = thumb.attr("href");
                if (TextUtils.isEmpty(href)) continue;
                String vid = href.startsWith("http") ? href : host + href;
                
                String name = thumb.attr("title");
                if (TextUtils.isEmpty(name)) {
                    Element titleLink = item.selectFirst("h4.stui-vodlist__title a");
                    if (titleLink != null) name = titleLink.text();
                }
                if (TextUtils.isEmpty(name)) continue;
                
                String pic = thumb.attr("data-original");
                if (TextUtils.isEmpty(pic)) pic = thumb.attr("src");
                
                Element remarkElem = item.selectFirst(".pic-text");
                String remark = remarkElem != null ? remarkElem.text().trim() : "";
                
                videos.add(new Vod(vid, name, pic, remark));
            }
            
            return Result.string(videos);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    // ==================== 详情页 ====================

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return Result.error("No id");
            String id = ids.get(0);
            String url = id.startsWith("http") ? id : host + id;
            String html = fetch(url);
            Document doc = Jsoup.parse(html);

            Vod vod = new Vod();
            vod.setVodId(id);

            // 标题
            Element titleElem = doc.selectFirst(".stui-content__detail .title");
            if (titleElem != null) vod.setVodName(titleElem.text());
            
            // 图片
            Element thumbImg = doc.selectFirst(".stui-content__thumb img");
            if (thumbImg != null) {
                String pic = thumbImg.attr("data-original");
                if (TextUtils.isEmpty(pic)) pic = thumbImg.attr("src");
                vod.setVodPic(pic);
            }

            // 导演、演员
            Element detail = doc.selectFirst(".stui-content__detail");
            if (detail != null) {
                Element dirElem = detail.selectFirst("p.data:contains(导演)");
                if (dirElem != null) vod.setVodDirector(dirElem.text().replace("导演：", "").trim());
                
                Element actElem = detail.selectFirst("p.data:contains(主演)");
                if (actElem != null) vod.setVodActor(actElem.text().replace("主演：", "").trim());
            }

            // 简介
            Element descElem = doc.selectFirst(".stui-content__desc");
            if (descElem != null) vod.setVodContent(descElem.text().trim());

            // 播放列表
            List<String> fromList = new ArrayList<>();
            List<String> urlList = new ArrayList<>();
            Elements heads = doc.select(".stui-pannel__head");
            
            for (Element head : heads) {
                Element headTitle = head.selectFirst("h3.title");
                if (headTitle == null) continue;
                String headTitleText = headTitle.text();
                if (headTitleText.contains("源") || headTitleText.contains("播放")) {
                    fromList.add(headTitleText);
                    Element parent = head.parent();
                    if (parent == null) continue;
                    Element playlist = parent.selectFirst("ul.stui-content__playlist");
                    if (playlist == null) continue;
                    List<String> links = new ArrayList<>();
                    for (Element a : playlist.select("a")) {
                        String name = a.text().trim();
                        String href = a.attr("href");
                        String fullHref = href.startsWith("http") ? href : host + href;
                        links.add(name + "$" + fullHref);
                    }
                    urlList.add(String.join("#", links));
                }
            }
            
            vod.setVodPlayFrom(String.join("$$$", fromList));
            vod.setVodPlayUrl(String.join("$$$", urlList));

            return Result.string(vod);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    // ==================== 搜索 ====================

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    public String searchContent(String key, boolean quick, String pg) {
        try {
            String url = host + "/qkwsearch/-------------.html?wd=" + URLEncoder.encode(key, "UTF-8") + "&submit=";
            String html = fetch(url);
            Document doc = Jsoup.parse(html);
            
            List<Vod> videos = new ArrayList<>();
            Elements items = doc.select("li.stui-vodlist__item");
            
            for (Element item : items) {
                Element thumb = item.selectFirst(".stui-vodlist__thumb");
                if (thumb == null) continue;
                
                String href = thumb.attr("href");
                if (TextUtils.isEmpty(href)) continue;
                String vid = href.startsWith("http") ? href : host + href;
                String name = thumb.attr("title");
                if (TextUtils.isEmpty(name)) continue;
                String pic = thumb.attr("data-original");
                if (TextUtils.isEmpty(pic)) pic = thumb.attr("src");
                Element remarkElem = item.selectFirst(".pic-text");
                String remark = remarkElem != null ? remarkElem.text().trim() : "";
                
                videos.add(new Vod(vid, name, pic, remark));
            }
            
            return Result.string(videos);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    // ==================== 播放解析 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String playUrl = id.startsWith("http") ? id : host + id;
        Map<String, String> reqHeaders = new HashMap<>(headers);
        reqHeaders.put("Referer", host + "/");
        
        String html = "";
        for (int i = 0; i < 2; i++) {
            try {
                html = fetch(playUrl, reqHeaders);
                if (html.contains("player_aaaa")) break;
                Thread.sleep(1000);
            } catch (Exception ignored) {}
        }
        
        if (TextUtils.isEmpty(html) || !html.contains("player_aaaa")) {
            return Result.get().parse(1).url(playUrl).string();
        }

        JSONObject pdata;
        try {
            int startIdx = html.indexOf("var player_aaaa=");
            startIdx = html.indexOf("{", startIdx);
            int count = 1, pos = startIdx + 1;
            while (pos < html.length() && count > 0) {
                char c = html.charAt(pos);
                if (c == '{') count++;
                else if (c == '}') count--;
                pos++;
            }
            String jsonStr = html.substring(startIdx, pos);
            pdata = new JSONObject(jsonStr);
        } catch (Exception e) {
            return Result.get().parse(1).url(playUrl).string();
        }

        // 构建中转页请求
        Map<String, String> params = new HashMap<>();
        params.put("url", pdata.optString("url", ""));
        params.put("type", pdata.optString("from", ""));
        params.put("next", pdata.optString("link_next", ""));
        params.put("data", pdata.optString("play_data", ""));

        try {
            Map<String, String> idxHeaders = new HashMap<>(headers);
            idxHeaders.put("Referer", host + "/");
            idxHeaders.put("Upgrade-Insecure-Requests", "1");
            idxHeaders.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");

            StringBuilder sb = new StringBuilder(jxHost + "/index.php");
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!TextUtils.isEmpty(e.getValue())) {
                    sb.append(first ? "?" : "&").append(e.getKey()).append("=").append(URLEncoder.encode(e.getValue(), "UTF-8"));
                    first = false;
                }
            }
            String idxResp = fetch(sb.toString(), idxHeaders);

            Pattern configPattern = Pattern.compile("var config = (\\{[\\s\\S]*?\\});", Pattern.DOTALL);
            Matcher configMatcher = configPattern.matcher(idxResp);
            if (!configMatcher.find()) {
                return Result.get().parse(1).url(playUrl).string();
            }
            String configStr = configMatcher.group(1);

            String urlVal = extractJsonField(configStr, "url");
            String timeVal = extractJsonField(configStr, "time");
            String vkeyVal = extractJsonField(configStr, "vkey");
            
            if (TextUtils.isEmpty(urlVal) || TextUtils.isEmpty(timeVal) || TextUtils.isEmpty(vkeyVal)) {
                return Result.get().parse(1).url(playUrl).string();
            }

            // POST 获取真实地址
            String apiUrl = jxHost + "/admin/mizhi_json.php";
            Map<String, String> apiParams = new HashMap<>();
            apiParams.put("url", urlVal);
            apiParams.put("time", timeVal);
            apiParams.put("key", "");
            apiParams.put("vkey", vkeyVal);

            Map<String, String> apiHeaders = new HashMap<>();
            apiHeaders.put("User-Agent", headers.get("User-Agent"));
            apiHeaders.put("Accept", "application/json, text/javascript, */*; q=0.01");
            apiHeaders.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            apiHeaders.put("X-Requested-With", "XMLHttpRequest");
            apiHeaders.put("Origin", jxHost);
            apiHeaders.put("Referer", sb.toString());

            OkResult apiResult = OkHttp.post(apiUrl, apiParams, apiHeaders);
            if (apiResult.getCode() == 200) {
                JSONObject resJson = new JSONObject(apiResult.getBody());
                String finalUrl = resJson.optString("url");
                if (TextUtils.isEmpty(finalUrl)) finalUrl = resJson.optString("video_url");
                if (!TextUtils.isEmpty(finalUrl) && finalUrl.startsWith("http")) {
                    Map<String, String> header = new HashMap<>();
                    header.put("User-Agent", headers.get("User-Agent"));
                    return Result.get().parse(0).url(finalUrl).header(header).string();
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("[Qkys] player error: " + e.getMessage());
        }

        return Result.get().parse(1).url(playUrl).string();
    }

    private String extractJsonField(String text, String field) {
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(text);
        if (m.find()) return m.group(1);
        p = Pattern.compile("'" + field + "'\\s*:\\s*'([^']*)'");
        m = p.matcher(text);
        if (m.find()) return m.group(1);
        p = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)");
        m = p.matcher(text);
        if (m.find()) return m.group(1);
        return "";
    }
}
