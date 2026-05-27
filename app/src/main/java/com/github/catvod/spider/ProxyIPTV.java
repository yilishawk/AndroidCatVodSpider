package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.*;
import java.util.*;

public class ProxyIPTV extends Spider {

    private static final String WORKER_URL = "https://tonkiang.us";
    private static final int MAX_IP_PER_PAGE = 2;
    private static final String IPTV_FILE = "/sdcard/Download/iptv.txt";

    private static boolean hasCrawled = false;
    private static String cachedM3u = "";

    @Override
    public void init(Context context, String extend) throws Exception {
        if (!hasCrawled) {
            new Thread(this::crawlIPTV).start();
        }
    }

    private void crawlIPTV() {
        hasCrawled = true;
        log("🚀 [ProxyIPTV] 开始抓取 Tonkiang IPTV...");

        String[] sources = {"iptvhotelx.php", "iptvproxy.php"};
        List<String> lines = new ArrayList<>();

        for (String php : sources) {
            crawlOneSource(php, lines);
        }

        if (!lines.isEmpty()) {
            saveIPTV(lines);
            cachedM3u = buildM3u(lines);
            log("✅ IPTV 抓取完成！共 " + lines.size() + " 行");
        }
    }

    private void crawlOneSource(String listPhp, List<String> allLines) {
        try {
            String url = WORKER_URL + "/" + listPhp;
            String html = fetch(url, WORKER_URL + "/");

            List<Map<String, String>> ips = parseIpList(html);
            List<Map<String, String>> valid = ips.subList(0, Math.min(MAX_IP_PER_PAGE, ips.size()));

            for (Map<String, String> ipInfo : valid) {
                String detailUrl = WORKER_URL + "/getall26.php?ip=" + ipInfo.get("ip") +
                        "&tk=" + ipInfo.get("tk") + "&p=" + ipInfo.get("p");

                String detailHtml = fetch(detailUrl, WORKER_URL + "/channellist.html?ip=" + ipInfo.get("ip"));
                List<Map<String, String>> channels = parseChannels(detailHtml);

                if (!channels.isEmpty()) {
                    allLines.add(ipInfo.get("region_isp") + ",#genre#");
                    for (Map<String, String> ch : channels) {
                        allLines.add(ch.get("name") + "," + ch.get("url"));
                    }
                }
            }
        } catch (Exception e) {
            log("⚠️ " + listPhp + " 抓取失败");
        }
    }

    private String fetch(String url, String referer) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.put("Referer", referer);
            OkResult res = OkHttp.get(url, null, headers);
            return res.getBody();
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, String>> parseIpList(String html) {
        List<Map<String, String>> list = new ArrayList<>();
        if (html == null) return list;

        Document doc = Jsoup.parse(html);
        for (Element div : doc.select("div.result")) {
            if (div.text().contains("暂时失效")) continue;

            Element a = div.selectFirst("a[href*=channellist.html?ip=]");
            if (a == null) continue;

            String href = a.attr("href");
            Map<String, String> params = parseQuery(href);

            String ip = params.get("ip");
            String tk = params.get("tk");
            if (ip == null || tk == null) continue;

            String region = "未知地区";
            Element i = div.selectFirst("i");
            if (i != null) region = i.text().trim();

            Map<String, String> m = new HashMap<>();
            m.put("ip", ip);
            m.put("tk", tk);
            m.put("p", params.getOrDefault("p", "1"));
            m.put("region_isp", region);
            list.add(m);
        }
        return list;
    }

    private List<Map<String, String>> parseChannels(String html) {
        List<Map<String, String>> channels = new ArrayList<>();
        if (html == null) return channels;

        Document doc = Jsoup.parse(html);
        for (Element div : doc.select("div.result")) {
            String name = "未知频道";
            Element tip = div.selectFirst("div.tip");
            if (tip != null) name = tip.text().trim();

            for (Element td : div.select("div.m3u8 td")) {
                String url = td.text().trim();
                if (url.startsWith("http")) {
                    Map<String, String> ch = new HashMap<>();
                    ch.put("name", name);
                    ch.put("url", url);
                    channels.add(ch);
                    break;
                }
            }
        }
        return channels;
    }

    private Map<String, String> parseQuery(String url) {
        Map<String, String> map = new HashMap<>();
        try {
            String query = url.contains("?") ? url.substring(url.indexOf("?") + 1) : "";
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) map.put(kv[0], kv[1]);
            }
        } catch (Exception ignored) {}
        return map;
    }

    private void saveIPTV(List<String> lines) {
        try (FileWriter fw = new FileWriter(IPTV_FILE)) {
            for (String line : lines) fw.write(line + "\n");
        } catch (Exception ignored) {}
    }

    private String buildM3u(List<String> lines) {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (String line : lines) sb.append(line).append("\n");
        return sb.toString();
    }

    private void log(String msg) {
        Proxy.log("[ProxyIPTV] " + msg);
    }

    // ==================== 关键：处理 proxy 请求 ====================
    public String proxy(String url) throws Exception {
        if (url != null && (url.contains("do=iptv") || url.contains("iptv"))) {
            if (cachedM3u.isEmpty() && !hasCrawled) {
                crawlIPTV();
            }
            return cachedM3u.isEmpty() ? "#EXTM3U\n# 正在抓取中，请稍后刷新直播源..." : cachedM3u;
        }
        return "";
    }
}
