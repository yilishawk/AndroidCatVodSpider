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
 */
public class IPlay361 extends Spider {

    // ==================== Config (same as Python) ====================
    static final String WORKER_URL       = "https://tonkiang.us";
    static final int    MAX_IP_PER_PAGE  = 4;

    static final String OUTPUT_HOTEL = "iptvhote.txt";
    static final String OUTPUT_PROXY = "iptvpmigu.txt";

    // ==================== Static state (thread-safe) ====================
    private static final ConcurrentHashMap<String, String> cache   = new ConcurrentHashMap<>();
    private static final AtomicBoolean                   loading   = new AtomicBoolean(false);
    private static final ExecutorService                 executor  = Executors.newSingleThreadExecutor(r -> {
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
        classes.add(new Class("hotel", "Hotel Source"));
        classes.add(new Class("proxy", "Migu Source"));
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

    // ==================== Internal crawl logic (strictly matches Python) ====================

    /** Trigger one-time async crawl (idempotent: ignore if already crawling) */
    public static void triggerAsyncCrawl() {
        if (loading.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    crawlSource(OUTPUT_HOTEL, "iptvhotelx.php");
                    crawlSource(OUTPUT_PROXY, "iptvproxy.php");
                } catch (Exception e) {
                    SpiderDebug.log("[IPlay361] Crawl exception: " + e.getMessage());
                } finally {
                    loading.set(false);
                }
            });
        }
    }

    /**
     * Crawl single source, write to corresponding cache.
     * Corresponds to Python's crawl_source()
     */
    private static void crawlSource(String outputKey, String listPhp) throws Exception {
        List<String> allLines = new ArrayList<>();

        for (int page = 1; ; page++) {
            String listUrl = page == 1
                    ? WORKER_URL + "/" + listPhp
                    : WORKER_URL + "/" + listPhp + "?page=" + page + "&iphone16=&code=";
            String referer = page == 1 ? WORKER_URL + "/" : WORKER_URL + "/" + listPhp;

            SpiderDebug.log("[IPlay361] Crawling page " + page + ": " + listUrl);

            String listHtml = fetchHtml(listUrl, referer);
            List<Map<String, String>> entries = parseIpList(listHtml);

            // Keep only first MAX_IP_PER_PAGE valid entries (same as Python)
            int takeCount = Math.min(MAX_IP_PER_PAGE, entries.size());
            SpiderDebug.log("[IPlay361] Extracted " + entries.size() + " entries, processing first " + takeCount);

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

                // time.sleep(random.uniform(1, 2))
                Thread.sleep(ThreadLocalRandom.current().nextLong(1000, 2000));
            }

            // Stop pagination if no more valid entries
            if (entries.isEmpty() || takeCount < MAX_IP_PER_PAGE) {
                break;
            }
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
            // Replace WORKER_URL with https://tonkiang.us (same as Python)
            String fixedReferer = referer.replace(WORKER_URL, "https://tonkiang.us");
            headers.put("Referer", fixedReferer);
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
        List<Map<String, String>> entries = new ArrayList<>();
        if (html == null) return entries;

        Document doc = Jsoup.parse(html);
        // soup.find_all('div', class_='result')
        for (Element div : doc.select("div.result")) {
            // if '暂时失效' in div.get_text(): continue
            if (div.text().contains("暂时失效")) continue;

            // div.find('a', href=re.compile(r'channellist\.html\?ip='))
            Element channelLink = div.selectFirst("a[href*=channellist.html][href*=ip]");
            if (channelLink == null) continue;

            String href = channelLink.attr("href");
            // parse_qs(urlparse(href).query)
            String query = href.contains("?") ? href.substring(href.indexOf('?') + 1) : "";
            Map<String, String> params = parseQuery(query);

            String ip  = params.getOrDefault("ip", "");
            String tk  = params.getOrDefault("tk", "");
            String pVal = params.getOrDefault("p", "1");
            if (TextUtils.isEmpty(ip) || TextUtils.isEmpty(tk)) continue;

            // info_tag = div.find('i')
            Element infoTag = div.selectFirst("i");
            String location = "Unknown Region";
            String isp      = "Unknown ISP";
            if (infoTag != null) {
                String infoText = infoTag.text().trim();
                // re.split(r'\d{2}:\d{2}上线\s*', info_text)
                String[] parts = infoText.split("(?<=上线)\\s*");
                String geoIspp = parts.length > 1 ? parts[parts.length - 1].strip() : infoText;

                // re.match(r'(.+?)\s+((?:[\u4e00-\u9fa5]+)?(?:电信|联通|移动|广电|铁通|长宽|教育网))\s*$', ...)
                Pattern geoPattern = Pattern.compile(
                        "^(.+?)\\s+((?:[\\u4e00-\\u9fa5]+)?(?:电信|联通|移动|广电|铁通|长宽|教育网))\\s*$");
                Matcher m = geoPattern.matcher(geoIspp);
                if (m.matches()) {
                    location = m.group(1).strip();
                    isp      = m.group(2).strip();
                } else {
                    location = geoIspp;
                }
            }

            Map<String, String> entry = new HashMap<>();
            entry.put("ip", ip);
            entry.put("tk", tk);
            entry.put("p",  pVal);
            entry.put("region_isp", location + " " + isp);
            entries.add(entry);
        }
        return entries;
    }

    /** Parse query string, corresponds to Python urllib.parse.parse_qs */
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (TextUtils.isEmpty(query)) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0], kv[1]);
            } else if (kv.length == 1) {
                map.put(kv[0], "");
            }
        }
        return map;
    }

    // ==================== extract_m3u_subscribe_url() ====================
    // Strictly corresponds to Python's extract_m3u_subscribe_url()

    private static String extractM3uSubscribeUrl(String html) {
        if (html == null) return null;
        // re.search(r"copytodr\(['\"](https?://[^'\"]+iptvlist\.php\?token=[^'\"]+)['\"],\s*['\"]m['\"]\)", ...)
        Pattern p1 = Pattern.compile(
                "copytodr\\(['\"](https?://[^'\"]+iptvlist\\.php\\?token=[^'\"]+)['\"],\\s*['\"]m['\"]\\)");
        Matcher m1 = p1.matcher(html);
        if (m1.find()) return m1.group(1);

        // Fallback: re.search(r'订阅m3u链接\s*(https?://[^\s<>"\']+)', ...)
        Pattern p2 = Pattern.compile("订阅m3u链接\\s*(https?://[^\\s<>\"']+)", Pattern.CASE_INSENSITIVE);
        Matcher m2 = p2.matcher(html);
        if (m2.find()) return m2.group(1);

        return null;
    }

    // ==================== parse_m3u_content() ====================
    // Strictly corresponds to Python's parse_m3u_content()

    private static List<Channel> parseM3uContent(String m3uText) {
        List<Channel> channels = new ArrayList<>();
        if (m3uText == null) return channels;

        String[] lines = m3uText.strip().split("\\n");
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            if (line.startsWith("#EXTINF:")) {
                // Channel name after last comma
                int lastComma = line.lastIndexOf(',');
                String name = lastComma >= 0 ? line.substring(lastComma + 1).strip() : "Unknown Channel";

                if (i + 1 < lines.length) {
                    String url = lines[i + 1].trim();
                    if (url.startsWith("http")) {
                        channels.add(new Channel(name, url));
                        i += 2;
                        continue;
                    }
                }
            }
            i++;
        }
        return channels;
    }

    // ==================== parse_channel_page() (fallback logic) ====================
    // Strictly corresponds to Python's parse_channel_page()

    private static List<Channel> parseChannelPage(String html) {
        List<Channel> channels = new ArrayList<>();
        if (html == null) return channels;

        Document doc = Jsoup.parse(html);
        for (Element div : doc.select("div.result")) {
            // channel_div = div.find('div', class_='channel')
            Element channelDiv = div.selectFirst("div.channel");
            if (channelDiv == null) continue;

            // tip_div = channel_div.find('div', class_='tip')
            Element tipDiv = channelDiv.selectFirst("div.tip");
            String channelName = tipDiv != null ? tipDiv.text().strip() : "Unknown Channel";

            // m3u8_div = div.find('div', class_='m3u8')
            Element m3u8Div = div.selectFirst("div.m3u8");
            if (m3u8Div == null) continue;

            // for td in m3u8_div.find_all('td'):
            for (Element td : m3u8Div.select("td")) {
                String text = td.text().strip();
                if (text.startsWith("http")) {
                    channels.add(new Channel(channelName, text));
                    break;
                }
            }
        }
        return channels;
    }

    // ==================== get_channels_from_detail() ====================
    // Strictly corresponds to Python's get_channels_from_detail()

    private static List<Channel> getChannelsFromDetail(String detailHtml, String referer) {
        String m3uUrl = extractM3uSubscribeUrl(detailHtml);
        if (m3uUrl != null) {
            SpiderDebug.log("[IPlay361] Found subscribe m3u URL, fetching full list...");
            String m3uContent = fetchHtml(m3uUrl, referer);
            if (m3uContent != null && m3uContent.trim().startsWith("#EXTM3U")) {
                List<Channel> channels = parseM3uContent(m3uContent);
                SpiderDebug.log("[IPlay361] Parsed " + channels.size() + " channels from subscribe URL");
                return channels;
            } else {
                SpiderDebug.log("[IPlay361] Subscribe URL invalid or not M3U format, fallback to page parsing");
            }
        }
        // Fallback to page HTML parsing
        List<Channel> channels = parseChannelPage(detailHtml);
        SpiderDebug.log("[IPlay361] Parsed " + channels.size() + " channels from page");
        return channels;
    }

    // ==================== Inner data structure ====================
    private static class Channel {
        final String name;
        final String url;
        Channel(String name, String url) { this.name = name; this.url = url; }
    }
}
