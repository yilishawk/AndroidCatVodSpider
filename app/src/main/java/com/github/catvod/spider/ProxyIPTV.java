package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import com.github.catvod.pojo.Class;
import com.github.catvod.pojo.Result;
import com.github.catvod.pojo.Vod;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.util.*;

public class ProxyIPTV extends Spider {

    private static final String HOST = "https://tonkiang.us";
    private final Map<String, List<String>> cacheData = new HashMap<>(); // 分类缓存

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
    }

    /**
     * 1. 首页分类：央视、卫视、其他
     */
    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("cctv", "央视"));
        classes.add(new Class("sat", "卫视"));
        classes.add(new Class("other", "其他"));
        return Result.get().classes(classes).string();
    }

    /**
     * 2. 分类列表页：触发抓取并显示频道
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        // 如果缓存为空，则去抓取
        if (cacheData.isEmpty()) {
            crawlAll();
        }

        List<String> channels = cacheData.getOrDefault(tid, new ArrayList<>());
        List<Vod> list = new ArrayList<>();

        for (String line : channels) {
            String[] split = line.split("\\$");
            if (split.length < 2) continue;
            Vod vod = new Vod();
            vod.setVodId(line); // 把 名字$链接 整体作为ID传递
            vod.setVodName(split[0]);
            vod.setVodPic("https://epg.112114.xyz/logo/" + split[0] + ".png"); // 尝试匹配通用图标
            vod.setVodRemarks("直播源");
            list.add(vod);
        }

        return Result.get().vod(list).string();
    }

    /**
     * 3. 详情页：直接把链接塞进播放列表
     */
    @Override
    public String detailContent(List<String> ids) throws Exception {
        String data = ids.get(0); // 格式为 名字$链接
        String[] split = data.split("\\$");
        
        Vod vod = new Vod();
        vod.setVodId(data);
        vod.setVodName(split[0]);
        vod.setVodPlayFrom("在线直播");
        vod.setVodPlayUrl(split[0] + "$" + split[1]);
        
        return Result.get().vod(vod).string();
    }

    /**
     * 4. 播放解析：直接返回直链播放
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 直播源点播不需要请求头，parse(0) 壳子直放
        return Result.get().url(id).parse(0).string();
    }

    // =================================爬虫逻辑=================================

    private synchronized void crawlAll() {
        cacheData.put("cctv", new ArrayList<>());
        cacheData.put("sat", new ArrayList<>());
        cacheData.get("other").clear(); // 防止重复

        String[] sources = {"iptvhotelx.php", "iptvproxy.php"};
        for (String php : sources) {
            try {
                String html = OkHttp.string(HOST + "/" + php);
                Document doc = Jsoup.parse(html);
                // 抓取前3个IP防止封锁
                int count = 0;
                for (Element div : doc.select("div.result")) {
                    if (count >= 3) break;
                    Element a = div.selectFirst("a[href*=channellist.html?ip=]");
                    if (a == null) continue;
                    
                    // 获取详情
                    String href = a.attr("href");
                    String ip = getParam(href, "ip");
                    String tk = getParam(href, "tk");
                    String detail = OkHttp.string(HOST + "/getall26.php?ip=" + ip + "&tk=" + tk);
                    
                    parseAndSort(detail);
                    count++;
                }
            } catch (Exception ignored) {}
        }
    }

    private void parseAndSort(String html) {
        if (TextUtils.isEmpty(html)) return;
        Document doc = Jsoup.parse(html);
        for (Element div : doc.select("div.result")) {
            String name = div.selectFirst("div.tip") != null ? div.selectFirst("div.tip").text().trim() : "";
            Element td = div.selectFirst("div.m3u8 td");
            if (name.isEmpty() || td == null) continue;
            String url = td.text().trim();
            if (!url.startsWith("http")) continue;

            String item = name + "$" + url;
            // 自动归类逻辑
            if (name.contains("CCTV") || name.contains("央视")) {
                cacheData.get("cctv").add(item);
            } else if (name.contains("卫视")) {
                cacheData.get("sat").add(item);
            } else {
                if (!cacheData.containsKey("other")) cacheData.put("other", new ArrayList<>());
                cacheData.get("other").add(item);
            }
        }
    }

    private String getParam(String url, String name) {
        int start = url.indexOf(name + "=");
        if (start == -1) return "";
        int end = url.indexOf("&", start);
        if (end == -1) end = url.length();
        return url.substring(start + name.length() + 1, end);
    }
}
