package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

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
    private static final long                            CACHE_TTL_MS    = 24 * 60 * 60 * 1000L; // 24小时
    private static final ExecutorService                 executor        = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IPlay361-Crawler");
        t.setDaemon(true);
        return t;
    });

    // ==================== Spider interface implementation ====================

    @Override
    public void init(Context context) throws Exception {
        super.init(context);
        // Strict requirement: async crawl starts when jar is loaded, do not block init()
        triggerAsyncCrawl();
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("hotel", "酒店源"));
        classes.add(new Class("proxy", "米菇源"));
        return Result.get().classes(classes).string();
    }

    @Override
    public String homeVideoContent() throws Exception {
        return Result.get().string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {
        // Proxy 直出模式：本方法不使用，返回空 vod 列表
        return Result.get().vod(new ArrayList<>()).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        // ids.get(0) = m3u8_url, return as-is to player
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

    /** Called by Proxy.handleIptv361() to get cached CSV text */
    public static String getCache(String key) {
        return cache.get(key);
    }

    public static boolean isLoading() {
        return loading.get();
    }

    /**
     * Trigger async crawl if not currently loading and cache is expired or empty.
     * Cache TTL: 24 hours.
     */
    public static synchronized void triggerAsyncCrawl() {
        long now = System.currentTimeMillis();
        boolean shouldCrawl = !loading.get() && (
            lastCrawlTime == 0 
            || (now - lastCrawlTime) > CACHE_TTL_MS
            || cache.get(OUTPUT_HOTEL) == null 
            || cache.get(OUTPUT_PROXY) == null
        );
        
        if (!shouldCrawl) {
            SpiderDebug.log("[IPlay361] Cache is fresh, skipping crawl. Last crawl: " + lastCrawlTime);
            return;
        }

        SpiderDebug.log("[IPlay361] Starting async crawl...");
        loading.set(true);
        executor.execute(() -> {
            try {
                // 只爬取第一页的前 MAX_IP_PER_PAGE 个有效IP
                crawlSource(OUTPUT_HOTEL, "iptvhotelx.php");
                crawlSource(OUTPUT_PROXY, "iptvproxy.php");
                lastCrawlTime = System.currentTimeMillis();
                SpiderDebug.log("[IPlay361] Crawl completed, cache updated");
            } catch (Exception e) {
                SpiderDebug.log("[IPlay361] Crawl failed: " + e.getMessage());
            } finally {
                loading.set(false);
            }
        });
    }

    /**
     * Crawl single source, write to corresponding cache.
     * Corresponds to Python's crawl_source()
     * Only crawls the first page, takes up to MAX_IP_PER_PAGE valid IPs.
     */
    private static void crawlSource(String outputKey, String listPhp) throws Exception {
        List<String> allLines = new ArrayList<>();

        // 只请求第一页
        String listUrl = WORKER_URL + "/" + listPhp;
        String referer = WORKER_URL + "/";
        SpiderDebug.log("[IPlay361] Crawling first page: " + listUrl);

        String listHtml = fetchHtml(listUrl, referer);
        List<Map<String, String>> entries = parseIpList(listHtml);

        // 只取前 MAX_IP_PER_PAGE 个有效IP
        int takeCount = Math.min(MAX_IP_PER_PAGE, entries.size());
        SpiderDebug.log("[IPlay361] Found " + entries.size() + " entries, processing first " + takeCount);

        for (int i = 0; i < takeCount; i++) {
            Map<String, String> entry = entries.get(i);
            String ip       = entry.get("ip");
            String tk       = entry.get("tk");
            String pVal     = entry.get("p");
            String regionIs = entry.get("region_isp");

            String detailUrl    = WORKER_URL + "/getall26.php?ip=" + ip + "&c=&tk=" + tk + "&p=" + pVal;
            String channelRef   = WORKER_URL + "/channellist.html?ip=" + ip + "&tk=" + tk + "&p=" + pVal;

            SpiderDebug.log("[IPlay361] Parsing: " + regionIs);

            String detailHtml = fetchHtml(detailUrl, channelRef);
            List<Channel> channels = getChannelsFromDetail(detailHtml, channelRef);

            if (!channels.isEmpty()) {
                allLines.add(regionIs + ",#genre#");
                for (Channel ch : channels) {
                    allLines.add(ch.name + "," + ch.url);
                }
            }

            // 每个IP请求间隔随机1-2秒
            Thread.sleep(ThreadLocalRandom.current().nextLong(1000, 2000));
        }

        if (!allLines.isEmpty()) {
            cache.put(outputKey, String.join("\n", allLines));
            SpiderDebug.log("[IPlay361] Cache written key=" + outputKey + ", total " + allLines.size() + " lines");
        }
    }

    // ==================== fetch_html() ====================
    // Strictly corresponds to Python's fetch_html()

    private static String fetchHtml(String url, String referer) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if (referer != null && !referer.isEmpty()) {
            headers.put("Referer", referer);
        }
        try {
            String resp = OkHttp.string(url, headers);
            if (resp == null) return null;
            return resp;
        } catch (Exception e) {
            SpiderDebug.log("[IPlay361] Request failed: " + url + ", error: " + e.getMessage());
            return null;
        }
    }

    // ==================== parse_ip_list() ====================
    // Strictly corresponds to Python's parse_ip_list()

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

    // ==================== get_channels_from_detail() ====================
    // Strictly corresponds to Python's get_channels_from_detail()

    private static List<Channel> getChannelsFromDetail(String html, String referer) {
        List<Channel> channels = new ArrayList<>();
        
        // Try M3U extraction first (from iframe src)
        String m3uSubscribeUrl = extractM3uSubscribeUrl(html);
        if (m3uSubscribeUrl != null) {
            channels.addAll(parseM3uContent(fetchHtml(m3uSubscribeUrl, referer)));
            if (!channels.isEmpty()) return channels;
        }

        // Fallback to page HTML parsing
        channels.addAll(parseChannelPage(html));
        SpiderDebug.log("[IPlay361] Parsed " + channels.size() + " channels from page");
        return channels;
    }

    // ==================== extract_m3u_subscribe_url() ====================
    // Strictly corresponds to Python's extract_m3u_subscribe_url()

    private static String extractM3uSubscribeUrl(String html) {
        if (html == null) return null;
        Pattern pattern = Pattern.compile("copytodr\\(['\"]([^'\"]+)['\"]\\)");
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    // ==================== parse_m3u_content() ====================
    // Strictly corresponds to Python's parse_m3u_content()

    private static List<Channel> parseM3uContent(String html) {
        List<Channel> channels = new ArrayList<>();
        if (html == null || !html.startsWith("#EXTM3U")) return channels;

        String[] lines = html.split("\n");
        String currentGroup = "";
        
        for (String line : lines) {
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

    // ==================== parse_channel_page() ====================
    // Strictly corresponds to Python's parse_channel_page()

    private static List<Channel> parseChannelPage(String html) {
        List<Channel> channels = new ArrayList<>();
        if (html == null) return channels;

        Document doc = Jsoup.parse(html);
        Element table = doc.selectFirst("table.table");
        if (table == null) return channels;

        for (Element tr : table.select("tr:gt(0)")) {
            Element[] tds = tr.select("td");
            if (tds.length < 5) continue;

            String regionIsp = tds.get(0).text().trim();
            String nameRaw = tds.get(1).text().trim();
            String timeStr = tds.get(2).text().trim();

            // Split time from name using same logic as Python
            String[] nameParts = nameRaw.split("(?<=上线)\\s*");
            String name = nameParts[0].trim();
            if (name.isEmpty()) continue;

            // Match regex pattern
            Pattern pattern = Pattern.compile(
                "(.+?)\\s+((?:中国大陆)?(?:电信|联通|移动|铁通|长城宽带|鹏博士|广电|其他)(?:教育|政企|海外|专线|公司|校园)?\\s*$)");
            Matcher matcher = pattern.matcher(name);
            if (!matcher.matches()) continue;

            String cleanName = matcher.group(1).trim();
            String isp = matcher.group(2).trim();
            
            // Build URL from remaining columns
            StringBuilder urlBuilder = new StringBuilder();
            for (int i = 3; i < tds.length; i++) {
                String cellText = tds.get(i).text().trim();
                if (!cellText.isEmpty()) {
                    if (urlBuilder.length() > 0) urlBuilder.append("|");
                    urlBuilder.append(cellText);
                }
            }
            String url = urlBuilder.toString();
            if (!url.isEmpty()) {
                channels.add(new Channel(cleanName + " " + isp, url));
            }
        }
        return channels;
    }

    // ==================== Inner data structure ====================
    static class Channel {
        final String name;
        final String url;
        Channel(String name, String url) { this.name = name; this.url = url; }
    }
}
