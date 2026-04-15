package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Qkys extends Spider {

    private String host = "https://www.qkw1.com";
    private String jxHost = "https://zyz-omtcqq-com-oss-cn-hangzhou-shanghai-yys-valipl-vip-cp11.xmsu8.top";

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", Util.CHROME);
        headers.put("Referer", host + "/");
        return headers;
    }

    // 修复点：去掉 @Override，因为基类没有这个方法
    public String getName() {
        return "全看影院";
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("guochan", "国产剧"));
        classes.add(new Class("2", "连续剧"));
        classes.add(new Class("1", "电影"));
        classes.add(new Class("3", "综艺"));
        classes.add(new Class("4", "动漫"));
        return Result.string(classes, new ArrayList<Vod>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String url = host + "/qkwshow/" + tid + "--------" + pg + "---.html";
        try {
            String html = OkHttp.string(url, getHeaders());
            List<Vod> list = parseList(html);
            int page = Integer.parseInt(pg);
            return Result.string(page, page + 1, list.size(), 1000, list);
        } catch (Exception e) {
            return Result.string(Integer.parseInt(pg), 0, 0, 0, new ArrayList<Vod>());
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        String url = id.startsWith("http") ? id : host + id;
        try {
            String html = OkHttp.string(url, getHeaders());
            Document doc = Jsoup.parse(html);
            Vod vod = new Vod();
            vod.setVodId(id);
            Element detail = doc.selectFirst(".stui-content__detail");
            vod.setVodName(detail.selectFirst(".title").text().trim());
            Element pic = doc.selectFirst(".stui-content__thumb img");
            if (pic != null) vod.setVodPic(pic.attr("data-original"));
            vod.setVodActor(detail.select("p.data:contains(主演)").text().replace("主演：", "").trim());
            vod.setVodDirector(detail.select("p.data:contains(导演)").text().replace("导演：", "").trim());
            vod.setVodContent(doc.selectFirst(".stui-content__desc").text().trim());

            List<String> fromList = new ArrayList<>();
            List<String> urlList = new ArrayList<>();
            for (Element head : doc.select(".stui-pannel__head")) {
                String title = head.select("h3.title").text();
                if (title.contains("源") || title.contains("播放")) {
                    fromList.add(title);
                    Elements as = head.parent().select("ul.stui-content__playlist a");
                    List<String> links = new ArrayList<>();
                    for (Element a : as) {
                        links.add(a.text() + "$" + a.attr("href"));
                    }
                    urlList.add(String.join("#", links));
                }
            }
            vod.setVodPlayFrom(String.join("$$$", fromList));
            vod.setVodPlayUrl(String.join("$$$", urlList));
            return Result.string(vod);
        } catch (Exception e) {
            return Result.string(new Vod());
        }
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = host + "/qkwsearch/-------------.html?wd=" + URLEncoder.encode(key, "UTF-8") + "&submit=";
        try {
            String html = OkHttp.string(url, getHeaders());
            List<Vod> list = parseList(html);
            return Result.string(list);
        } catch (Exception e) {
            return Result.string(new ArrayList<Vod>());
        }
    }

    private List<Vod> parseList(String html) {
        List<Vod> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        for (Element item : doc.select("li.stui-vodlist__item")) {
            Element thumb = item.selectFirst(".stui-vodlist__thumb");
            if (thumb == null) continue;
            list.add(new Vod(thumb.attr("href"), thumb.attr("title"), thumb.attr("data-original"), item.select(".pic-text").text().trim()));
        }
        return list;
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : host + id;
        String html = OkHttp.string(playUrl, getHeaders());
        if (!html.contains("player_aaaa")) return Result.get().url(playUrl).string();
        try {
            Matcher m = Pattern.compile("var player_aaaa=(\\{.*?\\})").matcher(html);
            if (!m.find()) return Result.get().url(playUrl).string();
            JsonObject pdata = JsonParser.parseString(m.group(1)).getAsJsonObject();
            String urlVal = pdata.get("url").getAsString();
            String fromVal = pdata.get("from").getAsString();
            String idxUrl = jxHost + "/index.php?url=" + urlVal + "&type=" + fromVal;
            String idxHtml = OkHttp.string(idxUrl, getHeaders());
            String url = extractField("url", idxHtml);
            String time = extractField("time", idxHtml);
            String vkey = extractField("vkey", idxHtml);
            if (url == null || time == null || vkey == null) return Result.get().url(playUrl).string();
            Map<String, String> apiPayload = new HashMap<>();
            apiPayload.put("url", url);
            apiPayload.put("time", time);
            apiPayload.put("key", "");
            apiPayload.put("vkey", vkey);
            HashMap<String, String> apiHeaders = getHeaders();
            apiHeaders.put("X-Requested-With", "XMLHttpRequest");
            apiHeaders.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            String apiResp = OkHttp.post(jxHost + "/admin/mizhi_json.php", apiPayload, apiHeaders).getBody();
            JsonObject resJson = JsonParser.parseString(apiResp).getAsJsonObject();
            String finalUrl = resJson.has("url") ? resJson.get("url").getAsString() : (resJson.has("video_url") ? resJson.get("video_url").getAsString() : "");
            if (!finalUrl.isEmpty()) return Result.get().url(finalUrl).header(getHeaders()).string();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Result.get().url(playUrl).string();
    }

    private String extractField(String name, String text) {
        Matcher m = Pattern.compile("\"" + name + "\":\\s*\"(.*?)\"").matcher(text);
        if (m.find()) return m.group(1);
        m = Pattern.compile("\"" + name + "\":\\s*'(.*?)'").matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }
}
