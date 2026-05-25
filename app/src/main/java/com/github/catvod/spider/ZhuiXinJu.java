package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.*;
import java.util.regex.*;

public class ZhuiXinJu extends Spider {

    private static final String HOST = "https://zhuixinju.com";
    private static final String UA   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    // ✅ 只用 Quark，不用 Cloud（避免触发天翼等其他网盘登录弹窗）
    private Quark quark = new Quark();

    // ──────────────────────────────────────────────
    // 工具
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
            // extend 里的 cookie 字段直接给夸克
            quark.init(context, extend);
            logger("✅ [追新剧] 夸克初始化完成");
        } catch (Exception e) {
            logger("⚠️ [追新剧] 初始化失败: " + e.getMessage());
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
            int page = Integer.parseInt(pg);
            String url = HOST + "/" + tid;
            if (page > 1) url = HOST + "/" + tid + "/page/" + page;

            logger("📂 [分类] " + tid + " 第" + page + "页 → " + url);
            String html = get(url);
            if (TextUtils.isEmpty(html)) return "{\"list\":[]}";

            Document doc  = Jsoup.parse(html);
            JSONArray list = new JSONArray();

            // ✅ 正确选择器：div.item-jx
            for (Element item : doc.select("div.item-jx")) {
                // vod_id：thumb 里的 a href
                Element thumbA = item.selectFirst("div.thumb a");
                if (thumbA == null) continue;
                String vodId = thumbA.attr("href");
                if (TextUtils.isEmpty(vodId)) continue;

                // vod_name：h5 a
                Element titleA = item.selectFirst("h5 a");
                String name = titleA != null ? titleA.text().trim() : "";

                // vod_pic：thumb img src
                Element img = item.selectFirst("div.thumb img");
                String pic  = img != null ? img.attr("src") : "";

                // vod_remarks：分类 + 时间
                Element sortA = item.selectFirst("div.sortbox a.sort");
                String remarks = sortA != null ? sortA.text().trim() : "";

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

            // ── 封面：正文第一张图 ──
            String pic = "";
            Element picElem = doc.selectFirst(".article-body img, .entry-content img");
            if (picElem != null) {
                pic = picElem.hasAttr("src") ? picElem.attr("src") : picElem.attr("data-src");
            }

            // ── 简介 ──
            StringBuilder contentSb = new StringBuilder();
            for (Element p : doc.select(".article-body p, .entry-content p")) {
                String txt = p.text().trim();
                if (!TextUtils.isEmpty(txt)
                        && !txt.startsWith("Views:")
                        && !txt.startsWith("下载地址")
                        && !txt.startsWith("夸克")) {
                    contentSb.append(txt).append("\n");
                    if (contentSb.length() > 300) break;
                }
            }
            String content = contentSb.toString().trim();

            // ── 导演 / 主演 / 年份 ──
            String director = "", actor = "", year = "";
            String bodyText = doc.select(".article-body, .entry-content").text();

            Matcher mDir = Pattern.compile("导演[:：]\\s*([^\n\r]{2,30})").matcher(bodyText);
            if (mDir.find()) director = mDir.group(1).trim().split("\\s{2,}")[0];

            Matcher mAct = Pattern.compile("主演[:：]\\s*([^\n\r]{2,60})").matcher(bodyText);
            if (mAct.find()) actor = mAct.group(1).trim().split("\\s{2,}")[0];

            Matcher mYear = Pattern.compile("(19|20)\\d{2}").matcher(name);
            if (mYear.find()) year = mYear.group();

            // ── 提取夸克网盘链接 ──
            List<String> quarkLinks = new ArrayList<>();
            for (Element a : doc.select(".article-body a[href], .entry-content a[href], blockquote a[href]")) {
                String href = a.attr("href");
                if (href.contains("pan.quark.cn") && !quarkLinks.contains(href)) {
                    quarkLinks.add(href);
                    logger("🔗 [详情] 发现夸克链接: " + href);
                }
            }

            // ── 交给 Quark 解析分享链接，获取每一集的文件列表 ──
            String playFrom = "";
            String playUrl  = "";

            if (!quarkLinks.isEmpty()) {
                try {
                    String quarkResult = quark.detailContent(quarkLinks);
                    if (!TextUtils.isEmpty(quarkResult)) {
                        JSONObject quarkJson = new JSONObject(quarkResult);
                        JSONArray  quarkList = quarkJson.optJSONArray("list");
                        if (quarkList != null && quarkList.length() > 0) {
                            JSONObject quarkVod = quarkList.getJSONObject(0);
                            playFrom = quarkVod.optString("vod_play_from", "");
                            playUrl  = quarkVod.optString("vod_play_url",  "");
                            logger("✅ [Quark] 解析成功，共 "
                                + playUrl.split("#").length + " 集");
                        }
                    }
                } catch (Exception e) {
                    logger("⚠️ [Quark] 解析失败: " + e.getMessage());
                }
            } else {
                logger("⚠️ [详情] 未找到夸克链接");
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

    // ──────────────────────────────────────────────
    // 播放
    // ──────────────────────────────────────────────

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
    try {
        logger("▶️ [播放] flag=" + flag + " id=" + id);
        return quark.playerContent(flag, id, vipFlags);
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

            for (Element item : doc.select("div.item-jx")) {
                Element thumbA = item.selectFirst("div.thumb a");
                if (thumbA == null) continue;
                String vodId = thumbA.attr("href");
                if (TextUtils.isEmpty(vodId)) continue;

                Element titleA = item.selectFirst("h5 a");
                String name = titleA != null ? titleA.text().trim() : "";

                Element img = item.selectFirst("div.thumb img");
                String pic  = img != null ? img.attr("src") : "";

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
