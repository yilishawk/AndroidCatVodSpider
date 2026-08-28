package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author wwgz
 * 农民影视 (Python 版逻辑，对齐 TVBox Spider 规格)
 */
public class NM extends Spider {

    private static final String siteUrl = "https://vip.wwgz.cn:5200";
    private static final String apiHost = "https://api.wwgz.cn:520";

    private final OkHttpClient client = new OkHttpClient();

    private Headers getHeaders() {
        return new Headers.Builder()
                .add("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
                .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .add("Accept-Language", "zh-CN,zh;q=0.9")
                .build();
    }

    private String fetch(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .headers(getHeaders())
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            } else {
                throw new Exception("Request failed: " + response.code());
            }
        }
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();

            String[][] classArr = {
                    {"12", "国产剧"},
                    {"1", "电影"},
                    {"2", "电视剧"},
                    {"3", "综艺"},
                    {"26", "短剧"}
            };
            for (String[] c : classArr) {
                JSONObject obj = new JSONObject();
                obj.put("type_id", c[0]);
                obj.put("type_name", c[1]);
                classes.put(obj);
            }
            result.put("class", classes);

            if (filter) {
                JSONObject filters = new JSONObject();

                // 公共选项
                JSONArray areaOptions = new JSONArray();
                areaOptions.put(createOption("全部", ""));
                for (String area : new String[]{"大陆","香港","台湾","美国","日本","韩国","英国","法国","泰国","新加坡","马来西亚","印度","加拿大","西班牙","俄罗斯","其它"}) {
                    areaOptions.put(createOption(area, area));
                }

                JSONArray yearOptions = new JSONArray();
                yearOptions.put(createOption("全部", "0"));
                for (int y = 2025; y >= 2005; y--) {
                    yearOptions.put(createOption(String.valueOf(y), String.valueOf(y)));
                }

                JSONArray orderOptions = new JSONArray();
                orderOptions.put(createOption("最新", "time"));
                orderOptions.put(createOption("最热", "hits"));
                orderOptions.put(createOption("评分", "score"));

                // 电影类型
                JSONArray movieType = new JSONArray();
                movieType.put(createOption("全部", "0"));
                String[][] mTypes = {{"动作片","5"},{"喜剧片","6"},{"爱情片","7"},{"科幻片","8"},{"恐怖片","9"},{"剧情片","10"},{"战争片","11"},{"惊悚片","16"},{"奇幻片","17"}};
                for (String[] t : mTypes) movieType.put(createOption(t[0], t[1]));

                // 电视剧类型
                JSONArray tvType = new JSONArray();
                tvType.put(createOption("全部", "0"));
                String[][] tvTypes = {{"国产剧","12"},{"港台泰","13"},{"日韩剧","14"},{"欧美剧","15"}};
                for (String[] t : tvTypes) tvType.put(createOption(t[0], t[1]));

                JSONArray onlyAll = new JSONArray();
                onlyAll.put(createOption("全部", "0"));

                // 电影 (1)
                JSONArray movieFilters = new JSONArray();
                movieFilters.put(createFilter("class", "类型", movieType));
                movieFilters.put(createFilter("area", "地区", areaOptions));
                movieFilters.put(createFilter("year", "年份", yearOptions));
                movieFilters.put(createFilter("order", "排序", orderOptions));
                filters.put("1", movieFilters);

                // 国产剧 (12) - 无 class 筛选
                JSONArray domesticFilters = new JSONArray();
                domesticFilters.put(createFilter("area", "地区", areaOptions));
                domesticFilters.put(createFilter("year", "年份", yearOptions));
                domesticFilters.put(createFilter("order", "排序", orderOptions));
                filters.put("12", domesticFilters);

                // 电视剧 (2)
                JSONArray tvFilters = new JSONArray();
                tvFilters.put(createFilter("class", "类型", tvType));
                tvFilters.put(createFilter("area", "地区", areaOptions));
                tvFilters.put(createFilter("year", "年份", yearOptions));
                tvFilters.put(createFilter("order", "排序", orderOptions));
                filters.put("2", tvFilters);

                // 综艺 (3)
                JSONArray varietyFilters = new JSONArray();
                varietyFilters.put(createFilter("class", "类型", onlyAll));
                varietyFilters.put(createFilter("area", "地区", areaOptions));
                varietyFilters.put(createFilter("year", "年份", yearOptions));
                varietyFilters.put(createFilter("order", "排序", orderOptions));
                filters.put("3", varietyFilters);

                // 短剧 (26)
                JSONArray shortFilters = new JSONArray();
                shortFilters.put(createFilter("class", "类型", onlyAll));
                shortFilters.put(createFilter("area", "地区", areaOptions));
                shortFilters.put(createFilter("year", "年份", yearOptions));
                shortFilters.put(createFilter("order", "排序", orderOptions));
                filters.put("26", shortFilters);

                result.put("filters", filters);
            }

            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return errorMsg(e.getMessage());
        }
    }

    private JSONObject createOption(String n, String v) throws Exception {
        JSONObject opt = new JSONObject();
        opt.put("n", n);
        opt.put("v", v);
        return opt;
    }

    private JSONObject createFilter(String key, String name, JSONArray value) throws Exception {
        JSONObject f = new JSONObject();
        f.put("key", key);
        f.put("name", name);
        f.put("value", value);
        return f;
    }

    private String errorMsg(String msg) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("msg", msg == null ? "未知错误" : msg);
            return obj.toString();
        } catch (Exception ignored) {}
        return "{}";
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

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            if (extend == null) extend = new HashMap<>();
            String order = extend.getOrDefault("order", "time");
            String classId = extend.getOrDefault("class", "0");
            String year = extend.getOrDefault("year", "0");
            String area = extend.getOrDefault("area", "");

            String classParam = "0";
            String listId;
            if (!classId.equals("0")) {
                listId = classId;
            } else {
                listId = tid;
            }

            String yearPart = year.equals("0") ? "--" : "-" + year;
            String areaPart;
            if (area.isEmpty()) {
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
                    listId, pg, order, classParam, yearPart, areaPart
            );

            String html = fetch(url);
            Document doc = Jsoup.parse(html);
            Elements items = doc.select("ul.resize_list li");

            JSONArray videoList = new JSONArray();
            for (Element li : items) {
                Element a = li.selectFirst("a");
                if (a == null) continue;
                String href = a.attr("href");
                String title = a.attr("title");
                if (title.isEmpty()) title = a.text().trim();

                Element picDiv = li.selectFirst("div.pic");
                String picUrl = "";
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

                JSONObject vod = new JSONObject();
                vod.put("vod_id", vodId);
                vod.put("vod_name", title);
                vod.put("vod_pic", picUrl);
                vod.put("vod_remarks", remarks);
                videoList.put(vod);
            }

            int totalPages = getTotalPages(doc);
            JSONObject result = new JSONObject();
            result.put("list", videoList);
            result.put("pagecount", totalPages);
            result.put("page", Integer.parseInt(pg));
            result.put("limit", videoList.length());
            result.put("total", totalPages * 20);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return errorMsg(e.getMessage());
        }
    }

    // ==================== 修改点：详细内容（支持多线路） ====================
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

            // 标题
            Element titleEl = doc.selectFirst("h1.title a");
            String title = titleEl != null ? titleEl.text().trim() : "";

            // 图片
            Element picEl = doc.selectFirst(".page-hd img");
            String pic = "";
            if (picEl != null) {
                pic = picEl.attr("src");
                if (pic.isEmpty()) pic = picEl.attr("data-echo");
            }

            // 主演
            StringBuilder actor = new StringBuilder();
            Elements actorLinks = doc.select(".desc_item:contains(主演:) a");
            for (Element a : actorLinks) {
                if (actor.length() > 0) actor.append(", ");
                actor.append(a.text().trim());
            }

            // 导演
            StringBuilder director = new StringBuilder();
            Elements dirLinks = doc.select(".desc_item:contains(导演:) a");
            for (Element a : dirLinks) {
                if (director.length() > 0) director.append(", ");
                director.append(a.text().trim());
            }

            // 年代
            Element yearEl = doc.selectFirst(".desc_item:contains(年代:) a");
            String year = yearEl != null ? yearEl.text().trim() : "";

            // 地区
            String area = "";
            Element areaEl = doc.selectFirst(".desc_item:contains(地区:) a");
            if (areaEl != null) area = areaEl.text().trim();

            // 类型名称
            String typeName = "";
            Element typeEl = doc.selectFirst(".type-title");
            if (typeEl != null) typeName = typeEl.text().trim();

            // 简介
            Element introEl = doc.selectFirst("article.detail-con p");
            if (introEl == null) introEl = doc.selectFirst(".detail-con");
            String intro = introEl != null ? introEl.text().replaceAll("\\s+", " ").trim() : "";

            // ---------- 播放列表解析（支持多线路） ----------
            List<String> playFromList = new ArrayList<>();
            List<String> playUrlList = new ArrayList<>();

            if (!detailId.isEmpty()) {
                String playPageUrl = siteUrl + "/vod-play-id-" + detailId + "-src-1-num-1.html";
                try {
                    String playHtml = fetch(playPageUrl);

                    // 获取 mac_from 和 mac_url
                    Matcher fromMatcher = Pattern.compile("mac_from\\s*=\\s*'([^']+)'").matcher(playHtml);
                    Matcher urlMatcher = Pattern.compile("mac_url\\s*=\\s*'([^']+)'").matcher(playHtml);

                    if (fromMatcher.find() && urlMatcher.find()) {
                        String macFrom = fromMatcher.group(1);
                        String macUrl = urlMatcher.group(1);

                        // 分割线路名
                        String[] fromParts = macFrom.split("\\$\\$\\$");
                        // 分割线路URL
                        String[] urlParts = macUrl.split("\\$\\$\\$");

                        // 确保线路数量一致
                        int lineCount = Math.min(fromParts.length, urlParts.length);
                        for (int i = 0; i < lineCount; i++) {
                            String lineName = fromParts[i].trim();
                            if (lineName.isEmpty()) lineName = "线路" + (i + 1);

                            String lineEpisodes = urlParts[i];
                            // 每个线路的剧集用 # 分隔
                            String[] episodes = lineEpisodes.split("#");
                            List<String> epList = new ArrayList<>();
                            for (String ep : episodes) {
                                if (ep.trim().isEmpty()) continue;
                                epList.add(ep.trim());
                            }

                            // 按集数排序（可选）
                            Collections.sort(epList, (o1, o2) -> {
                                int n1 = extractEpisodeNumber(o1);
                                int n2 = extractEpisodeNumber(o2);
                                return Integer.compare(n1, n2);
                            });

                            if (!epList.isEmpty()) {
                                playFromList.add(lineName);
                                playUrlList.add(String.join("#", epList));
                            }
                        }
                    } else {
                        // 回退：尝试只解析单个线路（兼容旧版）
                        Matcher singleFrom = Pattern.compile("mac_from\\s*=\\s*'([^']+)'").matcher(playHtml);
                        Matcher singleUrl = Pattern.compile("mac_url\\s*=\\s*'([^']+)'").matcher(playHtml);
                        if (singleFrom.find() && singleUrl.find()) {
                            String lineName = singleFrom.group(1);
                            String urlStr = singleUrl.group(1);
                            String[] episodes = urlStr.split("#");
                            List<String> epList = new ArrayList<>();
                            for (String ep : episodes) {
                                if (ep.trim().isEmpty()) continue;
                                epList.add(ep.trim());
                            }
                            Collections.sort(epList, (o1, o2) -> {
                                int n1 = extractEpisodeNumber(o1);
                                int n2 = extractEpisodeNumber(o2);
                                return Integer.compare(n1, n2);
                            });
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

            JSONObject vod = new JSONObject();
            vod.put("vod_id", vodId);
            vod.put("vod_name", title);
            vod.put("vod_pic", pic);
            vod.put("type_name", typeName);
            vod.put("vod_year", year);
            vod.put("vod_area", area);
            vod.put("vod_director", director.toString());
            vod.put("vod_actor", actor.toString());
            vod.put("vod_content", intro);
            vod.put("vod_play_from", String.join("$$$", playFromList));
            vod.put("vod_play_url", String.join("$$$", playUrlList));

            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return errorMsg(e.getMessage());
        }
    }

    private int extractEpisodeNumber(String s) {
        Matcher m = Pattern.compile("第(\\d+)集").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String pg = "1"; // 搜索接口不支持分页参数时固定为1
            String url = siteUrl + "/vod-search-pg-" + pg + "-wd-" + URLEncoder.encode(key, "UTF-8") + ".html";
            String html = fetch(url);
            Document doc = Jsoup.parse(html);
            Elements items = doc.select("ul#data_list li");
            if (items.isEmpty()) items = doc.select("ul.ulPicTxt li");

            JSONArray videoList = new JSONArray();
            for (Element li : items) {
                Element titleEl = li.selectFirst(".txt .sTit");
                if (titleEl == null) titleEl = li.selectFirst("a[title]");
                String title = titleEl != null ? titleEl.text().trim() : "";

                Element detailA = li.selectFirst(".pic a");
                if (detailA == null) detailA = li.selectFirst(".aPlayBtn");
                String href = detailA != null ? detailA.attr("href") : "";
                if (href.isEmpty() || title.isEmpty()) continue;

                Element imgEl = li.selectFirst(".pic img");
                String picUrl = "";
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

                JSONObject v = new JSONObject();
                v.put("vod_id", vodId);
                v.put("vod_name", title);
                v.put("vod_pic", picUrl);
                v.put("vod_remarks", remarks);
                videoList.put(v);
            }

            int pageCount = 1;
            Element lastPage = doc.selectFirst(".page a:last-child");
            if (lastPage != null) {
                String pageHref = lastPage.attr("href");
                Matcher m = Pattern.compile("pg-(\\d+)").matcher(pageHref);
                if (m.find()) pageCount = Integer.parseInt(m.group(1));
            }

            JSONObject result = new JSONObject();
            result.put("list", videoList);
            result.put("page", Integer.parseInt(pg));
            result.put("pagecount", pageCount);
            result.put("limit", videoList.length());
            result.put("total", videoList.length() * pageCount);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return errorMsg(e.getMessage());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            if (id != null && !id.contains("http") && !id.contains("$") && !id.contains("?")) {
                String apiUrl = apiHost + "/player/?url=" + id;
                String res = fetch(apiUrl);
                Matcher urlMatcher = Pattern.compile("\"url\":\\s*\"([^\"]+)\"").matcher(res);
                if (urlMatcher.find()) {
                    String realUrl = urlMatcher.group(1).replace("\\u0026", "&");
                    return successPlayerResult(realUrl);
                }
                Matcher iframeMatcher = Pattern.compile("<iframe[^>]+src=\"([^\"]+)\"").matcher(res);
                if (iframeMatcher.find()) {
                    return successPlayerResult(iframeMatcher.group(1));
                }
            } else {
                String playUrl = id.startsWith("http") ? id : siteUrl + id;
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
                // 注意：此处 macUrl 可能包含多线路（$$$），但 player 只处理当前线路，需按顺序取第一个？实际 TVBox 传入的 id 是加密串，不会传线路分隔符。
                // 因此我们尝试在所有线路中查找匹配集数的加密串。
                String[] lines = macUrl.split("\\$\\$\\$");
                for (String line : lines) {
                    String[] parts = line.split("#");
                    for (String part : parts) {
                        Matcher m = Pattern.compile("第(\\d+)集\\$(.*)").matcher(part);
                        if (m.find() && Integer.parseInt(m.group(1)) == currentNum) {
                            targetEncrypted = m.group(2);
                            break;
                        }
                    }
                    if (targetEncrypted != null) break;
                }
                if (targetEncrypted == null) {
                    // 尝试用正则找当前集
                    Pattern p = Pattern.compile("第" + currentNum + "集\\$(.*?)(?=#|$)");
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
                        String realUrl = urlMatcher.group(1).replace("\\u0026", "&");
                        return successPlayerResult(realUrl);
                    }
                }
            }
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return fallbackToParse(id);
    }

    private String successPlayerResult(String realUrl) {
        try {
            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", realUrl);
            result.put("header", new JSONObject()
                    .put("User-Agent", getHeaders().get("User-Agent"))
                    .put("Referer", siteUrl));
            return result.toString();
        } catch (Exception ignored) {}
        return "{\"parse\":0,\"url\":\"\"}";
    }

    private String fallbackToParse(String url) {
        try {
            JSONObject result = new JSONObject();
            result.put("parse", 1);
            result.put("url", url != null ? url : "");
            result.put("header", new JSONObject()
                    .put("User-Agent", getHeaders().get("User-Agent"))
                    .put("Referer", siteUrl));
            return result.toString();
        } catch (Exception ignored) {}
        return "{\"parse\":1,\"url\":\"\"}";
    }
}
