package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.net.OkHttp;

import android.text.TextUtils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PPnix extends Spider {

    private String host = "https://www.ppnix.com";
    private String commonUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private Map<String, String> baseHeaders;

    private boolean unlocked = false; // 密码是否验证通过

    @Override
    public void init(android.content.Context context, String extend) throws Exception {
        baseHeaders = new HashMap<>();
        baseHeaders.put("User-Agent", commonUa);
        baseHeaders.put("Referer", host + "/");
        // 注意：不设置 Accept-Encoding，避免 OkHttp 自动解压失效

        unlocked = PasswordGate.ensureUnlocked(context);
        if (!unlocked) {
            throw new Exception("密码验证未通过，拒绝加载该源");
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        if (!unlocked) return Result.get().classes(new ArrayList<>()).string();

        List<Class> classes = new ArrayList<>();
        classes.add(new Class("movie", "电影"));
        classes.add(new Class("tv", "电视剧"));
        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        if (!unlocked) {
            int page = Integer.parseInt(pg);
            return Result.get().vod(new ArrayList<>()).page(page, page, 0, 0).string();
        }

        int page = Integer.parseInt(pg);
        int pageIndex = page - 1;
        String url = host + "/cn/" + tid + "/---" + pageIndex + "-.html";

        Map<String, String> headers = new HashMap<>(baseHeaders);
        String html = OkHttp.string(url, headers);

        if (TextUtils.isEmpty(html)) {
            return Result.get().vod(new ArrayList<>()).page(page, page, 0, 0).string();
        }

        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".lists-content ul li");
        List<Vod> videos = new ArrayList<>();

        for (Element li : items) {
            Element thumbA = li.selectFirst("a.thumbnail");
            if (thumbA == null) continue;
            String detailHref = thumbA.attr("href");
            if (TextUtils.isEmpty(detailHref)) continue;
            if (!detailHref.startsWith("/")) detailHref = "/" + detailHref;
            String vodId = detailHref;

            Element img = thumbA.selectFirst("img");
            String pic = "";
            if (img != null) {
                pic = img.attr("src");
                if (TextUtils.isEmpty(pic)) pic = img.attr("data-src");
            }

            Element yearSpan = li.selectFirst(".countrie .orange");
            String remarks = yearSpan != null ? yearSpan.text().trim() : "";

            Element titleA = li.selectFirst("h2 a");
            String name = titleA != null ? titleA.text().trim() : "";

            if (!TextUtils.isEmpty(name)) {
                Vod vod = new Vod();
                vod.setVodId(vodId);
                vod.setVodName(name);
                vod.setVodPic(pic);
                vod.setVodRemarks(remarks);
                videos.add(vod);
            }
        }

        int count = videos.size() > 0 ? page + 1 : page;
        int limit = videos.size();
        int total = 0;

        return Result.get()
                .vod(videos)
                .page(page, count, limit, total)
                .string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (!unlocked) return Result.error("密码验证未通过");

        if (ids == null || ids.isEmpty()) return Result.error("id 为空");
        String id = ids.get(0);
        String url = id.startsWith("http") ? id : host + id;

        Map<String, String> headers = new HashMap<>(baseHeaders);
        String html = OkHttp.string(url, headers);
        if (TextUtils.isEmpty(html)) {
            return Result.error("请求详情失败");
        }

        Document doc = Jsoup.parse(html);

        // 标题与年份
        Element titleElem = doc.selectFirst("h1.product-title");
        String name = "";
        String year = "";
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
        String pic = "";
        if (picElem != null) {
            pic = picElem.attr("src");
            if (pic.startsWith("/")) pic = host + pic;
        }

        // 导演、演员、地区、简介
        String director = "";
        String actor = "";
        String area = "";
        String content = "";
        Elements excerpts = doc.select(".product-excerpt");
        for (Element ex : excerpts) {
            String exText = ex.text();
            Element span = ex.selectFirst("span");
            if (span == null) continue;
            if (exText.contains("导演")) {
                Elements links = span.select("a");
                List<String> names = new ArrayList<>();
                for (Element a : links) names.add(a.text());
                director = TextUtils.join(", ", names);
            } else if (exText.contains("主演")) {
                Elements links = span.select("a");
                List<String> names = new ArrayList<>();
                for (Element a : links) names.add(a.text());
                actor = TextUtils.join(", ", names);
            } else if (exText.contains("国家")) {
                Elements links = span.select("a");
                List<String> names = new ArrayList<>();
                for (Element a : links) names.add(a.text());
                area = TextUtils.join(", ", names);
            } else if (exText.contains("简介")) {
                content = span.text().trim();
            }
        }

        // 从 script 提取 infoid 和 m3u8 数组
        String infoid = null;
        List<String> episodeNumbers = new ArrayList<>();
        for (Element script : doc.select("script")) {
            String js = script.html();
            if (js.contains("infoid") && js.contains("m3u8")) {
                Matcher infoidMatcher = Pattern.compile("infoid\\s*=\\s*(\\d+)").matcher(js);
                if (infoidMatcher.find()) {
                    infoid = infoidMatcher.group(1);
                }
                Matcher m3u8Matcher = Pattern.compile("m3u8\\s*=\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(js);
                if (m3u8Matcher.find()) {
                    String arrayContent = m3u8Matcher.group(1);
                    Matcher epMatcher = Pattern.compile("['\"]?(\\d+)['\"]?").matcher(arrayContent);
                    while (epMatcher.find()) {
                        episodeNumbers.add(epMatcher.group(1));
                    }
                }
                break;
            }
        }

        String vodPlayFrom = "";
        String vodPlayUrl = "";
        if (infoid != null && !episodeNumbers.isEmpty()) {
            List<String> urls = new ArrayList<>();
            for (String ep : episodeNumbers) {
                urls.add("/info/m3u8/" + infoid + "/" + ep + ".m3u8");
            }
            vodPlayFrom = "PPnix";
            vodPlayUrl = TextUtils.join("#", urls);
        }

        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setVodContent(content);
        vod.setVodPlayFrom(vodPlayFrom);
        vod.setVodPlayUrl(vodPlayUrl);
        vod.setVodDirector(director);
        vod.setVodActor(actor);
        vod.setVodArea(area);
        vod.setVodYear(year);
        vod.setVodRemarks(year.isEmpty() ? "" : year + "年");

        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        if (!unlocked) return Result.get().vod(new ArrayList<>()).page(1, 1, 0, 0).string();

        // 未实现搜索，返回空列表
        return Result.get()
                .vod(new ArrayList<>())
                .page(1, 1, 0, 0)
                .string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String m3u8Url = id.startsWith("http") ? id : host + id;

        // 构造更合理的 Referer
        String referer = host + "/";
        String origin = host;
        Matcher m = Pattern.compile("/info/m3u8/(\\d+)/").matcher(id);
        if (m.find()) {
            String infoid = m.group(1);
            referer = host + "/cn/tv/" + infoid + ".html";
        }

        // 实时预请求 key（防止密钥变更，同时让 key 进入缓存）
        try {
            Map<String, String> keyHeaders = new HashMap<>(baseHeaders);
            keyHeaders.put("Referer", host + "/");
            keyHeaders.put("Origin", host);
            OkHttp.string(host + "/info/m3u8/key", keyHeaders);
        } catch (Exception e) {
            // 忽略，不影响主流程
        }

        // 构造请求头
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", commonUa);
        headers.put("Referer", referer);
        headers.put("Origin", origin);
        headers.put("Accept", "*/*");

        return Result.get()
                .url(m3u8Url)
                .header(headers)
                .string();
    }
}
