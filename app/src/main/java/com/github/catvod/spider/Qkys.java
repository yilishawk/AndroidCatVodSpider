package com.github.catvod.spider;

import android.content.Context;
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

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Qkys extends Spider {

    private String host = "https://www.qkw1.com";
    private String jxHost = "https://zyz-omtcqq-com-oss-cn-hangzhou-shanghai-yys-valipl-vip-cp11.xmsu8.top";

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36");
        headers.put("Referer", host + "/");
        return headers;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("guochan", "國產劇"));
        classes.add(new Class("2", "連續劇"));
        classes.add(new Class("1", "電影"));
        classes.add(new Class("3", "綜藝"));
        classes.add(new Class("4", "動漫"));
        return Result.string(classes, new ArrayList<Vod>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String url = host + "/qkwshow/" + tid + "--------" + pg + "---.html";
        String html = OkHttp.string(url, getHeaders());
        Document doc = Jsoup.parse(html);
        List<Vod> list = new ArrayList<>();
        for (Element item : doc.select(".stui-vodlist__item")) {
            Element thumb = item.selectFirst(".stui-vodlist__thumb");
            if (thumb == null) continue;
            list.add(new Vod(thumb.attr("href"), thumb.attr("title"), thumb.attr("data-original"), item.select(".pic-text").text().trim()));
        }
        return Result.string(Integer.parseInt(pg), Integer.parseInt(pg) + 1, list.size(), 1000, list);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = ids.get(0).startsWith("http") ? ids.get(0) : host + ids.get(0);
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders()));
        Vod vod = new Vod();
        vod.setVodName(doc.selectFirst(".stui-content__detail .title").text());
        vod.setVodPic(doc.selectFirst(".stui-content__thumb img").attr("data-original"));
        
        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();
        for (Element head : doc.select(".stui-pannel__head")) {
            String title = head.select("h3.title").text();
            if (title.contains("源") || title.contains("播放")) {
                fromList.add(title);
                Elements as = head.parent().select("ul.stui-content__playlist a");
                List<String> links = new ArrayList<>();
                for (Element a : as) links.add(a.text() + "$" + a.attr("href"));
                urlList.add(String.join("#", links));
            }
        }
        vod.setVodPlayFrom(String.join("$$$", fromList));
        vod.setVodPlayUrl(String.join("$$$", urlList));
        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : host + id;
        String html = OkHttp.string(playUrl, getHeaders());
        try {
            Matcher m = Pattern.compile("var player_aaaa=(\\{.*?\\})").matcher(html);
            if (!m.find()) return Result.get().parse(1).url(playUrl).string();
            JsonObject pdata = JsonParser.parseString(m.group(1)).getAsJsonObject();
            
            String idxUrl = jxHost + "/index.php?url=" + pdata.get("url").getAsString() + "&type=" + pdata.get("from").getAsString();
            String idxHtml = OkHttp.string(idxUrl, getHeaders());
            
            Map<String, String> apiPayload = new HashMap<>();
            apiPayload.put("url", extractField("url", idxHtml));
            apiPayload.put("time", extractField("time", idxHtml));
            apiPayload.put("vkey", extractField("vkey", idxHtml));
            
            HashMap<String, String> apiHeaders = getHeaders();
            apiHeaders.put("X-Requested-With", "XMLHttpRequest");
            apiHeaders.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

            String apiResp = OkHttp.post(jxHost + "/admin/mizhi_json.php", apiPayload, apiHeaders).getBody();
            JsonObject resJson = JsonParser.parseString(apiResp).getAsJsonObject();
            String finalUrl = resJson.has("url") ? resJson.get("url").getAsString() : resJson.get("video_url").getAsString();
            return Result.get().url(finalUrl).header(getHeaders()).string();
        } catch (Exception e) {
            return Result.get().parse(1).url(playUrl).string();
        }
    }

    private String extractField(String name, String text) {
        Matcher m = Pattern.compile("\"" + name + "\":\\s*\"(.*?)\"").matcher(text);
        return m.find() ? m.group(1) : "";
    }
}
