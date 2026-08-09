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

    private static String HOST = "https://v.xl.in.ua";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/147.0.7727.56 Safari/537.36";

    /**
     * ============================================================
     * 日志
     * ============================================================
     */
    private void logger(String msg) {
        try {
            Proxy.log("[Bdys] " + msg);
        } catch (Exception ignored) {
        }
    }

    /**
     * ============================================================
     * URL 补全
     * ============================================================
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
     * ============================================================
     * 主站请求头
     * ============================================================
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
     * ============================================================
     * GET
     * ============================================================
     */
    private String get(String url) {
        return get(url, HOST + "/");
    }

    private String get(
            String url,
            String referer) {

        try {

            logger("--------------------------------");
            logger("HTTP GET");
            logger("URL: " + url);
            logger("Referer: " + referer);

            Map<String, String> headers =
                    getHeaders(referer);

            logger("User-Agent: " +
                    headers.get("User-Agent"));

            logger("Origin: " +
                    headers.get("Origin"));

            String html =
                    OkHttp.string(
                            url,
                            headers
                    );

            if (TextUtils.isEmpty(html)) {

                logger("❌ HTTP 响应为空");

                return "";
            }

            logger(
                    "✅ HTTP 响应长度: " +
                    html.length()
            );

            /**
             * 打印前 500 字符，
             * 用来判断是不是 Cloudflare / 错误页面。
             */
            String preview = html;

            preview =
                    preview.replace(
                            "\n",
                            " "
                    );

            preview =
                    preview.replace(
                            "\r",
                            " "
                    );

            if (preview.length() > 500) {
                preview =
                        preview.substring(0, 500);
            }

            logger(
                    "响应预览: " +
                    preview
            );

            logger("--------------------------------");

            return html;

        } catch (Exception e) {

            logger(
                    "❌ HTTP 请求异常: " +
                    e.getClass().getName()
            );

            logger(
                    "❌ 异常信息: " +
                    e.getMessage()
            );

            return "";
        }
    }

    /**
     * ============================================================
     * 初始化
     * ============================================================
     */
    @Override
    public void init(
            Context context,
            String extend) {

        logger("================================");
        logger("Bdys Spider 初始化");
        logger("HOST = " + HOST);
        logger("UA = " + UA);
        logger("extend = " + extend);
        logger("================================");
    }

    /**
     * ============================================================
     * 首页
     * ============================================================
     */
    @Override
    public String homeContent(
            boolean filter) {

        try {

            logger("================================");
            logger("homeContent()");
            logger("filter = " + filter);

            List<Class> classes =
                    new ArrayList<>();

            /**
             * 电视剧
             */
            classes.add(
                    new Class(
                            "1",
                            "电视剧"
                    )
            );

            /**
             * 电影
             */
            classes.add(
                    new Class(
                            "0",
                            "电影"
                    )
            );

            logger(
                    "电视剧 tid = 1"
            );

            logger(
                    "电影 tid = 0"
            );

            LinkedHashMap<
                    String,
                    List<Filter>
                    > filterMap =
                    new LinkedHashMap<>();

            if (filter) {

                List<Filter> filterList =
                        new ArrayList<>();

                /**
                 * 类型
                 */
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

                filterList.add(
                        new Filter(
                                "type_slug",
                                "类型",
                                typeOptions
                        )
                );

                /**
                 * 年份
                 */
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

                filterList.add(
                        new Filter(
                                "year",
                                "年份",
                                yearOptions
                        )
                );

                filterMap.put(
                        "0",
                        filterList
                );

                filterMap.put(
                        "1",
                        filterList
                );
            }

            String result =
                    Result.string(
                            classes,
                            filterMap
                    );

            logger(
                    "homeContent 完成"
            );

            logger(
                    "返回分类数量 = " +
                    classes.size()
            );

            logger("================================");

            return result;

        } catch (Exception e) {

            logger(
                    "❌ homeContent 异常: " +
                    e.getMessage()
            );

            return Result.string(
                    new ArrayList<>()
            );
        }
    }

    /**
     * ============================================================
     * 分类
     * ============================================================
     */
    @Override
    public String categoryContent(
            String tid,
            String pg,
            boolean filter,
            HashMap<String, String> extend) {

        try {

            logger("");
            logger("================================");
            logger("========== categoryContent ==========");

            logger("tid = [" + tid + "]");
            logger("pg = [" + pg + "]");
            logger("filter = [" + filter + "]");

            /**
             * extend
             */
            if (extend == null) {

                logger(
                        "extend = null"
                );

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

            int page = 1;

            try {

                page =
                        Integer.parseInt(pg);

            } catch (Exception e) {

                logger(
                        "pg 转数字失败，使用 1"
                );
            }

            /**
             * 默认类型
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

            /**
             * 拼接分类 URL
             */
            StringBuilder url =
                    new StringBuilder();

            url.append(HOST)
                    .append("/s/")
                    .append(typeSlug)
                    .append("/")
                    .append(page)
                    .append("?type=")
                    .append(tid);

            if (extend != null) {

                String year =
                        extend.get("year");

                if (!TextUtils.isEmpty(year)) {

                    url.append(
                            "&year="
                    ).append(year);

                }
            }

            String categoryUrl =
                    url.toString();

            logger("--------------------------------");
            logger("分类 URL:");
            logger(categoryUrl);
            logger("--------------------------------");

            /**
             * 请求分类页
             */
            String html =
                    get(
                            categoryUrl,
                            HOST + "/"
                    );

            if (TextUtils.isEmpty(html)) {

                logger(
                        "❌ 分类页面 HTML 为空"
                );

                return Result.string(
                        new ArrayList<>()
                );
            }

            logger(
                    "HTML 长度 = " +
                    html.length()
            );

            Document doc =
                    Jsoup.parse(html);

            logger(
                    "HTML title = " +
                    doc.title()
            );

            /**
             * ====================================================
             * 根据你提供的真实 HTML：
             *
             * <div class="xl-list-card">
             *   <div class="xl-grid-container">
             *     <div class="xl-movies-grid-8">
             *       <div class="movie-card">
             *
             * 所以优先使用：
             *
             * .xl-list-card .movie-card
             * ====================================================
             */
            Elements cards =
                    doc.select(
                            ".xl-list-card .movie-card"
                    );

            logger(
                    ".xl-list-card .movie-card = " +
                    cards.size()
            );

            /**
             * 备用
             */
            if (cards.isEmpty()) {

                cards =
                        doc.select(
                                ".movie-card"
                        );

                logger(
                        "备用 .movie-card = " +
                        cards.size()
                );
            }

            List<Vod> list =
                    new ArrayList<>();

            /**
             * ====================================================
             * 解析影片
             * ====================================================
             */
            for (int i = 0;
                 i < cards.size();
                 i++) {

                try {

                    Element card =
                            cards.get(i);

                    logger("--------------------------------");
                    logger(
                            "开始解析影片 [" +
                            i +
                            "]"
                    );

                    /**
                     * 真实 HTML：
                     *
                     * <a class="card-img"
                     *    href="/guoju/27044.htm">
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

                    String vodId =
                            fixUrl(href);

                    logger(
                            "vodId = " +
                            vodId
                    );

                    /**
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

                    /**
                     * 图片
                     */
                    String pic = "";

                    Element img =
                            card.selectFirst(
                                    "img"
                            );

                    if (img != null) {

                        logger(
                                "img data-src = " +
                                img.attr(
                                        "data-src"
                                )
                        );

                        logger(
                                "img src = " +
                                img.attr("src")
                        );

                        if (img.hasAttr(
                                "data-src"
                        )) {

                            pic =
                                    img.attr(
                                            "data-src"
                                    );
                        }

                        if (TextUtils.isEmpty(pic)) {

                            pic =
                                    img.attr(
                                            "src"
                                    );
                        }

                        pic =
                                fixUrl(pic);
                    }

                    logger(
                            "pic = " +
                            pic
                    );

                    /**
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

                    logger(
                            "remark = " +
                            remark
                    );

                    /**
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
                            "✅ Vod 添加成功"
                    );

                } catch (Exception e) {

                    logger(
                            "❌ 第 " +
                            i +
                            " 个影片解析异常: " +
                            e.getMessage()
                    );
                }
            }

            /**
             * ====================================================
             * 去重
             * ====================================================
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
                    "分类解析完成"
            );

            logger(
                    "原始 Vod 数量 = " +
                    unique.size()
            );

            logger(
                    "最终 Vod 数量 = " +
                    list.size()
            );

            for (int i = 0;
                 i < list.size();
                 i++) {

                Vod vod =
                        list.get(i);

                logger(
                        "Vod[" +
                        i +
                        "]"
                );

                logger(
                        "  Name = " +
                        vod.getVodName()
                );

                logger(
                        "  ID = " +
                        vod.getVodId()
                );

                logger(
                        "  Pic = " +
                        vod.getVodPic()
                );

                logger(
                        "  Remark = " +
                        vod.getVodRemarks()
                );
            }

            logger("========== categoryContent END ==========");
            logger("================================");

            /**
             * 最终返回 TVBox
             */
            return Result.string(list);

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
     * ============================================================
     * 详情
     * ============================================================
     */
    @Override
    public String detailContent(
            List<String> ids) {

        try {

            logger("================================");
            logger("========== detailContent ==========");

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

            /**
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
                        doc.selectFirst(
                                "h1"
                        );

                if (h1 != null) {
                    name =
                            h1.text().trim();
                }
            }

            logger(
                    "详情 name = " +
                    name
            );

            /**
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

            if (img != null) {

                if (img.hasAttr(
                        "data-src"
                )) {

                    pic =
                            img.attr(
                                    "data-src"
                            );

                } else {

                    pic =
                            img.attr(
                                    "src"
                            );
                }

                pic =
                        fixUrl(pic);
            }

            logger(
                    "详情 pic = " +
                    pic
            );

            /**
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
                    "详情 content length = " +
                    content.length()
            );

            /**
             * ====================================================
             * 播放列表
             * ====================================================
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
                        a.attr("href")
                                .trim();

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
                        "播放源已设置"
                );

            } else {

                logger(
                        "⚠️ 没有找到播放地址"
                );

                vod.setVodPlayFrom("");
                vod.setVodPlayUrl("");
            }

            logger(
                    "detailContent 完成"
            );

            logger("================================");

            return Result.string(vod);

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
     * ============================================================
     * 播放
     * ============================================================
     */
    @Override
    public String playerContent(
            String flag,
            String id,
            List<String> vipFlags) {

        try {

            logger("================================");
            logger("========== playerContent ==========");

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
                    "最终播放 URL = " +
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

                return Result.get()
                        .parse(0)
                        .url(playUrl)
                        .header(
                                getHeaders(
                                        HOST + "/"
                                )
                        )
                        .string();
            }

            logger(
                    "检测为播放页面"
            );

            return Result.get()
                    .parse(1)
                    .url(playUrl)
                    .header(
                            getHeaders(
                                    HOST + "/"
                            )
                    )
                    .string();

        } catch (Exception e) {

            logger(
                    "❌ playerContent 异常: " +
                    e.getMessage()
            );

            return Result.get()
                    .parse(1)
                    .url(id)
                    .string();
        }
    }

    /**
     * ============================================================
     * 搜索
     * ============================================================
     */
    @Override
    public String searchContent(
            String key,
            boolean quick) {

        try {

            logger("================================");
            logger("========== searchContent ==========");

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

            /**
             * 搜索站与主站不同，
             * 不使用主站 Referer / Origin。
             */
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

            logger(
                    "搜索 User-Agent = " +
                    UA
            );

            String jsonStr =
                    OkHttp.string(
                            searchUrl,
                            headers
                    );

            if (TextUtils.isEmpty(
                    jsonStr
            )) {

                logger(
                        "❌ 搜索响应为空"
                );

                return Result.string(
                        new ArrayList<>()
                );
            }

            logger(
                    "搜索响应长度 = " +
                    jsonStr.length()
            );

            String preview =
                    jsonStr.replace(
                            "\n",
                            " "
                    );

            if (preview.length() > 500) {

                preview =
                        preview.substring(
                                0,
                                500
                        );
            }

            logger(
                    "搜索响应预览 = " +
                    preview
            );

            JSONArray jsonArray =
                    new JSONArray(jsonStr);

            logger(
                    "JSON 数量 = " +
                    jsonArray.length()
            );

            List<Vod> resultList =
                    new ArrayList<>();

            for (int i = 0;
                 i < jsonArray.length();
                 i++) {

                JSONObject item =
                        jsonArray.getJSONObject(i);

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

                resultList.add(
                        new Vod(
                                href,
                                title,
                                image,
                                ""
                        )
                );
            }

            logger(
                    "搜索最终结果 = " +
                    resultList.size()
            );

            logger("================================");

            return Result.string(
                    resultList
            );

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
