package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;

import org.json.JSONArray;
import org.json.JSONObject;
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

/**
 * 4k影视爬虫
 * 站点: https://www.4kvm.me
 */
public class FourKVM extends Spider {

    private String host = "https://www.4kvm.net";
    private Map<String, String> headers;

    public FourKVM() {
        headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Referer", host);
    }

    @Override
    public void init(Context context, String extend) {
        if (!TextUtils.isEmpty(extend)) {
            try {
                JSONObject cfg = new JSONObject(extend);
                if (cfg.has("host")) {
                    host = cfg.optString("host");
                    headers.put("Referer", host);
                }
            } catch (Exception e) {
                SpiderDebug.log("[4k影视] 解析扩展配置失败: " + e.getMessage());
            }
        }
        SpiderDebug.log("[4k影视] 初始化完成，host: " + host);
    }

    private String fetch(String url) throws Exception {
        // 使用 OkHttp.string 自动处理 gzip 和编码
        String html = OkHttp.string(url, headers);
        if (html == null) throw new Exception("请求失败: " + url);
        return html;
    }

    private String cleanTitle(String rawTitle) {
        if (TextUtils.isEmpty(rawTitle)) return "";
        rawTitle = rawTitle.trim();
        Pattern p = Pattern.compile("^(.*?)[\\s:：]+(\\1)$");
        Matcher m = p.matcher(rawTitle);
        if (m.find()) return m.group(1);
        if (rawTitle.contains(" ")) {
            String[] parts = rawTitle.split(" ", 2);
            if (parts[0].equals(parts[1])) return parts[0];
        }
        return rawTitle;
    }

    // ---------- 筛选器选项 ----------
    private JSONArray getAreasOptions() throws Exception {
        String[][] areas = {
                {"全部地区", ""}, {"中国", "7"}, {"美国", "5"}, {"日本", "11"},
                {"韩国", "12"}, {"英国", "30"}, {"法国", "6"}, {"德国", "18"},
                {"意大利", "19"}, {"西班牙", "24"}, {"加拿大", "32"}, {"澳大利亚", "22"},
                {"俄罗斯", "16"}, {"印度", "34"}, {"泰国", "33"}, {"中国香港", "14"},
                {"中国台湾", "21"}, {"巴西", "26"}, {"阿根廷", "27"}
        };
        JSONArray arr = new JSONArray();
        for (String[] opt : areas) {
            JSONObject o = new JSONObject();
            o.put("n", opt[0]);
            o.put("v", opt[1]);
            arr.put(o);
        }
        return arr;
    }

    private JSONArray getTvClassesOptions() throws Exception {
        String[][] tv = {
                {"全部类型", ""}, {"国产剧", "20"}, {"美剧", "21"}, {"韩剧", "22"},
                {"日剧", "23"}, {"泰剧", "24"}, {"日番", "25"}, {"国漫", "26"}
        };
        JSONArray arr = new JSONArray();
        for (String[] opt : tv) {
            JSONObject o = new JSONObject();
            o.put("n", opt[0]);
            o.put("v", opt[1]);
            arr.put(o);
        }
        return arr;
    }

    private JSONArray getTypesOptions() throws Exception {
        String[][] types = {
                {"全部类型", ""}, {"剧情", "1"}, {"悬疑", "2"}, {"恐怖", "3"},
                {"惊悚", "4"}, {"喜剧", "5"}, {"爱情", "6"}, {"科幻", "14"},
                {"动作", "10"}, {"冒险", "18"}, {"犯罪", "9"}, {"动画", "11"},
                {"奇幻", "12"}, {"音乐", "13"}, {"历史", "15"}, {"战争", "16"},
                {"家庭", "19"}, {"纪录", "20"}, {"西部", "23"}, {"情色", "25"},
                {"真人秀", "26"}, {"古装", "27"}, {"传记", "28"}, {"同性", "29"},
                {"运动", "30"}, {"武侠", "31"}, {"歌舞", "32"}, {"灾难", "34"},
                {"短片", "35"}
        };
        JSONArray arr = new JSONArray();
        for (String[] opt : types) {
            JSONObject o = new JSONObject();
            o.put("n", opt[0]);
            o.put("v", opt[1]);
            arr.put(o);
        }
        return arr;
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();

            // 自定义分类（国产剧使用参数传递）
            JSONObject custom = new JSONObject();
            custom.put("type_id", "2|tvclasses=20");
            custom.put("type_name", "国产剧");
            classes.put(custom);

            // 标准分类
            String[][] std = {{"1","电影"}, {"2","电视剧"}, {"4","综艺"}};
            for (String[] s : std) {
                JSONObject cls = new JSONObject();
                cls.put("type_id", s[0]);
                cls.put("type_name", s[1]);
                classes.put(cls);
            }
            result.put("class", classes);

            if (filter) {
                JSONObject filters = new JSONObject();

                // 电影筛选
                JSONArray movieFilters = new JSONArray();
                JSONObject areaFilter = new JSONObject();
                areaFilter.put("key", "areas");
                areaFilter.put("name", "地区");
                areaFilter.put("value", getAreasOptions());
                movieFilters.put(areaFilter);

                JSONObject typeFilter = new JSONObject();
                typeFilter.put("key", "types");
                typeFilter.put("name", "类型");
                typeFilter.put("value", getTypesOptions());
                movieFilters.put(typeFilter);
                filters.put("1", movieFilters);

                // 电视剧筛选
                JSONArray tvFilters = new JSONArray();
                JSONObject tvArea = new JSONObject();
                tvArea.put("key", "areas");
                tvArea.put("name", "地区");
                tvArea.put("value", getAreasOptions());
                tvFilters.put(tvArea);

                JSONObject tvClass = new JSONObject();
                tvClass.put("key", "tvclasses");
                tvClass.put("name", "电视剧分类");
                tvClass.put("value", getTvClassesOptions());
                tvFilters.put(tvClass);

                JSONObject tvType = new JSONObject();
                tvType.put("key", "types");
                tvType.put("name", "类型");
                tvType.put("value", getTypesOptions());
                tvFilters.put(tvType);
                filters.put("2", tvFilters);

                // 综艺筛选
                JSONArray varietyFilters = new JSONArray();
                varietyFilters.put(areaFilter); // 复用地区
                varietyFilters.put(typeFilter); // 复用类型
                filters.put("4", varietyFilters);

                result.put("filters", filters);
            }
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[4k影视] homeContent 错误: " + e.getMessage());
            return "{\"class\":[], \"filters\":{}}";
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            // 解析 tid 可能携带参数 (如 "2|tvclasses=20")
            String realTid = tid;
            Map<String, String> defaultParams = new HashMap<>();
            if (tid.contains("|")) {
                String[] parts = tid.split("\\|", 2);
                realTid = parts[0];
                for (String pair : parts[1].split("&")) {
                    if (pair.contains("=")) {
                        String[] kv = pair.split("=", 2);
                        defaultParams.put(kv[0], kv[1]);
                    }
                }
            }

            // 合并参数 (extend 优先)
            Map<String, String> params = new HashMap<>(defaultParams);
            if (extend != null) params.putAll(extend);

            StringBuilder urlBuilder = new StringBuilder(host);
            urlBuilder.append("/filter?classify=").append(realTid).append("&page=").append(pg);
            if (params.containsKey("areas")) urlBuilder.append("&areas=").append(params.get("areas"));
            if (params.containsKey("tvclasses")) urlBuilder.append("&tvclasses=").append(params.get("tvclasses"));
            if (params.containsKey("types")) urlBuilder.append("&types=").append(params.get("types"));

            String url = urlBuilder.toString();
            SpiderDebug.log("[4k影视] category URL: " + url);

            String html = fetch(url);
            Document doc = Jsoup.parse(html);

            Elements cards = doc.select(".movie-card");
            if (cards.isEmpty()) cards = doc.select(".group");

            JSONArray videos = new JSONArray();
            for (Element card : cards) {
                Element link = card.selectFirst("a[href^=/play/]");
                if (link == null) continue;
                String href = link.attr("href");
                String vodId = href.substring(href.lastIndexOf('/') + 1);
                Element titleElem = card.selectFirst("h3");
                String rawTitle = titleElem != null ? titleElem.text().trim() : "";
                String title = cleanTitle(rawTitle);
                Element img = card.selectFirst("img");
                String pic = "";
                if (img != null) {
                    pic = img.attr("data-src");
                    if (TextUtils.isEmpty(pic)) pic = img.attr("src");
                    if (!pic.startsWith("http")) pic = host + pic;
                }
                Element remarkElem = card.selectFirst("span.absolute.bottom-0, .remark");
                String remark = remarkElem != null ? remarkElem.text().trim() : "";

                JSONObject vod = new JSONObject();
                vod.put("vod_id", vodId);
                vod.put("vod_name", title);
                vod.put("vod_pic", pic);
                vod.put("vod_remarks", remark);
                videos.put(vod);
            }

            // 分页估算
            boolean hasNext = doc.select("a:contains(下一页)").size() > 0 || doc.select(".pagination .next").size() > 0;
            int currentPage = Integer.parseInt(pg);
            int pagecount = hasNext ? currentPage + 1 : currentPage;
            // 保守上限
            pagecount = Math.min(pagecount + 5, 20);

            JSONObject result = new JSONObject();
            result.put("list", videos);
            result.put("page", currentPage);
            result.put("pagecount", pagecount);
            result.put("limit", videos.length());
            result.put("total", videos.length() * pagecount);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[4k影视] categoryContent 错误: " + e.getMessage());
            return "{\"list\":[], \"page\":" + pg + "}";
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) return "{\"list\":[]}";
            String vodId = ids.get(0);
            String url = host + "/play/" + vodId;
            String html = fetch(url);
            Document doc = Jsoup.parse(html);

            // 标题
            Element titleElem = doc.selectFirst("h1");
            String rawTitle = titleElem != null ? titleElem.text().trim() : "";
            String title = cleanTitle(rawTitle);

            // 海报
            Element img = doc.selectFirst(".movie-poster img");
            String pic = "";
            if (img != null) {
                pic = img.attr("src");
                if (!pic.startsWith("http")) pic = host + pic;
            }

            // 导演、演员、地区、年份
            String director = "", actor = "", area = "", year = "";
            Elements infoItems = doc.select(".bg-dark-800.rounded-lg.p-3 .grid");
            for (Element item : infoItems) {
                Elements cells = item.select(".col-span-1, .col-span-2");
                List<Element> cellList = cells;
                for (int i = 0; i < cellList.size() - 1; i += 2) {
                    String key = cellList.get(i).text().trim();
                    String val = cellList.get(i + 1).text().trim();
                    if (key.contains("导演")) director = val;
                    else if (key.contains("主演")) actor = val;
                    else if (key.contains("地区")) area = val;
                    else if (key.contains("年份")) year = val;
                }
            }

            // 简介
            Element descElem = doc.selectFirst(".bg-dark-800.rounded-lg.p-3 p");
            String content = descElem != null ? descElem.text().trim() : "";

            // 剧集列表
            List<String> playFromList = new ArrayList<>();
            List<String> playUrlList = new ArrayList<>();
            Elements episodeLinks = doc.select(".episode-link");
            if (!episodeLinks.isEmpty()) {
                List<String> episodes = new ArrayList<>();
                for (Element a : episodeLinks) {
                    String epName = a.text().trim();
                    String link = a.attr("href");
                    if (!link.startsWith("http")) link = host + link;
                    episodes.add(epName + "$" + link);
                }
                if (!episodes.isEmpty()) {
                    playFromList.add("4K影视");
                    playUrlList.add(String.join("#", episodes));
                }
            }

            JSONObject vod = new JSONObject();
            vod.put("vod_id", vodId);
            vod.put("vod_name", title);
            vod.put("vod_pic", pic);
            vod.put("vod_director", director);
            vod.put("vod_actor", actor);
            vod.put("vod_area", area);
            vod.put("vod_year", year);
            vod.put("vod_content", content);
            vod.put("vod_play_from", String.join("$$$", playFromList));
            vod.put("vod_play_url", String.join("$$$", playUrlList));

            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[4k影视] detailContent 错误: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    public String searchContent(String key, boolean quick, String pg) {
        try {
            String url = host + "/search?q=" + URLEncoder.encode(key, "UTF-8");
            String html = fetch(url);
            Document doc = Jsoup.parse(html);
            JSONArray videos = new JSONArray();
            for (Element item : doc.select(".group")) {
                Element a = item.selectFirst("a[href^=/play/]");
                if (a == null) continue;
                String href = a.attr("href");
                String vodId = href.substring(href.lastIndexOf('/') + 1);
                Element titleElem = item.selectFirst("h3");
                String rawTitle = titleElem != null ? titleElem.text().trim() : "";
                String title = cleanTitle(rawTitle);
                Element img = item.selectFirst("img");
                String pic = "";
                if (img != null) {
                    pic = img.attr("data-src");
                    if (TextUtils.isEmpty(pic)) pic = img.attr("src");
                    if (!pic.startsWith("http")) pic = host + pic;
                }
                JSONObject vod = new JSONObject();
                vod.put("vod_id", vodId);
                vod.put("vod_name", title);
                vod.put("vod_pic", pic);
                vod.put("vod_remarks", "");
                videos.put(vod);
            }
            JSONObject result = new JSONObject();
            result.put("list", videos);
            result.put("page", Integer.parseInt(pg));
            result.put("pagecount", 1);
            result.put("limit", videos.length());
            result.put("total", videos.length());
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[4k影视] searchContent 错误: " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String url = id.startsWith("http") ? id : host + id;
        try {
            String html = fetch(url);
            // 多种正则匹配视频链接
            String[] patterns = {
                    "<video[^>]+src=\"([^\"]+)\"",
                    "<source[^>]+src=\"([^\"]+)\"",
                    "(?:var|let|const)\\s+videoUrl\\s*=\\s*[\"']([^\"']+)[\"']",
                    "(?:var|let|const)\\s+url\\s*=\\s*[\"']([^\"']+\\.m3u8)[\"']",
                    "\"url\"\\s*:\\s*\"([^\"]+\\.m3u8)\"",
                    "([^\"']+\\.m3u8[^\"']*)"
            };
            for (String pat : patterns) {
                Pattern p = Pattern.compile(pat, Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(html);
                if (m.find()) {
                    String videoUrl = m.group(1);
                    if (videoUrl.startsWith("//")) videoUrl = "https:" + videoUrl;
                    else if (videoUrl.startsWith("/")) videoUrl = host + videoUrl;
                    JSONObject result = new JSONObject();
                    result.put("parse", 0);
                    result.put("url", videoUrl);
                    JSONObject header = new JSONObject();
                    header.put("User-Agent", headers.get("User-Agent"));
                    header.put("Referer", url);
                    header.put("Origin", host);
                    result.put("header", header);
                    return result.toString();
                }
            }
            // 未找到视频，返回 parse=1 让壳子嗅探
            JSONObject fallback = new JSONObject();
            fallback.put("parse", 1);
            fallback.put("url", url);
            JSONObject header = new JSONObject();
            header.put("User-Agent", headers.get("User-Agent"));
            header.put("Referer", url);
            header.put("Origin", host);
            fallback.put("header", header);
            return fallback.toString();
        } catch (Exception e) {
            SpiderDebug.log("[4k影视] playerContent 错误: " + e.getMessage());
            try {
                JSONObject fallback = new JSONObject();
                fallback.put("parse", 1);
                fallback.put("url", url);
                JSONObject header = new JSONObject();
                header.put("User-Agent", headers.get("User-Agent"));
                fallback.put("header", header);
                return fallback.toString();
            } catch (Exception ex) {
                return "{\"parse\":1,\"url\":\"" + url + "\"}";
            }
        }
    }
}