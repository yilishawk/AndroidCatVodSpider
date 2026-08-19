package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.bean.Class;
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

public class SiniTV extends Spider {

    private static final String HOST = "https://sinitv.cc";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36";

    private static final LinkedHashMap<String, String> CATEGORY_MAP =
            new LinkedHashMap<>();

    static {
        CATEGORY_MAP.put("电视剧", "1");
        CATEGORY_MAP.put("电影", "2");
    }

    // =========================================================
    // HTTP
    // =========================================================

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();

        headers.put("User-Agent", UA);
        headers.put("Referer", HOST + "/");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

        return headers;
    }

    private String get(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }

        try {
            return OkHttp.string(url, getHeaders());
        } catch (Exception e) {
            return "";
        }
    }

    // =========================================================
    // URL
    // =========================================================

    /**
     * 把相对 URL 转换为绝对 URL。
     */
    private String absUrl(String url) {

        if (TextUtils.isEmpty(url)) {
            return "";
        }

        url = cleanUrl(url);

        if (url.startsWith("http://") ||
                url.startsWith("https://")) {
            return url;
        }

        if (url.startsWith("//")) {
            return "https:" + url;
        }

        if (url.startsWith("/")) {
            return HOST + url;
        }

        return HOST + "/" + url;
    }

    /**
     * 清理网页 URL 转义。
     */
    private String cleanUrl(String url) {

        if (TextUtils.isEmpty(url)) {
            return "";
        }

        return url
                .trim()
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("&amp;", "&")
                .replace("\\\"", "\"");
    }

    // =========================================================
    // HOME
    // =========================================================

    /**
     * 首页分类。
     */
    @Override
    public String homeContent(boolean filter) {

        try {

            List<Class> classes = new ArrayList<>();

            for (Map.Entry<String, String> entry :
                    CATEGORY_MAP.entrySet()) {

                classes.add(
                        new Class(
                                entry.getValue(),
                                entry.getKey()
                        )
                );
            }

            return Result.string(classes);

        } catch (Exception e) {

            return Result.string(
                    new ArrayList<Class>()
            );
        }
    }

    /**
     * 首页推荐。
     */
    @Override
    public String homeVideoContent() {

        try {

            String html = get(HOST + "/");

            if (TextUtils.isEmpty(html)) {
                return Result.string(
                        new ArrayList<Vod>()
                );
            }

            List<Vod> list =
                    parseVodList(html);

            return Result.string(list);

        } catch (Exception e) {

            return Result.string(
                    new ArrayList<Vod>()
            );
        }
    }

    // =========================================================
    // CATEGORY
    // =========================================================

    /**
     * 分类列表。
     *
     * FongMi:
     *
     * tid  = 分类 ID
     * pg   = 页码，从 1 开始
     * filter = 是否启用筛选
     * extend = 筛选参数
     */
    @Override
    public String categoryContent(
            String tid,
            String pg,
            boolean filter,
            HashMap<String, String> extend) throws Exception {

        List<Vod> list = new ArrayList<>();

        if (TextUtils.isEmpty(tid)) {
            return Result.string(list);
        }

        // -----------------------------------------------------
        // 页码
        // -----------------------------------------------------

        int page = 1;

        try {
            if (!TextUtils.isEmpty(pg)) {
                page = Integer.parseInt(pg);
            }
        } catch (Exception ignored) {
        }

        if (page < 1) {
            page = 1;
        }

        // -----------------------------------------------------
        // 分类 URL
        // -----------------------------------------------------
        //
        // SiniTV:
        //
        // /vodshow/1--------1---.html
        // /vodshow/1--------2---.html
        //
        // /vodshow/2--------1---.html
        //
        // -----------------------------------------------------

        String url = buildCategoryUrl(
                tid,
                page,
                filter,
                extend
        );

        String html = get(url);

        if (TextUtils.isEmpty(html)) {
            return Result.string(list);
        }

        // -----------------------------------------------------
        // 解析影片
        // -----------------------------------------------------

        list = parseVodList(html);

        // -----------------------------------------------------
        // 解析总页数
        // -----------------------------------------------------

        int pageCount =
                parsePageCount(
                        html,
                        page
                );

        /*
         * 注意：
         *
         * 不同 FongMi fork 的 Result.java
         * 可能没有：
         *
         * Result.string(list, pageCount)
         *
         * 因此这里使用 JSONObject 方式生成，
         * 避免依赖 Result 的重载。
         */

        return resultWithPageCount(
                list,
                pageCount
        );
    }

    /**
     * 构造分类 URL。
     *
     * 当前 SiniTV 分类格式：
     *
     * /vodshow/{tid}--------{page}---.html
     */
    private String buildCategoryUrl(
            String tid,
            int page,
            boolean filter,
            HashMap<String, String> extend) {

        StringBuilder url =
                new StringBuilder();

        url.append(HOST)
                .append("/vodshow/")
                .append(tid)
                .append("--------")
                .append(page)
                .append("---.html");

        /*
         * 目前不强行拼 extend。
         *
         * 原因：
         *
         * FongMi 的 extend 是 Spider 收到的
         * 筛选值，不代表网站一定使用 query 参数。
         *
         * 如果 SiniTV 实际筛选 URL 是：
         *
         * /vodshow/1---area------year---1---.html
         *
         * 必须根据实际网站规则拼接。
         *
         * 在没有真实筛选 URL 规则之前，
         * 不能凭空猜测。
         */

        return url.toString();
    }

    /**
     * 生成带 pagecount 的 Result。
     *
     * 使用 org.json，避免依赖不同 Result.java
     * 是否提供 string(list, pageCount)。
     */
    private String resultWithPageCount(
            List<Vod> list,
            int pageCount) {

        try {

            org.json.JSONObject object =
                    new org.json.JSONObject();

            org.json.JSONArray array =
                    new org.json.JSONArray();

            for (Vod vod : list) {

                if (vod == null) {
                    continue;
                }

                /*
                 * 这里不能直接依赖 Gson。
                 *
                 * Result.string(list) 已经是项目标准
                 * 序列化方式，因此正常情况下优先使用
                 * Result.string(list)。
                 */

                String json =
                        Result.string(
                                java.util.Collections.singletonList(vod)
                        );

                org.json.JSONObject result =
                        new org.json.JSONObject(json);

                org.json.JSONArray resultList =
                        result.optJSONArray("list");

                if (resultList != null &&
                        resultList.length() > 0) {

                    array.put(
                            resultList.getJSONObject(0)
                    );
                }
            }

            object.put("list", array);

            if (pageCount > 0) {
                object.put(
                        "pagecount",
                        pageCount
                );
            }

            return object.toString();

        } catch (Exception e) {

            /*
             * 如果 JSON 组装失败，
             * 至少保证正常返回影片列表。
             */
            return Result.string(list);
        }
    }

    /**
     * 解析分页总页数。
     */
    private int parsePageCount(
            String html,
            int currentPage) {

        if (TextUtils.isEmpty(html)) {
            return currentPage;
        }

        try {

            Document doc =
                    Jsoup.parse(html);

            int maxPage =
                    currentPage;

            /*
             * -------------------------------------------------
             * 1. 常见分页结构
             * -------------------------------------------------
             */

            Elements pages =
                    doc.select(
                            ".page-link, " +
                            ".pagination a, " +
                            ".mac_page a, " +
                            ".page a, " +
                            ".pages a"
                    );

            for (Element page : pages) {

                String text =
                        page.text().trim();

                String href =
                        page.attr("href").trim();

                int number =
                        extractPageNumber(
                                text,
                                href
                        );

                if (number > maxPage) {
                    maxPage = number;
                }
            }

            /*
             * -------------------------------------------------
             * 2. 从 href 中读取：
             *
             * /vodshow/1--------12---.html
             * -------------------------------------------------
             */

            Elements links =
                    doc.select("a[href]");

            for (Element link : links) {

                String href =
                        link.attr("href");

                if (!href.contains("/vodshow/")) {
                    continue;
                }

                Matcher matcher =
                        Pattern.compile(
                                "/vodshow/[^/]*?--------(\\d+)---\\.html",
                                Pattern.CASE_INSENSITIVE
                        ).matcher(href);

                if (matcher.find()) {

                    int number =
                            safeInt(
                                    matcher.group(1)
                            );

                    if (number > maxPage) {
                        maxPage = number;
                    }
                }
            }

            /*
             * -------------------------------------------------
             * 3. data-page
             * -------------------------------------------------
             */

            Elements dataPages =
                    doc.select(
                            "[data-page]"
                    );

            for (Element element :
                    dataPages) {

                int number =
                        safeInt(
                                element.attr(
                                        "data-page"
                                )
                        );

                if (number > maxPage) {
                    maxPage = number;
                }
            }

            return maxPage;

        } catch (Exception e) {

            return currentPage;
        }
    }

    /**
     * 从分页元素提取页码。
     */
    private int extractPageNumber(
            String text,
            String href) {

        int number = 0;

        if (!TextUtils.isEmpty(text)) {

            Matcher matcher =
                    Pattern.compile(
                            "\\d+"
                    ).matcher(text);

            while (matcher.find()) {

                int n =
                        safeInt(
                                matcher.group()
                        );

                if (n > number) {
                    number = n;
                }
            }
        }

        if (number > 0) {
            return number;
        }

        if (!TextUtils.isEmpty(href)) {

            Matcher matcher =
                    Pattern.compile(
                            "--------(\\d+)---\\.html",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(href);

            if (matcher.find()) {

                return safeInt(
                        matcher.group(1)
                );
            }
        }

        return 0;
    }

    // =========================================================
    // DETAIL
    // =========================================================

    @Override
    public String detailContent(
            List<String> ids) {

        try {

            if (ids == null ||
                    ids.isEmpty()) {

                return Result.string(
                        new ArrayList<Vod>()
                );
            }

            String vodId =
                    ids.get(0);

            if (TextUtils.isEmpty(vodId)) {
                return Result.string(
                        new ArrayList<Vod>()
                );
            }

            String url =
                    absUrl(vodId);

            String html =
                    get(url);

            if (TextUtils.isEmpty(html)) {

                return Result.string(
                        new ArrayList<Vod>()
                );
            }

            Document doc =
                    Jsoup.parse(
                            html,
                            url
                    );

            Vod vod =
                    new Vod();

            /*
             * ID 必须和列表阶段保持一致。
             */
            vod.setVodId(url);

            // -------------------------------------------------
            // 标题
            // -------------------------------------------------

            String name = "";

            Element title =
                    doc.selectFirst(
                            ".this-desc-title"
                    );

            if (title != null) {
                name =
                        title.text().trim();
            }

            if (TextUtils.isEmpty(name)) {

                title =
                        doc.selectFirst("h1");

                if (title != null) {
                    name =
                            title.text().trim();
                }
            }

            if (TextUtils.isEmpty(name)) {

                Element meta =
                        doc.selectFirst(
                                "meta[property=og:title]"
                        );

                if (meta != null) {
                    name =
                            meta.attr(
                                    "content"
                            ).trim();
                }
            }

            if (TextUtils.isEmpty(name)) {
                name =
                        doc.title().trim();
            }

            // -------------------------------------------------
            // 图片
            // -------------------------------------------------

            String pic = "";

            Element img =
                    doc.selectFirst(
                            ".this-pic-bj img"
                    );

            if (img == null) {

                img =
                        doc.selectFirst(
                                "meta[property=og:image]"
                        );
            }

            if (img != null) {

                if ("meta".equalsIgnoreCase(
                        img.tagName())) {

                    pic =
                            img.attr(
                                    "content"
                            );

                } else {

                    pic =
                            getImageUrl(img);
                }
            }

            pic =
                    absUrl(
                            cleanUrl(pic)
                    );

            // -------------------------------------------------
            // 演员
            // -------------------------------------------------

            String actor = "";

            Element actorElem =
                    doc.selectFirst(
                            ".this-info"
                    );

            if (actorElem != null) {

                actor =
                        actorElem.text()
                                .trim();

                actor =
                        actor
                                .replace(
                                        "Pemeran:",
                                        ""
                                )
                                .replace(
                                        "演员:",
                                        ""
                                )
                                .replace(
                                        "演員:",
                                        ""
                                )
                                .trim();
            }

            // -------------------------------------------------
            // 简介
            // -------------------------------------------------

            String content = "";

            Element descElem =
                    doc.selectFirst(
                            "#height_limit"
                    );

            if (descElem != null) {

                content =
                        descElem.text()
                                .trim();

                content =
                        content
                                .replace(
                                        "Deskripsi:",
                                        ""
                                )
                                .replace(
                                        "简介:",
                                        ""
                                )
                                .replace(
                                        "簡介:",
                                        ""
                                )
                                .trim();
            }

            if (TextUtils.isEmpty(content)) {

                Element meta =
                        doc.selectFirst(
                                "meta[property=og:description]"
                        );

                if (meta != null) {

                    content =
                            meta.attr(
                                    "content"
                            ).trim();
                }
            }

            // -------------------------------------------------
            // 播放线路
            // -------------------------------------------------

            List<String> playFromList =
                    new ArrayList<>();

            List<String> playGroupList =
                    new ArrayList<>();

            Elements fromElems =
                    doc.select(
                            ".anthology-tab .swiper-slide"
                    );

            Elements listBoxes =
                    doc.select(
                            ".anthology-list-play"
                    );

            /*
             * 正常情况下：
             *
             * fromElems[0] <-> listBoxes[0]
             * fromElems[1] <-> listBoxes[1]
             *
             * 必须一一对应。
             */

            int count =
                    Math.min(
                            fromElems.size(),
                            listBoxes.size()
                    );

            for (int i = 0;
                 i < count;
                 i++) {

                Element from =
                        fromElems.get(i);

                Element box =
                        listBoxes.get(i);

                String fromName =
                        from.text().trim();

                if (TextUtils.isEmpty(fromName)) {

                    fromName =
                            "线路" + (i + 1);
                }

                List<String> episodes =
                        parseEpisodeList(box);

                if (episodes.isEmpty()) {
                    continue;
                }

                playFromList.add(
                        fromName
                );

                playGroupList.add(
                        TextUtils.join(
                                "#",
                                episodes
                        )
                );
            }

            /*
             * 页面没有线路标题时，
             * 直接按照播放盒生成线路。
             */
            if (playGroupList.isEmpty() &&
                    !listBoxes.isEmpty()) {

                for (int i = 0;
                     i < listBoxes.size();
                     i++) {

                    Element box =
                            listBoxes.get(i);

                    List<String> episodes =
                            parseEpisodeList(box);

                    if (episodes.isEmpty()) {
                        continue;
                    }

                    playFromList.add(
                            "线路" + (i + 1)
                    );

                    playGroupList.add(
                            TextUtils.join(
                                    "#",
                                    episodes
                            )
                    );
                }
            }

            /*
             * 确保两边严格对应。
             */
            if (!playFromList.isEmpty() &&
                    playFromList.size() ==
                            playGroupList.size()) {

                vod.setVodPlayFrom(
                        TextUtils.join(
                                "$$$",
                                playFromList
                        )
                );

                vod.setVodPlayUrl(
                        TextUtils.join(
                                "$$$",
                                playGroupList
                        )
                );
            }

            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodActor(actor);
            vod.setVodContent(content);

            return Result.string(vod);

        } catch (Exception e) {

            return Result.string(
                    new ArrayList<Vod>()
            );
        }
    }

    // =========================================================
    // EPISODE
    // =========================================================

    private List<String> parseEpisodeList(
            Element box) {

        List<String> result =
                new ArrayList<>();

        if (box == null) {
            return result;
        }

        Elements links =
                box.select("a");

        for (Element link : links) {

            String epName =
                    link.text().trim();

            String href =
                    link.attr("href")
                            .trim();

            if (TextUtils.isEmpty(href)) {

                href =
                        link.attr(
                                "data-href"
                        ).trim();
            }

            if (TextUtils.isEmpty(href)) {

                href =
                        link.attr(
                                "data-url"
                        ).trim();
            }

            if (TextUtils.isEmpty(href)) {
                continue;
            }

            href =
                    cleanUrl(href);

            /*
             * 直接媒体地址保持原样。
             */
            if (!isVideoUrl(href)) {
                href = absUrl(href);
            }

            if (TextUtils.isEmpty(epName)) {

                epName =
                        "第" +
                        (result.size() + 1) +
                        "集";
            }

            result.add(
                    epName + "$" + href
            );
        }

        return result;
    }

    // =========================================================
    // SEARCH
    // =========================================================

    @Override
    public String searchContent(
            String key,
            boolean quick) {

        return searchContent(
                key,
                quick,
                "1"
        );
    }

    @Override
    public String searchContent(
            String key,
            boolean quick,
            String pg) {

        if (TextUtils.isEmpty(key)) {

            return Result.string(
                    new ArrayList<Vod>()
            );
        }

        try {

            String keyword;

            try {

                keyword =
                        URLEncoder.encode(
                                key.trim(),
                                "UTF-8"
                        );

            } catch (Exception e) {

                keyword =
                        key.trim();
            }

            int page = 1;

            try {

                page =
                        Integer.parseInt(pg);

            } catch (Exception ignored) {
            }

            if (page < 1) {
                page = 1;
            }

            String url;

            if (page <= 1) {

                url =
                        HOST +
                        "/vodsearch/-" +
                        keyword +
                        "------------.html";

            } else {

                url =
                        HOST +
                        "/vodsearch/-" +
                        keyword +
                        "----------" +
                        page +
                        "---.html";
            }

            String html =
                    get(url);

            if (TextUtils.isEmpty(html)) {

                return Result.string(
                        new ArrayList<Vod>()
                );
            }

            List<Vod> list =
                    parseVodList(html);

            return Result.string(list);

        } catch (Exception e) {

            return Result.string(
                    new ArrayList<Vod>()
            );
        }
    }

    // =========================================================
    // PLAYER
    // =========================================================

    @Override
    public String playerContent(
            String flag,
            String id,
            List<String> vipFlags) {

        try {

            if (TextUtils.isEmpty(id)) {

                return Result.get()
                        .msg("播放地址为空")
                        .string();
            }

            id =
                    cleanUrl(id);

            /*
             * 已经是 m3u8/mp4 等直链：
             * 直接播放。
             */
            if (isVideoUrl(id)) {

                return Result.get()
                        .parse(0)
                        .url(id)
                        .header(getHeaders())
                        .string();
            }

            String playPageUrl =
                    absUrl(id);

            String html =
                    get(playPageUrl);

            if (TextUtils.isEmpty(html)) {

                return Result.get()
                        .parse(1)
                        .url(playPageUrl)
                        .header(getHeaders())
                        .string();
            }

            String realPlayUrl =
                    findPlayUrl(html);

            if (TextUtils.isEmpty(
                    realPlayUrl)) {

                return Result.get()
                        .parse(1)
                        .url(playPageUrl)
                        .header(getHeaders())
                        .string();
            }

            realPlayUrl =
                    cleanUrl(realPlayUrl);

            if (realPlayUrl.startsWith("//")) {

                realPlayUrl =
                        "https:" + realPlayUrl;
            }

            Map<String, String> headers =
                    new HashMap<>();

            headers.put(
                    "User-Agent",
                    UA
            );

            headers.put(
                    "Referer",
                    playPageUrl
            );

            return Result.get()
                    .parse(0)
                    .url(realPlayUrl)
                    .header(headers)
                    .string();

        } catch (Exception e) {

            return Result.get()
                    .parse(1)
                    .url(id)
                    .string();
        }
    }

    /**
     * 寻找真实视频地址。
     */
    private String findPlayUrl(
            String html) {

        String result;

        // "url":"xxx.m3u8"
        result =
                findByPattern(
                        html,
                        "\"url\"\\s*:\\s*\"([^\"]+)\""
                );

        if (isVideoUrl(result)) {
            return result;
        }

        // "playUrl":"xxx"
        result =
                findByPattern(
                        html,
                        "\"playUrl\"\\s*:\\s*\"([^\"]+)\""
                );

        if (isVideoUrl(result)) {
            return result;
        }

        // "play_url":"xxx"
        result =
                findByPattern(
                        html,
                        "\"play_url\"\\s*:\\s*\"([^\"]+)\""
                );

        if (isVideoUrl(result)) {
            return result;
        }

        // m3u8
        result =
                findByPattern(
                        html,
                        "https?[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*"
                );

        if (!TextUtils.isEmpty(result)) {
            return result;
        }

        // mp4
        result =
                findByPattern(
                        html,
                        "https?[^\"'\\s<>]+\\.mp4[^\"'\\s<>]*"
                );

        if (!TextUtils.isEmpty(result)) {
            return result;
        }

        // source src
        result =
                findByPattern(
                        html,
                        "source[^>]+src=[\"']([^\"']+)[\"']"
                );

        if (!TextUtils.isEmpty(result)) {
            return result;
        }

        // data-url
        result =
                findByPattern(
                        html,
                        "data-url=[\"']([^\"']+)[\"']"
                );

        if (isVideoUrl(result)) {
            return result;
        }

        return "";
    }

    /**
     * 正则提取。
     */
    private String findByPattern(
            String text,
            String regex) {

        if (TextUtils.isEmpty(text)) {
            return "";
        }

        try {

            Pattern pattern =
                    Pattern.compile(
                            regex,
                            Pattern.CASE_INSENSITIVE
                    );

            Matcher matcher =
                    pattern.matcher(text);

            if (matcher.find()) {

                if (matcher.groupCount() >= 1) {
                    return matcher.group(1);
                }

                return matcher.group();
            }

        } catch (Exception ignored) {
        }

        return "";
    }

    // =========================================================
    // VIDEO URL
    // =========================================================

    private boolean isVideoUrl(
            String url) {

        if (TextUtils.isEmpty(url)) {
            return false;
        }

        String lower =
                url.toLowerCase();

        return lower.contains(".m3u8")
                || lower.contains(".mp4")
                || lower.contains(".mkv")
                || lower.contains(".flv")
                || lower.contains(".ts")
                || lower.contains(".webm")
                || lower.contains(".mpd");
    }

    // =========================================================
    // LIST
    // =========================================================

    private List<Vod> parseVodList(
            String html) {

        List<Vod> list =
                new ArrayList<>();

        if (TextUtils.isEmpty(html)) {
            return list;
        }

        try {

            Document doc =
                    Jsoup.parse(
                            html,
                            HOST
                    );

            Elements items =
                    doc.select(
                            ".public-list-box"
                    );

            /*
             * 备用结构。
             */
            if (items.isEmpty()) {

                items =
                        doc.select(
                                ".module-item, " +
                                ".module-card-item"
                        );
            }

            for (Element item :
                    items) {

                Element link =
                        item.selectFirst(
                                "a.public-list-exp"
                        );

                if (link == null) {

                    link =
                            item.selectFirst(
                                    "a[href]"
                            );
                }

                if (link == null) {
                    continue;
                }

                String href =
                        link.attr(
                                "href"
                        ).trim();

                if (TextUtils.isEmpty(href)) {

                    href =
                            link.attr(
                                    "data-href"
                            ).trim();
                }

                if (TextUtils.isEmpty(href)) {
                    continue;
                }

                href =
                        cleanUrl(href);

                String vodId =
                        absUrl(href);

                // ---------------------------------------------
                // 名称
                // ---------------------------------------------

                String name =
                        link.attr(
                                "title"
                        ).trim();

                if (TextUtils.isEmpty(name)) {

                    Element title =
                            item.selectFirst(
                                    ".public-list-title"
                            );

                    if (title != null) {

                        name =
                                title.text()
                                        .trim();
                    }
                }

                if (TextUtils.isEmpty(name)) {

                    name =
                            link.text()
                                    .trim();
                }

                // ---------------------------------------------
                // 图片
                // ---------------------------------------------

                String pic = "";

                Element img =
                        item.selectFirst(
                                "img"
                        );

                if (img != null) {

                    pic =
                            getImageUrl(img);
                }

                pic =
                        absUrl(
                                cleanUrl(pic)
                        );

                // ---------------------------------------------
                // 备注
                // ---------------------------------------------

                String remarks = "";

                Element remark =
                        item.selectFirst(
                                ".public-list-prb"
                        );

                if (remark != null) {

                    remarks =
                            remark.text()
                                    .trim();
                }

                // ---------------------------------------------
                // Vod
                // ---------------------------------------------

                Vod vod =
                        new Vod();

                vod.setVodId(vodId);
                vod.setVodName(name);
                vod.setVodPic(pic);

                /*
                 * 你的项目如果有 setVodRemarks()
                 * 就设置。
                 *
                 * 如果旧版 Vod 没有这个方法，
                 * 不影响其他字段。
                 */
                try {

                    vod.setVodRemarks(
                            remarks
                    );

                } catch (Throwable ignored) {
                }

                list.add(vod);
            }

        } catch (Exception ignored) {
        }

        return list;
    }

    // =========================================================
    // IMAGE
    // =========================================================

    private String getImageUrl(
            Element img) {

        if (img == null) {
            return "";
        }

        String url =
                img.attr(
                        "data-src"
                ).trim();

        if (TextUtils.isEmpty(url)) {

            url =
                    img.attr(
                            "data-original"
                    ).trim();
        }

        if (TextUtils.isEmpty(url)) {

            url =
                    img.attr(
                            "data-url"
                    ).trim();
        }

        if (TextUtils.isEmpty(url)) {

            url =
                    img.attr(
                            "src"
                    ).trim();
        }

        return cleanUrl(url);
    }

    // =========================================================
    // UTIL
    // =========================================================

    private int safeInt(
            String value) {

        try {

            return Integer.parseInt(
                    value
            );

        } catch (Exception e) {

            return 0;
        }
    }
}
