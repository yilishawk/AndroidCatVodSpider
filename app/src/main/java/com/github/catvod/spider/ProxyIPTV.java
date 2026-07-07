package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProxyIPTV extends Spider {

    private static final String HOST = "https://tonkiang.us";
    private static final Map<String, List<String>> cacheData = new HashMap<>();

    /**
     * 供 Proxy.java 静态调用:按需触发爬取并返回缓存数据
     */
    public static synchronized Map<String, List<String>> getCacheData() {
        if (cacheData.isEmpty()) {
            crawlAll();
        }
        return cacheData;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        // 根据 Class.java: 构造函数为 (String typeId, String typeName)
        classes.add(new Class("cctv", "央视"));
        classes.add(new Class("sat", "卫视"));
        classes.add(new Class("other", "其他"));
        
        // 显式使用实例方法链，避免静态方法歧义
        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        if (cacheData.isEmpty()) {
            crawlAll();
        }

        List<String> channels = cacheData.getOrDefault(tid, new ArrayList<>());
        List<Vod> list = new ArrayList<>();

        for (String line : channels) {
            String[] split = line.split("\\$");
            if (split.length < 2) continue;
            // 根据 Vod.java: 构造函数 (String id, String name, String pic, String remarks)
            list.add(new Vod(line, split[0], "https://epg.112114.xyz/logo/" + split[0] + ".png", "直播源"));
        }

        // 显式使用实例方法链
        return Result.get().vod(list).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String data = ids.get(0);
        String[] split = data.split("\\$");

        Vod vod = new Vod();
        vod.setVodId(data);
        vod.setVodName(split[0]);
        vod.setVodPlayFrom("在线直播");
        vod.setVodPlayUrl(split[0] + "$" + split[1]);

        return Result.get().vod(vod).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 直播源 parse(0) 代表直接播放
        return Result.get().url(id).parse(0).string();
    }

    private static synchronized void crawlAll() {
        cacheData.put("cctv", new ArrayList<>());
        cacheData.put("sat", new ArrayList<>());
        cacheData.put("other", new ArrayList<>());

        String[] sources = {"iptvhotelx.php", "iptvproxy.php"};
        for (String php : sources) {
            try {
                // 使用 OkHttp.java 中的静态方法
                String html = OkHttp.string(HOST + "/" + php);
                Document doc = Jsoup.parse(html);
                int count = 0;
                for (Element div : doc.select("div.result")) {
                    if (count >= 3) break;
                    Element a = div.selectFirst("a[href*=channellist.html?ip=]");
                    if (a == null) continue;

                    String href = a.attr("href");
                    String ip = getParam(href, "ip");
                    String tk = getParam(href, "tk");
                    String detailUrl = HOST + "/getall26.php?ip=" + ip + "&tk=" + tk;
                    String detail = OkHttp.string(detailUrl);

                    parseAndSort(detail);
                    count++;
                }
            } catch (Exception ignored) {}
        }
    }

    private static void parseAndSort(String html) {
        if (html == null || html.isEmpty()) return;
        Document doc = Jsoup.parse(html);
        for (Element div : doc.select("div.result")) {
            String name = "";
            Element tip = div.selectFirst("div.tip");
            if (tip != null) name = tip.text().trim();

            Element td = div.selectFirst("div.m3u8 td");
            if (name.isEmpty() || td == null) continue;
            String url = td.text().trim();
            if (!url.startsWith("http")) continue;

            String item = name + "$" + url;
            if (name.contains("CCTV") || name.contains("央视")) {
                cacheData.get("cctv").add(item);
            } else if (name.contains("卫视")) {
                cacheData.get("sat").add(item);
            } else {
                cacheData.get("other").add(item);
            }
        }
    }

    private static String getParam(String url, String name) {
        int start = url.indexOf(name + "=");
        if (start == -1) return "";
        int end = url.indexOf("&", start);
        if (end == -1) end = url.length();
        return url.substring(start + name.length() + 1, end);
    }
}
