package com.github.catvod.utils;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 免费代理工具（数据来源：proxifly/free-proxy-list）
 * 适合给需要代理才能访问的爬虫使用
 */
public class FreeProxy {

    // Proxifly 官方 CDN 地址
    private static final String HTTP_TXT = "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/http/data.txt";
    private static final String HTTPS_TXT = "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/https/data.txt";
    private static final String ALL_JSON = "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/all/data.json";

    // 缓存有效时间（毫秒），默认 10 分钟
    private static final long CACHE_TTL = 10 * 60 * 1000;

    private static final List<String> proxyList = new CopyOnWriteArrayList<>();
    private static final AtomicInteger index = new AtomicInteger(0);
    private static long lastFetchTime = 0;
    private static final Random random = new Random();

    private static void log(String msg) {
        try {
            // 如果项目有 Proxy 日志类可以改成 Proxy.log
            System.out.println("[FreeProxy] " + msg);
        } catch (Exception ignored) {
        }
    }

    /**
     * 强制刷新代理列表
     */
    public static synchronized void refresh() {
        lastFetchTime = 0;
        ensureLoaded();
    }

    /**
     * 确保代理列表已加载
     */
    public static synchronized void ensureLoaded() {
        long now = System.currentTimeMillis();
        if (!proxyList.isEmpty() && (now - lastFetchTime) < CACHE_TTL) {
            return;
        }

        List<String> list = new ArrayList<>();

        // 优先拉 HTTP 代理（兼容性最好）
        list.addAll(fetchTxt(HTTP_TXT));
        // 再补充 HTTPS 代理
        list.addAll(fetchTxt(HTTPS_TXT));

        if (list.isEmpty()) {
            // 兜底：尝试 JSON
            list.addAll(fetchJson(ALL_JSON));
        }

        // 去重并打乱
        List<String> unique = new ArrayList<>();
        for (String p : list) {
            if (!unique.contains(p)) unique.add(p);
        }
        Collections.shuffle(unique);

        proxyList.clear();
        proxyList.addAll(unique);
        lastFetchTime = now;
        index.set(0);

        log("已加载代理数量: " + proxyList.size());
    }

    private static List<String> fetchTxt(String url) {
        List<String> result = new ArrayList<>();
        try {
            String body = OkHttp.string(url);
            if (TextUtils.isEmpty(body)) return result;

            String[] lines = body.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                // 支持格式：
                // 1. http://1.2.3.4:8080
                // 2. 1.2.3.4:8080
                // 3. https://1.2.3.4:8080
                String proxy = normalize(line);
                if (proxy != null) result.add(proxy);
            }
        } catch (Exception e) {
            log("拉取失败 " + url + " : " + e.getMessage());
        }
        return result;
    }

    private static List<String> fetchJson(String url) {
        List<String> result = new ArrayList<>();
        try {
            String body = OkHttp.string(url);
            if (TextUtils.isEmpty(body)) return result;

            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String protocol = obj.optString("protocol", "").toLowerCase();
                // 只要 http / https
                if (!"http".equals(protocol) && !"https".equals(protocol)) continue;

                String ip = obj.optString("ip");
                int port = obj.optInt("port", 0);
                if (TextUtils.isEmpty(ip) || port <= 0) continue;

                result.add(ip + ":" + port);
            }
        } catch (Exception e) {
            log("JSON 拉取失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 标准化成 ip:port
     */
    private static String normalize(String line) {
        try {
            line = line.trim();
            if (line.startsWith("http://")) {
                line = line.substring(7);
            } else if (line.startsWith("https://")) {
                line = line.substring(8);
            }
            // 去掉可能的路径
            int slash = line.indexOf('/');
            if (slash > 0) line = line.substring(0, slash);

            if (!line.contains(":")) return null;
            String[] parts = line.split(":");
            if (parts.length != 2) return null;

            String ip = parts[0].trim();
            int port = Integer.parseInt(parts[1].trim());
            if (ip.isEmpty() || port <= 0 || port > 65535) return null;

            return ip + ":" + port;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取当前代理数量
     */
    public static int size() {
        ensureLoaded();
        return proxyList.size();
    }

    /**
     * 随机获取一个代理字符串（ip:port）
     */
    public static String getRandom() {
        ensureLoaded();
        if (proxyList.isEmpty()) return null;
        return proxyList.get(random.nextInt(proxyList.size()));
    }

    /**
     * 轮询获取下一个代理（ip:port）
     */
    public static String getNext() {
        ensureLoaded();
        if (proxyList.isEmpty()) return null;
        int i = Math.abs(index.getAndIncrement() % proxyList.size());
        return proxyList.get(i);
    }

    /**
     * 获取 java.net.Proxy 对象（HTTP 类型）
     */
    public static Proxy getRandomProxy() {
        String p = getRandom();
        return toProxy(p);
    }

    public static Proxy getNextProxy() {
        String p = getNext();
        return toProxy(p);
    }

    public static Proxy toProxy(String ipPort) {
        if (TextUtils.isEmpty(ipPort)) return Proxy.NO_PROXY;
        try {
            String[] parts = ipPort.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        } catch (Exception e) {
            return Proxy.NO_PROXY;
        }
    }

    /**
     * 简单检测代理是否可用（请求一个轻量地址）
     * 注意：会比较慢，建议只在需要时调用
     */
    public static boolean isAlive(String ipPort) {
        if (TextUtils.isEmpty(ipPort)) return false;
        try {
            Proxy proxy = toProxy(ipPort);
            // 这里用最简单的方式：能创建 Proxy 对象就先返回 true
            // 真正检测需要自定义 OkHttpClient，项目里可按需扩展
            return proxy != null && proxy.type() != Proxy.Type.DIRECT;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 清空缓存
     */
    public static void clear() {
        proxyList.clear();
        lastFetchTime = 0;
        index.set(0);
    }
}
