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

        // 1. 获取包含 player_aaaa 的网页源码
        HashMap<String, String> h1 = getHeaders();
        h1.put("Referer", host + "/");
        String html = "";
        
        // 对应 Python 的重试机制
        for (int i = 0; i < 2; i++) {
            html = OkHttp.string(playUrl, h1);
            if (html.contains("player_aaaa")) break;
            Thread.sleep(1000);
        }

        if (html.isEmpty()) return Result.get().parse(1).url(playUrl).string();

        // 2. 提取 player_aaaa 变量并解析 JSON
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

        // 3. 构建中转页请求（使用 Map 传参，OkHttp 工具类会自动处理 URL 编码）
        Map<String, String> params = new HashMap<>();
        params.put("url", pdata.get("url").getAsString());
        params.put("type", pdata.get("from").getAsString());
        params.put("next", pdata.has("link_next") ? pdata.get("link_next").getAsString() : "");
        params.put("data", pdata.has("play_data") ? pdata.get("play_data").getAsString() : "");

        HashMap<String, String> h2 = getHeaders();
        h2.put("Upgrade-Insecure-Requests", "1");
        h2.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");

        // 获取中转页源码
        String idxHtml = OkHttp.string(jxHost + "/index.php", params, h2);

        // 4. 提取 config 变量中的核心字段
        Matcher configMatch = Pattern.compile("var config = (\\{[\\s\\S]*?\\});").matcher(idxHtml);
        if (!configMatch.find()) return Result.get().parse(1).url(playUrl).string();
        
        String configStr = configMatch.group(1);
        String urlVal = extractField("url", configStr);
        String timeVal = extractField("time", configStr);
        String vkeyVal = extractField("vkey", configStr);

        if (urlVal.isEmpty() || timeVal.isEmpty()) return Result.get().parse(1).url(playUrl).string();

        // 5. POST 获取真实地址
        Map<String, String> apiPayload = new HashMap<>();
        apiPayload.put("url", urlVal);
        apiPayload.put("time", timeVal);
        apiPayload.put("key", "");
        apiPayload.put("vkey", vkeyVal);

        HashMap<String, String> headersApi = new HashMap<>();
        headersApi.put("X-Requested-With", "XMLHttpRequest");
        headersApi.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        // Referer 必须是带参数的中转页全路径，手动还原 Python 里的 full_idx_link 逻辑
        headersApi.put("Referer", jxHost + "/index.php?url=" + params.get("url")); 
        headersApi.put("Origin", jxHost);

        try {
            // 调用 OkHttp.post 返回 OkResult，获取 Body 并解析
            String apiResp = OkHttp.post(jxHost + "/admin/mizhi_json.php", apiPayload, headersApi).getBody();
            JsonObject resJson = JsonParser.parseString(apiResp).getAsJsonObject();
            
            String finalUrl = resJson.has("url") ? resJson.get("url").getAsString() : "";
            if (finalUrl.isEmpty() && resJson.has("video_url")) finalUrl = resJson.get("video_url").getAsString();

            if (!finalUrl.isEmpty()) {
                return Result.get().url(finalUrl).header(getHeaders()).string();
            }
        } catch (Exception e) {
            // 失败则兜底
        }

        return Result.get().parse(1).url(playUrl).string();
    }

    // 强化版字段提取，完美兼容 Python 的 re 提取逻辑
    private String extractField(String name, String text) {
        // 兼容 "key":"value" , "key":'value' , "key":12345
        Pattern p = Pattern.compile("[\"']" + name + "[\"']\\s*[:=]\\s*[\"']?(.*?)[\"']?[,}]");
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }
}
