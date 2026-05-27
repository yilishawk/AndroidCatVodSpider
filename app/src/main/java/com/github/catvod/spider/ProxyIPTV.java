package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProxyIPTV extends Spider {

    // Tonkiang
    private static final String HOST = "https://tonkiang.us";

    // 每个列表页最多抓几个IP
    private static final int MAX_IP_PER_PAGE = 2;

    // 缓存
    private static boolean hasCrawled = false;
    private static String cachedM3u = "";

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);

        // 首次同步抓取
        if (!hasCrawled) {
            crawlIPTV();
        }
    }

    // =====================================================
    // 本地代理入口
    // 使用:
    // proxy://do=iptv
    // =====================================================
    @Override
    public Object[] proxyLocal(Map<String, String> params) {

        try {

            String doType = params.get("do");

            if ("iptv".equals(doType)) {

                // 缓存为空重新抓
                if (cachedM3u.isEmpty()) {
                    crawlIPTV();
                }

                String result = cachedM3u;

                if (result == null || result.isEmpty()) {
                    result = "#EXTM3U\n# 暂无数据";
                }

                return new Object[]{
                        200,
                        "application/vnd.apple.mpegurl",
                        result
                };
            }

        } catch (Exception e) {
            log("proxyLocal error: " + e.getMessage());
        }

        return null;
    }

    // =====================================================
    // 开始抓取
    // =====================================================
    private synchronized void crawlIPTV() {

        if (hasCrawled && !cachedM3u.isEmpty()) {
            return;
        }

        hasCrawled = true;

        log("开始抓取 IPTV...");

        try {

            String[] sources = {
                    "iptvhotelx.php",
                    "iptvproxy.php"
            };

            List<String> lines = new ArrayList<>();

            for (String php : sources) {
                crawlOneSource(php, lines);
            }

            if (!lines.isEmpty()) {

                cachedM3u = buildM3u(lines);

                log("抓取完成: " + lines.size() + " 行");

            } else {

                cachedM3u = "#EXTM3U\n# 无可用数据";

                log("未抓到任何频道");
            }

        } catch (Exception e) {

            cachedM3u = "#EXTM3U\n# 抓取失败";

            log("crawlIPTV error: " + e.getMessage());
        }
    }

    // =====================================================
    // 抓单个列表
    // =====================================================
    private void crawlOneSource(String listPhp, List<String> allLines) {

        try {

            String url = HOST + "/" + listPhp;

            log("抓列表: " + url);

            String html = fetch(url, HOST + "/");

            if (html == null || html.isEmpty()) {
                log("列表为空");
                return;
            }

            List<Map<String, String>> ips = parseIpList(html);

            if (ips.isEmpty()) {
                log("未解析到IP");
                return;
            }

            int max = Math.min(MAX_IP_PER_PAGE, ips.size());

            for (int i = 0; i < max; i++) {

                Map<String, String> ipInfo = ips.get(i);

                String ip = ipInfo.get("ip");
                String tk = ipInfo.get("tk");
                String p = ipInfo.get("p");

                String detailUrl =
                        HOST + "/getall26.php?ip=" + ip +
                                "&tk=" + tk +
                                "&p=" + p;

                log("抓频道: " + detailUrl);

                String detailHtml = fetch(
                        detailUrl,
                        HOST + "/channellist.html?ip=" + ip
                );

                if (detailHtml == null || detailHtml.isEmpty()) {
                    log("频道页为空");
                    continue;
                }

                List<Map<String, String>> channels =
                        parseChannels(detailHtml);

                if (!channels.isEmpty()) {

                    allLines.add(
                            ipInfo.get("region_isp") + ",#genre#"
                    );

                    for (Map<String, String> ch : channels) {

                        String name = ch.get("name");
                        String playUrl = ch.get("url");

                        allLines.add(name + "," + playUrl);
                    }

                    log("频道数: " + channels.size());

                } else {

                    log("未解析到频道");
                }
            }

        } catch (Exception e) {

            log("crawlOneSource error: " + e.getMessage());
        }
    }

    // =====================================================
    // HTTP
    // =====================================================
    private String fetch(String url, String referer) {

        try {

            Map<String, String> headers = new HashMap<>();

            headers.put(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36"
            );

            headers.put("Referer", referer);

            headers.put(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            );

            headers.put("Accept-Language", "zh-CN,zh;q=0.9");

            OkResult result = OkHttp.get(url, null, headers);

            if (result == null) {
                return "";
            }

            String body = result.getBody();

            if (body == null) {
                return "";
            }

            return body;

        } catch (Exception e) {

            log("fetch error: " + e.getMessage());

            return "";
        }
    }

    // =====================================================
    // 解析IP列表
    // =====================================================
    private List<Map<String, String>> parseIpList(String html) {

        List<Map<String, String>> list = new ArrayList<>();

        try {

            Document doc = Jsoup.parse(html);

            for (Element div : doc.select("div.result")) {

                if (div.text().contains("暂时失效")) {
                    continue;
                }

                Element a =
                        div.selectFirst("a[href*=channellist.html?ip=]");

                if (a == null) {
                    continue;
                }

                String href = a.attr("href");

                Map<String, String> query = parseQuery(href);

                String ip = query.get("ip");
                String tk = query.get("tk");

                if (ip == null || tk == null) {
                    continue;
                }

                String region = "未知地区";

                Element i = div.selectFirst("i");

                if (i != null) {
                    region = i.text().trim();
                }

                Map<String, String> item = new HashMap<>();

                item.put("ip", ip);
                item.put("tk", tk);
                item.put("p", query.getOrDefault("p", "1"));
                item.put("region_isp", region);

                list.add(item);
            }

        } catch (Exception e) {

            log("parseIpList error: " + e.getMessage());
        }

        return list;
    }

    // =====================================================
    // 解析频道
    // =====================================================
    private List<Map<String, String>> parseChannels(String html) {

        List<Map<String, String>> channels = new ArrayList<>();

        try {

            Document doc = Jsoup.parse(html);

            for (Element div : doc.select("div.result")) {

                String name = "未知频道";

                Element tip = div.selectFirst("div.tip");

                if (tip != null) {
                    name = tip.text().trim();
                }

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

        } catch (Exception e) {

            log("parseChannels error: " + e.getMessage());
        }

        return channels;
    }

    // =====================================================
    // Query解析
    // =====================================================
    private Map<String, String> parseQuery(String url) {

        Map<String, String> map = new HashMap<>();

        try {

            String query = "";

            if (url.contains("?")) {
                query = url.substring(url.indexOf("?") + 1);
            }

            String[] arr = query.split("&");

            for (String s : arr) {

                String[] kv = s.split("=", 2);

                if (kv.length == 2) {
                    map.put(kv[0], kv[1]);
                }
            }

        } catch (Exception ignored) {
        }

        return map;
    }

    // =====================================================
    // M3U生成
    // =====================================================
    private String buildM3u(List<String> lines) {

        StringBuilder sb = new StringBuilder();

        sb.append("#EXTM3U\n");

        for (String line : lines) {
            sb.append(line).append("\n");
        }

        return sb.toString();
    }

    // =====================================================
    // LOG
    // =====================================================
    private void log(String msg) {
        Proxy.log("[ProxyIPTV] " + msg);
    }
}