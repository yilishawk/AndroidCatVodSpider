package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.github.catvod.net.OkResult;

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

public class Qkys extends Spider {

    private String host = "https://www.qkw1.com";
    private String jxHost = "https://zyz-omtcqq-com-oss-cn-hangzhou-shanghai-yys-valipl-vip-cp11.xmsu8.top";

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Connection", "keep-alive");
        headers.put("Referer", host + "/");
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("guochan", "国产剧"));
        classes.add(new Class("2", "连续剧"));
        classes.add(new Class("1", "电影"));
        classes.add(new Class("3", "综艺"));
        classes.add(new Class("4", "动漫"));
        return Result.string(classes, new ArrayList<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String url = host + "/qkwshow/" + tid + "--------" + pg + "---.html";
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders()));
        List<Vod> list = new ArrayList<>();
        for (Element item : doc.select(".stui-vodlist__item")) {
            Element thumb = item.selectFirst(".stui-vodlist__thumb");
            if (thumb == null) continue;
            String href = thumb.attr("href");
            if (!href.startsWith("http")) href = host + href;
            String pic = thumb.attr("data-original");
            if (pic == null || pic.isEmpty()) pic = thumb.attr("src");
            list.add(new Vod(href, thumb.attr("title"), pic, item.select(".pic-text").text().trim()));
        }
        return Result.get().page(Integer.parseInt(pg), Integer.parseInt(pg) + 1, list.size(), 1000).vod(list).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = ids.get(0).startsWith("http") ? ids.get(0) : host + ids.get(0);
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders()));
        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        
        Element titleElem = doc.selectFirst(".stui-content__detail .title");
        if (titleElem != null) vod.setVodName(titleElem.text().trim());
        
        Element thumbImg = doc.selectFirst(".stui-content__thumb img");
        if (thumbImg != null) {
            String pic = thumbImg.attr("data-original");
            if (pic == null || pic.isEmpty()) pic = thumbImg.attr("src");
            vod.setVodPic(pic);
        }
        Element picText = doc.selectFirst(".stui-content__thumb .pic-text");
        if (picText != null) vod.setVodRemarks(picText.text().trim());
        
        Element dirElem = doc.selectFirst(".stui-content__detail p.data:contains(导演)");
        if (dirElem != null) vod.setVodDirector(dirElem.text().replace("导演：", "").trim());
        
        Element actElem = doc.selectFirst(".stui-content__detail p.data:contains(主演)");
        if (actElem != null) vod.setVodActor(actElem.text().replace("主演：", "").trim());
        
        Element typeElem = doc.selectFirst(".stui-content__detail p.data:contains(类型)");
        if (typeElem != null) vod.setTypeName(typeElem.text().replace("类型：", "").trim());
        
        Element areaElem = doc.selectFirst(".stui-content__detail p.data:contains(地区)");
        if (areaElem != null) vod.setVodArea(areaElem.text().replace("地区：", "").trim());
        
        Element yearElem = doc.selectFirst(".stui-content__detail p.data:contains(年份)");
        if (yearElem != null) vod.setVodYear(yearElem.text().replace("年份：", "").trim());
        
        Element descElem = doc.selectFirst(".stui-content__desc");
        if (descElem != null) vod.setVodContent(descElem.text().trim());
        
        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();
        for (Element head : doc.select(".stui-pannel__head")) {
            String title = head.select("h3.title").text();
            if (title.contains("源") || title.contains("播放")) {
                fromList.add(title);
                Elements as = head.parent().select("ul.stui-content__playlist a");
                List<String> links = new ArrayList<>();
                for (Element a : as) {
                    String href = a.attr("href");
                    if (!href.startsWith("http")) href = host + href;
                    links.add(a.text() + "$" + href);
                }
                urlList.add(String.join("#", links));
            }
        }
        vod.setVodPlayFrom(String.join("$$$", fromList));
        vod.setVodPlayUrl(String.join("$$$", urlList));
        
        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        String url = host + "/qkwsearch/-------------.html?wd=" + URLEncoder.encode(key, "UTF-8") + "&submit=";
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders()));
        List<Vod> list = new ArrayList<>();
        for (Element item : doc.select(".stui-vodlist__item")) {
            Element thumb = item.selectFirst(".stui-vodlist__thumb");
            if (thumb == null) continue;
            String href = thumb.attr("href");
            if (!href.startsWith("http")) href = host + href;
            String pic = thumb.attr("data-original");
            if (pic == null || pic.isEmpty()) pic = thumb.attr("src");
            list.add(new Vod(href, thumb.attr("title"), pic, item.select(".pic-text").text().trim()));
        }
        return Result.string(list);
    }

    // === 根据 Python 逻辑深度重构的视频解析部分 ===
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : host + id;

        HashMap<String, String> h1 = getHeaders();
        h1.put("Referer", host + "/");

        String html = "";
        // 重试获取包含 player_aaaa 的页面
        for (int i = 0; i < 3; i++) {
            try {
                html = OkHttp.string(playUrl, h1);
                if (html.contains("player_aaaa")) {
                    break;
                }
                Thread.sleep(1000);
            } catch (Exception e) {
                Thread.sleep(1000);
            }
        }

        if (!html.contains("player_aaaa")) {
            return Result.get().parse(1).url(playUrl).string();
        }

        // ==================== 提取 player_aaaa JSON（精确匹配Python） ====================
        JsonObject pdata;
        try {
            int start = html.indexOf("var player_aaaa=");
            if (start == -1) throw new Exception("player_aaaa not found");

            start = html.indexOf("{", start);
            if (start == -1) throw new Exception("json start not found");

            int end = findJsonEnd(html, start);
            String jsonStr = html.substring(start, end + 1).trim();

            pdata = JsonParser.parseString(jsonStr).getAsJsonObject();
        } catch (Exception e) {
            return Result.get().parse(1).url(playUrl).string();
        }

        // ==================== 请求中转页 index.php ====================
        Map<String, String> params = new HashMap<>();
        params.put("url", pdata.get("url").getAsString());
        params.put("type", pdata.get("from").getAsString());
        if (pdata.has("link_next")) params.put("next", pdata.get("link_next").getAsString());
        if (pdata.has("play_data")) params.put("data", pdata.get("play_data").getAsString());

        String fullIdxLink = jxHost + "/index.php?" + urlEncodeParams(params);

        HashMap<String, String> h2 = getHeaders();
        h2.put("Referer", host + "/");
        h2.put("Upgrade-Insecure-Requests", "1");
        h2.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");

        String idxHtml = OkHttp.string(jxHost + "/index.php", params, h2);

        // ==================== 提取 config ====================
        Matcher configMatch = Pattern.compile("var config\\s*=\\s*(\\{[\\s\\S]*?\\});", Pattern.DOTALL).matcher(idxHtml);
        if (!configMatch.find()) {
            return Result.get().parse(1).url(playUrl).string();
        }

        String configStr = configMatch.group(1);

        String urlVal = extractField("url", configStr);
        String timeVal = extractField("time", configStr);
        String vkeyVal = extractField("vkey", configStr);

        if (urlVal.isEmpty() || timeVal.isEmpty()) {
            return Result.get().parse(1).url(playUrl).string();
        }

        // ==================== POST 请求 mizhi_json.php ====================
        Map<String, String> apiPayload = new HashMap<>();
        apiPayload.put("url", urlVal);
        apiPayload.put("time", timeVal);
        apiPayload.put("key", "");
        apiPayload.put("vkey", vkeyVal);

        HashMap<String, String> apiHeaders = new HashMap<>();
        apiHeaders.put("User-Agent", getHeaders().get("User-Agent"));
        apiHeaders.put("Accept", "application/json, text/javascript, */*; q=0.01");
        apiHeaders.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        apiHeaders.put("X-Requested-With", "XMLHttpRequest");
        apiHeaders.put("Origin", jxHost);
        apiHeaders.put("Referer", fullIdxLink);

        try {
            OkResult apiRes = OkHttp.post(jxHost + "/admin/mizhi_json.php", apiPayload, apiHeaders);
            String apiResp = apiRes.getBody();

            if (!apiResp.isEmpty()) {
                JsonObject resJson = JsonParser.parseString(apiResp).getAsJsonObject();
                String finalUrl = resJson.has("url") ? resJson.get("url").getAsString() : 
                                 (resJson.has("video_url") ? resJson.get("video_url").getAsString() : "");

                if (!finalUrl.isEmpty()) {
                    return Result.get().url(finalUrl).header(getHeaders()).string();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 兜底
        return Result.get().parse(1).url(playUrl).string();
    }

    // ==================== 辅助方法 ====================

    private int findJsonEnd(String text, int start) {
        int count = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') count++;
            else if (c == '}') {
                count--;
                if (count == 0) return i;
            }
        }
        return text.length() - 1;
    }

    private String extractField(String name, String text) {
        if (text == null || text.isEmpty()) return "";

        // 支持 "key": "value"、'key': 'value'、key: value
        String[] patterns = {
            "\"?" + name + "\"?\\s*[:=]\\s*\"([^\"]*)\"",
            "\"?" + name + "\"?\\s*[:=]\\s*'([^']*)'",
            "\"?" + name + "\"?\\s*[:=]\\s*(\\d+)"
        };

        for (String pat : patterns) {
            Matcher m = Pattern.compile(pat, Pattern.DOTALL).matcher(text);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return "";
    }

    private String urlEncodeParams(Map<String, String> params) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
              .append("=")
              .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return sb.toString();
    }
}
