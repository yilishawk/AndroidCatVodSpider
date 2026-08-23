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
    // ==================== Static initializer ====================
    // Trigger crawl when class is loaded (ensure it starts even if init() is not called)
    static {
        try {
            SpiderDebug.log("[IPlay361] Class loaded, triggering async crawl...");
            triggerAsyncCrawl();
        } catch (Exception e) {
            SpiderDebug.log("[IPlay361] Static init error: " + e.getMessage());
        }
    }


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

    // ==================== crawl_source() ====================

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

    // ==================== fetch_html() ====================

    private static String fetchHtml(String url, String referer) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if (referer != null && !referer.isEmpty()) {
            headers.put("Referer", referer.replace(WORKER_URL, "https://tonkiang.us"));
        }
        try {
            String resp = OkHttp.string(url, headers);
            return resp;
        } catch (Exception e) {
            SpiderDebug.log("[IPlay361] Fetch failed: " + url + " - " + e.getMessage());
            return null;
        }
    }

    // ==================== parse_ip_list() ====================

    private static List<Map<String, String>> parseIpList(String html) {
        List<Map<String, String>> result = new ArrayList<>();
        if (html == null || html.isEmpty()) return result;

        Document doc = Jsoup.parse(html);
        Elements resultDivs = doc.select("div.result");
        
        for (Element div : resultDivs) {
            if (div.text().contains("temporary invalid")) continue;
            
            Element anchor = div.selectFirst("a[href*=channellist]");
            if (anchor == null) continue;
            
            String href = anchor.attr("href");
            String ip = getQueryParam(href, "ip");
            String tk = getQueryParam(href, "tk");
            String p = getQueryParam(href, "p");
            if (p == null) p = "1";
            
            if (ip == null || ip.isEmpty() || tk == null || tk.isEmpty()) continue;
            
            String location = "unknown";
            String isp = "unknown";
            Element iTag = div.selectFirst("i");
            if (iTag != null) {
                String infoText = iTag.text().trim();
                String[] parts = infoText.split("(?<=online)\\s*");
                String geoIsp = parts.length > 1 ? parts[parts.length - 1].trim() : infoText;
                
                Matcher matcher = Pattern.compile(
                    "(.+?)\\s+((?:China)?(?:Telecom|Unicom|Mobile|IronTone|GreatWall|Dragon)|\\s*$)"
                ).matcher(geoIsp);
                if (matcher.matches()) {
                    location = matcher.group(1).trim();
                    isp = matcher.group(2).trim();
                } else {
                    location = geoIsp;
                }
            }
            
            Map<String, String> entry = new HashMap<>();
            entry.put("ip", ip);
            entry.put("tk", tk);
            entry.put("p", p);
            entry.put("region_isp", location + " " + isp);
            result.add(entry);
        }
        return result;
    }

    private static String getQueryParam(String url, String key) {
        if (url == null || !url.contains("?")) return null;
        String query = url.substring(url.indexOf('?') + 1);
        for (String param : query.split("&")) {
            String[] parts = param.split("=");
            if (parts.length >= 2 && key.equals(parts[0])) {
                return parts[1];
            }
        }
        return null;
    }

    // ==================== get_channels_from_detail() ====================

    private static List<Channel> getChannelsFromDetail(String html, String referer) {
        List<Channel> channels = new ArrayList<>();
        
        String m3uUrl = extractM3uSubscribeUrl(html);
        if (m3uUrl != null) {
            SpiderDebug.log("[IPlay361] Found M3U URL");
            String m3uContent = fetchHtml(m3uUrl, referer);
            if (m3uContent != null && m3uContent.trim().startsWith("#EXTM3U")) {
                channels.addAll(parseM3uContent(m3uContent));
                SpiderDebug.log("[IPlay361] Parsed " + channels.size() + " from M3U");
                if (!channels.isEmpty()) return channels;
            }
        }

        channels.addAll(parseChannelPage(html));
        SpiderDebug.log("[IPlay361] Parsed " + channels.size() + " from HTML");
        return channels;
    }

    // ==================== extract_m3u_subscribe_url() ====================

    private static String extractM3uSubscribeUrl(String html) {
        if (html == null) return null;
        
        // Match: copytodr('https://xxx/iptvlist.php?token=XXX','m')
        Pattern pattern1 = Pattern.compile(
            "copytodr\\(['\"](https?://[^'\"\\s]+iptvlist\\.php\\?token=[^'\"\\s]+)['\"],\\s*['\"]m['\"]",
            Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern1.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        // Match: direct access m3u link url (English fallback)
        Pattern pattern2 = Pattern.compile(
            "[Dd]irect\\s+[Aa]ccess\\s+[Tt]he\\s+m3u\\s+[Ll]ink\\s*(https?://[^\\s<>\"']+)",
            Pattern.CASE_INSENSITIVE
        );
        matcher = pattern2.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        return null;
    }

    // ==================== parse_m3u_content() ====================

    private static List<Channel> parseM3uContent(String html) {
        List<Channel> channels = new ArrayList<>();
        if (html == null) return channels;

        String[] lines = html.trim().split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXTINF:")) continue;
            
            String namePart = line.substring(8);
            int commaIdx = namePart.lastIndexOf(',');
            String channelName = commaIdx > 0 ? namePart.substring(commaIdx + 1).trim() : "unknown";
            
            if (i + 1 < lines.length) {
                String url = lines[i + 1].trim();
                if (url.startsWith("http")) {
                    channels.add(new Channel(channelName, url));
                    i++;
                }
            }
        }
        return channels;
    }

    // ==================== parse_channel_page() ====================

    private static List<Channel> parseChannelPage(String html) {
        List<Channel> channels = new ArrayList<>();
        if (html == null) return channels;

        Document doc = Jsoup.parse(html);
        Elements resultDivs = doc.select("div.result");
        
        for (Element div : resultDivs) {
            Element channelDiv = div.selectFirst("div.channel");
            if (channelDiv == null) continue;
            
            Element tipDiv = channelDiv.selectFirst("div.tip");
            String channelName = tipDiv != null ? tipDiv.text().trim() : "unknown";
            if (channelName.isEmpty()) continue;
            
            Element m3u8Div = div.selectFirst("div.m3u8");
            if (m3u8Div == null) continue;
            
            for (Element td : m3u8Div.select("td")) {
                String text = td.text().trim();
                if (text.startsWith("http")) {
                    channels.add(new Channel(channelName, text));
                    break;
                }
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
