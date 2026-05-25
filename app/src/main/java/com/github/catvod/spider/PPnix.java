package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.net.OkHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PPnix 影视 (ppnix.com)
 * 严格适配 Fongmi / CatVodSpider 标准
 */
public class PPnix extends Spider {

    private final String host = "https://www.ppnix.com";
    private final String common_ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // 本地 HTTP 服务器
    private static ServerSocket localServer = null;
    private static Map<String, String> m3u8Cache = new ConcurrentHashMap<>();
    private static int localPort = 0;
    private static boolean serverStarted = false;

    private Map<String, String> getHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", common_ua);
        headers.put("Referer", host + "/");
        return headers;
    }

    // ──────────────────────────────────────────────
    // 本地 HTTP 服务器（提供修改后的 M3U8）
    // ──────────────────────────────────────────────

    private synchronized void startLocalHttpServer() {
        if (serverStarted) return;
        serverStarted = true;
        new Thread(() -> {
            try {
                localServer = new ServerSocket(0);
                localPort = localServer.getLocalPort();
                Proxy.log("✅ [PPnix] 本地服务器启动，端口: " + localPort);
                while (true) {
                    try (Socket client = localServer.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        String requestLine = in.readLine();
                        if (requestLine == null) continue;
                        String[] parts = requestLine.split(" ");
                        if (parts.length < 2) continue;
                        String path = parts[1];

                        if (path.startsWith("/m3u8/")) {
                            String id = path.substring("/m3u8/".length());
                            // 去除可能的查询参数
                            if (id.contains("?")) id = id.substring(0, id.indexOf("?"));
                            String content = m3u8Cache.get(id);
                            if (content != null) {
                                byte[] data = content.getBytes("UTF-8");
                                OutputStream out = client.getOutputStream();
                                out.write(("HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                        "Content-Length: " + data.length + "\r\n" +
                                        "Connection: close\r\n\r\n").getBytes());
                                out.write(data);
                                out.flush();
                                Proxy.log("📤 [PPnix] M3U8 已提供: " + id);
                            } else {
                                client.getOutputStream().write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                            }
                        } else {
                            client.getOutputStream().write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                        }
                    } catch (Exception e) {
                        Proxy.log("🚨 [PPnix] 本地服务器请求异常: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                Proxy.log("🚨 [PPnix] 本地服务器启动失败: " + e.getMessage());
                serverStarted = false;
            }
        }).start();
    }

    /** 注册 M3U8 内容，返回本地访问 URL */
    private String registerM3u8(String content) {
        if (!serverStarted || localPort == 0) {
            startLocalHttpServer();
            if (localPort == 0) return null;
        }
        String id = System.currentTimeMillis() + "_" + new Random().nextInt(10000);
        m3u8Cache.put(id, content);
        // 清理旧缓存，保留最近 50 个
        if (m3u8Cache.size() > 50) {
            String first = m3u8Cache.keySet().iterator().next();
            m3u8Cache.remove(first);
        }
        return "http://127.0.0.1:" + localPort + "/m3u8/" + id;
    }

    /** 下载 M3U8 并替换 TS 域名 */
    private String downloadAndReplaceTsDomain(String m3u8Url, String referer) {
        try {
            Map<String, String> headers = getHeader();
            headers.put("Referer", referer);
            String content = OkHttp.string(m3u8Url, headers);
            if (TextUtils.isEmpty(content)) {
                Proxy.log("⚠️ [PPnix] M3U8 下载失败: " + m3u8Url);
                return null;
            }

            String[] lines = content.split("\n");
            StringBuilder modified = new StringBuilder();
            Random random = new Random();
            int replaceCount = 0;

            for (String line : lines) {
                if (line.startsWith("#")) {
                    modified.append(line).append("\n");
                } else if (line.trim().startsWith("http")) {
                    String newLine = line;
                    if (line.contains("ipfs.ppnix.com")) {
                        int num = random.nextInt(16) + 1;
                        newLine = line.replace("ipfs.ppnix.com", num + ".ppnix.com");
                        replaceCount++;
                    }
                    modified.append(newLine).append("\n");
                } else {
                    modified.append(line).append("\n");
                }
            }

            Proxy.log("✅ [PPnix] TS 域名替换完成，共 " + replaceCount + " 处");
            return modified.toString();
        } catch (Exception e) {
            Proxy.log("🚨 [PPnix] 处理 M3U8 异常: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void init(Context context, String extend) {
        startLocalHttpServer();
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("movie", "电影"));
        classes.add(new Class("tv", "电视剧"));
        return Result.string(classes, new ArrayList<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int pageIndex = Integer.parseInt(pg) - 1;
            String url = String.format("%s/cn/%s/---%d-.html", host, tid, pageIndex);

            String html = OkHttp.string(url, getHeader());
            Document doc = Jsoup.parse(html);
            Elements items = doc.select(".lists-content ul li");

            List<Vod> list = new ArrayList<>();
            for (Element li : items) {
                Element thumbA = li.selectFirst("a.thumbnail");
                if (thumbA == null) continue;

                String detailHref = thumbA.attr("href");
                if (detailHref.isEmpty()) continue;
                if (!detailHref.startsWith("/")) detailHref = "/" + detailHref;

                Element img = thumbA.selectFirst("img");
                String pic = "";
                if (img != null) {
                    pic = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
                }

                Element yearSpan = li.selectFirst(".countrie .orange");
                String remarks = yearSpan != null ? yearSpan.text().trim() : "";

                Element titleA = li.selectFirst("h2 a");
                String name = titleA != null ? titleA.text().trim() : "";

                Vod vod = new Vod();
                vod.setVodId(detailHref);
                vod.setVodName(name);
                vod.setVodPic(pic);
                vod.setVodRemarks(remarks);
                list.add(vod);
            }
            return Result.string(list);
        } catch (Exception e) {
            Proxy.log("🚨 [PPnix] 分类异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String url = ids.get(0).startsWith("http") ? ids.get(0) : host + ids.get(0);
            String html = OkHttp.string(url, getHeader());
            Document doc = Jsoup.parse(html);

            String name = "", year = "", pic = "";
            String director = "", actor = "", area = "", content = "";

            Element titleElem = doc.selectFirst("h1.product-title");
            if (titleElem != null) {
                String fullText = titleElem.text().trim();
                Matcher m = Pattern.compile("(.+?)\\s*\\((\\d{4})\\)").matcher(fullText);
                if (m.find()) {
                    name = m.group(1).trim();
                    year = m.group(2);
                } else {
                    name = fullText;
                }
            }

            Element picElem = doc.selectFirst(".product-header img.thumb");
            if (picElem != null) {
                pic = picElem.attr("src");
                if (pic.startsWith("/")) pic = host + pic;
            }

            Element dirElem = doc.selectFirst(".product-excerpt:contains(导演) span");
            if (dirElem != null) director = extractTextFromLinks(dirElem);

            Element actElem = doc.selectFirst(".product-excerpt:contains(主演) span");
            if (actElem != null) actor = extractTextFromLinks(actElem);

            Element areaElem = doc.selectFirst(".product-excerpt:contains(国家) span");
            if (areaElem != null) area = extractTextFromLinks(areaElem);

            Element descElem = doc.selectFirst(".product-excerpt:contains(简介) span");
            if (descElem != null) content = descElem.text().trim();

            String scriptText = "";
            for (Element script : doc.select("script")) {
                String data = script.data();
                if (data.contains("infoid") && data.contains("m3u8")) {
                    scriptText = data;
                    break;
                }
            }

            String infoid = "";
            List<String> playUrls = new ArrayList<>();

            if (!scriptText.isEmpty()) {
                Matcher infoMatch = Pattern.compile("infoid\\s*=\\s*(\\d+)").matcher(scriptText);
                if (infoMatch.find()) infoid = infoMatch.group(1);

                Matcher m3u8Match = Pattern.compile("m3u8\\s*=\\s*\\[(.*?)\\]").matcher(scriptText);
                if (m3u8Match.find()) {
                    String arrayContent = m3u8Match.group(1);
                    Matcher epMatch = Pattern.compile("['\"]?(\\d+)['\"]?").matcher(arrayContent);
                    while (epMatch.find()) {
                        String ep = epMatch.group(1);
                        playUrls.add("第" + ep + "集$" + "/info/m3u8/" + infoid + "/" + ep + ".m3u8");
                    }
                }
            }

            Vod vod = new Vod();
            vod.setVodId(ids.get(0));
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodYear(year);
            vod.setVodArea(area);
            vod.setVodDirector(director);
            vod.setVodActor(actor);
            vod.setVodContent(content);
            vod.setVodRemarks(year.isEmpty() ? "" : year + "年");

            if (!playUrls.isEmpty()) {
                vod.setVodPlayFrom("PPnix");
                vod.setVodPlayUrl(TextUtils.join("#", playUrls));
            }

            return Result.string(vod);
        } catch (Exception e) {
            Proxy.log("🚨 [PPnix] 详情异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    private String extractTextFromLinks(Element parent) {
        List<String> list = new ArrayList<>();
        for (Element a : parent.select("a")) {
            list.add(a.text().trim());
        }
        return TextUtils.join(", ", list);
    }

    // ──────────────────────────────────────────────
    // 播放核心：下载 M3U8 → 替换 TS 域名 → 本地代理转发
    // ──────────────────────────────────────────────

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String originalUrl = id.startsWith("http") ? id : host + id;

            // 构建 Referer
            String referer = host + "/";
            Matcher match = Pattern.compile("/info/m3u8/(\\d+)/").matcher(id);
            if (match.find()) {
                String infoid = match.group(1);
                referer = host + "/cn/tv/" + infoid + ".html";
            }

            Proxy.log("▶️ [PPnix] 原始 M3U8: " + originalUrl);
            Proxy.log("🔗 [PPnix] Referer: " + referer);

            // 1. 下载 M3U8 内容并替换 TS 域名
            String modifiedContent = downloadAndReplaceTsDomain(originalUrl, referer);
            String finalUrl;

            if (modifiedContent != null) {
                // 2. 通过本地代理提供修改后的 M3U8
                finalUrl = registerM3u8(modifiedContent);
                if (finalUrl != null) {
                    Proxy.log("✅ [PPnix] 本地代理: " + finalUrl);
                } else {
                    finalUrl = originalUrl;
                    Proxy.log("⚠️ [PPnix] 本地代理失败，回退原始 URL");
                }
            } else {
                finalUrl = originalUrl;
                Proxy.log("⚠️ [PPnix] M3U8 处理失败，使用原始 URL");
            }

            // 3. 构造请求头（只保留 UA、Origin、Referer）
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", common_ua);
            headers.put("Referer", referer);
            headers.put("Origin", host);

            // 4. 使用 FongMi 规范的 Result 返回
            return Result.get().url(finalUrl).header(headers).string();

        } catch (Exception e) {
            Proxy.log("🚨 [PPnix] 播放异常: " + e.getMessage());
            return Result.get().url(id).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        // 可自行实现搜索功能
        return Result.string(new ArrayList<>());
    }
}
