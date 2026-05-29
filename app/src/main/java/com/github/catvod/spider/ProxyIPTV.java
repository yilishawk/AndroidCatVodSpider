package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class ProxyIPTV extends Spider {

    private static final String HOST = "https://tonkiang.us";
    private static final int MAX_IP_PER_PAGE = 2; // 每个来源抓2个IP
    private static String cachedM3u = "";
    private static final AtomicBoolean isCrawling = new AtomicBoolean(false);

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        // 不再在init里执行耗时操作
    }

    /**
     * 本地代理访问入口
     * 访问地址示例: proxy://do=proxy&site=ProxyIPTV&doType=iptv
     */
    @Override
    public Object[] proxyLocal(Map<String, String> params) {
    try {
        String type = params.get("doType");
        if ("iptv".equals(type)) {
            // 1. 同步抓取（FongMi建议在代理请求时完成，以保证返回实时性）
            synchronized (ProxyIPTV.class) {
                if (cachedM3u.isEmpty()) {
                    crawlIPTV();
                }
            }

            // 2. 准备返回内容
            String result = cachedM3u.isEmpty() ? "#EXTM3U\n# 抓取失败" : cachedM3u;

            // 3. 【关键】按照文档返回 200 状态码和内容字符串
            // 数组长度必须为 2
            return new Object[]{ 200, result };
        }
    } catch (Exception e) {
        log("proxyLocal error: " + e.getMessage());
    }

    // 默认返回 404
    return new Object[]{ 404, "Not Found" };
}


    private void crawlIPTV() {
        log("开始抓取 IPTV...");
        try {
            String[] sources = {"iptvhotelx.php", "iptvproxy.php"};
            List<String> lines = new ArrayList<>();
            for (String php : sources) {
                crawlOneSource(php, lines);
            }
            if (!lines.isEmpty()) {
                cachedM3u = buildM3u(lines);
                log("抓取完成，共 " + lines.size() + " 行");
            } else {
                cachedM3u = "#EXTM3U\n# 未抓取到有效源";
            }
        } catch (Exception e) {
            cachedM3u = "#EXTM3U\n# 抓取过程出错";
        }
    }

    private void crawlOneSource(String listPhp, List<String> allLines) {
        try {
            String url = HOST + "/" + listPhp;
            String html = fetch(url, HOST + "/");
            if (html.isEmpty()) return;

            Document doc = Jsoup.parse(html);
            Elements results = doc.select("div.result");
            int count = 0;

            for (Element div : results) {
                if (count >= MAX_IP_PER_PAGE) break;
                if (div.text().contains("暂时失效")) continue;

                Element a = div.selectFirst("a[href*=channellist.html?ip=]");
                if (a == null) continue;

                Map<String, String> query = parseQuery(a.attr("href"));
                String ip = query.get("ip");
                String tk = query.get("tk");
                String region = div.selectFirst("i") != null ? div.selectFirst("i").text().trim() : "未知";

                // 详情页请求
                String detailUrl = HOST + "/getall26.php?ip=" + ip + "&tk=" + tk + "&p=1";
                String detailHtml = fetch(detailUrl, HOST + "/channellist.html?ip=" + ip);

                if (!detailHtml.isEmpty()) {
                    List<String> chList = parseDetail(detailHtml);
                    if (!chList.isEmpty()) {
                        allLines.add(region + ",#genre#");
                        allLines.addAll(chList);
                        count++;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private List<String> parseDetail(String html) {
        List<String> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        for (Element div : doc.select("div.result")) {
            String name = div.selectFirst("div.tip") != null ? div.selectFirst("div.tip").text().trim() : "未知频道";
            Element m3u8Td = div.selectFirst("div.m3u8 td");
            if (m3u8Td != null && m3u8Td.text().startsWith("http")) {
                list.add(name + "," + m3u8Td.text().trim());
            }
        }
        return list;
    }

    private String fetch(String url, String referer) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
            headers.put("Referer", referer);
            OkResult result = OkHttp.get(url, null, headers);
            return result != null ? result.getBody() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, String> parseQuery(String url) {
        Map<String, String> map = new HashMap<>();
        if (url.contains("?")) {
            String[] arr = url.substring(url.indexOf("?") + 1).split("&");
            for (String s : arr) {
                String[] kv = s.split("=", 2);
                if (kv.length == 2) map.put(kv[0], kv[1]);
            }
        }
        return map;
    }

    private String buildM3u(List<String> lines) {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (String line : lines) sb.append(line).append("\n");
        return sb.toString();
    }

    private void log(String msg) {
        // 这里的Proxy.log需要根据你壳子的实际Log类修改
    }
}
