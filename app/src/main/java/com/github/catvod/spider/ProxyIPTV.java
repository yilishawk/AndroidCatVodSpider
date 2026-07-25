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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProxyIPTV extends Spider {

    private static final String HOST = "https://tonkiang.us";
    private static final long CACHE_EXPIRE_MS = 24 * 60 * 60 * 1000; // 24小时 过期
    private static final String CACHE_KEY_TIME = "iptv_cache_time";
    private static final String CACHE_KEY_DATA = "iptv_cache_data";
    private static final File CACHE_FILE = new File(Environment.getExternalStorageDirectory(), "TV/iptv_cache.json");

    private static final Map<String, List<String>> cacheData = new ConcurrentHashMap<>();
    private static volatile boolean loading = false;
    private static volatile boolean complete = false;
    private static volatile long lastCrawlTime = 0;

    /**
     * 供 Proxy.java 静态调用:直接返回缓存数据，不阻塞、不触发爬取
     */
    public static synchronized Map<String, List<String>> getCacheData() {
        if (cacheData.isEmpty() && !loading) {
            loading = true;
            complete = false;
            new Thread(() -> {
                try {
                    crawlAll();
                } finally {
                    loading = false;
                    complete = true;
                }
            }, "IPTV-Crawler").start();
        }
        return cacheData;
    }

    /** 爬虫是否正在运行 */
    public static boolean isLoading() {
        return loading;
    }

    /** 爬虫是否已完成 */
    public static boolean isComplete() {
        return complete;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);

        // 优先读本地缓存（静态方法）
        ProxyIPTV.loadCacheFromFile();

        // 异步启动爬虫更新，不影响其他爬虫
        Init.execute(new Runnable() {
            @Override
            public void run() {
                crawlAll();
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
        List<String> channels = cacheData.getOrDefault(tid, new ArrayList<>());
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

    /**
     * 从本地缓存文件加载数据
     */
    private static void loadCacheFromFile() {
        try {
            if (!CACHE_FILE.exists()) return;

            String json = "";
            try (FileInputStream fis = new FileInputStream(CACHE_FILE)) {
                byte[] data = new byte[fis.available()];
                int read = fis.read(data);
                if (read > 0) {
                    json = new String(data, 0, read, "UTF-8");
                }
            }

            if (json.isEmpty()) return;

            // 检查缓存是否过期
            long now = System.currentTimeMillis();
            long cacheTime = Prefers.getLong(CACHE_KEY_TIME);
            if (now - cacheTime > CACHE_EXPIRE_MS) {
                Proxy.log("🔄 IPTV 缓存过期，将重新爬取");
                return;
            }

            // 解析 JSON 数据
            Type type = new TypeToken<Map<String, List<String>>>() {}.getType();
            LinkedHashMap<String, List<String>> parsed = Json.parseSafe(json, type);
            if (parsed != null && !parsed.isEmpty()) {
                cacheData.clear();
                cacheData.putAll(parsed);
                lastCrawlTime = System.currentTimeMillis();
                Proxy.log("✅ IPTV 从缓存加载成功，共 " + cacheData.size() + " 个分组");
            }
        } catch (Exception e) {
            Proxy.log("❌ IPTV 加载缓存失败: " + e.getMessage() + "<br><pre>" + Proxy.getStackTrace(e) + "</pre>");
        }
    }

    /**
     * 保存数据到本地缓存文件
     */
    private static void saveCacheToFile() {
        try {
            CACHE_FILE.getParentFile().mkdirs();
            String json = Json.toJson(cacheData);
            try (FileOutputStream fos = new FileOutputStream(CACHE_FILE)) {
                fos.write(json.getBytes("UTF-8"));
                fos.flush();
            }

            // 同时保存到 SharedPreferences
            Prefers.put(CACHE_KEY_TIME, System.currentTimeMillis());

            Proxy.log("💾 IPTV 缓存已写入本地文件");
        } catch (Exception e) {
            Proxy.log("❌ IPTV 保存缓存失败: " + e.getMessage() + "<br><pre>" + Proxy.getStackTrace(e) + "</pre>");
        }
    }

    private static synchronized void crawlAll() {
        Map<String, List<String>> newCacheData = new ConcurrentHashMap<>();
        newCacheData.put("cctv", new ArrayList<>());
        newCacheData.put("sat", new ArrayList<>());
        newCacheData.put("other", new ArrayList<>());

        String[] sources = {"iptvhotelx.php", "iptvproxy.php"};
        for (String php : sources) {
            try {
                Map<String, String> listHeaders = buildBrowserHeaders(HOST + "/");
                okhttp3.Response listResp = OkHttp.newCall(HOST + "/" + php, listHeaders);
                String html = listResp.body() != null ? listResp.body().string() : null;
                Proxy.log("🔍 IPTV 请求 " + php + " 完成，html长度=" + (html == null ? "null" : html.length()));

                Document doc = Jsoup.parse(html == null ? "" : html);
                int resultCount = doc.select("div.result").size();
                Proxy.log("🔍 IPTV " + php + " 匹配到 div.result 数量=" + resultCount);

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
                    Proxy.log("🔍 IPTV 详情页 " + detailUrl + " html长度=" + detailLen);
                    if (detailLen > 0 && detailLen < 500) {
                        Proxy.log("🔍 IPTV 详情页短响应内容: <pre>" + detail.replace("<", "&lt;") + "</pre>");
                    }

                    String[] subLink = extractSubscribeLink(detail);
                    boolean gotFromSubscription = false;
                    if (subLink != null) {
                        try {
                            Map<String, String> subHeaders = buildBrowserHeaders(detailUrl);
                            okhttp3.Response subResp = OkHttp.newCall(subLink[0], subHeaders);
                            String subContent = subResp.body() != null ? subResp.body().string() : null;
                            int subLen = subContent == null ? 0 : subContent.length();
                            Proxy.log("🔍 IPTV 订阅链接(" + subLink[1] + ") " + subLink[0] + " 长度=" + subLen);

                            int before = countChannels(newCacheData);
                            if ("m".equals(subLink[1])) {
                                parseM3uContent(subContent, newCacheData);
                            } else {
                                parseTxtContent(subContent, newCacheData);
                            }
                            int added = countChannels(newCacheData) - before;
                            Proxy.log("🔍 IPTV 订阅解析新增频道=" + added);
                            gotFromSubscription = added > 0;
                        } catch (Exception e) {
                            Proxy.log("❌ IPTV 订阅链接请求失败: " + e.getMessage());
                        }
                    }
                    if (!gotFromSubscription) {
                        parseAndSort(detail, newCacheData);
                    }
                    count++;
                }
            } catch (Exception e) {
                Proxy.log("❌ IPTV 抓取 " + php + " 失败: " + e.getMessage() + "<br><pre>" + Proxy.getStackTrace(e) + "</pre>");
            }
        }

        // 使用新的缓存数据替换旧数据
        cacheData.clear();
        cacheData.putAll(newCacheData);
        lastCrawlTime = System.currentTimeMillis();
        complete = true;
        loading = false;

        long totalChannels = getTotalChannels();
        Proxy.log("✅ IPTV 爬虫完成，共加载 " + totalChannels + " 个频道");

        // 保存到本地文件
        saveCacheToFile();
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

    /**
     * 解析标准 M3U 播放列表：#EXTINF:-1 ...,频道名 后紧跟一行播放地址
     */
    private static void parseM3uContent(String content, Map<String, List<String>> targetMap) {
        if (content == null || content.isEmpty()) return;
        String[] lines = content.split("\r?\n");
        String pendingName = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("#EXTINF")) {
                int commaIdx = trimmed.lastIndexOf(',');
                pendingName = commaIdx >= 0 ? trimmed.substring(commaIdx + 1).trim() : null;
            } else if (trimmed.startsWith("#")) {
                // 其他 M3U 标签行，忽略
            } else if (pendingName != null && trimmed.startsWith("http")) {
                addChannel(targetMap, pendingName, trimmed);
                pendingName = null;
            }
        }
    }

    /**
     * 解析 TVBox 标准 txt 格式：分组,#genre# 或 频道名,地址
     * 分组行按站点原分组，我们统一按频道名重新归类到 cctv/sat/other，忽略 #genre# 行
     */
    private static void parseTxtContent(String content, Map<String, List<String>> targetMap) {
        if (content == null || content.isEmpty()) return;
        String[] lines = content.split("\r?\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.contains("#genre#")) continue;
            int idx = trimmed.indexOf(',');
            if (idx < 0) continue;
            String name = trimmed.substring(0, idx).trim();
            String url = trimmed.substring(idx + 1).trim();
            if (name.isEmpty() || !url.startsWith("http")) continue;
            addChannel(targetMap, name, url);
        }
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

    private static long getTotalChannels() {
        return countChannels(cacheData);
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
