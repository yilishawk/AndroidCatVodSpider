package com.github.catvod.spider;

import android.content.Context;
import android.os.Environment;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
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

public class ProxyIPTV extends Spider {

    private static final String HOST = "https://tonkiang.us";
    private static final long CACHE_EXPIRE_MS = 24 * 60 * 60 * 1000; // 24小时过期
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
            Proxy.log("❌ IPTV 加载缓存失败: " + e.getMessage());
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

            long totalChannels = 0;
            for (List<String> channels : cacheData.values()) {
                totalChannels += channels.size();
            }

            Proxy.log("✅ IPTV 爬虫完成，共加载 " + totalChannels + " 个频道");
        } catch (Exception e) {
            Proxy.log("❌ IPTV 保存缓存失败: " + e.getMessage());
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

                    parseAndSort(detail, newCacheData);
                    count++;
                }
            } catch (Exception ignored) {}
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
                targetMap.get("cctv").add(item);
            } else if (name.contains("卫视")) {
                targetMap.get("sat").add(item);
            } else {
                targetMap.get("other").add(item);
            }
        }
    }

    private static long getTotalChannels() {
        long total = 0;
        for (List<String> channels : cacheData.values()) {
            total += channels.size();
        }
        return total;
    }

    private static String getParam(String url, String name) {
        int start = url.indexOf(name + "=");
        if (start == -1) return "";
        int end = url.indexOf("&", start);
        if (end == -1) end = url.length();
        return url.substring(start + name.length() + 1, end);
    }
}
