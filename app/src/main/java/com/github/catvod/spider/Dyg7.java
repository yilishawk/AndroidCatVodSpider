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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 电影港 (dyg7.com) Spider 爬虫
 */
public class Dyg7 extends Spider {

    private static final String HOST = "https://www.dyg7.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private void logger(String msg) {
        try {
            com.github.catvod.spider.Proxy.log("[Dyg7] " + msg);
        } catch (Exception e) {
            System.out.println("[Dyg7] " + msg);
        }
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", HOST + "/");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        return headers;
    }

    private String get(String url) {
        try {
            return OkHttp.string(url, getHeaders());
        } catch (Exception e) {
            logger("get error: " + e.getMessage());
            return "";
        }
    }

    private String absUrl(String src) {
        if (TextUtils.isEmpty(src)) return "";
        if (src.startsWith("http")) return src;
        if (src.startsWith("//")) return "https:" + src;
        if (src.startsWith("/")) return HOST + src;
        return HOST + "/" + src;
    }

    @Override
    public void init(Context context, String extend) {
        try {
            super.init(context, extend);
        } catch (Exception ignored) {
        }
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("dy", "电影"));
        classes.add(new Class("dy/bangumi", "动作片"));
        classes.add(new Class("dy/tvplay", "喜剧片"));
        classes.add(new Class("dy/aqp", "爱情片"));
        classes.add(new Class("dy/khp", "科幻片"));
        classes.add(new Class("dy/jqp", "剧情片"));
        classes.add(new Class("dy/kbp", "恐怖片"));
        classes.add(new Class("dy/zzp", "战争片"));
        classes.add(new Class("dy/jlp", "纪录片"));
        classes.add(new Class("dy/donghuapian", "动画片"));
        classes.add(new Class("dsj", "电视剧"));
        classes.add(new Class("dsj/dlj", "国剧"));
        classes.add(new Class("dsj/rhj", "日韩剧"));
        classes.add(new Class("dsj/omj", "欧美剧"));
        classes.add(new Class("duanju", "短剧"));
        classes.add(new Class("dongman", "动漫"));
        classes.add(new Class("zyjm", "综艺"));

        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();

        // 电影大类筛选
        List<Filter.Value> dyValues = new ArrayList<>();
        dyValues.add(new Filter.Value("全部", "dy"));
        dyValues.add(new Filter.Value("动作片", "dy/bangumi"));
        dyValues.add(new Filter.Value("喜剧片", "dy/tvplay"));
        dyValues.add(new Filter.Value("爱情片", "dy/aqp"));
        dyValues.add(new Filter.Value("科幻片", "dy/khp"));
        dyValues.add(new Filter.Value("剧情片", "dy/jqp"));
        dyValues.add(new Filter.Value("恐怖片", "dy/kbp"));
        dyValues.add(new Filter.Value("战争片", "dy/zzp"));
        dyValues.add(new Filter.Value("纪录片", "dy/jlp"));
        dyValues.add(new Filter.Value("动画片", "dy/donghuapian"));

        List<Filter> dyFilters = new ArrayList<>();
        dyFilters.add(new Filter("cate", "类型", dyValues));
        filters.put("dy", dyFilters);

        // 电视剧大类筛选
        List<Filter.Value> dsjValues = new ArrayList<>();
        dsjValues.add(new Filter.Value("全部", "dsj"));
        dsjValues.add(new Filter.Value("国剧", "dsj/dlj"));
        dsjValues.add(new Filter.Value("日韩剧", "dsj/rhj"));
        dsjValues.add(new Filter.Value("欧美剧", "dsj/omj"));

        List<Filter> dsjFilters = new ArrayList<>();
        dsjFilters.add(new Filter("cate", "类型", dsjValues));
        filters.put("dsj", dsjFilters);

        return Result.get().classes(classes).filters(filters).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            // 优先获取筛选传入的子分类 key
            String realTid = tid;
            if (extend != null && extend.containsKey("cate") && !TextUtils.isEmpty(extend.get("cate"))) {
                realTid = extend.get("cate");
            }

            int page = parsePage(pg);
            String url;
            if (page <= 1) {
                url = HOST + "/" + realTid + "/index.html";
            } else {
                url = HOST + "/" + realTid + "/index_" + page + ".html";
            }

            logger("分类页请求: " + url);
            String html = get(url);
            List<Vod> list = parseList(html);
            return vodPageResult(list, page);
        } catch (Exception e) {
            logger("categoryContent 异常: " + e.getMessage());
            return Result.get().vod(new ArrayList<Vod>()).string();
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            String detailUrl = absUrl(id);
            logger("详情页请求: " + detailUrl);
            String html = get(detailUrl);

            if (TextUtils.isEmpty(html)) return Result.get().string();

            Document doc = Jsoup.parse(html);
            Vod vod = new Vod();
            vod.setVodId(id);

            // 1. 提取标题
            String title = "";
            Element nameEl = doc.selectFirst("div.ct-l p.name");
            if (nameEl != null) {
                title = nameEl.text().trim();
            }
            if (TextUtils.isEmpty(title)) {
                Matcher mTitle = Pattern.compile("◎片  名[:：]?\\s*([^<\\n]+)").matcher(html);
                if (mTitle.find()) {
                    title = mTitle.group(1).trim();
                } else {
                    title = doc.title().replace("-电影港", "").trim();
                }
            }
            vod.setVodName(title);

            // 2. 提取封面
            Element imgEl = doc.selectFirst("div.ct-l img");
            if (imgEl != null) {
                vod.setVodPic(absUrl(imgEl.attr("src")));
            }

            // 3. 提取导演
            Matcher mDirector = Pattern.compile("◎导  演[:：]?\\s*([^<\\n]+)").matcher(html);
            if (mDirector.find()) {
                vod.setVodDirector(mDirector.group(1).replaceAll("&nbsp;", " ").trim());
            }

            // 4. 提取主演（处理多行与长名单，自动清理多余空格与英文名）
            Matcher mActor = Pattern.compile("◎主 {1,2}演[:：]?(.*?)(?=◎|&nbsp;|<hr|<div>&nbsp;</div>)", Pattern.DOTALL).matcher(html);
            if (mActor.find()) {
                String rawActors = mActor.group(1).replaceAll("<[^>]+>", "\n");
                String[] actorLines = rawActors.split("\n");
                List<String> cleanActors = new ArrayList<>();
                for (String line : actorLines) {
                    String clean = line.replace("&nbsp;", "").replaceAll("^[\\s\u3000]+|[\\s\u3000]+$", "").trim();
                    if (!TextUtils.isEmpty(clean)) {
                        clean = clean.replaceAll("\\s+[A-Za-z].*", "");
                        cleanActors.add(clean);
                    }
                }
                vod.setVodActor(TextUtils.join(" / ", cleanActors));
            }

            // 5. 提取集数/备注
            Matcher mRemarks = Pattern.compile("◎集 {1,2}数[:：]?\\s*([^<\\n]+)").matcher(html);
            if (mRemarks.find()) {
                vod.setVodRemarks("共" + mRemarks.group(1).trim() + "集");
            } else {
                Matcher mYear = Pattern.compile("◎年  代[:：]?\\s*([^<\\n]+)").matcher(html);
                if (mYear.find()) {
                    vod.setVodRemarks(mYear.group(1).trim());
                }
            }

            // 6. 提取简介
            Matcher mDesc = Pattern.compile("◎简 {1,2}介[\\s\\S]*?</div>([\\s\\S]*?)(?=<hr|<strong>|<p>|<table)", Pattern.CASE_INSENSITIVE).matcher(html);
            if (mDesc.find()) {
                String descText = mDesc.group(1).replaceAll("<[^>]+>", "").replace("&nbsp;", "").trim();
                vod.setVodContent(descText);
            }

            // 7. 解析播放列表（视频播列表优先，磁力最后，舍去云盘）
            List<String> playFromList = new ArrayList<>();
            List<String> playUrlList = new ArrayList<>();

            // A. 解析网页在线视频播放列表
            Elements playBlocks = doc.select("div.tab-down");
            int videoListIdx = 1;
            for (Element block : playBlocks) {
                String tabName = block.select("div.playfrom li").text().trim();
                if (TextUtils.isEmpty(tabName)) {
                    tabName = "视频播放列表" + videoListIdx;
                }

                Elements aNodes = block.select("div.videourl ul li a");
                List<String> urls = new ArrayList<>();
                for (Element a : aNodes) {
                    String epTitle = a.text().trim();
                    String href = a.attr("href");
                    if (!TextUtils.isEmpty(href)) {
                        urls.add(epTitle + "$" + href);
                    }
                }

                if (!urls.isEmpty()) {
                    playFromList.add(tabName);
                    playUrlList.add(TextUtils.join("#", urls));
                    videoListIdx++;
                }
            }

            // B. 解析磁力链接 (magnet:)
            Elements magnetLinks = doc.select("a[href^=magnet:]");
            if (!magnetLinks.isEmpty()) {
                List<String> magnetUrls = new ArrayList<>();
                for (Element a : magnetLinks) {
                    String magnetTitle = a.text().trim();
                    if (TextUtils.isEmpty(magnetTitle)) magnetTitle = "磁力下载";
                    String href = a.attr("href");
                    magnetUrls.add(magnetTitle + "$" + href);
                }
                if (!magnetUrls.isEmpty()) {
                    playFromList.add("磁力下载");
                    playUrlList.add(TextUtils.join("#", magnetUrls));
                }
            }

            vod.setVodPlayFrom(TextUtils.join("$$$", playFromList));
            vod.setVodPlayUrl(TextUtils.join("$$$", playUrlList));

            return Result.get().vod(vod).string();
        } catch (Exception e) {
            logger("detailContent 异常: " + e.getMessage());
            return Result.get().string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        try {
            int page = parsePage(pg);
            String searchUrl = HOST + "/e/search/index.php";

            Map<String, String> params = new HashMap<>();
            params.put("keyboard", key);
            params.put("submit", "");
            params.put("show", "title,zhuyan");
            params.put("tempid", "1");

            Map<String, String> headers = getHeaders();
            headers.put("Origin", HOST);
            headers.put("Content-Type", "application/x-www-form-urlencoded");

            logger("搜索提交关键词: " + key);
            String html = OkHttp.post(searchUrl, params, headers).getBody();
            List<Vod> list = parseList(html);

            return vodPageResult(list, page);
        } catch (Exception e) {
            logger("searchContent 异常: " + e.getMessage());
            return Result.get().vod(new ArrayList<Vod>()).string();
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            // 如果是磁力链接，直接原样推给壳子
            if (id.startsWith("magnet:")) {
                return Result.get().url(id).string();
            }

            String playPageUrl = absUrl(id);
            logger("播放页请求: " + playPageUrl);
            String html = get(playPageUrl);

            String realUrl = "";
            if (!TextUtils.isEmpty(html)) {
                Document doc = Jsoup.parse(html);
                Element iframe = doc.selectFirst("div.video iframe");
                if (iframe != null) {
                    realUrl = iframe.attr("src").trim();
                } else {
                    Matcher mFrame = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
                    if (mFrame.find()) {
                        realUrl = mFrame.group(1).trim();
                    }
                }
            }

            // 1. 如果成功匹配到 iframe src 真实播放链接，直接作为直链返回 (parse = 0)
            if (!TextUtils.isEmpty(realUrl)) {
                logger("提取到真实播放地址: " + realUrl);
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", UA);
                return Result.get().url(realUrl).header(headers).string();
            }

            // 2. 提取失败时降级容错：以 parse = 1 将原始播放页面地址交给壳子二次解析
            logger("未提取到真实播放地址，推给壳子二次解析: " + playPageUrl);
            return Result.get().parse(1).url(playPageUrl).string();

        } catch (Exception e) {
            logger("playerContent 异常: " + e.getMessage());
            return Result.get().parse(1).url(absUrl(id)).string();
        }
    }

    // ==================== 私有解析辅助方法 ====================

    private List<Vod> parseList(String html) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return list;

        Document doc = Jsoup.parse(html);
        Elements liList = doc.select("div.index-area ul li");

        for (Element li : liList) {
            Element a = li.selectFirst("a.link-hover");
            if (a == null) a = li.selectFirst("a");
            if (a == null) continue;

            String href = a.attr("href");
            String title = a.attr("title");

            Element img = li.selectFirst("img.lazy");
            if (img == null) img = li.selectFirst("img");

            String pic = "";
            if (img != null) {
                pic = img.hasAttr("data-original") ? img.attr("data-original") : img.attr("src");
            }

            if (TextUtils.isEmpty(title)) {
                Element nameP = li.selectFirst("p.name");
                if (nameP != null) title = nameP.text().trim();
            }

            Element remarkEl = li.selectFirst("p.other i");
            String remark = remarkEl != null ? remarkEl.text().trim() : "";

            if (!TextUtils.isEmpty(href) && !TextUtils.isEmpty(title)) {
                Vod vod = new Vod();
                vod.setVodId(href);
                vod.setVodName(title);
                vod.setVodPic(absUrl(pic));
                vod.setVodRemarks(remark);
                list.add(vod);
            }
        }
        return list;
    }

    private int parsePage(String pg) {
        try {
            int p = Integer.parseInt(pg);
            return p < 1 ? 1 : p;
        } catch (Exception e) {
            return 1;
        }
    }

    private String vodPageResult(List<Vod> list, int page) {
        int pagecount = list.isEmpty() ? page : (page + 1);
        int limit = list.isEmpty() ? 20 : Math.max(list.size(), 1);
        int total = pagecount * limit;
        return Result.get().vod(list).page(page, pagecount, limit, total).string();
    }
}
