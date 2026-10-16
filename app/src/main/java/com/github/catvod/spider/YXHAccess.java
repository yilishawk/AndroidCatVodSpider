package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YXHAccess extends Spider {

    private final String host = "https://zh.xhaccess.com";
    private final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private Map<String, String> getHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", host + "/");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        headers.put("Cookie", "age_confirmed=1; ageVerified=1; xh_age_verified=1; lang=zh");
        return headers;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        OkHttp.string(host + "/", getHeader());
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("newest", "最新"));
        classes.add(new Class("best", "最佳"));
        classes.add(new Class("hd", "高清"));
        classes.add(new Class("4k", "4K"));
        classes.add(new Class("vr", "VR"));
        classes.add(new Class("categories", "分类"));

        if (!filter) {
            return Result.get().classes(classes).string();
        }

        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();

        List<Filter.Value> sortValues = new ArrayList<>();
        sortValues.add(new Filter.Value("最新", "newest"));
        sortValues.add(new Filter.Value("最佳", "best"));
        sortValues.add(new Filter.Value("最多观看", "most-viewed"));
        sortValues.add(new Filter.Value("最多评论", "most-commented"));
        List<Filter> commonFilter = new ArrayList<>();
        commonFilter.add(new Filter("sort", "排序", sortValues));

        filters.put("newest", commonFilter);
        filters.put("best", commonFilter);
        filters.put("hd", commonFilter);
        filters.put("4k", commonFilter);
        filters.put("vr", commonFilter);

        List<Filter.Value> cateValues = new ArrayList<>();
        cateValues.add(new Filter.Value("全部", ""));
        cateValues.add(new Filter.Value("亚洲", "asian"));
        cateValues.add(new Filter.Value("日本", "japanese"));
        cateValues.add(new Filter.Value("中国", "chinese"));
        cateValues.add(new Filter.Value("韩国", "korean"));
        cateValues.add(new Filter.Value("欧美", "western"));
        cateValues.add(new Filter.Value("女优", "pornstar"));
        cateValues.add(new Filter.Value("素人", "amateur"));
        cateValues.add(new Filter.Value("动画", "hentai"));
        cateValues.add(new Filter.Value("VR", "vr"));
        List<Filter> cateFilter = new ArrayList<>();
        cateFilter.add(new Filter("cate", "分类", cateValues));
        filters.put("categories", cateFilter);

        return Result.string(classes, filters);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String cate = "";
        if (extend != null && extend.containsKey("cate")) {
            cate = extend.get("cate");
        }

        String url;
        if ("categories".equals(tid)) {
            if (TextUtils.isEmpty(cate)) {
                url = host + "/categories";
            } else {
                url = host + "/categories/" + cate;
            }
        } else {
            url = host + "/" + tid;
        }

        // 分页：第1页不带数字，第2页开始加 /2
        if (pg != null && !"1".equals(pg) && !TextUtils.isEmpty(pg)) {
            url = url + "/" + pg;
        }

        String html = OkHttp.string(url, getHeader());
        if (TextUtils.isEmpty(html)) {
            return Result.string(new ArrayList<>());
        }

        Document doc = Jsoup.parse(html);

        // 根据你提供的真实结构定位
        Elements items = doc.select("div.thumb-list__item.video-thumb, div.video-thumb--type-video");

        List<Vod> list = new ArrayList<>();
        for (Element item : items) {
            Element a = item.selectFirst("a[data-role=thumb-link], a.video-thumb__image-container");
            if (a == null) continue;

            String href = a.attr("href");
            String vid = extractVid(href);
            if (TextUtils.isEmpty(vid)) continue;

            // 标题
            String name = a.attr("aria-label");
            if (TextUtils.isEmpty(name)) {
                name = a.attr("title");
            }
            if (TextUtils.isEmpty(name)) {
                Element titleEl = item.selectFirst("a.video-thumb-info__name, [data-role=thumb-link]");
                if (titleEl != null) {
                    name = titleEl.attr("title");
                    if (TextUtils.isEmpty(name)) name = titleEl.text().trim();
                }
            }

            // 图片
            String pic = "";
            Element img = item.selectFirst("img[data-role=thumb-preview-img], img.thumb-image-container__image");
            if (img != null) {
                pic = img.attr("src");
                if (TextUtils.isEmpty(pic)) pic = img.attr("data-src");
                if (TextUtils.isEmpty(pic)) pic = img.attr("srcset");
            }

            // 时长
            String remark = "";
            Element duration = item.selectFirst("[data-role=video-duration], .thumb-image-container__duration");
            if (duration != null) {
                remark = duration.text().trim();
            }

            Vod vod = new Vod();
            vod.setVodId(vid);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodRemarks(remark);
            list.add(vod);
        }
        return Result.string(list);
    }

    private String extractVid(String href) {
        if (TextUtils.isEmpty(href)) return "";
        // 匹配 /videos/xxx-xhZjE7I 这种格式
        Matcher m = Pattern.compile("/videos/([^/?#]+)").matcher(href);
        return m.find() ? m.group(1) : "";
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.string(new Vod());
        String vid = ids.get(0);
        String url = host + "/videos/" + vid;

        String html = OkHttp.string(url, getHeader());
        if (TextUtils.isEmpty(html)) return Result.string(new Vod());

        Document doc = Jsoup.parse(html);

        String name = "";
        Element h1 = doc.selectFirst("h1");
        if (h1 != null) name = h1.text().trim();

        String pic = "";
        Element img = doc.selectFirst("img[data-role=thumb-preview-img], .player-container img");
        if (img != null) {
            pic = img.attr("src");
            if (TextUtils.isEmpty(pic)) pic = img.attr("data-src");
        }

        Vod vod = new Vod();
        vod.setVodId(vid);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setVodPlayFrom("XHAccess");
        vod.setVodPlayUrl("播放$" + url);
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = host + "/search/" + URLEncoder.encode(key, "UTF-8");
        String html = OkHttp.string(url, getHeader());
        if (TextUtils.isEmpty(html)) return Result.string(new ArrayList<>());

        Document doc = Jsoup.parse(html);
        Elements items = doc.select("div.thumb-list__item.video-thumb, div.video-thumb--type-video");

        List<Vod> list = new ArrayList<>();
        for (Element item : items) {
            Element a = item.selectFirst("a[data-role=thumb-link]");
            if (a == null) continue;

            String vid = extractVid(a.attr("href"));
            if (TextUtils.isEmpty(vid)) continue;

            String name = a.attr("aria-label");
            if (TextUtils.isEmpty(name)) name = a.attr("title");

            String pic = "";
            Element img = item.selectFirst("img[data-role=thumb-preview-img]");
            if (img != null) pic = img.attr("src");

            Vod vod = new Vod();
            vod.setVodId(vid);
            vod.setVodName(name);
            vod.setVodPic(pic);
            list.add(vod);
        }
        return Result.string(list);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        try {
            String html = OkHttp.string(id, getHeader());
            if (!TextUtils.isEmpty(html)) {
                String realUrl = extractRegex(html, "<link[^>]+rel=[\"']preload[\"'][^>]+href=[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']");
                if (TextUtils.isEmpty(realUrl)) {
                    realUrl = extractRegex(html, "(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)");
                }
                if (!TextUtils.isEmpty(realUrl)) {
                    realUrl = realUrl.replace("\\/", "/").trim();
                    return Result.get().url(realUrl).header(getHeader()).string();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Result.get().url(id).parse(1).string();
    }

    private String extractRegex(String text, String regex) {
        if (TextUtils.isEmpty(text)) return "";
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : "";
    }
}
