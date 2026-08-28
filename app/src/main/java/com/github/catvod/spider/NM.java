package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 农民影视
 * 网络走项目 OkHttp；返回走Class / Vod / Result，兼容新 FongMi 壳
 */
public class NM extends Spider {

    private static final String siteUrl = "https://vip.wwgz.cn:5200";
    private static final String apiHost = "https://api.wwgz.cn:520";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";

    private Map<String, String> getHeaderMap() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Referer", siteUrl + "/");
        return headers;
    }

    private String fetch(String url) throws Exception {
        String html = OkHttp.string(url, getHeaderMap());
        if (html == null || html.isEmpty()) {
            throw new Exception("Request empty: " + url);
        }
        return html;
    }

    // ==================== 首页 ====================
    @Override
    public String homeContent(boolean filter) {
        try {
            List<Class> classes = new ArrayList<>();
            classes.add(new Class("12", "国产剧"));
            classes.add(new Class("1", "电影"));
            classes.add(new Class("2", "电视剧"));
            classes.add(new Class("3", "综艺"));
            classes.add(new Class("26", "短剧"));

            if (!filter) {
                return Result.string(classes);
            }

            List<Filter.Value> areaOptions = new ArrayList<>();
            areaOptions.add(new Filter.Value("全部", ""));
            for (String area : new String[]{
                    "大陆", "香港", "台湾", "美国", "日本", "韩国", "英国", "法国",
                    "泰国", "新加坡", "马来西亚", "印度", "加拿大", "西班牙", "俄罗斯", "其它"
            }) {
                areaOptions.add(new Filter.Value(area, area));
            }

            List<Filter.Value> yearOptions = new ArrayList<>();
            yearOptions.add(new Filter.Value("全部", "0"));
            for (int y = 2026; y >= 2005; y--) {
                yearOptions.add(new Filter.Value(String.valueOf(y), String.valueOf(y)));
            }

            List<Filter.Value> orderOptions = Arrays.asList(
                    new Filter.Value("最新", "time"),
                    new Filter.Value("最热", "hits"),
                    new Filter.Value("评分", "score")
            );

            List<Filter.Value> movieType = new ArrayList<>();
            movieType.add(new Filter.Value("全部", "0"));
            String[][] mTypes = {
                    {"动作片", "5"}, {"喜剧片", "6"}, {"爱情片", "7"}, {"科幻片", "8"},
                    {"恐怖片", "9"}, {"剧情片", "10"}, {"战争片", "11"}, {"惊悚片", "16"}, {"奇幻片", "17"}
            };
            for (String[] t : mTypes) movieType.add(new Filter.Value(t[0], t[1]));

            List<Filter.Value> tvType = new ArrayList<>();
            tvType.add(new Filter.Value("全部", "0"));
            String[][] tvTypes = {
                    {"国产剧", "12"}, {"港台泰", "13"}, {"日韩剧", "14"}, {"欧美剧", "15"}
            };
            for (String[] t : tvTypes) tvType.add(new Filter.Value(t[0], t[1]));

            List<Filter.Value> onlyAll = Collections.singletonList(new Filter.Value("全部", "0"));

            LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();

            // 电影
            filters.put("1", Arrays.asList(
                    new Filter("class", "类型", movieType),
                    new Filter("area", "地区", areaOptions),
                    new Filter("year", "年份", yearOptions),
                    new Filter("order", "排序", orderOptions)
            ));
            // 国产剧
            filters.put("12", Arrays.asList(
                    new Filter("area", "地区", areaOptions),
                    new Filter("year", "年份", yearOptions),
                    new Filter("order", "排序", orderOptions)
            ));
            // 电视剧
            filters.put("2", Arrays.asList(
                    new Filter("class", "类型", tvType),
                    new Filter("area", "地区", areaOptions),
                    new Filter("year", "年份", yearOptions),
                    new Filter("order", "排序", orderOptions)
            ));
            // 综艺
            filters.put("3", Arrays.asList(
                    new Filter("class", "类型", onlyAll),
                    new Filter("area", "地区", areaOptions),
                    new Filter("year", "年份", yearOptions),
                    new Filter("order", "排序", orderOptions)
            ));
            // 短剧
            filters.put("26", Arrays.asList(
                    new Filter("class", "类型", onlyAll),
                    new Filter("area", "地区", areaOptions),
                    new Filter("year", "年份", yearOptions),
                    new Filter("order", "排序", orderOptions)
            ));

            return Result.string(classes, filters);
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.error(e.getMessage());
        }
    }

    private int getTotalPages(Document doc) {
        Elements pageLinks = doc.select(".page a");
        int max = 1;
        for (Element a : pageLinks) {
            String text = a.text().trim();
            if (text.matches("\\d+")) {
                int p = Integer.parseInt(text);
                if (p > max) max = p;
            }
        }
        return max;
    }

    // ==================== 分类 ====================
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            if (extend == null) extend = new HashMap<>();
            String order = extend.getOrDefault("order", "time");
            String classId = extend.getOrDefault("class", "0");
            String year = extend.getOrDefault("year", "0");
            String area = extend.getOrDefault("area", "");

            String listId = !"0".equals(classId) ? classId : tid;
            String yearPart = "0".equals(year) ? "--" : "-" + year;
            String areaPart;
            if (area == null || area.isEmpty()) {
                areaPart = "--";
            } else {
                try {
                    areaPart = "-" + URLEncoder.encode(area, "UTF-8");
                } catch (Exception e) {
                    areaPart = "-" + area;
                }
            }

            String url = siteUrl + String.format(
                    "/vod-list-id-%s-pg-%s-order--by-%s-class-%s-year%s-letter--area%s-lang-.html",
                    listId, pg, order, "0", yearPart, areaPart
            );

            String html = fetch(url);
            Document doc = Jsoup.parse(html);
            Elements items = doc.select("ul.resize_list li");

            List<Vod> list = new ArrayList<>();
            for (Element li : items) {
                Element a = li.selectFirst("a");
                if (a == null) continue;
                String href = a.attr("href");
                String title = a.attr("title");
                if (title.isEmpty()) title = a.text().trim();

                String picUrl = "";
                Element picDiv = li.selectFirst("div.pic");
                if (picDiv != null) {
                    Element img = picDiv.selectFirst("img");
                    if (img != null) {
                        picUrl = img.attr("data-echo");
                        if (picUrl.isEmpty()) picUrl = img.attr("src");
                    }
                }

                String remarks = "";
                Element span = li.selectFirst("span.sBottom span");
                if (span != null) remarks = span.text().trim();

                String vodId;
                if (href.startsWith("/vod-detail-id-")) {
                    String detailId = href.split("-")[3].replace(".html", "");
                    vodId = "detail_" + detailId;
                } else {
                    vodId = href;
                }

                Vod vod = new Vod();
                vod.setVodId(vodId);
                vod.setVodName(title);
                vod.setVodPic(picUrl);
                vod.setVodRemarks(remarks);
                list.add(vod);
            }

            int totalPages = getTotalPages(doc);
            int page = Integer.parseInt(pg);
            return Result.get()
                    .vod(list)
                    .page(page, totalPages, list.size(), totalPages * 20)
                    .string();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.error(e.getMessage());
        }
    }

    // ==================== 详情 ====================
    @Override
    public String detailContent(List<String> ids) {
        try {
            String vodId = ids.get(0);
            String detailId = "";
            String detailUrl;
            if (vodId.startsWith("detail_")) {
                detailId = vodId.substring(7);
                detailUrl = siteUrl + "/vod-detail-id-" + detailId + ".html";
            } else {
                detailUrl = vodId.startsWith("http") ? vodId : siteUrl + vodId;
                Matcher m = Pattern.compile("vod-detail-id-(\\d+)").matcher(detailUrl);
                if (m.find()) detailId = m.group(1);
            }

            String html = fetch(detailUrl);
            Document doc = Jsoup.parse(html);

            Element titleEl = doc.selectFirst("h1.title a");
            String title = titleEl != null ? titleEl.text().trim() : "";

            String pic = "";
            Element picEl = doc.selectFirst(".page-hd img");
            if (picEl != null) {
                pic = picEl.attr("src");
                if (pic.isEmpty()) pic = picEl.attr("data-echo");
            }

            StringBuilder actor = new StringBuilder();
            for (Element a : doc.select(".desc_item:contains(主演:) a")) {
                if (actor.length() > 0) actor.append(", ");
                actor.append(a.text().trim());
            }

            StringBuilder director = new StringBuilder();
            for (Element a : doc.select(".desc_item:contains(导演:) a")) {
                if (director.length() > 0) director.append(", ");
                director.append(a.text().trim());
            }

            Element yearEl = doc.selectFirst(".desc_item:contains(年代:) a");
            String year = yearEl != null ? yearEl.text().trim() : "";

            String area = "";
            Element areaEl = doc.selectFirst(".desc_item:contains(地区:) a");
            if (areaEl != null) area = areaEl.text().trim();

            String typeName = "";
            Element typeEl = doc.selectFirst(".type-title");
            if (typeEl != null) typeName = typeEl.text().trim();

            Element introEl = doc.selectFirst("article.detail-con p");
            if (introEl == null) introEl = doc.selectFirst(".detail-con");
            String intro = introEl != null ? introEl.text().replaceAll("\\s+", " ").trim() : "";

            List<String> playFromList = new ArrayList<>();
            List<String> playUrlList = new ArrayList<>();

            if (!detailId.isEmpty()) {
                String playPageUrl = siteUrl + "/vod-play-id-" + detailId + "-src-1-num-1.html";
                try {
                    String playHtml = fetch(playPageUrl);
                    Matcher fromMatcher = Pattern.compile("mac_from\\s*=\\s*'([^']+)'").matcher(playHtml);
                    Matcher urlMatcher = Pattern.compile("mac_url\\s*=\\s*'([^']+)'").matcher(playHtml);

                    if (fromMatcher.find() && urlMatcher.find()) {
                        String[] fromParts = fromMatcher.group(1).split("\\$\\$\\$");
                        String[] urlParts = urlMatcher.group(1).split("\\$\\$\\$");
                        int lineCount = Math.min(fromParts.length, urlParts.length);
                        for (int i = 0; i < lineCount; i++) {
                            String lineName = fromParts[i].trim();
                            if (lineName.isEmpty()) lineName = "线路" + (i + 1);

                            List<String> epList = new ArrayList<>();
                            for (String ep : urlParts[i].split("#")) {
                                if (ep.trim().isEmpty()) continue;
                                epList.add(ep.trim());
                            }
                            Collections.sort(epList, (o1, o2) ->
                                    Integer.compare(extractEpisodeNumber(o1), extractEpisodeNumber(o2)));
                            if (!epList.isEmpty()) {
                                playFromList.add(lineName);
                                playUrlList.add(String.join("#", epList));
                            }
                        }
                    }
                } catch (Exception ignored) {
                    SpiderDebug.log(ignored);
                }
            }

            Vod vod = new Vod();
            vod.setVodId(vodId);
            vod.setVodName(title);
            vod.setVodPic(pic);
            vod.setTypeName(typeName);
            vod.setVodYear(year);
            vod.setVodArea(area);
            vod.setVodDirector(director.toString());
            vod.setVodActor(actor.toString());
            vod.setVodContent(intro);
            vod.setVodPlayFrom(String.join("$$$", playFromList));
            vod.setVodPlayUrl(String.join("$$$", playUrlList));
            return Result.string(vod);
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.error(e.getMessage());
        }
    }

    private int extractEpisodeNumber(String s) {
        Matcher m = Pattern.compile("第(\\d+)集").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }

    // ==================== 搜索 ====================
    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String pg = "1";
            String url = siteUrl + "/vod-search-pg-" + pg + "-wd-" + URLEncoder.encode(key, "UTF-8") + ".html";
            String html = fetch(url);
            Document doc = Jsoup.parse(html);
            Elements items = doc.select("ul#data_list li");
            if (items.isEmpty()) items = doc.select("ul.ulPicTxt li");

            List<Vod> list = new ArrayList<>();
            for (Element li : items) {
                Element titleEl = li.selectFirst(".txt .sTit");
                if (titleEl == null) titleEl = li.selectFirst("a[title]");
                String title = titleEl != null ? titleEl.text().trim() : "";

                Element detailA = li.selectFirst(".pic a");
                if (detailA == null) detailA = li.selectFirst(".aPlayBtn");
                String href = detailA != null ? detailA.attr("href") : "";
                if (href.isEmpty() || title.isEmpty()) continue;

                String picUrl = "";
                Element imgEl = li.selectFirst(".pic img");
                if (imgEl != null) {
                    picUrl = imgEl.attr("data-src");
                    if (picUrl.isEmpty()) picUrl = imgEl.attr("src");
                }

                Element remarksEl = li.selectFirst(".sStyle");
                if (remarksEl == null) remarksEl = li.selectFirst(".sDes em:not(.emTit)");
                String remarks = remarksEl != null ? remarksEl.text().trim() : "";

                String vodId;
                if (href.startsWith("/vod-detail-id-")) {
                    String detailId = href.split("-")[3].replace(".html", "");
                    vodId = "detail_" + detailId;
                } else {
                    vodId = href;
                }

                Vod vod = new Vod();
                vod.setVodId(vodId);
                vod.setVodName(title);
                vod.setVodPic(picUrl);
                vod.setVodRemarks(remarks);
                list.add(vod);
            }

            int pageCount = 1;
            Element lastPage = doc.selectFirst(".page a:last-child");
            if (lastPage != null) {
                Matcher m = Pattern.compile("pg-(\\d+)").matcher(lastPage.attr("href"));
                if (m.find()) pageCount = Integer.parseInt(m.group(1));
            }

            return Result.get()
                    .vod(list)
                    .page(Integer.parseInt(pg), pageCount, list.size(), list.size() * pageCount)
                    .string();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.error(e.getMessage());
        }
    }

    // ==================== 播放 ====================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            if (id != null && !id.contains("http") && !id.contains("$") && !id.contains("?")) {
                String apiUrl = apiHost + "/player/?url=" + id;
                String res = fetch(apiUrl);
                Matcher urlMatcher = Pattern.compile("\"url\":\\s*\"([^\"]+)\"").matcher(res);
                if (urlMatcher.find()) {
                    return successPlayerResult(urlMatcher.group(1).replace("\\u0026", "&"));
                }
                Matcher iframeMatcher = Pattern.compile("<iframe[^>]+src=\"([^\"]+)\"").matcher(res);
                if (iframeMatcher.find()) {
                    return successPlayerResult(iframeMatcher.group(1));
                }
            } else {
                String playUrl = id != null && id.startsWith("http") ? id : siteUrl + id;
                String html = fetch(playUrl);
                Matcher macUrlMatcher = Pattern.compile("mac_url\\s*=\\s*'([^']+)'").matcher(html);
                if (!macUrlMatcher.find()) {
                    return fallbackToParse(playUrl);
                }
                String macUrl = macUrlMatcher.group(1);
                int currentNum = 1;
                Matcher numMatcher = Pattern.compile("-num-(\\d+)\\.html").matcher(playUrl);
                if (numMatcher.find()) currentNum = Integer.parseInt(numMatcher.group(1));

                String targetEncrypted = null;
                String[] lines = macUrl.split("\\$\\$\\$");
                for (String line : lines) {
                    for (String part : line.split("#")) {
                        Matcher m = Pattern.compile("第(\\d+)集\\$(.*)").matcher(part);
                        if (m.find() && Integer.parseInt(m.group(1)) == currentNum) {
                            targetEncrypted = m.group(2);
                            break;
                        }
                    }
                    if (targetEncrypted != null) break;
                }
                if (targetEncrypted == null) {
                    // 修复：正则中匹配字面括号，使用 \\( 和 \\)
                    Pattern p = Pattern.compile("第" + currentNum + "集\\( (.*?)(?=#| \\))");
                    for (String line : lines) {
                        Matcher m = p.matcher(line);
                        if (m.find()) {
                            targetEncrypted = m.group(1);
                            break;
                        }
                    }
                }

                if (targetEncrypted != null && !targetEncrypted.isEmpty()) {
                    String apiUrl = apiHost + "/player/?url=" + targetEncrypted;
                    String apiRes = fetch(apiUrl);
                    Matcher urlMatcher = Pattern.compile("\"url\":\\s*\"([^\"]+)\"").matcher(apiRes);
                    if (urlMatcher.find()) {
                        return successPlayerResult(urlMatcher.group(1).replace("\\u0026", "&"));
                    }
                }
            }
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return fallbackToParse(id);
    }

    private String successPlayerResult(String realUrl) {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", UA);
        header.put("Referer", siteUrl);
        return Result.get()
                .parse(0)
                .url(realUrl)
                .header(header)
                .string();
    }

    private String fallbackToParse(String url) {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", UA);
        header.put("Referer", siteUrl);
        return Result.get()
                .parse(1)
                .url(url != null ? url : "")
                .header(header)
                .string();
    }
}
