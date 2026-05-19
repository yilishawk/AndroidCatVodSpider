package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

        // 1. 获取包含 player_aaaa 的网页源码（参考 Python 循环重试机制）
        HashMap<String, String> h1 = getHeaders();
        h1.put("Referer", host + "/");
        String html = "";
        for (int i = 0; i < 2; i++) {
            try {
                html = OkHttp.string(playUrl, h1);
                if (html != null && html.contains("player_aaaa")) {
                    break;
                }
                Thread.sleep(1000);
            } catch (Exception e) {
                // 忽略重试异常
            }
        }

        if (html == null || html.isEmpty()) {
            return Result.get().parse(1).url(playUrl).string();
        }

        // 2. 准确裁切提取 player_aaaa 对应的 JSON 字符串
        JsonObject pdata;
        try {
            int start = html.indexOf("var player_aaaa=");
            start = html.indexOf("{", start);
            int count = 1;
            int pos = start + 1;
            while (pos < html.length() && count > 0) {
                char c = html.charAt(pos);
                if (c == '{') count++;
                else if (c == '}') count--;
                pos++;
            }
            pdata = JsonParser.parseString(html.substring(start, pos)).getAsJsonObject();
        } catch (Exception e) {
            return Result.get().parse(1).url(playUrl).string();
        }

        // 3. 构造请求解析中转页的 URL 和参数
        try {
            String urlParam = pdata.has("url") ? pdata.get("url").getAsString() : "";
            String typeParam = pdata.has("from") ? pdata.get("from").getAsString() : "";
            String nextParam = pdata.has("link_next") ? pdata.get("link_next").getAsString() : "";
            String dataParam = pdata.has("play_data") ? pdata.get("play_data").getAsString() : "";

            // 拼接全路径用于 API 报头的 Referer 字段
            String fullIdxLink = jxHost + "/index.php?url=" + URLEncoder.encode(urlParam, "UTF-8")
                    + "&type=" + URLEncoder.encode(typeParam, "UTF-8")
                    + "&next=" + URLEncoder.encode(nextParam, "UTF-8")
                    + "&data=" + URLEncoder.encode(dataParam, "UTF-8");

            // 4. 发送中转页 GET 请求
            HashMap<String, String> h2 = getHeaders();
            h2.put("Referer", host + "/");
            h2.put("Upgrade-Insecure-Requests", "1");
            h2.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");

            // 使用带 Query 参数的请求方式
            Map<String, String> getParams = new HashMap<>();
            getParams.put("url", urlParam);
            getParams.put("type", typeParam);
            getParams.put("next", nextParam);
            getParams.put("data", dataParam);
            
            // 注意：因为 CatVod 的 OkHttp.string(url, headers) 未原生提供多参数 Map 的 GET，
            // 此处直接请求拼接好的全路径以确保逻辑 100% 对应 Python 的参数传递。
            String idxHtml = OkHttp.string(fullIdxLink, h2);

            // 5. 正则提取 var config = {...};
            Matcher configMatcher = Pattern.compile("var config = (\\{[\\s\\S]*?\\});").matcher(idxHtml);
            if (!configMatcher.find()) {
                return Result.get().parse(1).url(playUrl).string();
            }
            String configStr = configMatcher.group(1);

            // 6. 提取核心参数
            String urlVal = extractField("url", configStr);
            String timeVal = extractField("time", configStr);
            String vkeyVal = extractField("vkey", configStr);

            if (urlVal.isEmpty() || timeVal.isEmpty() || vkeyVal.isEmpty()) {
                return Result.get().parse(1).url(playUrl).string();
            }

            // 7. 组装 POST 载荷
            Map<String, String> apiPayload = new HashMap<>();
            apiPayload.put("url", urlVal);
            apiPayload.put("time", timeVal);
            apiPayload.put("key", "");
            apiPayload.put("vkey", vkeyVal);

            // 8. 组装接口专属 Headers
            HashMap<String, String> headersApi = new HashMap<>();
            headersApi.put("User-Agent", getHeaders().get("User-Agent"));
            headersApi.put("Accept", "application/json, text/javascript, */*; q=0.01");
            headersApi.put("Accept-Language", "zh-CN,zh;q=0.9");
            headersApi.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            headersApi.put("X-Requested-With", "XMLHttpRequest");
            headersApi.put("Origin", jxHost);
            headersApi.put("Referer", fullIdxLink);
            headersApi.put("Connection", "keep-alive");

            // 9. 发送 POST 请求并解析返回的最终流媒体直链
            String apiResp = OkHttp.post(jxHost + "/admin/mizhi_json.php", apiPayload, headersApi).getBody();
            JsonObject resJson = JsonParser.parseString(apiResp).getAsJsonObject();
            
            String finalUrl = resJson.has("url") ? resJson.get("url").getAsString() : "";
            if (finalUrl.isEmpty() && resJson.has("video_url")) {
                finalUrl = resJson.get("video_url").getAsString();
            }

            if (!finalUrl.isEmpty()) {
                return Result.get().url(finalUrl).header(getHeaders()).string();
            }

        } catch (Exception e) {
            // 解析失败时降级回壳探测
        }

        return Result.get().parse(1).url(playUrl).string();
    }

    // 对齐 Python 里的多模式正则提取器（支持双引号、单引号、纯纯数字的匹配）
    private String extractField(String name, String text) {
        if (text == null) return "";
        
        // 模式 1: "name" : "value"
        Matcher m1 = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(text);
        if (m1.find()) return m1.group(1);
        
        // 模式 2: "name" : 'value'
        Matcher m2 = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*'([^']*)'").matcher(text);
        if (m2.find()) return m2.group(1);
        
        // 模式 3: "name" : 123456 (纯数字)
        Matcher m3 = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(\\d+)").matcher(text);
        if (m3.find()) return m3.group(1);
        
        return "";
    }
}
