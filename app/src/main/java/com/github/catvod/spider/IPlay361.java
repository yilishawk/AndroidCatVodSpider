package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crawl IPTV sources from https://tonkiang.us
 * JAR 被加载后异步抓取，结果写入 /sdcard/TV/ 本地文件。
 * 用免费 API 取北京时间，缓存不是当天则重新爬取。
 */
public class IPlay361 extends Spider {

    static final String WORKER_URL = "https://tonkiang.us";
    static final int MAX_IP_PER_PAGE = 4;

    public static final String OUTPUT_HOTEL = "iptvhote.txt";
    public static final String OUTPUT_PROXY = "iptvpmigu.txt";

    private static final String FILE_HOTEL = "iplay361_hotel.txt";
    private static final String FILE_PROXY = "iplay361_proxy.txt";
    private static final String FILE_DATE = "iplay361_date.txt";

    private static final String[] BEIJING_TIME_APIS = new String[]{
            "https://www.timeapi.io/api/Time/current/zone?timeZone=Asia/Shanghai",
            "https://timeapi.world/api/timezone/Asia/Shanghai",
            "https://worldtimeapi.org/api/timezone/Asia/Shanghai"
    };

    private static final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private static final AtomicBoolean loading = new AtomicBoolean(false);
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IPlay361-Crawler");
        t.setDaemon(true);
        return t;
    });

    static {
        try {
            SpiderDebug.log("[IPlay361] Class loaded, load disk + async crawl...");
            loadFromDisk();
            triggerAsyncCrawl();
        } catch (Exception e) {
            SpiderDebug.log("[IPlay361] Static init error: " + e.getMessage());
        }
    }

    @Override
    public void init(Context context) throws Exception {
        super.init(context);
        loadFromDisk();
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
        return Result.get().vod(new ArrayList<Vod>()).string();
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
        return Result.get().vod(new ArrayList<Vod>()).string();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        return Result.get().vod(new ArrayList<Vod>()).string();
    }

    public static String getCache(String key) {
        String mem = cache.get(key);
        if (mem != null && !mem.isEmpty()) return mem;
        loadFromDisk();
        return cache.get(key);
    }

    public static boolean isLoading() {
        return loading.get();
    }

    public static synchronized void triggerAsyncCrawl() {
        loadFromDisk();

        String today = fetchBeijingDate();
        String saved = readLocalDate();
        boolean hotelOk = notEmpty(cache.get(OUTPUT_HOTEL));
        boolean proxyOk = notEmpty(cache.get(OUTPUT_PROXY));
        boolean sameDay = today != null && today.equals(saved);

        SpiderDebug.log("[IPlay361] Beijing=" + today + " saved=" + saved
                + " hotel=" + hotelOk + " proxy=" + proxyOk + " loading=" + loading.get());

        if (sameDay && hotelOk && proxyOk) {
            SpiderDebug.log("[IPlay361] Cache is from today, skip crawl");
            return;
        }
        if (loading.get()) {
            SpiderDebug.log("[IPlay361] Already crawling, skip");
            return;
        }

        SpiderDebug.log("[IPlay361] Start async crawl (need refresh)...");
        loading.set(true);
        final String crawlDate = today;
        executor.execute(() -> {
            try {
                crawlSource(OUTPUT_HOTEL, "iptvhotelx.php");
                crawlSource(OUTPUT_PROXY, "iptvproxy.php");
                saveToDisk();
                writeLocalDate(crawlDate != null ? crawlDate : fetchBeijingDate());
                SpiderDebug.log("[IPlay361] Done, saved local date=" + readLocalDate());
            } catch (Exception e) {
                SpiderDebug.log("[IPlay361] Failed: " + e.getMessage());
            } finally {
                loading.set(false);
            }
        });
    }

    public static String fetchBeijingDate() {
        for (String api : BEIJING_TIME_APIS) {
            try {
                SpiderDebug.log("[IPlay361] Time API: " + api);
                String body = OkHttp.string(api);
                if (body == null || body.length() < 8) continue;
                String date = parseDateFromTimeJson(body);
                if (date != null) {
                    SpiderDebug.log("[IPlay361] Beijing date=" + date);
                    return date;
                }
            } catch (Exception e) {
                SpiderDebug.log("[IPlay361] Time API fail: " + e.getMessage());
            }
        }
        String fallback = deviceBeijingDate();
        SpiderDebug.log("[IPlay361] Time API all failed, device GMT+8=" + fallback);
        return fallback;
    }

    private static String parseDateFromTimeJson(String body) {
        try {
            JSONObject obj = new JSONObject(body);
            if (obj.has("year") && obj.has("month") && obj.has("day")) {
                return String.format("%04d-%02d-%02d",
                        obj.getInt("year"), obj.getInt("month"), obj.getInt("day"));
            }
            String dt = obj.optString("datetime", obj.optString("dateTime", ""));
            if (dt.length() >= 10 && dt.charAt(4) == '-') {
                return dt.substring(0, 10);
            }
        } catch (Exception ignored) {
        }
        Matcher m = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})").matcher(body);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String deviceBeijingDate() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+08:00"));
        return String.format("%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH));
    }

    private static File hotelFile() {
        return Path.tv(FILE_HOTEL);
    }

    private static File proxyFile() {
        return Path.tv(FILE_PROXY);
    }

    private static File dateFile() {
        return Path.tv(FILE_DATE);
    }

    private static synchronized void loadFromDisk() {
        try {
            String hotel = Path.read(hotelFile());
            String proxy = Path.read(proxyFile());
            if (notEmpty(hotel)) cache.put(OUTPUT_HOTEL, hotel);
            if (notEmpty(proxy)) cache.put(OUTPUT_PROXY, proxy);
            SpiderDebug.log("[IPlay361] Disk load hotel=" + (hotel == null ? 0 : hotel.length())
                    + " proxy=" + (proxy == null ? 0 : proxy.length())
                    + " date=" + readLocalDate());
        } catch (Exception e) {
            SpiderDebug.log("[IPlay361] Disk load fail: " + e.getMessage());
        }
    }

    private static synchronized void saveToDisk() {
        try {
            String hotel = cache.get(OUTPUT_HOTEL);
            String proxy = cache.get(OUTPUT_PROXY);
            if (notEmpty(hotel)) {
                Path.write(hotelFile(), hotel);
                SpiderDebug.log("[IPlay361] Wrote " + hotelFile().getAbsolutePath()
                        + " bytes=" + hotel.length());
            }
            if (notEmpty(proxy)) {
                Path.write(proxyFile(), proxy);
                SpiderDebug.log("[IPlay361] Wrote " + proxyFile().getAbsolutePath()
                        + " bytes=" + proxy.length());
            }
        } catch (Exception e) {
            SpiderDebug.log("[IPlay361] Disk save fail: " + e.getMessage());
        }
    }

    private static String readLocalDate() {
        try {
            String s = Path.read(dateFile());
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeLocalDate(String date) {
        if (date == null || date.isEmpty()) return;
        try {
            Path.write(dateFile(), date);
            SpiderDebug.log("[IPlay361] Wrote date file=" + date);
        } catch (Exception e) {
            SpiderDebug.log("[IPlay361] Write date fail: " + e.getMessage());
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
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
            String ip = entry.get("ip");
            String tk = entry.get("tk");
            String pVal = entry.get("p");
            String regionIs = entry.get("region_isp");

            String detailUrl = WORKER_URL + "/getall26.php?ip=" + ip + "&c=&tk=" + tk + "&p=" + pVal;
            String channelRef = WORKER_URL + "/channellist.html?ip=" + ip + "&tk=" + tk + "&p=" + pVal;

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
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if (referer != null && !referer.isEmpty()) {
            headers.put("Referer", referer.replace(WORKER_URL, "https://tonkiang.us"));
        }
        try {
            return OkHttp.string(url, headers);
        } catch (Exception e) {
            SpiderDebug.log("[IPlay361] Fetch failed: " + url + " - " + e.getMessage());
            return null;
        }
    }

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

    private static String extractM3uSubscribeUrl(String html) {
        if (html == null) return null;

        Pattern pattern1 = Pattern.compile(
                "copytodr\\(['\"](https?://[^'\"\\s]+iptvlist\\.php\\?token=[^'\"\\s]+)['\"],\\s*['\"]m['\"]",
                Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern1.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }

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

    static class Channel {
        final String name;
        final String url;

        Channel(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }
}
