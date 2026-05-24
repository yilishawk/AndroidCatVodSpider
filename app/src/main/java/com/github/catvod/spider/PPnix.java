package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

// 解决编译报错的关键：导入 Spider 父类
import com.github.catvod.crawler.Spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.net.OkHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PPnix 影视 (ppnix.com)
 */
public class PPnix extends Spider {

    private final String host = "https://www.ppnix.com";
    private final String common_ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private Map<String, String> getHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", common_ua);
        headers.put("Referer", host + "/");
        return headers;
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

                list.add(new Vod(detailHref, name, pic, remarks, true));
            }
            return Result.string(list);
        } catch (Exception e) {
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

            // 标题与年份
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

            // 封面
            Element picElem = doc.selectFirst(".product-header img.thumb");
            if (picElem != null) {
                pic = picElem.attr("src");
                if (pic.startsWith("/")) pic = host + pic;
            }

            // 导演、演员、地区、简介
            Element dirElem = doc.selectFirst(".product-excerpt:contains(导演) span");
            if (dirElem != null) director = extractTextFromLinks(dirElem);

            Element actElem = doc.selectFirst(".product-excerpt:contains(主演) span");
            if (actElem != null) actor = extractTextFromLinks(actElem);

            Element areaElem = doc.selectFirst(".product-excerpt:contains(国家) span");
            if (areaElem != null) area = extractTextFromLinks(areaElem);

            Element descElem = doc.selectFirst(".product-excerpt:contains(简介) span");
            if (descElem != null) content = descElem.text().trim();

            // 从 script 中提取 infoid 和 m3u8 数组
            String scriptText = "";
            for (Element script : doc.select("script")) {
                String data = script.html();
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
                        playUrls.add(ep + "$" + "/info/m3u8/" + infoid + "/" + ep + ".m3u8");
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
            return Result.string(new ArrayList<>());
        }
    }

    // 辅助方法：提取 a 标签中的文本并用逗号拼接
    private String extractTextFromLinks(Element parent) {
        List<String> list = new ArrayList<>();
        for (Element a : parent.select("a")) {
            list.add(a.text().trim());
        }
        return TextUtils.join(", ", list);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String m3u8Url = id.startsWith("http") ? id : host + id;

            // 模拟 Service Worker：将 ipfs.ppnix.com 替换为 1~16 的随机数字子域名
            if (m3u8Url.contains("ipfs.ppnix.com")) {
                int randNum = new Random().nextInt(16) + 1;
                m3u8Url = m3u8Url.replace("ipfs.ppnix.com", randNum + ".ppnix.com");
            }

            // 提取 infoid 构造防盗链 Referer
            String referer = host + "/";
            Matcher match = Pattern.compile("/info/m3u8/(\\d+)/").matcher(id);
            if (match.find()) {
                String infoid = match.group(1);
                referer = host + "/cn/tv/" + infoid + ".html";
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", common_ua);
            headers.put("Referer", referer);
            headers.put("Origin", host);
            headers.put("Accept", "*/*");
            headers.put("Sec-Fetch-Site", "same-origin");
            headers.put("Sec-Fetch-Mode", "cors");
            headers.put("Sec-Fetch-Dest", "empty");
            headers.put("Accept-Encoding", "gzip, deflate, zstd");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9");

            return Result.get().url(m3u8Url).header(headers).string();
        } catch (Exception e) {
            return Result.get().url(id).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return Result.string(new ArrayList<>());
    }
}
