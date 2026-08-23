package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crawl IPTV sources from https://tonkiang.us
 * Logic strictly matches TVBox_Debug/361.py
 *
 * Pre-crawl strategy: async crawl starts when jar is loaded by TVBox,
 * local proxy http://127.0.0.1:9978/proxy?do=iptv361 returns cached data directly.
 * Cache refreshes once per day (24h TTL).
 */
public class IPlay361 extends Spider {

    // ==================== Config (same as Python) ====================
    static final String WORKER_URL       = "https://tonkiang.us";
    static final int    MAX_IP_PER_PAGE  = 4;

    static final String OUTPUT_HOTEL = "iptvhote.txt";
    static final String OUTPUT_PROXY = "iptvpmigu.txt";

    // ==================== Static state (thread-safe) ====================
    private static final ConcurrentHashMap<String, String> cache         = new ConcurrentHashMap<>();
    private static final AtomicBoolean                   loading         = new AtomicBoolean(false);
    private static volatile long                         lastCrawlTime   = 0;
    private static final long                            CACHE_TTL_MS    = 24 * 60 * 60 * 1000L;
    private static final ExecutorService                 executor        = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IPlay361-Crawler");
        t.setDaemon(true);
        return t;
    });

    // ==================== Spider interface implementation ====================

    @Override
    public void init(Context context) throws Exception {
        super.init(context);
        triggerAsyncCrawl();
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("hotel", "Hotel"));
        classes.add(new Class("proxy", "Proxy"));
        return Result.get().classes(classes).string();
    }

    @Override
    public String homeVideoContent() throws Exception {
        return Result.get().string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {
        return Result.get().vod(new ArrayList<>()).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = ids.get(0);
        Vod vod = new Vod();
        vod.setVodId(url);
        vod.setVodName(url);
        vod.setVodPlayFrom("Live");
        vod.setVodPlayUrl("Play$" + url);
        return Result.get().vod(vod).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return Result.get().url(id).string();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return Result.get().vod(new ArrayList<>()).string();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        return Result.get().vod(new ArrayList<>()).string();
    }

    // ==================== Static methods for Proxy.java ====================

    public static String getCache(String key) {
        return cache.get(key);
    }

    public static boolean isLoading() {
        return loading.get();
    }

    public static synchronized void triggerAsyncCrawl() {
        long now = System.currentTimeMillis();
        boolean shouldCrawl = !loading.get() && (
            lastCrawlTime == 0 
            || (now - lastCrawlTime) > CACHE_TTL_MS
            || cache.get(OUTPUT_HOTEL) == null 
            || cache.get(OUTPUT_PROXY) == null
        );
        
        if (!shouldCrawl) {
            SpiderDebug.log("[IPlay361] Cache fresh, skip. Last: " + lastCrawlTime);
            return;
        }

        SpiderDebug.log("[IPlay361] Start async crawl...");
        loading.set(true);
        executor.execute(() -> {
            try {
                crawlSource(OUTPUT_HOTEL, "iptvhotelx.php");
                crawlSource(OUTPUT_PROXY, "iptvproxy.php");
                lastCrawlTime = System.currentTimeMillis();
                SpiderDebug.log("[IPlay361] Done");
            } catch (Exception e) {
                SpiderDebug.log("[IPlay361] Failed: " + e.getMessage());
            } finally {
                loading.set(false);
            }
        });
    }

    private static void crawlSource(String outputKey, String listPhp) throws Exception {
        List<String> allLines = new ArrayList<>();

        String listUrl = WORKER_URL + "/" + listPhp;
        String referer = WORKER_URL + "/";
        SpiderDebug.log("[IPlay361] Page: " + listUrl);

        String listHtml = fetchHtml(listUrl, referer);
        List<Map<String, String>> entries = parseIpList(listHtml);

        int takeCount = Math.min(MAX_IP_PER_PAGE, entries.size());
        SpiderDebug.log("[IPlay361] Found " + entries.size() + ", process " + takeCount);

        for (int i = 0; i < takeCount; i++) {
            Map<String, String> entry = entries.get(i);
            String ip       = entry.get("ip");
            String tk       = entry.get("tk");
            String pVal     = entry.get("p");
            String regionIs = entry.get("region_isp");

            String detailUrl    = WORKER_URL + "/getall26.php?ip=" + ip + "&c=&tk=" + tk + "&p=" + pVal;
            String channelRef   = WORKER_URL + "/channellist.html?ip=" + ip + "&tk=" + tk + "&p=" + pVal;

            SpiderDebug.log("[IPlay361] Parse: " + regionIs);

            String detailHtml = fetchHtml(detailUrl, channelRef);
            List<Channel> channels = getChannelsFromDetail(detailHtml, channelRef);

            if (!channels.isEmpty()) {
                allLines.add(regionIs + ",#genre#");
                for (Channel ch : channels) {
                    allLines.add(ch.name + "," + ch.url);
                }
            }

            Thread.sleep(ThreadLocalRandom.current().nextLong(1000, 2000));
        }

        if (!allLines.isEmpty()) {
            cache.put(outputKey, String.join("\n", allLines));
            SpiderDebug.log("[IPlay361] Cache: " + outputKey + ", " + allLines.size() + " lines");
        }
    }

    private static String fetchHtml(String url, String referer) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if (referer != null && !referer.isEmpty()) {
            headers.put("Referer", referer);
        }
        try {
            String resp = OkHttp.string(url, headers);
            return resp;
        } catch (Exception e) {
            SpiderDebug.log("[IPlay361] Fetch failed: " + url + " - " + e.getMessage());
            return null;
        }
    }

    private static List<Map<String, String>> parseIpList(String html) {
        List<Map<String, String>> result = new ArrayList<>();
        if (html == null || html.isEmpty()) return result;

        Document doc = Jsoup.parse(html);
        Element div = doc.selectFirst("div.container div.row");
        if (div == null) return result;

        Pattern hrefPattern = Pattern.compile(".*channellist.html\\?(ip=[^&]*&tk=([^&]*)&p=(\\d+))[^\\r\\n]*?>([^<]+)</a>.*");
        
        for (Element a : div.select("a[href*=channellist]")) {
            String href = a.attr("href");
            Matcher matcher = hrefPattern.matcher(href);
            if (!matcher.matches()) continue;

            Map<String, String> entry = new HashMap<>();
            entry.put("ip", matcher.group(1));
            entry.put("tk", matcher.group(2));
            entry.put("p", matcher.group(3));
            entry.put("region_isp", matcher.group(4).trim());
            result.add(entry);
        }
        return result;
    }

    private static List<Channel> getChannelsFromDetail(String html, String referer) {
        List<Channel> channels = new ArrayList<>();
        
        String m3uUrl = extractM3uSubscribeUrl(html);
        if (m3uUrl != null) {
            channels.addAll(parseM3uContent(fetchHtml(m3uUrl, referer)));
            if (!channels.isEmpty()) return channels;
        }

        channels.addAll(parseChannelPage(html));
        return channels;
    }

    private static String extractM3uSubscribeUrl(String html) {
        if (html == null) return null;
        Pattern pattern = Pattern.compile("copytodr\\(['\"]([^'\"]+)['\"]\\)");
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static List<Channel> parseM3uContent(String html) {
        List<Channel> channels = new ArrayList<>();
        if (html == null || !html.startsWith("#EXTM3U")) return channels;

        for (String line : html.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            int commaIdx = line.lastIndexOf(',');
            if (commaIdx <= 0) continue;

            String name = line.substring(0, commaIdx).trim();
            String url = line.substring(commaIdx + 1).trim();
            
            if (!name.isEmpty() && !url.isEmpty()) {
                channels.add(new Channel(name, url));
            }
        }
        return channels;
    }

    private static List<Channel> parseChannelPage(String html) {
        List<Channel> channels = new ArrayList<>();
        if (html == null) return channels;

        Document doc = Jsoup.parse(html);
        Element table = doc.selectFirst("table.table");
        if (table == null) return channels;

        for (Element tr : table.select("tr:gt(0)")) {
            Elements tds = tr.select("td");
            if (tds.size() < 5) continue;

            String nameRaw = tds.get(1).text().trim();
            String[] nameParts = nameRaw.split("(?<=上线)\\s*");
            String name = nameParts[0].trim();
            if (name.isEmpty()) continue;

            Pattern pattern = Pattern.compile(
                "(.+?)\\s+((?:中国大陆)?(?:电信|联通|移动|铁通|长城宽带|鹏博士|广电|其他)(?:教育|政企|海外|专线|公司|校园)?\\s*$)");
            Matcher matcher = pattern.matcher(name);
            if (!matcher.matches()) continue;

            StringBuilder urlBuilder = new StringBuilder();
            for (int i = 3; i < tds.size(); i++) {
                String cellText = tds.get(i).text().trim();
                if (!cellText.isEmpty()) {
                    if (urlBuilder.length() > 0) urlBuilder.append("|");
                    urlBuilder.append(cellText);
                }
            }
            
            String url = urlBuilder.toString();
            if (!url.isEmpty()) {
                channels.add(new Channel(name, url));
            }
        }
        return channels;
    }

    static class Channel {
        final String name;
        final String url;
        Channel(String name, String url) { this.name = name; this.url = url; }
    }
}
