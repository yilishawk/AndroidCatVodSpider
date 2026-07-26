package com.github.catvod.spider;

import android.content.Context;
import android.os.Environment;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProxyIPTV extends Spider {

    private static final String HOST = "https://tonkiang.us";
    private static final long CACHE_EXPIRE_MS = 24 * 60 * 60 * 1000; // 24小时 过期

    private static final File CACHE_FILE_PROXY = new File(Environment.getExternalStorageDirectory(), "TV/iptv_cache_proxy.json");
    private static final File CACHE_FILE_HOTEL = new File(Environment.getExternalStorageDirectory(), "TV/iptv_cache_hotel.json");
    private static final String CACHE_KEY_TIME_PROXY = "iptv_cache_time_proxy";
    private static final String CACHE_KEY_TIME_HOTEL = "iptv_cache_time_hotel";

    /**
     * 单个源（iptvproxy.php / iptvhotelx.php）各自独立的缓存与状态。
     * format = "m3u"：该源本轮判定为有订阅链接，m3uContent 是合并后的原始 m3u 文本
     * format = "txt"：该源本轮判定为没有订阅链接，txtData 是 cctv/sat/other 分类后的频道
     */
    public static class SourceCache {
        public final String php;
        public volatile String format = "txt";
        public volatile String m3uContent = null;
        public final Map<String, List<String>> txtData = new ConcurrentHashMap<>();
        public volatile boolean loading = false;
        public volatile boolean complete = false;

        SourceCache(String php) {
            this.php = php;
            txtData.put("cctv", new ArrayList<>());
            txtData.put("sat", new ArrayList<>());
            txtData.put("other", new ArrayList<>());
        }

        boolean isEmpty() {
            if ("m3u".equals(format)) return m3uContent == null || m3uContent.isEmpty();
            return countChannels(txtData) == 0;
        }
    }

    private static final SourceCache PROXY_CACHE = new SourceCache("iptvproxy.php");
    private static final SourceCache HOTEL_CACHE = new SourceCache("iptvhotelx.php");

    /** 供 Proxy.java 调用：iptvproxy.php 源，不阻塞、按需触发爬取 */
    public static SourceCache getProxyCache() {
        triggerCrawlIfNeeded(PROXY_CACHE, CACHE_FILE_PROXY, CACHE_KEY_TIME_PROXY);
        return PROXY_CACHE;
    }

    /** 供 Proxy.java 调用：iptvhotelx.php 源，不阻塞、按需触发爬取 */
    public static SourceCache getHotelCache() {
        triggerCrawlIfNeeded(HOTEL_CACHE, CACHE_FILE_HOTEL, CACHE_KEY_TIME_HOTEL);
        return HOTEL_CACHE;
    }

    /** @deprecated 兼容旧调用方（ProxyServer.kt），等价于 getProxyCache().txtData */
    public static Map<String, List<String>> getCacheData() {
        return getProxyCache().txtData;
    }

    /** @deprecated 兼容旧调用方，等价于 iptvproxy.php 源的 loading 状态 */
    public static boolean isLoading() {
        return PROXY_CACHE.loading;
    }

    /** @deprecated 兼容旧调用方，等价于 iptvproxy.php 源的 complete 状态 */
    public static boolean isComplete() {
        return PROXY_CACHE.complete;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);

        loadCacheFromFile(PROXY_CACHE, CACHE_FILE_PROXY, CACHE_KEY_TIME_PROXY);
        loadCacheFromFile(HOTEL_CACHE, CACHE_FILE_HOTEL, CACHE_KEY_TIME_HOTEL);

        // 异步启动两个源的爬虫更新，不影响其他爬虫
        Init.execute(new Runnable() {
            @Override
            public void run() {
                triggerCrawlIfNeeded(PROXY_CACHE, CACHE_FILE_PROXY, CACHE_KEY_TIME_PROXY);
                triggerCrawlIfNeeded(HOTEL_CACHE, CACHE_FILE_HOTEL, CACHE_KEY_TIME_HOTEL);
            }
        });
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("cctv", "央视"));
        classes.add(new Class("sat", "卫视"));
        classes.add(new Class("other", "其他"));

        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        List<String> channels = PROXY_CACHE.txtData.getOrDefault(tid, new ArrayList<>());
        List<Vod> list = new ArrayList<>();

        for (String line : channels) {
            String[] split = line.split("\\$");
            if (split.length < 2) continue;
            list.add(new Vod(line, split[0], "https://epg.112114.xyz/logo/" + split[0] + ".png", "直播源"));
        }

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
        return Result.get().url(id).parse(0).string();
    }

    /** 序列化用的载体：把某个源的完整缓存状态存到本地文件 */
    private static class CachePayload {
        String format;
        String m3uContent;
        Map<String, List<String>> txtData;
    }

    private static void loadCacheFromFile(SourceCache cache, File file, String timeKey) {
        try {
            if (!file.exists()) return;

            String json = "";
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] data = new byte[fis.available()];
                int read = fis.read(data);
                if (read > 0) {
                    json = new String(data, 0, read, "UTF-8");
                }
            }

            if (json.isEmpty()) return;

            long now = System.currentTimeMillis();
            long cacheTime = Prefers.getLong(timeKey);
            if (now - cacheTime > CACHE_EXPIRE_MS) {
                Proxy.log("🔄 IPTV[" + cache.php + "] 缓存过期，将重新爬取");
                return;
            }

            Type type = new TypeToken<CachePayload>() {}.getType();
            CachePayload payload = Json.parseSafe(json, type);
            if (payload != null) {
                cache.format = payload.format != null ? payload.format : "txt";
                cache.m3uContent = payload.m3uContent;
                cache.txtData.clear();
                if (payload.txtData != null) cache.txtData.putAll(payload.txtData);
                Proxy.log("✅ IPTV[" + cache.php + "] 从缓存加载成功，格式=" + cache.format);
            }
        } catch (Exception e) {
            Proxy.log("❌ IPTV[" + cache.php + "] 加载缓存失败: " + e.getMessage() + "<br><pre>" + Proxy.getStackTrace(e) + "</pre>");
        }
    }

    private static void saveCacheToFile(SourceCache cache, File file, String timeKey) {
        try {
            file.getParentFile().mkdirs();
            CachePayload payload = new CachePayload();
            payload.format = cache.format;
            payload.m3uContent = cache.m3uContent;
            payload.txtData = cache.txtData;
            String json = Json.toJson(payload);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(json.getBytes("UTF-8"));
                fos.flush();
            }
            Prefers.put(timeKey, System.currentTimeMillis());
            Proxy.log("💾 IPTV[" + cache.php + "] 缓存已写入本地文件");
        } catch (Exception e) {
            Proxy.log("❌ IPTV[" + cache.php + "] 保存缓存失败: " + e.getMessage() + "<br><pre>" + Proxy.getStackTrace(e) + "</pre>");
        }
    }

    private static synchronized void triggerCrawlIfNeeded(SourceCache cache, File file, String timeKey) {
        if (!cache.isEmpty() || cache.loading) return;
        cache.loading = true;
        cache.complete = false;
        new Thread(() -> {
            try {
                crawlSource(cache, file, timeKey);
            } finally {
                cache.loading = false;
                cache.complete = true;
            }
        }, "IPTV-Crawler-" + cache.php).start();
    }

    /**
     * 抓取单个源（iptvhotelx.php 或 iptvproxy.php）的前 3 个 IP。
     * 用第一个 IP 的详情页判断本轮格式：有订阅链接 -> m3u，没有 -> txt；后两个 IP 复用同一判定。
     */
    private static void crawlSource(SourceCache cache, File file, String timeKey) {
        List<String> m3uBlocks = new ArrayList<>();
        Map<String, List<String>> txtData = new ConcurrentHashMap<>();
        txtData.put("cctv", new ArrayList<>());
        txtData.put("sat", new ArrayList<>());
        txtData.put("other", new ArrayList<>());
        String detectedFormat = null;

        try {
            Map<String, String> listHeaders = buildBrowserHeaders(HOST + "/");
            okhttp3.Response listResp = OkHttp.newCall(HOST + "/" + cache.php, listHeaders);
            String html = listResp.body() != null ? listResp.body().string() : null;
            Proxy.log("🔍 IPTV[" + cache.php + "] 请求完成，html长度=" + (html == null ? "null" : html.length()));

            Document doc = Jsoup.parse(html == null ? "" : html);
            int resultCount = doc.select("div.result").size();
            Proxy.log("🔍 IPTV[" + cache.php + "] 匹配到 div.result 数量=" + resultCount);

            int count = 0;
            for (Element div : doc.select("div.result")) {
                if (count >= 3) break;
                if (div.text().contains("暂时失效")) continue;
                Element a = div.selectFirst("a[href*=channellist.html?ip=]");
                if (a == null) continue;

                String href = a.attr("href");
                String ip = getParam(href, "ip");
                String tk = getParam(href, "tk");
                String p = getParam(href, "p");
                if (p == null || p.isEmpty()) p = "1";
                if (ip.isEmpty() || tk.isEmpty()) continue;

                String detailUrl = HOST + "/getall26.php?ip=" + ip + "&c=&tk=" + tk + "&p=" + p;
                String channelReferer = HOST + "/channellist.html?ip=" + ip + "&tk=" + tk + "&p=" + p;

                Map<String, String> detailHeaders = buildBrowserHeaders(channelReferer);
                okhttp3.Response detailResp = OkHttp.newCall(detailUrl, detailHeaders);
                String detail = detailResp.body() != null ? detailResp.body().string() : null;
                int detailLen = detail == null ? 0 : detail.length();
                Proxy.log("🔍 IPTV[" + cache.php + "] 详情页 " + detailUrl + " html长度=" + detailLen);
                if (detailLen > 0 && detailLen < 500) {
                    Proxy.log("🔍 IPTV[" + cache.php + "] 详情页短响应内容: <pre>" + detail.replace("<", "&lt;") + "</pre>");
                }

                String[] subLink = extractSubscribeLink(detail);

                if (count == 0) {
                    detectedFormat = subLink != null ? "m3u" : "txt";
                    Proxy.log("🔍 IPTV[" + cache.php + "] 判定格式=" + detectedFormat);
                }

                if ("m3u".equals(detectedFormat)) {
                    if (subLink != null && "m".equals(subLink[1])) {
                        try {
                            Map<String, String> subHeaders = buildBrowserHeaders(detailUrl);
                            okhttp3.Response subResp = OkHttp.newCall(subLink[0], subHeaders);
                            String subContent = subResp.body() != null ? subResp.body().string() : null;
                            Proxy.log("🔍 IPTV[" + cache.php + "] 订阅链接 " + subLink[0] + " 长度=" + (subContent == null ? 0 : subContent.length()));
                            if (subContent != null && !subContent.isEmpty()) {
                                m3uBlocks.add(stripM3uHeader(subContent));
                            }
                        } catch (Exception e) {
                            Proxy.log("❌ IPTV[" + cache.php + "] 订阅链接请求失败: " + e.getMessage());
                        }
                    }
                    // 该源本轮判定为 m3u，但这个 IP 恰好没订阅链接（异常情况）：跳过，不降级到 txt，保持源内格式一致
                } else {
                    parseAndSort(detail, txtData);
                }
                count++;
            }
        } catch (Exception e) {
            Proxy.log("❌ IPTV[" + cache.php + "] 抓取失败: " + e.getMessage() + "<br><pre>" + Proxy.getStackTrace(e) + "</pre>");
        }

        if ("m3u".equals(detectedFormat) && !m3uBlocks.isEmpty()) {
            StringBuilder merged = new StringBuilder("#EXTM3U\n");
            for (String block : m3uBlocks) merged.append(block).append("\n");
            cache.format = "m3u";
            cache.m3uContent = merged.toString();
            cache.txtData.clear();
            Proxy.log("✅ IPTV[" + cache.php + "] 爬虫完成，格式=m3u，内容长度=" + cache.m3uContent.length());
        } else {
            cache.format = "txt";
            cache.m3uContent = null;
            cache.txtData.clear();
            cache.txtData.putAll(txtData);
            Proxy.log("✅ IPTV[" + cache.php + "] 爬虫完成，格式=txt，共加载 " + countChannels(cache.txtData) + " 个频道");
        }

        saveCacheToFile(cache, file, timeKey);
    }

    private static void parseAndSort(String html, Map<String, List<String>> targetMap) {
        if (html == null || html.isEmpty()) return;
        Document doc = Jsoup.parse(html);
        int resultDivCount = 0;
        int nameFoundCount = 0;
        int urlFoundCount = 0;
        int appendedCount = 0;
        for (Element div : doc.select("div.result")) {
            resultDivCount++;
            String name = "";
            Element tip = div.selectFirst("div.tip");
            if (tip != null) name = tip.text().trim();
            if (!name.isEmpty()) nameFoundCount++;

            Element td = div.selectFirst("div.m3u8 td");
            if (name.isEmpty() || td == null) continue;
            String url = td.text().trim();
            if (!url.startsWith("http")) continue;
            urlFoundCount++;

            addChannel(targetMap, name, url);
            appendedCount++;
        }
        Proxy.log("🔍 parseAndSort: div.result总数=" + resultDivCount
                + " 取到名称=" + nameFoundCount
                + " 取到地址=" + urlFoundCount
                + " 成功加入=" + appendedCount);
    }

    /**
     * 从详情页 HTML 里提取订阅链接（copytodr('url','m'|'t')），优先 m3u，其次 txt
     * 返回 {url, type}，找不到返回 null
     */
    private static String[] extractSubscribeLink(String detailHtml) {
        if (detailHtml == null) return null;
        Pattern pattern = Pattern.compile("copytodr\\('([^']+)'\\s*,\\s*'([tm])'\\)");
        Matcher matcher = pattern.matcher(detailHtml);
        String txtUrl = null;
        String m3uUrl = null;
        while (matcher.find()) {
            String url = matcher.group(1);
            String type = matcher.group(2);
            if ("m".equals(type) && m3uUrl == null) m3uUrl = url;
            if ("t".equals(type) && txtUrl == null) txtUrl = url;
        }
        if (m3uUrl != null) return new String[]{m3uUrl, "m"};
        if (txtUrl != null) return new String[]{txtUrl, "t"};
        return null;
    }

    /** 去掉 m3u 内容的 #EXTM3U 头行，用于把多个源的 m3u 合并成一份 */
    private static String stripM3uHeader(String content) {
        if (content == null) return "";
        String[] lines = content.split("\r?\n", 2);
        if (lines.length > 0 && lines[0].trim().startsWith("#EXTM3U")) {
            return lines.length > 1 ? lines[1] : "";
        }
        return content;
    }

    private static void addChannel(Map<String, List<String>> targetMap, String name, String url) {
        String item = name + "$" + url;
        if (name.contains("CCTV") || name.contains("央视")) {
            targetMap.get("cctv").add(item);
        } else if (name.contains("卫视")) {
            targetMap.get("sat").add(item);
        } else {
            targetMap.get("other").add(item);
        }
    }

    private static long countChannels(Map<String, List<String>> map) {
        long total = 0;
        for (List<String> channels : map.values()) {
            total += channels.size();
        }
        return total;
    }

    private static Map<String, String> buildBrowserHeaders(String referer) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if (referer != null) headers.put("Referer", referer);
        return headers;
    }

    private static String getParam(String url, String name) {
        if (url == null || name == null) return "";
        String key1 = "?" + name + "=";
        String key2 = "&" + name + "=";
        int start = url.indexOf(key1);
        int keyLen = key1.length();
        if (start == -1) {
            start = url.indexOf(key2);
            keyLen = key2.length();
        }
        if (start == -1) return "";
        start += keyLen;
        int end = url.indexOf("&", start);
        if (end == -1) end = url.length();
        return url.substring(start, end);
    }
}
