package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;
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

public class Bdys extends Spider {

    private static final String HOST = "https://v.xl.in.ua";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/147.0.7727.56 Safari/537.36";

    private void logger(String msg) {
        try {
            Proxy.log("[Bdys] " + msg);
        } catch (Exception ignored) {
        }
    }

    /**
     * URL 补全
     */
    private String fixUrl(String path) {

        if (TextUtils.isEmpty(path)) {
            return "";
        }

        path = path.trim();

        if (path.startsWith("http://") ||
                path.startsWith("https://")) {
            return path;
        }

        if (path.startsWith("//")) {
            return "https:" + path;
        }

        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        return HOST + path;
    }

    /**
     * 主站请求头
     */
    private Map<String, String> getHeaders(String referer) {

        Map<String, String> headers =
                new LinkedHashMap<>();

        if (TextUtils.isEmpty(referer)) {
            referer = HOST + "/";
        }

        headers.put("User-Agent", UA);
        headers.put("Referer", referer);
        headers.put("Origin", HOST);

        headers.put(
                "Accept",
                "text/html,application/xhtml+xml," +
                "application/xml;q=0.9," +
                "image/avif,image/webp,image/apng," +
                "*/*;q=0.8"
        );

        headers.put(
                "Accept-Language",
                "zh-CN,zh;q=0.9"
        );

        headers.put(
                "Cache-Control",
                "no-cache"
        );

        headers.put(
                "Pragma",
                "no-cache"
        );

        headers.put(
                "sec-ch-ua",
                "\"Not/A)Brand\";v=\"8\", " +
                "\"Chromium\";v=\"147\", " +
                "\"Google Chrome\";v=\"147\""
        );

        headers.put(
                "sec-ch-ua-mobile",
                "?0"
        );

        headers.put(
                "sec-ch-ua-platform",
                "\"Windows\""
        );

        headers.put(
                "sec-fetch-dest",
                "document"
        );

        headers.put(
                "sec-fetch-mode",
                "navigate"
        );

        headers.put(
                "sec-fetch-site",
                "same-origin"
        );

        headers.put(
                "sec-fetch-user",
                "?1"
        );

        headers.put(
                "upgrade-insecure-requests",
                "1"
        );

        return headers;
    }

    /**
     * HTTP GET
     */
    private String get(String url) {
        return get(url, HOST + "/");
    }

    private String get(
            String url,
            String referer) {

        try {

            logger("================================");
            logger("HTTP GET");
            logger("URL = " + url);
            logger("Referer = " + referer);

            Map<String, String> headers =
                    getHeaders(referer);

            logger(
                    "User-Agent = " +
                    headers.get("User-Agent")
            );

            logger(
                    "Origin = " +
                    headers.get("Origin")
            );

            String body =
                    OkHttp.string(
                            url,
                            headers
                    );

            if (TextUtils.isEmpty(body)) {

                logger("❌ HTTP 返回为空");

                return "";
            }

            logger(
                    "HTTP 返回长度 = " +
                    body.length()
            );

            String preview =
                    body.replace("\n", " ")
                            .replace("\r", " ")
                            .trim();

            if (preview.length() > 600) {
                preview =
                        preview.substring(0, 600);
            }

            logger(
                    "HTTP 返回预览 = " +
                    preview
            );

            logger("================================");

            return body;

        } catch (Exception e) {

            logger(
                    "❌ HTTP 异常 = " +
                    e.getClass().getName()
            );

            logger(
                    "❌ HTTP 异常信息 = " +
                    e.getMessage()
            );

            return "";
        }
    }

    @Override
    public void init(
            Context context,
            String extend) {

        logger("================================");
        logger("Bdys 初始化");
        logger("HOST = " + HOST);
        logger("extend = " + extend);
        logger("================================");
    }

    /**
     * 首页分类
     */
    @Override
    public String homeContent(
            boolean filter) {

        try {

            logger("================================");
            logger("homeContent");
            logger("filter = " + filter);

            List<Class> classes =
                    new ArrayList<>();

            /*
             * 网站分类：
             *
             * type=1 -> 电视剧
             * type=0 -> 电影
             */
            classes.add(
                    new Class(
                            "1",
                            "电视剧"
                    )
            );

            classes.add(
                    new Class(
                            "0",
                            "电影"
                    )
            );

            logger("电视剧 tid = 1");
            logger("电影 tid = 0");

            LinkedHashMap<
                    String,
                    List<Filter>
                    > filterMap =
                    new LinkedHashMap<>();

            if (filter) {

                List<Filter> filters =
                        new ArrayList<>();

                String[] typeNames = {
                        "全部",
                        "动作",
                        "爱情",
                        "喜剧",
                        "科幻",
                        "恐怖",
                        "剧情",
                        "动画",
                        "悬疑",
                        "犯罪",
                        "古装",
                        "奇幻",
                        "美剧",
                        "韩剧",
                        "国产",
                        "日剧"
                };

                String[] typeValues = {
                        "all",
                        "dongzuo",
                        "aiqing",
                        "xiju",
                        "kehuan",
                        "kongbu",
                        "juqing",
                        "donghua",
                        "xuanyi",
                        "fanzui",
                        "guzhuang",
                        "qihuan",
                        "meiju",
                        "hanju",
                        "guoju",
                        "riju"
                };

                List<Filter.Value>
                        typeOptions =
                        new ArrayList<>();

                for (int i = 0;
                     i < typeNames.length;
                     i++) {

                    typeOptions.add(
                            new Filter.Value(
                                    typeNames[i],
                                    typeValues[i]
                            )
                    );
                }

                filters.add(
                        new Filter(
                                "type_slug",
                                "类型",
                                typeOptions
                        )
                );

                List<Filter.Value>
                        yearOptions =
                        new ArrayList<>();

                yearOptions.add(
                        new Filter.Value(
                                "全部",
                                ""
                        )
                );

                for (int y = 2026;
                     y >= 2015;
                     y--) {

                    yearOptions.add(
                            new Filter.Value(
                                    String.valueOf(y),
                                    String.valueOf(y)
                            )
                    );
                }

                filters.add(
                        new Filter(
                                "year",
                                "年份",
                                yearOptions
                        )
                );

                filterMap.put(
                        "0",
                        filters
                );

                filterMap.put(
                        "1",
                        filters
                );
            }

            String result =
                    Result.string(
                            classes,
                            filterMap
                    );

            logger(
                    "homeContent 返回长度 = " +
                    result.length()
            );

            logger(
                    "homeContent JSON = " +
                    result
            );

            logger("================================");

            return result;

        } catch (Exception e) {

            logger(
                    "❌ homeContent 异常 = " +
                    e.getMessage()
            );

            return Result.string(
                    new ArrayList<>()
            );
        }
    }

    /**
     * 分类
     */
    @Override
    public String categoryContent(
            String tid,
            String pg,
            boolean filter,
            HashMap<String, String> extend) {

        try {

            logger("");
            logger("################################");
            logger("######## categoryContent ########");
            logger("################################");

            logger("tid = [" + tid + "]");
            logger("pg = [" + pg + "]");
            logger("filter = [" + filter + "]");

            /*
             * 打印 extend
             */
            if (extend == null) {

                logger("extend = null");

            } else {

                logger(
                        "extend.size = " +
                        extend.size()
                );

                for (Map.Entry<
                        String,
                        String
                        > entry :
                        extend.entrySet()) {

                    logger(
                            "extend[" +
                            entry.getKey() +
                            "] = [" +
                            entry.getValue() +
                            "]"
                    );
                }
            }

            /*
             * 页码
             */
            int page = 1;

            try {

                if (!TextUtils.isEmpty(pg)) {
                    page =
                            Integer.parseInt(pg);
                }

            } catch (Exception e) {

                logger(
                        "pg 解析失败，使用 page=1"
                );
            }

            if (page < 1) {
                page = 1;
            }

            /*
             * 类型
             */
            String typeSlug = "all";

            if (extend != null) {

                String value =
                        extend.get("type_slug");

                if (!TextUtils.isEmpty(value)) {

                    typeSlug =
                            value.trim();
                }
            }

            logger(
                    "最终 typeSlug = " +
                    typeSlug
            );

            /*
             * 拼接 URL
             *
             * 电视剧：
             * https://v.xl.in.ua/s/all/1?type=1
             */
            StringBuilder builder =
                    new StringBuilder();

            builder.append(HOST)
                    .append("/s/")
                    .append(typeSlug)
                    .append("/")
                    .append(page)
                    .append("?type=")
                    .append(tid);

            /*
             * 年份
             */
            if (extend != null) {

                String year =
                        extend.get("year");

                if (!TextUtils.isEmpty(year)) {

                    builder.append(
                            "&year="
                    ).append(year);

                    logger(
                            "year = " +
                            year
                    );
                }
            }

            String url =
                    builder.toString();

            logger("--------------------------------");
            logger("最终分类 URL:");
            logger(url);
            logger("--------------------------------");

            /*
             * 请求
             */
            String html =
                    get(
                            url,
                            HOST + "/"
                    );

            if (TextUtils.isEmpty(html)) {

                logger(
                        "❌ 分类 HTML 为空"
                );

                return Result.string(
                        new ArrayList<>()
                );
            }

            /*
             * Jsoup
             */
            Document doc =
                    Jsoup.parse(html);

            logger(
                    "网页 title = " +
                    doc.title()
            );

            logger(
                    "HTML 长度 = " +
                    html.length()
            );

            /*
             * 根据你提供的真实 HTML：
             *
             * .xl-list-card
             *   .xl-grid-container
             *     .xl-movies-grid-8
             *       .movie-card
             */
            Elements cards =
                    doc.select(
                            ".xl-list-card .movie-card"
                    );

            logger(
                    ".xl-list-card .movie-card 数量 = " +
                    cards.size()
            );

            /*
             * 如果没有找到，再尝试全局
             */
            if (cards.isEmpty()) {

                cards =
                        doc.select(
                                ".movie-card"
                        );

                logger(
                        "备用 .movie-card 数量 = " +
                        cards.size()
                );
            }

            /*
             * 如果仍然没有：
             * 打印几个关键节点数量
             */
            if (cards.isEmpty()) {

                logger(
                        "❌ 没有找到 movie-card"
                );

                logger(
                        ".xl-list-card = " +
                        doc.select(
                                ".xl-list-card"
                        ).size()
                );

                logger(
                        ".xl-grid-container = " +
                        doc.select(
                                ".xl-grid-container"
                        ).size()
                );

                logger(
                        ".xl-movies-grid-8 = " +
                        doc.select(
                                ".xl-movies-grid-8"
                        ).size()
                );

                logger(
                        "a.card-img = " +
                        doc.select(
                                "a.card-img"
                        ).size()
                );

                logger(
                        "a[href] = " +
                        doc.select(
                                "a[href]"
                        ).size()
                );

                return Result.string(
                        new ArrayList<>()
                );
            }

            List<Vod> list =
                    new ArrayList<>();

            /*
             * 解析影片
             */
            for (int i = 0;
                 i < cards.size();
                 i++) {

                try {

                    Element card =
                            cards.get(i);

                    logger("--------------------------------");
                    logger(
                            "解析影片 #" +
                            i
                    );

                    /*
                     * a.card-img
                     */
                    Element a =
                            card.selectFirst(
                                    "a.card-img"
                            );

                    if (a == null) {

                        logger(
                                "❌ 找不到 a.card-img"
                        );

                        continue;
                    }

                    /*
                     * href
                     */
                    String href =
                            a.attr("href")
                                    .trim();

                    logger(
                            "href = " +
                            href
                    );

                    if (TextUtils.isEmpty(href)) {

                        logger(
                                "❌ href 为空"
                        );

                        continue;
                    }

                    /*
                     * 注意：
                     *
                     * vod_id 保留网站原始路径。
                     *
                     * /guoju/27044.htm
                     *
                     * 这样最接近网站原始数据。
                     */
                    String vodId =
                            href;

                    logger(
                            "vodId = " +
                            vodId
                    );

                    /*
                     * 标题
                     */
                    String name = "";

                    Element h4 =
                            card.selectFirst(
                                    ".card-info h4"
                            );

                    if (h4 != null) {

                        name =
                                h4.text().trim();
                    }

                    /*
                     * 如果 h4 没有，
                     * 使用 a 的 title
                     */
                    if (TextUtils.isEmpty(name)) {

                        name =
                                a.attr(
                                        "title"
                                ).trim();
                    }

                    /*
                     * 最后使用 a 文本
                     */
                    if (TextUtils.isEmpty(name)) {

                        name =
                                a.text().trim();
                    }

                    logger(
                            "name = " +
                            name
                    );

                    if (TextUtils.isEmpty(name)) {

                        logger(
                                "❌ name 为空"
                        );

                        continue;
                    }

                    /*
                     * 图片
                     */
                    String pic = "";

                    Element img =
                            card.selectFirst(
                                    "img"
                            );

                    if (img != null) {

                        String dataSrc =
                                img.attr(
                                        "data-src"
                                );

                        String src =
                                img.attr(
                                        "src"
                                );

                        logger(
                                "data-src = " +
                                dataSrc
                        );

                        logger(
                                "src = " +
                                src
                        );

                        if (!TextUtils.isEmpty(
                                dataSrc
                        )) {

                            pic =
                                    dataSrc;

                        } else {

                            pic =
                                    src;
                        }

                        pic =
                                fixUrl(pic);
                    }

                    logger(
                            "pic = " +
                            pic
                    );

                    /*
                     * 备注
                     */
                    String remark = "";

                    Element badge =
                            card.selectFirst(
                                    ".episode-badge"
                            );

                    if (badge != null) {

                        remark =
                                badge.text().trim();
                    }

                    if (TextUtils.isEmpty(
                            remark
                    )) {

                        Element rating =
                                card.selectFirst(
                                        ".rating-badge"
                                );

                        if (rating != null) {

                            remark =
                                    rating.text()
                                            .trim();
                        }
                    }

                    logger(
                            "remark = " +
                            remark
                    );

                    /*
                     * 创建 Vod
                     */
                    Vod vod =
                            new Vod(
                                    vodId,
                                    name,
                                    pic,
                                    remark
                            );

                    list.add(vod);

                    logger(
                            "✅ Vod 加入 list"
                    );

                } catch (Exception e) {

                    logger(
                            "❌ 影片 #" +
                            i +
                            " 解析异常: " +
                            e.getMessage()
                    );
                }
            }

            /*
             * 去重
             */
            LinkedHashMap<
                    String,
                    Vod
                    > unique =
                    new LinkedHashMap<>();

            for (Vod vod : list) {

                if (vod == null) {
                    continue;
                }

                String id =
                        vod.getVodId();

                if (TextUtils.isEmpty(id)) {
                    continue;
                }

                if (!unique.containsKey(id)) {

                    unique.put(
                            id,
                            vod
                    );
                }
            }

            list =
                    new ArrayList<>(
                            unique.values()
                    );

            logger("--------------------------------");
            logger(
                    "原始解析数量 = " +
                    list.size()
            );

            /*
             * 逐条输出最终数据
             */
            for (int i = 0;
                 i < list.size();
                 i++) {

                Vod vod =
                        list.get(i);

                logger(
                        "最终 Vod[" +
                        i +
                        "]"
                );

                logger(
                        "  vod_id = " +
                        vod.getVodId()
                );

                logger(
                        "  vod_name = " +
                        vod.getVodName()
                );

                logger(
                        "  vod_pic = " +
                        vod.getVodPic()
                );

                logger(
                        "  vod_remarks = " +
                        vod.getVodRemarks()
                );
            }

            /*
             * 最关键：
             * 直接打印 Result.string()
             * 最终返回给 TVBox 的 JSON。
             */
            String result =
                    Result.string(list);

            logger("--------------------------------");
            logger(
                    "Result JSON 长度 = " +
                    result.length()
            );

            logger(
                    "Result JSON = " +
                    result
            );

            logger(
                    "最终 Vod 数量 = " +
                    list.size()
            );

            logger("######## categoryContent END ########");
            logger("################################");

            return result;

        } catch (Exception e) {

            logger(
                    "❌ categoryContent 总异常"
            );

            logger(
                    "Exception = " +
                    e.getClass().getName()
            );

            logger(
                    "Message = " +
                    e.getMessage()
            );

            return Result.string(
                    new ArrayList<>()
            );
        }
    }

    /**
     * 详情
     */
    @Override
    public String detailContent(
            List<String> ids) {

        try {

            logger("################################");
            logger("######## detailContent ########");
            logger("################################");

            if (ids == null ||
                    ids.isEmpty()) {

                logger(
                        "❌ ids 为空"
                );

                return Result.string(
                        new ArrayList<>()
                );
            }

            logger(
                    "ids.size = " +
                    ids.size()
            );

            for (int i = 0;
                 i < ids.size();
                 i++) {

                logger(
                        "ids[" +
                        i +
                        "] = " +
                        ids.get(i)
                );
            }

            String id =
                    ids.get(0);

            String url =
                    fixUrl(id);

            logger(
                    "详情 URL = " +
                    url
            );

            String html =
                    get(
                            url,
                            HOST + "/"
                    );

            if (TextUtils.isEmpty(html)) {

                logger(
                        "❌ 详情 HTML 为空"
                );

                return Result.string(
                        new ArrayList<>()
                );
            }

            Document doc =
                    Jsoup.parse(html);

            logger(
                    "详情 title = " +
                    doc.title()
            );

            logger(
                    "详情 HTML 长度 = " +
                    html.length()
            );

            /*
             * 名称
             */
            String name = "";

            Element h4 =
                    doc.selectFirst(
                            ".card-info h4"
                    );

            if (h4 != null) {
                name =
                        h4.text().trim();
            }

            if (TextUtils.isEmpty(name)) {

                Element h1 =
                        doc.selectFirst("h1");

                if (h1 != null) {
                    name =
                            h1.text().trim();
                }
            }

            if (TextUtils.isEmpty(name)) {

                Element h2 =
                        doc.selectFirst("h2");

                if (h2 != null) {
                    name =
                            h2.text().trim();
                }
            }

            logger(
                    "name = " +
                    name
            );

            /*
             * 图片
             */
            String pic = "";

            Element img =
                    doc.selectFirst(
                            ".movie-card img"
                    );

            if (img == null) {

                img =
                        doc.selectFirst(
                                ".cover-lg-max-25 img"
                        );
            }

            if (img == null) {

                img =
                        doc.selectFirst(
                                ".card-info img"
                        );
            }

            if (img != null) {

                String dataSrc =
                        img.attr("data-src");

                String src =
                        img.attr("src");

                if (!TextUtils.isEmpty(
                        dataSrc
                )) {

                    pic =
                            dataSrc;

                } else {

                    pic =
                            src;
                }

                pic =
                        fixUrl(pic);
            }

            logger(
                    "pic = " +
                    pic
            );

            /*
             * 简介
             */
            String content = "";

            Element synopsis =
                    doc.selectFirst(
                            "#synopsis"
                    );

            if (synopsis != null) {

                content =
                        synopsis.text().trim();
            }

            if (TextUtils.isEmpty(content)) {

                Element e =
                        doc.selectFirst(
                                ".synopsis"
                        );

                if (e != null) {

                    content =
                            e.text().trim();
                }
            }

            logger(
                    "content length = " +
                    content.length()
            );

            /*
             * 播放列表
             */
            Elements playLinks =
                    doc.select(
                            "#play-list a"
                    );

            logger(
                    "#play-list a = " +
                    playLinks.size()
            );

            if (playLinks.isEmpty()) {

                playLinks =
                        doc.select(
                                "#playList a,"
                                + ".play-list a,"
                                + ".playlist a,"
                                + ".episode-list a,"
                                + ".episodes a,"
                                + ".episode-item a,"
                                + ".play-item a"
                        );

                logger(
                        "备用播放选择器 = " +
                        playLinks.size()
                );
            }

            /*
             * 如果仍然没有，
             * 扫描所有 href，
             * 但只保留疑似剧集链接。
             */
            if (playLinks.isEmpty()) {

                Elements allLinks =
                        doc.select(
                                "a[href]"
                        );

                logger(
                        "全页面 a[href] = " +
                        allLinks.size()
                );

                List<Element> candidates =
                        new ArrayList<>();

                for (Element a :
                        allLinks) {

                    String text =
                            a.text().trim();

                    String href =
                            a.attr(
                                    "href"
                            ).trim();

                    if (TextUtils.isEmpty(
                            href
                    )) {
                        continue;
                    }

                    if (TextUtils.isEmpty(
                            text
                    )) {
                        continue;
                    }

                    String lower =
                            href.toLowerCase();

                    boolean episode =
                            text.matches(
                                    ".*第?\\d+集.*"
                            )
                            ||
                            text.matches(
                                    ".*第?\\d+话.*"
                            )
                            ||
                            text.matches(
                                    ".*EP\\.?\\d+.*"
                            )
                            ||
                            text.matches(
                                    "\\d+"
                            );

                    boolean playPath =
                            lower.contains(
                                    "/play/"
                            )
                            ||
                            lower.contains(
                                    "/episode/"
                            )
                            ||
                            lower.contains(
                                    "/watch/"
                            );

                    if (episode ||
                            playPath) {

                        candidates.add(a);
                    }
                }

                logger(
                        "疑似播放链接 = " +
                        candidates.size()
                );

                playLinks =
                        new Elements(
                                candidates
                        );
            }

            /*
             * 播放列表
             */
            List<String> playPairs =
                    new ArrayList<>();

            for (int i = 0;
                 i < playLinks.size();
                 i++) {

                Element a =
                        playLinks.get(i);

                String epName =
                        a.text().trim();

                String epHref =
                        a.attr(
                                "href"
                        ).trim();

                logger(
                        "播放[" +
                        i +
                        "] name = " +
                        epName
                );

                logger(
                        "播放[" +
                        i +
                        "] href = " +
                        epHref
                );

                if (TextUtils.isEmpty(
                        epHref
                )) {
                    continue;
                }

                if (TextUtils.isEmpty(
                        epName
                )) {

                    epName =
                            "第" +
                            (i + 1) +
                            "集";
                }

                playPairs.add(
                        epName +
                        "$" +
                        epHref
                );
            }

            /*
             * 去重
             */
            LinkedHashMap<
                    String,
                    String
                    > uniquePlay =
                    new LinkedHashMap<>();

            for (String pair :
                    playPairs) {

                int p =
                        pair.indexOf("$");

                if (p <= 0) {
                    continue;
                }

                String href =
                        pair.substring(
                                p + 1
                        );

                if (!uniquePlay.containsKey(
                        href
                )) {

                    uniquePlay.put(
                            href,
                            pair
                    );
                }
            }

            playPairs =
                    new ArrayList<>(
                            uniquePlay.values()
                    );

            logger(
                    "最终播放集数 = " +
                    playPairs.size()
            );

            Vod vod =
                    new Vod();

            vod.setVodId(id);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodContent(content);

            if (!playPairs.isEmpty()) {

                vod.setVodPlayFrom(
                        "哔嘀影视"
                );

                vod.setVodPlayUrl(
                        TextUtils.join(
                                "#",
                                playPairs
                        )
                );

                logger(
                        "播放源 = 哔嘀影视"
                );

                logger(
                        "播放URL = " +
                        vod.getVodPlayUrl()
                );

            } else {

                logger(
                        "⚠️ 没有找到播放地址"
                );

                vod.setVodPlayFrom("");
                vod.setVodPlayUrl("");
            }

            String result =
                    Result.string(vod);

            logger(
                    "详情 Result JSON = " +
                    result
            );

            logger("######## detailContent END ########");
            logger("################################");

            return result;

        } catch (Exception e) {

            logger(
                    "❌ detailContent 异常"
            );

            logger(
                    "Exception = " +
                    e.getClass().getName()
            );

            logger(
                    "Message = " +
                    e.getMessage()
            );

            return Result.string(
                    new ArrayList<>()
            );
        }
    }

    /**
     * 播放
     */
    @Override
    public String playerContent(
            String flag,
            String id,
            List<String> vipFlags) {

        try {

            logger("################################");
            logger("######## playerContent ########");
            logger("################################");

            logger(
                    "flag = " +
                    flag
            );

            logger(
                    "id = " +
                    id
            );

            String playUrl =
                    fixUrl(id);

            logger(
                    "playUrl = " +
                    playUrl
            );

            String lower =
                    playUrl.toLowerCase();

            if (lower.contains(".m3u8") ||
                    lower.contains(".mp4") ||
                    lower.contains(".mkv") ||
                    lower.contains(".flv") ||
                    lower.contains(".ts")) {

                logger(
                        "检测为直接媒体地址"
                );

                String result =
                        Result.get()
                                .parse(0)
                                .url(playUrl)
                                .header(
                                        getHeaders(
                                                HOST + "/"
                                        )
                                )
                                .string();

                logger(
                        "player result = " +
                        result
                );

                return result;
            }

            logger(
                    "检测为播放页面"
            );

            String result =
                    Result.get()
                            .parse(1)
                            .url(playUrl)
                            .header(
                                    getHeaders(
                                            HOST + "/"
                                    )
                            )
                            .string();

            logger(
                    "player result = " +
                    result
            );

            return result;

        } catch (Exception e) {

            logger(
                    "❌ playerContent 异常 = " +
                    e.getMessage()
            );

            return Result.get()
                    .parse(1)
                    .url(id)
                    .string();
        }
    }

    /**
     * 搜索
     */
    @Override
    public String searchContent(
            String key,
            boolean quick) {

        try {

            logger("################################");
            logger("######## searchContent ########");
            logger("################################");

            logger(
                    "key = " +
                    key
            );

            logger(
                    "quick = " +
                    quick
            );

            if (TextUtils.isEmpty(key)) {

                logger(
                        "❌ 搜索关键词为空"
                );

                return Result.string(
                        new ArrayList<>()
                );
            }

            String searchUrl =
                    "https://kwyili.dpdns.org/bdys.php?q="
                    +
                    URLEncoder.encode(
                            key,
                            "UTF-8"
                    );

            logger(
                    "搜索 URL = " +
                    searchUrl
            );

            Map<String, String>
                    headers =
                    new LinkedHashMap<>();

            headers.put(
                    "User-Agent",
                    UA
            );

            headers.put(
                    "Accept",
                    "application/json,text/plain,*/*"
            );

            String json =
                    OkHttp.string(
                            searchUrl,
                            headers
                    );

            if (TextUtils.isEmpty(json)) {

                logger(
                        "❌ 搜索返回为空"
                );

                return Result.string(
                        new ArrayList<>()
                );
            }

            logger(
                    "搜索 JSON 长度 = " +
                    json.length()
            );

            String preview =
                    json.replace(
                            "\n",
                            " "
                    ).replace(
                            "\r",
                            " "
                    );

            if (preview.length() > 600) {

                preview =
                        preview.substring(
                                0,
                                600
                        );
            }

            logger(
                    "搜索 JSON 预览 = " +
                    preview
            );

            JSONArray array =
                    new JSONArray(json);

            logger(
                    "搜索结果数量 = " +
                    array.length()
            );

            List<Vod> list =
                    new ArrayList<>();

            for (int i = 0;
                 i < array.length();
                 i++) {

                JSONObject item =
                        array.getJSONObject(i);

                String title =
                        item.optString(
                                "title"
                        );

                String image =
                        item.optString(
                                "image"
                        );

                String href =
                        item.optString(
                                "href"
                        );

                logger(
                        "搜索[" +
                        i +
                        "] title = " +
                        title
                );

                logger(
                        "搜索[" +
                        i +
                        "] href = " +
                        href
                );

                if (TextUtils.isEmpty(
                        title
                ) ||
                        TextUtils.isEmpty(
                                href
                        )) {
                    continue;
                }

                list.add(
                        new Vod(
                                href,
                                title,
                                fixUrl(image),
                                ""
                        )
                );
            }

            String result =
                    Result.string(list);

            logger(
                    "搜索最终数量 = " +
                    list.size()
            );

            logger(
                    "搜索 Result JSON = " +
                    result
            );

            logger("################################");

            return result;

        } catch (Exception e) {

            logger(
                    "❌ searchContent 异常"
            );

            logger(
                    "Exception = " +
                    e.getClass().getName()
            );

            logger(
                    "Message = " +
                    e.getMessage()
            );

            return Result.string(
                    new ArrayList<>()
            );
        }
    }
}
