package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class 4kvm extends Spider {

    private String host = "https://www.4kvm.me";

    private HashMap<String, String> getHeaders(String referer) {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", Util.CHROME);
        headers.put("Referer", referer);
        return headers;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
    }

    public String getName() {
        return "4K影视";
    }

    private String cleanTitle(String title) {
        if (TextUtils.isEmpty(title)) return "";
        title = title.trim();
        String[] parts = title.split("\\s+");
        if (parts.length >= 2 && parts[0].equals(parts[1])) return parts[0];
        return title;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("2|tvclasses=20", "国产剧"));
        classes.add(new Class("1", "电影"));
        classes.add(new Class("2", "电视剧"));
        classes.add(new Class("4", "综艺"));

        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        if (filter) {
            filters.put("1", getStandardFilters());
            filters.put("2", getTvFilters());
            filters.put("4", getStandardFilters());
        }
        return Result.string(classes, filters);
    }

    private List<Filter> getStandardFilters() {
        List<Filter> list = new ArrayList<>();
        list.add(new Filter("areas", "地区", Arrays.asList(new Filter.Value("全部", ""), new Filter.Value("中国", "7"), new Filter.Value("美国", "5"), new Filter.Value("日本", "11"), new Filter.Value("韩国", "12"), new Filter.Value("香港", "14"), new Filter.Value("台湾", "21"))));
        list.add(new Filter("types", "类型", Arrays.asList(new Filter.Value("全部", ""), new Filter.Value("剧情", "1"), new Filter.Value("悬疑", "2"), new Filter.Value("科幻", "14"), new Filter.Value("动作", "10"), new Filter.Value("动画", "11"))));
        return list;
    }

    private List<Filter> getTvFilters() {
        List<Filter> list = new ArrayList<>();
        list.add(new Filter("tvclasses", "分类", Arrays.asList(new Filter.Value("全部", ""), new Filter.Value("国产剧", "20"), new Filter.Value("美剧", "21"), new Filter.Value("韩剧", "22"), new Filter.Value("日剧", "23"), new Filter.Value("日番", "25"))));
        list.addAll(getStandardFilters());
        return list;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String realTid = tid;
        HashMap<String, String> params = new HashMap<>();
        if (tid.contains("|")) {
            String[] parts = tid.split("\\|");
            realTid = parts[0];
            for (String pair : parts[1].split("&")) {
                String[] kv = pair.split("=");
                if (kv.length == 2) params.put(kv[0], kv[1]);
            }
        }
        if (extend != null) params.putAll(extend);

        StringBuilder url = new StringBuilder(host).append("/filter?classify=").append(realTid).append("&page=").append(pg);
        for (String key : new String[]{"areas", "tvclasses", "types"}) {
            if (params.containsKey(key)) url.append("&").append(key).append("=").append(params.get(key));
        }

        String html = OkHttp.string(url.toString(), getHeaders(host));
        Document doc = Jsoup.parse(html);
        List<Vod> list = new ArrayList<>();
        for (Element card : doc.select(".movie-card, .group")) {
            Element a = card.selectFirst("a[href^=/play/]");
            if (a == null) continue;
            String name = cleanTitle(card.select("h3").text());
            Element img = card.selectFirst("img");
            String pic = img != null ? (img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src")) : "";
            list.add(new Vod(a.attr("href").replace("/play/", ""), name, pic.startsWith("http") ? pic : host + pic, card.select("span.absolute.bottom-0, .remark").text().trim()));
        }
        return Result.string(Integer.parseInt(pg), Integer.parseInt(pg) + 1, list.size(), 1000, list);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = host + "/play/" + ids.get(0);
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders(host)));
        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        vod.setVodName(cleanTitle(doc.selectFirst("h1").text()));
        Element img = doc.selectFirst(".movie-poster img");
        if (img != null) vod.setVodPic(img.attr("src").startsWith("http") ? img.attr("src") : host + img.attr("src"));
        vod.setVodContent(doc.select(".bg-dark-800.rounded-lg.p-3 p").text().trim());
        
        List<String> playUrls = new ArrayList<>();
        for (Element a : doc.select(".episode-link")) {
            playUrls.add(a.text().trim() + "$" + (a.attr("href").startsWith("http") ? a.attr("href") : host + a.attr("href")));
        }
        vod.setVodPlayFrom("4K影视");
        vod.setVodPlayUrl(String.join("#", playUrls));
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = host + "/search?q=" + URLEncoder.encode(key, "UTF-8");
        Document doc = Jsoup.parse(OkHttp.string(url, getHeaders(host)));
        List<Vod> list = new ArrayList<>();
        for (Element item : doc.select(".group")) {
            Element a = item.selectFirst("a[href^=/play/]");
            if (a == null) continue;
            Element img = item.selectFirst("img");
            String pic = img != null ? (img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src")) : "";
            list.add(new Vod(a.attr("href").replace("/play/", ""), cleanTitle(item.select("h3").text()), pic.startsWith("http") ? pic : host + pic, ""));
        }
        return Result.string(list);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String html = OkHttp.string(id.startsWith("http") ? id : host + id, getHeaders(id));
        Matcher m = Pattern.compile("(https?://[^\\s\"']+\\.m3u8[^\\s\"']*)", Pattern.CASE_INSENSITIVE).matcher(html);
        String videoUrl = m.find() ? m.group(1).replace("\\/", "/") : id;
        return Result.get().url(videoUrl).header(getHeaders(id)).string();
    }
}
