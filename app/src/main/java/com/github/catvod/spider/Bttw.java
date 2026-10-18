package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 两个bt影视爬虫
 * 站点: https://www.bttwo.me
 */
public class Bttw extends Spider {

    private String host = "https://www.bttwo.life";
    private Map<String, String> headers;
    private String userlink = "X1VUXUVdXQsHBg4FCgc7IUleV0Bb";
    private static final String XOR_KEY = "nbmovie2024secretkey";

    public Bttw() {
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
        SpiderDebug.log("[Bttw] 初始化完成，host: " + host);
        refreshUserlink();
    }

    /** 播放签名需要 userlink，从首页提取；失败则用内置固定值（服务端不强绑会话，固定值实测可用） */
    private void refreshUserlink() {
        try {
            String html = OkHttp.string(host + "/", headers);
            if (!TextUtils.isEmpty(html)) {
                Matcher m = Pattern.compile("userlink:'([^']+)'").matcher(html);
                if (m.find() && !TextUtils.isEmpty(m.group(1))) {
                    userlink = m.group(1);
                    SpiderDebug.log("[Bttw] userlink 已刷新");
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("[Bttw] userlink 刷新失败，使用固定值: " + e.getMessage());
        }
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

            // 剧集列表（episode-link 元素上带每集独立的 dataid，播放签名需要）
            List<String> playFromList = new ArrayList<>();
            List<String> playUrlList = new ArrayList<>();
            Elements episodeLinks = doc.select(".episode-link");
            if (!episodeLinks.isEmpty()) {
                List<String> episodes = new ArrayList<>();
                for (Element a : episodeLinks) {
                    String epName = a.text().trim();
                    String dataid = a.attr("dataid");
                    if (!TextUtils.isEmpty(dataid)) {
                        // dataid@slug：playerContent 据此签名取真实播放地址
                        episodes.add(epName + "$" + dataid + "@" + vodId);
                    } else {
                        // 无 dataid 的元素退回播放页链接，playerContent 会自行抓页解析
                        String link = a.attr("href");
                        if (!link.startsWith("http")) link = host + link;
                        episodes.add(epName + "$" + link);
                    }
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
        try {
            // id 两种格式:
            //   1) dataid@slug           —— detailContent 生成，每集独立 dataid
            //   2) /play/slug 或完整 URL  —— 旧数据，抓播放页取第一个 dataid
            String dataid = null;
            String slug = null;
            int at = id.indexOf('@');
            if (at > 0) {
                dataid = id.substring(0, at);
                slug = id.substring(at + 1);
            } else {
                String pageUrl = id.startsWith("http") ? id : host + id;
                String html = fetch(pageUrl);
                int idx = pageUrl.lastIndexOf('/');
                if (idx >= 0 && idx < pageUrl.length() - 1) slug = pageUrl.substring(idx + 1);
                if (!TextUtils.isEmpty(html)) {
                    Matcher m = Pattern.compile("dataid=\"(\\d+)\"").matcher(html);
                    if (m.find()) dataid = m.group(1);
                }
            }
            if (TextUtils.isEmpty(dataid) || TextUtils.isEmpty(slug)) return fallback(id);

            // 签名调 /video/play 接口取真实播放地址（算法逆向自站点 wasm）
            String t = String.valueOf(System.currentTimeMillis());
            String s = sign(dataid, slug, t);
            String k = token(userlink);
            String url = host + "/video/play?p=" + URLEncoder.encode(dataid, "UTF-8")
                    + "&v=" + URLEncoder.encode(slug, "UTF-8")
                    + "&q=1080&s=" + s + "&t=" + t
                    + "&k=" + URLEncoder.encode(k, "UTF-8");

            String resp = fetch(url);
            if (TextUtils.isEmpty(resp)) return fallback(id);
            JSONObject j = new JSONObject(resp);
            if (j.optInt("code", -1) != 200) {
                SpiderDebug.log("[Bttw] play 接口 code=" + j.optInt("code", -1) + " msg=" + j.optString("message", ""));
                return fallback(id);
            }
            String playUrl = extractQualityUrl(j.optJSONObject("data"));
            if (TextUtils.isEmpty(playUrl)) return fallback(id);

            JSONObject result = new JSONObject();
            result.put("parse", 0);
            result.put("url", playUrl);
            JSONObject header = new JSONObject();
            header.put("User-Agent", headers.get("User-Agent"));
            header.put("Referer", host + "/");
            header.put("Origin", host);
            result.put("header", header);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log("[Bttw] playerContent 错误: " + e.getMessage());
            return fallback(id);
        }
    }

    /** 取不到直链时返回 parse=1 让壳子嗅探（保持原行为） */
    private String fallback(String id) {
        try {
            String url = id.startsWith("http") ? id : host + id;
            JSONObject f = new JSONObject();
            f.put("parse", 1);
            f.put("url", url);
            JSONObject header = new JSONObject();
            header.put("User-Agent", headers.get("User-Agent"));
            f.put("header", header);
            return f.toString();
        } catch (Exception e) {
            return "{\"parse\":1,\"url\":\"" + id + "\"}";
        }
    }

    // ---------- 播放签名（算法逆向自站点 wasm，36/36 用例 + 现场双视频验证） ----------

    /** 小写 hex */
    private static String hex(byte[] bs) {
        StringBuilder sb = new StringBuilder(bs.length * 2);
        for (byte b : bs) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /**
     * s = hex(SHA256((K^0x5C) || SHA256((K^0x36) || "dataid:t:secret_key")))[0:32]
     * K = secretKey 零填充到 64 字节（超过 64 字节先 SHA-256）
     * 注：0x5C = 0x36^0x6A，原站 opad 作用在已 ipad 的块上，服务端已固化该构造
     */
    private static String sign(String dataid, String secretKey, String t) throws Exception {
        byte[] msg = (dataid + ":" + t + ":" + secretKey).getBytes("UTF-8");
        byte[] k = secretKey.getBytes("UTF-8");
        if (k.length > 64) k = sha256(k);
        byte[] kb = new byte[64];
        System.arraycopy(k, 0, kb, 0, Math.min(k.length, 64));
        byte[] ipad = kb.clone();
        for (int i = 0; i < 64; i++) ipad[i] ^= 0x36;
        byte[] opad = ipad.clone();
        for (int i = 0; i < 64; i++) opad[i] ^= 0x6A;
        byte[] inner = sha256(concat(ipad, msg));
        byte[] outer = sha256(concat(opad, inner));
        return hex(outer).substring(0, 32);
    }

    /** k = Base64(userlink XOR "nbmovie2024secretkey" 循环) */
    private static String token(String ul) throws Exception {
        byte[] b = ul.getBytes("UTF-8");
        byte[] key = XOR_KEY.getBytes("UTF-8");
        for (int i = 0; i < b.length; i++) b[i] ^= key[i % key.length];
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    /** 取 data.quality_urls 中第一个未锁定清晰度的 url，全部锁定则退第一个非空 */
    private static String extractQualityUrl(JSONObject data) {
        if (data == null) return null;
        JSONArray qs = data.optJSONArray("quality_urls");
        if (qs == null) return null;
        String first = null;
        for (int i = 0; i < qs.length(); i++) {
            JSONObject q = qs.optJSONObject(i);
            if (q == null) continue;
            String u = q.optString("url", "");
            if (TextUtils.isEmpty(u) || u.equals("1")) continue;
            if (first == null) first = u;
            if (!q.optBoolean("locked", false)) return u;
        }
        return first;
    }
}
