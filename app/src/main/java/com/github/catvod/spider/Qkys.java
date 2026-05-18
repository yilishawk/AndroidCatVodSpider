package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全看影院爬虫
 * 站点: https://www.qkw1.com
 */
public class Qkys extends Spider {

    private String host = "https://www.qkw1.com";
    private String jxHost = "https://zyz-omtcqq-com-oss-cn-hangzhou-shanghai-yys-valipl-vip-cp11.xmsu8.top";
    private Map<String, String> baseHeaders;

    public Qkys() {
        baseHeaders = new HashMap<>();
        baseHeaders.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36");
        baseHeaders.put("Accept-Language", "zh-CN,zh;q=0.9");
        baseHeaders.put("Accept-Encoding", "gzip, deflate, br, zstd");
        baseHeaders.put("Connection", "keep-alive");
    }

    @Override
    public void init(Context context, String extend) {
        // 可在此处通过 extend 覆盖域名
        if (!TextUtils.isEmpty(extend)) {
            try {
                JSONObject cfg = new JSONObject(extend);
                if (cfg.has("host")) host = cfg.optString("host");
                if (cfg.has("jxHost")) jxHost = cfg.optString("jxHost");
            } catch (Exception e) {
                SpiderDebug.log("[全看影院] 解析扩展配置失败: " + e.getMessage());
            }
        }
        // 预热访问
        try {
            OkHttp.string(host, baseHeaders);
        } catch (Exception ignored) {}
        SpiderDebug.log("[全看影院] 初始化完成，host: " + host);
    }

    @Override
    public String getName() {
        return "全看影院";
    }

    // ---------- 工具方法 ----------
    private String fetch(String url) throws Exception {
        return OkHttp.string(url, baseHeaders);
    }

    private String fetch(String url, Map<String, String> extraHeaders) throws Exception {
        Map<String, String> headers = new HashMap<>(baseHeaders);
        if (extraHeaders != null) headers.putAll(extraHeaders);
        return OkHttp.string(url, headers);
    }

    private JSONArray parseList(String html) {
        JSONArray videos = new JSONArray();
        Document doc = Jsoup.parse(html);
        Elements items = doc.select("li.stui-vodlist__item");
        for (Element item : items) {
            Element thumb = item.selectFirst(".stui-vodlist__thumb");
            if (thumb == null) continue;
            String href = thumb.attr("href");
            String vid = href.startsWith("http") ? href : host + href;
            String name = thumb.attr("title");
            String pic = thumb.attr("data-original");
            if (TextUtils.isEmpty(pic)) pic = thumb.attr("src");
            Element remarkElem = item.selectFirst(".pic-text");
            String remark = remarkElem != null ? remarkElem.text().trim() : "";
            try {
                JSONObject vod = new JSONObject();
                vod.put("vod_id", vid);
                vod.put("vod_name", name);
                vod.put("vod_pic", pic);
                vod.put("vod_remarks", remark);
                videos.put(vod);
            } catch (Exception e) {
                SpiderDebug.log("[全看影院] 解析列表项失败: " + e.getMessage());
            }
        }
        return videos;
    }

    // ---------- 首页 ----------
    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();

            String[][] cls = {
                    {"国产剧", "guochan"},
                    {"连续剧", "2"},
                    {"电影", "1"},
                    {"综艺", "3"},
                    {"动漫", "4"}
            };
            for (String[] c : cls) {
                JSONObject obj = new JSONObject();
                obj.put("type_name", c[0]);
                obj.put("type_id", c[1]);
                classes.put(obj);
            }
            result.put("class", classes);
            result.put("filters", new JSONObject()); // 无筛选器
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[全看影院] homeContent 错误: " + e.getMessage());
            return "{\"class\":[], \"filters\":{}}";
        }
    }

    // ---------- 分类列表 ----------
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String url = host + "/qkwshow/" + tid + "--------" + pg + "---.html";
            String html = fetch(url);
            JSONArray list = parseList(html);
            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("page", Integer.parseInt(pg));
            // 不返回 pagecount 也没问题，前端会继续请求直到无数据
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[全看影院] categoryContent 错误: " + e.getMessage());
            return "{\"list\":[], \"page\":" + pg + "}";
        }
    }

    // ---------- 详情页 ----------
    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return "{\"list\":[]}";
            String id = ids.get(0);
            String url = id.startsWith("http") ? id : host + id;
            String html = fetch(url);
            Document doc = Jsoup.parse(html);

            Element detail = doc.selectFirst(".stui-content__detail");
            if (detail == null) return "{\"list\":[]}";

            String title = detail.selectFirst(".title") != null ? detail.selectFirst(".title").text() : "";
            Element thumbImg = doc.selectFirst(".stui-content__thumb img");
            String pic = thumbImg != null ? thumbImg.attr("data-original") : "";
            if (TextUtils.isEmpty(pic)) pic = thumbImg != null ? thumbImg.attr("src") : "";

            String director = "";
            Element dirElem = detail.selectFirst("p.data:contains(导演)");
            if (dirElem != null) director = dirElem.text().replace("导演：", "").trim();

            String actor = "";
            Element actElem = detail.selectFirst("p.data:contains(主演)");
            if (actElem != null) actor = actElem.text().replace("主演：", "").trim();

            Element descElem = doc.selectFirst(".stui-content__desc");
            String content = descElem != null ? descElem.text().trim() : "";

            // 播放来源和剧集
            List<String> fromList = new ArrayList<>();
            List<String> urlList = new ArrayList<>();
            Elements heads = doc.select(".stui-pannel__head");
            for (Element head : heads) {
                Element titleElem = head.selectFirst("h3.title");
                if (titleElem == null) continue;
                String headTitle = titleElem.text();
                if (headTitle.contains("源") || headTitle.contains("播放")) {
                    fromList.add(headTitle);
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

            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", title);
            vod.put("vod_pic", pic);
            vod.put("vod_director", director);
            vod.put("vod_actor", actor);
            vod.put("vod_content", content);
            vod.put("vod_play_from", String.join("$$$", fromList));
            vod.put("vod_play_url", String.join("$$$", urlList));

            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[全看影院] detailContent 错误: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    // ---------- 搜索 ----------
    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    public String searchContent(String key, boolean quick, String pg) {
        try {
            String url = host + "/qkwsearch/-------------.html?wd=" + URLEncoder.encode(key, "UTF-8") + "&submit=";
            String html = fetch(url);
            JSONArray list = parseList(html);
            JSONObject result = new JSONObject();
            result.put("list", list);
            result.put("page", Integer.parseInt(pg));
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[全看影院] searchContent 错误: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    // ---------- 播放解析 ----------
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String playUrl = id.startsWith("http") ? id : host + id;
        Map<String, String> headers = new HashMap<>(baseHeaders);
        headers.put("Referer", host + "/");
        String html = "";
        // 重试一次
        for (int i = 0; i < 2; i++) {
            try {
                html = fetch(playUrl, headers);
                if (html.contains("player_aaaa")) break;
                Thread.sleep(1000);
            } catch (Exception e) {
                SpiderDebug.log("[全看影院] 播放页请求失败: " + e.getMessage());
            }
        }
        if (TextUtils.isEmpty(html) || !html.contains("player_aaaa")) {
            return "{\"parse\":1,\"url\":\"" + playUrl + "\"}";
        }

        // 提取 player_aaaa JSON
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
            SpiderDebug.log("[全看影院] 解析 player_aaaa 失败: " + e.getMessage());
            return "{\"parse\":1,\"url\":\"" + playUrl + "\"}";
        }

        // 构建中转页请求
        String idxUrl = jxHost + "/index.php";
        Map<String, String> params = new HashMap<>();
        params.put("url", pdata.optString("url", ""));
        params.put("type", pdata.optString("from", ""));
        params.put("next", pdata.optString("link_next", ""));
        params.put("data", pdata.optString("play_data", ""));

        try {
            // 请求中转页
            Map<String, String> idxHeaders = new HashMap<>(baseHeaders);
            idxHeaders.put("Referer", host + "/");
            idxHeaders.put("Upgrade-Insecure-Requests", "1");
            idxHeaders.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");

            // 使用 GET 并拼接参数
            StringBuilder sb = new StringBuilder(idxUrl);
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!TextUtils.isEmpty(e.getValue())) {
                    sb.append(first ? "?" : "&").append(e.getKey()).append("=").append(URLEncoder.encode(e.getValue(), "UTF-8"));
                    first = false;
                }
            }
            String idxResp = fetch(sb.toString(), idxHeaders);

            // 提取 config = {...}
            Pattern configPattern = Pattern.compile("var config = (\\{[\\s\\S]*?\\});", Pattern.DOTALL);
            Matcher configMatcher = configPattern.matcher(idxResp);
            if (!configMatcher.find()) {
                return "{\"parse\":1,\"url\":\"" + playUrl + "\"}";
            }
            String configStr = configMatcher.group(1);

            // 提取 url, time, vkey
            String urlVal = extractJsonField(configStr, "url");
            String timeVal = extractJsonField(configStr, "time");
            String vkeyVal = extractJsonField(configStr, "vkey");
            if (TextUtils.isEmpty(urlVal) || TextUtils.isEmpty(timeVal) || TextUtils.isEmpty(vkeyVal)) {
                return "{\"parse\":1,\"url\":\"" + playUrl + "\"}";
            }

            // POST 到 mizhi_json.php
            String apiUrl = jxHost + "/admin/mizhi_json.php";
            Map<String, String> apiParams = new HashMap<>();
            apiParams.put("url", urlVal);
            apiParams.put("time", timeVal);
            apiParams.put("key", "");
            apiParams.put("vkey", vkeyVal);

            Map<String, String> apiHeaders = new HashMap<>();
            apiHeaders.put("User-Agent", baseHeaders.get("User-Agent"));
            apiHeaders.put("Accept", "application/json, text/javascript, */*; q=0.01");
            apiHeaders.put("Accept-Language", "zh-CN,zh;q=0.9");
            apiHeaders.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            apiHeaders.put("X-Requested-With", "XMLHttpRequest");
            apiHeaders.put("Origin", jxHost);
            apiHeaders.put("Referer", sb.toString()); // 中转页完整 URL
            apiHeaders.put("Sec-Fetch-Dest", "empty");
            apiHeaders.put("Sec-Fetch-Mode", "cors");
            apiHeaders.put("Sec-Fetch-Site", "same-origin");

            OkResult apiResult = OkHttp.post(apiUrl, apiParams, apiHeaders);
            if (apiResult.getCode() == 200) {
                String body = apiResult.getBody();
                JSONObject resJson = new JSONObject(body);
                String finalUrl = resJson.optString("url");
                if (TextUtils.isEmpty(finalUrl)) finalUrl = resJson.optString("video_url");
                if (!TextUtils.isEmpty(finalUrl) && finalUrl.startsWith("http")) {
                    JSONObject result = new JSONObject();
                    result.put("parse", 0);
                    result.put("url", finalUrl);
                    JSONObject header = new JSONObject();
                    header.put("User-Agent", baseHeaders.get("User-Agent"));
                    result.put("header", header);
                    return result.toString();
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("[全看影院] 播放解析异常: " + e.getMessage());
        }

        return "{\"parse\":1,\"url\":\"" + playUrl + "\"}";
    }

    /**
     * 从类似 JSON 的字符串中提取指定字段的值（支持双引号和单引号）
     */
    private String extractJsonField(String text, String field) {
        // 匹配 "field":"value" 或 'field':'value'
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