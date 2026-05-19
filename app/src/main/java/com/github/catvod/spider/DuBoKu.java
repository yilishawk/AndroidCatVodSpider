package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;

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

public class DuBoKu extends Spider {

    private static final String HOST = "https://www.dbku.tv";

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", Util.CHROME);
        headers.put("Referer", HOST + "/");
        return headers;
    }

    private String fetch(String url) {
        try {
            return OkHttp.string(url, getHeaders());
        } catch (Exception e) {
            SpiderDebug.log("[DuBoKu] fetch error: " + e.getMessage());
            return "";
        }
    }

    private String encode(String s) throws Exception {
        return s == null || s.isEmpty() ? "" : URLEncoder.encode(s, "UTF-8");
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("13", "陆剧"));
        classes.add(new Class("2", "电视剧"));
        classes.add(new Class("1", "电影"));
        classes.add(new Class("3", "综艺"));
        classes.add(new Class("4", "动漫"));
        classes.add(new Class("15", "日韩剧"));
        classes.add(new Class("21", "短剧"));
        classes.add(new Class("14", "台泰剧"));
        classes.add(new Class("20", "港剧"));

        if (!filter) {
            return Result.string(classes, new LinkedHashMap<>());
        }

        // 电视剧筛选器
        List<Filter.Value> classValues = new ArrayList<>();
        String[][] classOpts = {
            {"全部", ""}, {"悬疑", "悬疑"}, {"武侠", "武侠"}, {"科幻", "科幻"},
            {"都市", "都市"}, {"爱情", "爱情"}, {"古装", "古装"}, {"战争", "战争"},
            {"青春", "青春"}, {"偶像", "偶像"}, {"喜剧", "喜剧"}, {"家庭", "家庭"},
            {"奇幻", "奇幻"}, {"剧情", "剧情"}, {"乡村", "乡村"}, {"年代", "年代"},
            {"警匪", "警匪"}, {"谍战", "谍战"}, {"历险", "历险"}, {"罪案", "罪案"},
            {"宫廷", "宫廷"}, {"经典", "经典"}, {"动作", "动作"}, {"惊悚", "惊悚"},
            {"历史", "历史"}, {"穿越", "穿越"}, {"同性", "同性"}
        };
        for (String[] opt : classOpts) classValues.add(new Filter.Value(opt[0], opt[1]));

        List<Filter.Value> areaValues = new ArrayList<>();
        String[][] areaOpts = {
            {"全部", ""}, {"大陆", "大陆"}, {"香港", "香港"}, {"台湾", "台湾"},
            {"韩国", "韩国"}, {"日本", "日本"}, {"新加坡", "新加坡"}, {"泰国", "泰国"}
        };
        for (String[] opt : areaOpts) areaValues.add(new Filter.Value(opt[0], opt[1]));

        List<Filter.Value> yearValues = new ArrayList<>();
        String[][] yearOpts = {
            {"全部", ""}, {"2026", "2026"}, {"2025", "2025"}, {"2024", "2024"},
            {"2023", "2023"}, {"2022", "2022"}, {"2021", "2021"}, {"2020", "2020"},
            {"2019", "2019"}, {"2018", "2018"}, {"2017", "2017"}, {"更早", "更早"}
        };
        for (String[] opt : yearOpts) yearValues.add(new Filter.Value(opt[0], opt[1]));

        List<Filter.Value> langValues = new ArrayList<>();
        String[][] langOpts = {
            {"全部", ""}, {"国语", "国语"}, {"粤语", "粤语"}, {"韩语", "韩语"},
            {"泰语", "泰语"}, {"日语", "日语"}
        };
        for (String[] opt : langOpts) langValues.add(new Filter.Value(opt[0], opt[1]));

        List<Filter.Value> sortValues = new ArrayList<>();
        sortValues.add(new Filter.Value("时间", "time"));
        sortValues.add(new Filter.Value("人气", "hits"));
        sortValues.add(new Filter.Value("评分", "score"));

        List<Filter> tvFilter = new ArrayList<>();
        tvFilter.add(new Filter("class", "剧情", classValues));
        tvFilter.add(new Filter("area", "地区", areaValues));
        tvFilter.add(new Filter("year", "年份", yearValues));
        tvFilter.add(new Filter("lang", "语言", langValues));
        tvFilter.add(new Filter("by", "排序", sortValues));

        // 电影筛选器
        List<Filter.Value> movieClassValues = new ArrayList<>();
        String[][] movieClassOpts = {
            {"全部", ""}, {"喜剧", "喜剧"}, {"爱情", "爱情"}, {"恐怖", "恐怖"},
            {"动作", "动作"}, {"科幻", "科幻"}, {"剧情", "剧情"}, {"警匪", "警匪"},
            {"战争", "战争"}, {"犯罪", "犯罪"}, {"动画", "动画"}, {"奇幻", "奇幻"},
            {"武侠", "武侠"}, {"冒险", "冒险"}, {"悬疑", "悬疑"}, {"惊悚", "惊悚"},
            {"古装", "古装"}, {"同性", "同性"}
        };
        for (String[] opt : movieClassOpts) movieClassValues.add(new Filter.Value(opt[0], opt[1]));

        List<Filter.Value> movieAreaValues = new ArrayList<>();
        String[][] movieAreaOpts = {
            {"全部", ""}, {"大陆", "大陆"}, {"香港", "香港"}, {"台湾", "台湾"},
            {"韩国", "韩国"}, {"英国", "英国"}, {"法国", "法国"}, {"加拿大", "加拿大"},
            {"澳大利亚", "澳大利亚"}
        };
        for (String[] opt : movieAreaOpts) movieAreaValues.add(new Filter.Value(opt[0], opt[1]));

        List<Filter.Value> movieLangValues = new ArrayList<>();
        String[][] movieLangOpts = {
            {"全部", ""}, {"国语", "国语"}, {"粤语", "粤语"}, {"韩语", "韩语"},
            {"英语", "英语"}, {"法语", "法语"}
        };
        for (String[] opt : movieLangOpts) movieLangValues.add(new Filter.Value(opt[0], opt[1]));

        List<Filter> movieFilter = new ArrayList<>();
        movieFilter.add(new Filter("class", "剧情", movieClassValues));
        movieFilter.add(new Filter("area", "地区", movieAreaValues));
        movieFilter.add(new Filter("year", "年份", yearValues));
        movieFilter.add(new Filter("lang", "语言", movieLangValues));
        movieFilter.add(new Filter("by", "排序", sortValues));

        // 动漫筛选器（只有全部）
        List<Filter.Value> animeClassValues = new ArrayList<>();
        animeClassValues.add(new Filter.Value("全部", ""));
        List<Filter> animeFilter = new ArrayList<>();
        animeFilter.add(new Filter("class", "类型", animeClassValues));

        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        filters.put("1", movieFilter);
        filters.put("4", animeFilter);
        for (String tid : new String[]{"2", "3", "13", "14", "15", "20", "21"}) {
            filters.put(tid, tvFilter);
        }

        return Result.string(classes, filters);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            if (extend == null) extend = new HashMap<>();
            String area = extend.containsKey("area") ? extend.get("area") : "";
            String by = extend.containsKey("by") ? extend.get("by") : "time";
            String class_ = extend.containsKey("class") ? extend.get("class") : "";
            String lang = extend.containsKey("lang") ? extend.get("lang") : "";
            String year = extend.containsKey("year") ? extend.get("year") : "";

            String url = HOST + "/vodshow/" + tid + "-" + encode(area) + "-" + by + "-" +
                         encode(class_) + "-" + encode(lang) + "----" + pg + "---" + year + ".html";

            SpiderDebug.log("[DuBoKu] category URL: " + url);
            String html = fetch(url);
            if (html.isEmpty()) return Result.error("请求失败");

            Document doc = Jsoup.parse(html);
            Elements items = doc.select("ul.myui-vodlist li");
            if (items.isEmpty()) items = doc.select("li .myui-vodlist__thumb").parents();

            List<Vod> list = new ArrayList<>();
            for (Element item : items) {
                Element a = item.selectFirst("a.myui-vodlist__thumb");
                if (a == null) a = item.selectFirst("a[data-original]");
                if (a == null) continue;

                String href = a.attr("href");
                String vid = href.startsWith("/") ? href : "/" + href;
                String name = a.attr("title");
                String pic = a.attr("data-original");
                Element picText = item.selectFirst(".pic-text");
                String remarks = picText != null ? picText.text().trim() : "";

                list.add(new Vod(vid, name, pic, remarks));
            }

            int page = Integer.parseInt(pg);
            return Result.string(page, page + 5, list.size(), 1000, list);
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.error(e.getMessage());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return Result.error("No id");
            String vodId = ids.get(0);
            String url = vodId.startsWith("http") ? vodId : HOST + vodId;
            String html = fetch(url);
            if (html.isEmpty()) return Result.error("请求失败");

            Document doc = Jsoup.parse(html);
            Element detail = doc.selectFirst(".myui-content__detail");
            if (detail == null) return Result.error("未找到详情");

            Vod vod = new Vod();
            vod.setVodId(vodId);

            Element titleElem = detail.selectFirst("h1.title");
            if (titleElem != null) vod.setVodName(titleElem.text().trim());

            Element img = doc.selectFirst(".myui-content__thumb img");
            if (img != null) {
                String pic = img.attr("data-original");
                vod.setVodPic(pic);
            }

            Element sketch = doc.selectFirst(".sketch.content");
            if (sketch != null) vod.setVodContent(sketch.text().trim());

            for (Element p : detail.select("p.data")) {
                String text = p.text().trim();
                if (text.contains("导演：")) {
                    vod.setVodDirector(text.replace("导演：", "").trim());
                } else if (text.contains("主演：")) {
                    StringBuilder sb = new StringBuilder();
                    for (Element a : p.select("a")) {
                        if (sb.length() > 0) sb.append(" / ");
                        sb.append(a.text().trim());
                    }
                    vod.setVodActor(sb.toString());
                } else if (text.contains("分类：")) {
                    Elements aTags = p.select("a");
                    if (aTags.size() > 0) vod.setTypeName(aTags.get(0).text().trim());
                    if (aTags.size() > 1) vod.setVodArea(aTags.get(1).text().trim());
                    if (aTags.size() > 2) vod.setVodYear(aTags.get(2).text().trim());
                } else if (text.contains("更新：")) {
                    vod.setVodRemarks(text.replace("更新：", "").trim());
                }
            }

            // 播放列表
            List<String> playFromList = new ArrayList<>();
            List<String> playUrlList = new ArrayList<>();
            Elements playList = doc.select(".myui-content__list li a");
            if (playList.isEmpty()) playList = doc.select(".tab-content .myui-content__list li a");

            if (!playList.isEmpty()) {
                playFromList.add("独播库");
                List<String> episodes = new ArrayList<>();
                for (Element a : playList) {
                    String href = a.attr("href");
                    if (href.startsWith("/")) href = HOST + href;
                    episodes.add(a.text().trim() + "$" + href);
                }
                playUrlList.add(String.join("#", episodes));
                vod.setVodPlayFrom(String.join("$$$", playFromList));
                vod.setVodPlayUrl(String.join("$$$", playUrlList));
            }

            return Result.string(vod);
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.error(e.getMessage());
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = HOST + "/vodsearch/-------------.html?wd=" + encode(key);
            String html = fetch(url);
            if (html.isEmpty()) return Result.error("搜索失败");

            Document doc = Jsoup.parse(html);
            List<Vod> list = new ArrayList<>();
            for (Element li : doc.select("#searchList li")) {
                Element a = li.selectFirst("a.myui-vodlist__thumb");
                if (a == null) continue;
                String href = a.attr("href");
                String vid = href.startsWith("/") ? href : "/" + href;
                String name = a.attr("title");
                String pic = a.attr("data-original");
                Element tag = a.selectFirst(".tag");
                String remarks = tag != null ? tag.text().trim() : "";
                list.add(new Vod(vid, name, pic, remarks));
            }
            return Result.string(list);
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.error(e.getMessage());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String url = id.startsWith("http") ? id : HOST + id;
        try {
            String html = fetch(url);
            if (html.isEmpty()) return Result.get().parse(1).url(url).string();

            // 提取 iframe
            Pattern iframePattern = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']");
            Matcher iframeMatcher = iframePattern.matcher(html);
            if (iframeMatcher.find()) {
                String jxUrl = iframeMatcher.group(1);
                if (jxUrl.startsWith("//")) jxUrl = "https:" + jxUrl;
                if (!jxUrl.startsWith("http")) jxUrl = HOST + jxUrl;
                Map<String, String> header = new HashMap<>();
                header.put("Referer", HOST + "/");
                return Result.get().parse(1).url(jxUrl).header(header).string();
            }

            // 提取 video 标签
            Pattern videoPattern = Pattern.compile("<video[^>]+src=[\"']([^\"']+)[\"']");
            Matcher videoMatcher = videoPattern.matcher(html);
            if (videoMatcher.find()) {
                String videoUrl = videoMatcher.group(1);
                if (videoUrl.startsWith("//")) videoUrl = "https:" + videoUrl;
                Map<String, String> header = new HashMap<>();
                header.put("Referer", url);
                return Result.get().url(videoUrl).header(header).string();
            }

            // 提取 m3u8
            Pattern m3u8Pattern = Pattern.compile("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*");
            Matcher m3u8Matcher = m3u8Pattern.matcher(html);
            if (m3u8Matcher.find()) {
                Map<String, String> header = new HashMap<>();
                header.put("Referer", url);
                return Result.get().url(m3u8Matcher.group()).header(header).string();
            }
        } catch (Exception e) {
            SpiderDebug.log("[DuBoKu] player error: " + e.getMessage());
        }
        return Result.get().parse(1).url(url).string();
    }
}
