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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Bdys extends Spider {

    private static String HOST = "https://v.xl.in.ua";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.7727.56 Safari/537.36";

    private void logger(String msg) {
        try {
            Proxy.log("[Bdys] " + msg);
        } catch (Exception ignored) {
        }
    }

    private String fixUrl(String path) {
        if (TextUtils.isEmpty(path)) return "";
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        if (path.startsWith("//")) return "https:" + path;
        if (!path.startsWith("/")) path = "/" + path;
        return HOST + path;
    }

    /**
     * 借鉴 Czzyv：根据抓包特征补全防盗链与 Request Header
     */
    private Map<String, String> getHeaders(String referer) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", TextUtils.isEmpty(referer) ? HOST + "/" : referer);
        headers.put("Origin", HOST);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Cache-Control", "no-cache");
        headers.put("Pragma", "no-cache");

        // Chrome 安全校验参数
        headers.put("sec-ch-ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"147\", \"Google Chrome\";v=\"147\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", "document");
        headers.put("sec-fetch-mode", "navigate");
        headers.put("sec-fetch-site", "same-origin");
        headers.put("sec-fetch-user", "?1");
        headers.put("upgrade-insecure-requests", "1");
        return headers;
    }

    /**
     * 借鉴 Czzyv：统一封装 HTTP GET 请求并自动捕获、打印日志
     */
    private String get(String url) {
        return get(url, HOST + "/");
    }

    private String get(String url, String referer) {
        try {
            logger("🌐 发起 GET 请求: " + url);
            String html = OkHttp.string(url, getHeaders(referer));
            if (TextUtils.isEmpty(html)) {
                logger("⚠️ 请求响应内容为空: " + url);
                return "";
            }
            return html;
        } catch (Exception e) {
            logger("❌ 网络请求失败 (" + url + "): " + e.getMessage());
            return "";
        }
    }

    @Override
    public void init(Context context, String extend) {
        logger("🚀 初始化 Bdys Spider 插件...");
        logger("当前激活主站域名: " + HOST);
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            List<Class> classes = new ArrayList<>();
            classes.add(new Class("1", "电视剧"));
            classes.add(new Class("0", "电影"));

            LinkedHashMap<String, List<Filter>> filterMap = new LinkedHashMap<>();
            if (filter) {
                List<Filter> filterList = new ArrayList<>();

                String[] typeNames = {"全部", "动作", "爱情", "喜剧", "科幻", "恐怖", "剧情", "动画", "悬疑", "犯罪", "古装", "奇幻", "美剧", "韩剧", "国产", "日剧"};
                String[] typeValues = {"all", "dongzuo", "aiqing", "xiju", "kehuan", "kongbu", "juqing", "donghua", "xuanyi", "fanzui", "guzhuang", "qihuan", "meiju", "hanju", "guoju", "riju"};
                List<Filter.Value> typeOptions = new ArrayList<>();
                for (int i = 0; i < typeNames.length; i++) {
                    typeOptions.add(new Filter.Value(typeNames[i], typeValues[i]));
                }
                filterList.add(new Filter("type_slug", "类型", typeOptions));

                List<Filter.Value> yearOptions = new ArrayList<>();
                yearOptions.add(new Filter.Value("全部", ""));
                for (int y = 2026; y >= 2015; y--) {
                    yearOptions.add(new Filter.Value(String.valueOf(y), String.valueOf(y)));
                }
                filterList.add(new Filter("year", "年份", yearOptions));

                filterMap.put("0", filterList);
                filterMap.put("1", filterList);
            }

            return Result.string(classes, filterMap);
        } catch (Exception e) {
            logger("homeContent 异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int page = Integer.parseInt(pg);
            String typeSlug = (extend != null && extend.containsKey("type_slug") && !TextUtils.isEmpty(extend.get("type_slug"))) 
                    ? extend.get("type_slug") : "all";

            String url = HOST + "/s/" + typeSlug + "/" + page + "?type=" + tid;
            if (extend != null && extend.containsKey("year") && !TextUtils.isEmpty(extend.get("year"))) {
                url += "&year=" + extend.get("year");
            }

            logger("分类请求: " + tid + " 页码: " + page + " → " + url);
            String html = get(url);
            if (TextUtils.isEmpty(html)) {
                return Result.string(new ArrayList<>());
            }

            Document doc = Jsoup.parse(html);
            Elements cards = doc.select(".movie-card");
            if (cards.isEmpty()) {
                cards = doc.select(".card-sm");
            }

            logger("找到候选节点条数: " + cards.size());

            List<Vod> list = new ArrayList<>();
            for (Element card : cards) {
                Element a = card.selectFirst("a");
                if (a == null) continue;

                String vodId = a.attr("href").trim();
                if (TextUtils.isEmpty(vodId)) continue;

                String name = "";
                Element h4 = card.selectFirst(".card-info h4");
                if (h4 != null) {
                    name = h4.text().trim();
                }
                if (TextUtils.isEmpty(name) && a.hasAttr("title")) {
                    String fullTitle = a.attr("title").trim();
                    Matcher matcher = Pattern.compile("《(.*?)》").matcher(fullTitle);
                    if (matcher.find()) {
                        name = matcher.group(1);
                    } else {
                        name = fullTitle;
                    }
                }
                if (TextUtils.isEmpty(name)) {
                    name = card.select(".text-truncate").text().trim();
                }

                if (TextUtils.isEmpty(name)) continue;

                Element img = card.selectFirst("img");
                String pic = "";
                if (img != null) {
                    pic = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
                    pic = fixUrl(pic);
                }

                String remark = "";
                Element episodeBadge = card.selectFirst(".episode-badge");
                if (episodeBadge != null) {
                    remark = episodeBadge.text().trim();
                } else {
                    Element ratingBadge = card.selectFirst(".rating-badge");
                    if (ratingBadge != null) {
                        remark = ratingBadge.text().trim();
                    }
                }

                list.add(new Vod(vodId, name, pic, remark));
            }

            logger("分类解析完成，成功打包条数: " + list.size());
            return Result.string(list);
        } catch (Exception e) {
            logger("分类解析异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String id = ids.get(0);
            String url = fixUrl(id);

            logger("详情页请求: " + url);
            String html = get(url);
            if (TextUtils.isEmpty(html)) {
                return Result.string(new ArrayList<>());
            }

            Document doc = Jsoup.parse(html);

            String name = doc.select(".card-info h4, h2.d-sm-block, h1").text().trim();

            Element imgElem = doc.selectFirst(".movie-card img, .cover-lg-max-25 img");
            String pic = "";
            if (imgElem != null) {
                pic = imgElem.hasAttr("data-src") ? imgElem.attr("data-src") : imgElem.attr("src");
                pic = fixUrl(pic);
            }

            String content = doc.select("#synopsis .card-body, .synopsis").text().trim();

            Elements playLinks = doc.select("#play-list a");
            List<String> playPairs = new ArrayList<>();
            for (Element a : playLinks) {
                String epName = a.text().trim();
                String epHref = a.attr("href");
                if (!TextUtils.isEmpty(epHref)) {
                    playPairs.add(epName + "$" + epHref);
                }
            }

            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodContent(content);

            if (!playPairs.isEmpty()) {
                vod.setVodPlayFrom("哔嘀影视");
                vod.setVodPlayUrl(TextUtils.join("#", playPairs));
            }

            logger("详情解析完成: " + name + "，集数: " + playPairs.size());
            return Result.string(vod);
        } catch (Exception e) {
            logger("详情解析异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String playUrl = fixUrl(id);
            logger("开始解析播放页: " + playUrl);
            return Result.get().parse(1).url(playUrl).string();
        } catch (Exception e) {
            logger("播放处理异常: " + e.getMessage());
            return Result.get().parse(1).url(id).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return Result.string(new ArrayList<>());
        try {
            String searchUrl = "https://kwyili.dpdns.org/bdys.php?q=" + URLEncoder.encode(key, "UTF-8");
            logger("搜索请求: " + searchUrl);

            String jsonStr = get(searchUrl);
            if (TextUtils.isEmpty(jsonStr)) {
                logger("搜索页面获取失败");
                return Result.string(new ArrayList<>());
            }

            JSONArray jsonArray = new JSONArray(jsonStr);
            List<Vod> resultList = new ArrayList<>();

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject item = jsonArray.getJSONObject(i);
                String title = item.optString("title");
                String image = item.optString("image");
                String href = item.optString("href");

                resultList.add(new Vod(href, title, image, ""));
            }

            logger("搜索完成，成功匹配条数: " + resultList.size());
            return Result.string(resultList);
        } catch (Exception e) {
            logger("搜索出现异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }
}

