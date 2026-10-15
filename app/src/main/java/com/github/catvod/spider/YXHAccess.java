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
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
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
            return Result.string(classes);
        }

        // 筛选
        Map<String, List<Filter>> filters = new HashMap<>();

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
            if (pg != null && !"1".equals(pg) && !TextUtils.isEmpty(pg)) {
                url = url + "/" + pg;
            }
        }

        String html = OkHttp.string(url, getHeader());
        if (TextUtils.isEmpty(html)) {
            return Result.string(new ArrayList<>());
        }

        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".thumb-list .thumb-list__item, .video-thumb, .thumb");

        List<Vod> list = new ArrayList<>();
        for (Element item : items) {
            Element a = item.selectFirst("a");
            if (a == null) continue;

            String href = a.attr("href");
            String vid = extractVid(href);
            if (TextUtils.isEmpty(vid)) continue;

            String name = a.attr("title");
            if (TextUtils.isEmpty(name)) {
                Element titleEl = item.selectFirst(".video-thumb-info__name, .thumb-image-container__title");
                if (titleEl != null) {
                    name = titleEl.text().trim();
                }
            }
            if (TextUtils.isEmpty(name)) {
                name = a.text().trim();
            }

            String pic = "";
            Element img = item.selectFirst("img");
            if (img != null) {
                pic = img.attr("data-src");
                if (TextUtils.isEmpty(pic)) {
                    pic = img.attr("src");
                }
                if (!TextUtils.isEmpty(pic) && !pic.startsWith("http")) {
                    pic = host + pic;
                }
            }

            String remark = "";
            Element duration = item.selectFirst(".duration, .thumb-image-container__duration");
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
        Matcher m = Pattern.compile("/(?:videos|movies)/([^/?#]+)").matcher(href);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) {
            return Result.string(new Vod());
        }
        String vid = ids.get(0);
        String url = host + "/videos/" + vid;

        String html = OkHttp.string(url, getHeader());
        if (TextUtils.isEmpty(html)) {
            return Result.string(new Vod());
        }

        Document doc = Jsoup.parse(html);

        String name = "";
        Element h1 = doc.selectFirst("h1");
        if (h1 != null) {
            name = h1.text().trim();
        }

        String pic = "";
        Element img = doc.selectFirst(".player-container img, .video-page img");
        if (img != null) {
            pic = img.attr("src");
            if (TextUtils.isEmpty(pic)) {
                pic = img.attr("data-src");
            }
            if (!TextUtils.isEmpty(pic) && !pic.startsWith("http")) {
                pic = host + pic;
            }
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
        if (TextUtils.isEmpty(html)) {
            return Result.string(new ArrayList<>());
        }

        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".thumb-list .thumb-list__item, .video-thumb, .thumb");

        List<Vod> list = new ArrayList<>();
        for (Element item : items) {
            Element a = item.selectFirst("a");
            if (a == null) continue;

            String vid = extractVid(a.attr("href"));
            if (TextUtils.isEmpty(vid)) continue;

            String name = a.attr("title");
            if (TextUtils.isEmpty(name)) {
                Element titleEl = item.selectFirst(".video-thumb-info__name");
                if (titleEl != null) {
                    name = titleEl.text().trim();
                }
            }

            String pic = "";
            Element img = item.selectFirst("img");
            if (img != null) {
                pic = img.attr("data-src");
                if (TextUtils.isEmpty(pic)) {
                    pic = img.attr("src");
                }
                if (!TextUtils.isEmpty(pic) && !pic.startsWith("http")) {
                    pic = host + pic;
                }
            }

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
                // 优先提取 preload 中的真实 m3u8
                String realUrl = extractRegex(html, "<link[^>]+rel=[\"']preload[\"'][^>]+href=[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']");

                // 兜底匹配
                if (TextUtils.isEmpty(realUrl)) {
                    realUrl = extractRegex(html, "(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)");
                }

                if (!TextUtils.isEmpty(realUrl)) {
                    realUrl = realUrl.replace("\\/", "/").trim();
                    return Result.get().url(realUrl).string();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 提取失败，降级嗅探
        return Result.get().url(id).parse(1).string();
    }

    private String extractRegex(String text, String regex) {
        if (TextUtils.isEmpty(text)) return "";
        Matcher m = Pattern.compile(regex).matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }
}
