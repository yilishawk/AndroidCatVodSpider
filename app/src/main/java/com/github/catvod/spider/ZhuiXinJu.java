package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.*;
import java.util.regex.*;

public class ZhuiXinJu extends Spider {

    private static final String HOST = "https://zhuixinju.com";
    private static final String UA   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private Cloud cloud = new Cloud();

    // ──────────────────────────────────────────────
    // 工 具
    // ──────────────────────────────────────────────

    private void logger(String msg) {
        try { Proxy.log(msg); } catch (Exception ignored) {}
    }

    private Map<String, String> baseHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", HOST + "/");
        return h;
    }

    private String get(String url) {
        try {
            return KaiGeNet.smartRequest(HOST, "get", url, null, baseHeaders()).getBody();
        } catch (Exception e) {
            logger("🚨 [请求失败] " + url + " → " + e.getMessage());
            return "";
        }
    }

    // ──────────────────────────────────────────────
    // 生命周期
    // ──────────────────────────────────────────────

    @Override
    public void init(Context context, String extend) {
        logger("🚀 [追新剧] 初始化...");
        try {
            cloud.init(context, extend);
            logger("✅ [追新剧] Cloud网盘模块初始化完成");
        } catch (Exception e) {
            logger("⚠️ [追新剧] Cloud初始化失败: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    // 首页
    // ──────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONArray classes = new JSONArray();
            classes.put(makeClass("电视剧", "dsj"));
            classes.put(makeClass("电影",   "dy"));
            classes.put(makeClass("综艺",   "zy"));
            classes.put(makeClass("记录片", "jlp"));

            JSONObject result = new JSONObject();
            result.put("class", classes);
            result.put("list",  new JSONArray());
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private JSONObject makeClass(String name, String id) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type_name", name);
        o.put("type_id",   id);
        return o;
    }

    @Override
    public String homeVideoContent() {
        return "{\"list\":[]}";
    }

    // ──────────────────────────────────────────────
    // 分类列表
    // ──────────────────────────────────────────────

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
    try {
        String url = HOST + "/" + tid;
        int page = Integer.parseInt(pg);
        if (page > 1) url = HOST + "/" + tid + "/page/" + page;

        logger("📂 [分类] " + tid + " 第" + page + "页 → " + url);
        String html = get(url);
        if (TextUtils.isEmpty(html)) return "{\"list\":[]}";

        Document doc  = Jsoup.parse(html);
        JSONArray list = new JSONArray();

        // 查找所有资源项
        for (Element item : doc.select(".item-jx, .item-blog, article")) {
            // 标题和链接
            Element titleElem = item.selectFirst("h5 a, h2 a, .line-tow a");
            if (titleElem == null) continue;

            String vodId  = titleElem.attr("href");
            String name   = titleElem.text().trim();

            // 封面
            String pic = "";
            Element img = item.selectFirst("img");
            if (img != null) {
                pic = img.hasAttr("src") ? img.attr("src") : img.attr("data-src");
                // 处理相对路径
                if (!TextUtils.isEmpty(pic) && !pic.startsWith("http")) {
                    if (pic.startsWith("/")) {
                        pic = HOST + pic;
                    } else {
                        pic = HOST + "/" + pic;
                    }
                }
            }

            // 分类标签
            String remarks = "";
            Element sortElem = item.selectFirst(".sortbox a");
            if (sortElem != null) {
                remarks = sortElem.text().trim();
            }

            if (TextUtils.isEmpty(vodId) || TextUtils.isEmpty(name)) continue;

            JSONObject vod = new JSONObject();
            vod.put("vod_id",      vodId);
            vod.put("vod_name",    name);
            vod.put("vod_pic",     pic);
            vod.put("vod_remarks", remarks);
            list.put(vod);
        }

        logger("✅ [分类] 共 " + list.length() + " 条");
        JSONObject result = new JSONObject();
        result.put("list", list);
        result.put("page", page);
        return result.toString();
    } catch (Exception e) {
        logger("🚨 [分类异常] " + e.getMessage());
        return "{\"list\":[]}";
    }
}

    // ──────────────────────────────────────────────
    // 详情页
    // ──────────────────────────────────────────────

    @Override
    public String detailContent(List<String> ids) {
        try {
            String url = ids.get(0);
            logger("📄 [详情] → " + url);
            String html = get(url);
            if (TextUtils.isEmpty(html)) return "{\"list\":[]}";

            Document doc = Jsoup.parse(html);

            // ── 标题 ──
            String name = "";
            Element titleElem = doc.selectFirst("h1.post-title, h1.entry-title");
            if (titleElem != null) name = titleElem.text().trim();

            // ── 封面 ──
            String pic = "";
            Element picElem = doc.selectFirst(".article-body img, .entry-content img");
            if (picElem != null) {
                pic = picElem.hasAttr("src") ? picElem.attr("src") : picElem.attr("data-src");
            }

            // ── 简介：正文段落拼接 ──
            StringBuilder contentSb = new StringBuilder();
            for (Element p : doc.select(".article-body p, .entry-content p")) {
                String txt = p.text().trim();
                if (!TextUtils.isEmpty(txt) && !txt.startsWith("Views:") 
                        && !txt.startsWith("下载地址") && !txt.startsWith("夸克")) {
                    contentSb.append(txt).append("\n");
                    if (contentSb.length() > 300) break;
                }
            }
            String content = contentSb.toString().trim();

            // ── 导演/主演/年份 从正文提取 ──
            String director = "", actor = "", year = "";
            String bodyText = doc.select(".article-body, .entry-content").text();

            Matcher mDir = Pattern.compile("导演[:：]\\s*([^\n\r<]{2,30})").matcher(bodyText);
            if (mDir.find()) director = mDir.group(1).trim().split("\\s{2,}")[0];

            Matcher mAct = Pattern.compile("主演[:：]\\s*([^\n\r<]{2,60})").matcher(bodyText);
            if (mAct.find()) actor = mAct.group(1).trim().split("\\s{2,}")[0];

            Matcher mYear = Pattern.compile("(19|20)\\d{2}").matcher(name);
            if (mYear.find()) year = mYear.group();

            // ── 提取所有网盘链接 ──
            // blockquote 里的链接，或正文里的链接
            List<String> panLinks = new ArrayList<>();
            for (Element a : doc.select(".article-body a[href], .entry-content a[href], blockquote a[href]")) {
                String href = a.attr("href");
                if (isPanLink(href) && !panLinks.contains(href)) {
                    panLinks.add(href);
                    logger("🔗 [详情] 发现网盘链接: " + href);
                }
            }

            // ── 构建播放列表 ──
            String playFrom = "";
            String playUrl  = "";

            if (!panLinks.isEmpty()) {
                // 多个网盘链接用 $$$ 分隔（不同来源），每个链接作为独立播放源
                List<String> froms = new ArrayList<>();
                List<String> urls  = new ArrayList<>();
                for (int i = 0; i < panLinks.size(); i++) {
                    String link  = panLinks.get(i);
                    String label = panLabel(link) + (panLinks.size() > 1 ? (i + 1) : "");
                    froms.add(label);
                    // 每个网盘链接作为单集，名称$链接
                    urls.add("播放$" + link);
                }
                playFrom = TextUtils.join("$$$", froms);
                playUrl  = TextUtils.join("$$$", urls);
                logger("✅ [详情] 共找到 " + panLinks.size() + " 个网盘链接");
            } else {
                logger("⚠️ [详情] 未找到网盘链接");
            }

            JSONObject vod = new JSONObject();
            vod.put("vod_id",        url);
            vod.put("vod_name",      name);
            vod.put("vod_pic",       pic);
            vod.put("vod_year",      year);
            vod.put("vod_director",  director);
            vod.put("vod_actor",     actor);
            vod.put("vod_content",   content);
            vod.put("vod_play_from", playFrom);
            vod.put("vod_play_url",  playUrl);

            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            logger("🚨 [详情异常] " + e.getMessage());
            return "{\"list\":[]}";
        }
    }

    /** 判断是否是支持的网盘链接 */
    private boolean isPanLink(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.contains("pan.quark.cn")
            || url.contains("drive.uc.cn")
            || url.contains("pan.baidu.com")
            || url.contains("aliyundrive.com")
            || url.contains("alipan.com")
            || url.contains("123pan.com")
            || url.contains("123684.com")
            || url.contains("123912.com")
            || url.contains("yun.139.com")
            || url.contains("caiyun.139.com");
    }

    /** 返回网盘的 flag 标签，需和 Cloud.playerContent() 里的判断一致 */
    private String panLabel(String url) {
        if (url.contains("pan.quark.cn"))   return "quark";
        if (url.contains("drive.uc.cn"))    return "uc";
        if (url.contains("pan.baidu.com"))  return "BD";
        if (url.contains("aliyundrive.com") 
         || url.contains("alipan.com"))     return "ali";
        if (url.contains("123pan.com") 
         || url.contains("123684.com")
         || url.contains("123912.com"))     return "pan123";
        if (url.contains("yun.139.com") 
         || url.contains("caiyun.139.com")) return "移动";
        return "网盘";
    }

    // ──────────────────────────────────────────────
    // 播放
    // ──────────────────────────────────────────────

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            logger("▶️ [播放] flag=" + flag + " id=" + id);

            // ✅ 转发给 Cloud 处理网盘链接
            try {
                String cloudResult = cloud.playerContent(flag, id, vipFlags);
                if (!TextUtils.isEmpty(cloudResult) && !cloudResult.equals("{}") 
                        && !cloudResult.equals(flag)) {
                    logger("✅ [Cloud] 网盘播放处理成功");
                    return cloudResult;
                }
            } catch (Exception e) {
                logger("⚠️ [Cloud] 处理失败: " + e.getMessage());
            }

            // 兜底：直接返回链接
            JSONObject result = new JSONObject();
            result.put("parse", 1);
            result.put("url",   id);
            return result.toString();
        } catch (Exception e) {
            logger("🚨 [播放异常] " + e.getMessage());
            return "{}";
        }
    }

    // ──────────────────────────────────────────────
    // 搜索
    // ──────────────────────────────────────────────

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url  = HOST + "/?s=" + java.net.URLEncoder.encode(key, "UTF-8");
            logger("🔍 [搜索] → " + url);
            String html = get(url);
            if (TextUtils.isEmpty(html)) return "{\"list\":[]}";

            Document doc  = Jsoup.parse(html);
            JSONArray list = new JSONArray();

            for (Element article : doc.select("article")) {
                Element titleElem = article.selectFirst("h2 a, h1 a, .entry-title a");
                if (titleElem == null) continue;

                String vodId = titleElem.attr("href");
                String name  = titleElem.text().trim();

                String pic = "";
                Element img = article.selectFirst("img");
                if (img != null) pic = img.hasAttr("src") ? img.attr("src") : img.attr("data-src");

                if (TextUtils.isEmpty(vodId)) continue;

                JSONObject vod = new JSONObject();
                vod.put("vod_id",   vodId);
                vod.put("vod_name", name);
                vod.put("vod_pic",  pic);
                list.put(vod);
            }

            logger("✅ [搜索] 共 " + list.length() + " 条");
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            logger("🚨 [搜索异常] " + e.getMessage());
            return "{\"list\":[]}";
        }
    }
}
