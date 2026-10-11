package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.TmdbUtil;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 采集站展示 + drama123.tv 出源
 *
 * 分工 (2026-10-11 定的): 视频展示(首页/分类/搜索)全部走苹果CMS 采集站;
 * 点进某条视频时, 拿它的中文标题翻译成英文, 去 drama123.tv 搜, 用搜到的剧集出源并解析播放.
 *
 * 一、展示层: 采集站 CMS (https://caiji.xgzyapi.com/api.php/provide/vod)
 *   - 列表  ?ac=list&pg=N&pagesize=30
 *   - 分类  ?ac=list&t=<type_id>&pg=N
 *   - 搜索  ?ac=list&wd=<关键词>&pg=N   (中文直接搜, 不用翻译)
 *   - 详情  ?ac=detail&ids=<vod_id>     (返回 vod_play_from / vod_play_url)
 *   - 站上**没有 ac=class 接口**(传了也照返回 list), 分类表是实测枚举 t=1..60 得来的,
 *     见 CMS_TYPES —— 只有表里这些 id 有数据, 其余返回空.
 *   - CMS 自带源是 xigua / xiguam3u8, 值是播放页(不是直链), 需要壳子解析,
 *     playerContent 里对这类 flag 走 parse(1). 它只在 drama123 搜不到时兜底用.
 *
 * 二、出源层: drama123.tv (Kshow123 站型, 英文亚洲剧站)
 *   - 搜索 不是 ?keyword=, 站内 JS 拼的是 /search/<slug>/ , 分页 ?page=N;
 *     无结果时返回 200 + 0 卡片(标题 404 Page Not Found), 不是 HTTP 404.
 *     slug 规则照抄站内 JS: 小写 -> 去变音符号 -> 只留 [a-z0-9~\s.:;+=_]
 *     -> [\s.:;=+] 转 '-' -> 折叠/去首尾 '-'. 中文会被整段抹掉, 所以必须先翻译.
 *     实测: 中文直搜 0 结果; 拼音连写(leitingling) 0 结果;
 *           拼音带连字符(lei-ting-ling) 和 英文(thunder-chaser) 都能命中.
 *   - 详情 /series/<slug>/ , 剧集是表格里 <a href=".../episode-N.html">, 倒序排列.
 *   - 播放 (三级跳转):
 *       单集页 <script> var videoList = '[["<base64>"]]'
 *         -> https://vidbasic.top/embed/<id>
 *         -> 嵌入页 data-video="/3rdplayer.html?key=<密文>&id=&sub="
 *         -> key 是 AES-256-CBC(key/iv 均为硬编码 UTF-8 串) 的 base64 密文,
 *            解出来就是 https://stream.vidbasic.top/<uuid>/index.m3u8?<ts>
 *            (?<ts> 实测去掉也能 200, 无时效校验, 但仍原样带上)
 *     sub 参数同样密文, 解出来是 https://kk.vidbasic.top/track/xxx —— 该端点
 *            实测稳定 400 Invalid request, 故本爬虫不挂字幕.
 *
 * 翻译走 TmdbUtil.getEnTitle() (TMDB 两级检索, 拿不到时原样返回).
 * TMDB 没收录的剧 -> 翻不出英文 -> drama123 必然 0 结果, 是数据边界不是 bug.
 */
public class Drama123 extends Spider {

    private static final String CMS_API = "https://caiji.xgzyapi.com/api.php/provide/vod";
    private static final String DEFAULT_HOST = "https://drama123.tv";
    // 第二出源站: LayarAsia (印尼 sub indo 站, WordPress 站型). 多源托管, 播放页用 base64 编码的
    // <option> 装各托管 iframe (UPNShare=upns.online / HydraX=abyssplayer.com / StreamP2P=strp2p.site).
    // 这些托管本身都是浏览器渲染的播放页, 交给壳子 WebView(parse=1) 直接加载即可, 不必深解 m3u8.
    private static final String LAYAR_HOST = "https://server-1.layar.asia";
    private static final String FLAG_LAYAR = "LayarAsia";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final int PAGE_SIZE = 30;
    private static final String FLAG_DRAMA = "Drama123";

    /** 实测枚举 t=1..60 得到的分类表 (2026-10-11), 其余 id 返回空 */
    private static final String[][] CMS_TYPES = {
            {"2", "连续剧"}, {"6", "动作片"}, {"7", "喜剧片"}, {"8", "爱情片"},
            {"9", "科幻片"}, {"10", "恐怖片"}, {"11", "剧情片"}, {"12", "战争片"},
            {"13", "国产剧"}, {"14", "香港剧"}, {"15", "韩国剧"}, {"16", "欧美剧"},
            {"20", "记录片"}, {"21", "台湾剧"}, {"22", "日本剧"}, {"23", "海外剧"},
            {"24", "泰国剧"}, {"25", "大陆综艺"}, {"26", "港台综艺"}, {"27", "日韩综艺"},
            {"28", "欧美综艺"}, {"29", "国产动漫"}, {"30", "日韩动漫"}, {"31", "欧美动漫"},
            {"32", "港台动漫"}, {"34", "伦理片"}, {"36", "短剧"}, {"37", "动画片"},
            {"38", "女恋总裁"}, {"39", "都市脑洞"}, {"40", "闪婚离婚"}, {"41", "现代言情"},
            {"42", "反转爽文"}, {"43", "古装仙侠"}, {"44", "重生民国"}, {"45", "穿越年代"}
    };

    /** drama123 播放器解密常量: 3rdplayer.html 内联混淆脚本里硬编码 */
    private static final String PLAY_KEY = "94588293375053432799222445521289";
    private static final String PLAY_IV = "5259228356829423";

    private static final Pattern SERIES_PATH = Pattern.compile("/series/([^/]+)/?");
    private static final Pattern EPISODE = Pattern.compile("/series/([^/]+)/episode-(\\d+)\\.html");

    private String cmsApi = CMS_API;
    private String host = DEFAULT_HOST;

    // 采集站最近一次列表的分页信息 (给壳子翻页用)
    private int cmsPage = 1;
    private int cmsPagecount = 1;
    private int cmsLimit = PAGE_SIZE;
    private int cmsTotal = 0;

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        // extend 里给的地址自动分流: 含 provide/vod 的是采集站, 其余当 drama123 镜像
        if (!TextUtils.isEmpty(extend)) {
            for (String part : extend.split("[|,;]")) {
                String p = part.trim();
                if (!p.startsWith("http")) continue;
                if (p.contains("provide/vod")) cmsApi = p;
                else host = p.replaceAll("/+$", "");
            }
        }
    }

    // ==================== 展示层: 采集站 ====================

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        for (String[] t : CMS_TYPES) classes.add(new Class(t[0], t[1]));
        return Result.string(classes, cmsList("ac=list&pg=1&pagesize=" + PAGE_SIZE));
    }

    @Override
    public String homeVideoContent() throws Exception {
        return listResult(cmsList("ac=list&pg=1&pagesize=" + PAGE_SIZE));
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String page = TextUtils.isEmpty(pg) ? "1" : pg;
        String query = "ac=list&pg=" + page + "&pagesize=" + PAGE_SIZE;
        if (!TextUtils.isEmpty(tid)) query += "&t=" + tid;
        return listResult(cmsList(query));
    }

    /** 采集站搜索: 中文关键词直接搜, 不用翻译 */
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        String word = key == null ? "" : key.trim();
        if (TextUtils.isEmpty(word)) return listResult(new ArrayList<Vod>());
        String page = TextUtils.isEmpty(pg) ? "1" : pg;
        String query = "ac=list&pg=" + page + "&pagesize=" + PAGE_SIZE + "&wd=" + encode(word);
        return listResult(cmsList(query));
    }

    // ==================== 点击之后: CMS 拿名 -> 翻译 -> drama123 搜 + 解析 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("id 为空");
        String id = ids.get(0);
        JSONObject cms = cmsJson("ac=detail&ids=" + encode(id));

        Vod vod = new Vod();
        vod.setVodId(id);
        String name = "";
        if (cms != null) {
            name = cms.optString("vod_name");
            vod.setVodName(name);
            vod.setVodPic(cms.optString("vod_pic"));
            vod.setVodRemarks(cms.optString("vod_remarks"));
            vod.setVodYear(cms.optString("vod_year"));
            vod.setVodArea(cms.optString("vod_area"));
            vod.setVodActor(cms.optString("vod_actor"));
            vod.setVodDirector(cms.optString("vod_director"));
            vod.setVodTag(cms.optString("vod_class"));
            String blurb = cms.optString("vod_content");
            if (TextUtils.isEmpty(blurb)) blurb = cms.optString("vod_blurb");
            vod.setVodContent(clean(blurb));
        }

        List<String> froms = new ArrayList<>();
        List<String> urls = new ArrayList<>();

        // 中文标题 -> 英文 -> drama123 搜索 -> 剧集列表
        String en = containsChinese(name) ? TmdbUtil.getEnTitle(name) : name;
        String slug = toSlug(en);
        if (!TextUtils.isEmpty(slug)) {
            Vod best = pickBest(parseCards(doc(host + "/search/" + slug + "/")), slug);
            if (best != null) {
                TreeMap<Integer, String> eps = episodes(abs(best.getVodId()));
                if (!eps.isEmpty()) {
                    froms.add(FLAG_DRAMA);
                    urls.add(TextUtils.join("#", new ArrayList<>(eps.values())));
                }
            }
            if (froms.isEmpty()) vod.setVodRemarks(join(vod.getVodRemarks(), "drama123无结果"));
        }

        // LayarAsia 也搜一遍: 英文全文搜索(?s=), 拿 series -> 每集播放页 -> base64 编码的多托管 iframe.
        // 这些 iframe 源(UPN/Hydra/P2P)本身都是播放页, 交给壳子 WebView(parse=1) 加载即可.
        if (!TextUtils.isEmpty(en)) {
            String series = layarSearch(en);
            if (!TextUtils.isEmpty(series)) {
                TreeMap<Integer, List<String>> leps = layarEpisodes(absLayar(series));
                if (!leps.isEmpty()) {
                    int n = 0;
                    for (List<String> arr : leps.values()) n = Math.max(n, arr.size());
                    for (int s = 0; s < n; s++) {
                        List<String> one = new ArrayList<>();
                        for (Map.Entry<Integer, List<String>> e : leps.entrySet()) {
                            List<String> arr = e.getValue();
                            String src = s < arr.size() ? arr.get(s) : "";
                            if (!TextUtils.isEmpty(src)) one.add("Episode " + e.getKey() + "$" + src);
                        }
                        if (!one.isEmpty()) {
                            froms.add(n > 1 ? (FLAG_LAYAR + (s + 1)) : FLAG_LAYAR);
                            urls.add(TextUtils.join("#", one));
                        }
                    }
                }
                if (froms.isEmpty()) vod.setVodRemarks(join(vod.getVodRemarks(), "layar无结果"));
            }
        }

        // 兜底: 采集站自己的源 (播放页, 需要壳子解析)
        if (cms != null) appendCmsSources(cms, froms, urls);

        vod.setVodPlayFrom(TextUtils.join("$$$", froms));
        vod.setVodPlayUrl(TextUtils.join("$$$", urls));
        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (TextUtils.isEmpty(id)) return Result.error("播放地址为空");
        // 采集站的源是播放页, 交给壳子解析
        if (!FLAG_DRAMA.equals(flag)) {
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", UA);
            return Result.get().parse(1).url(id).header(h).string();
        }

        String html = OkHttp.string(abs(id), dramaHeaders());
        if (TextUtils.isEmpty(html)) return Result.error("单集页拉取失败");

        List<String> urls = new ArrayList<>();
        // 浏览器里 m3u8 请求是 iframe 发出的, Referer 带的是嵌入页域名(vidbasic.top),
        // 不是 m3u8 自己的域名 —— 照抄, 免得 CDN 校验 Referer 时挂掉
        String referer = "";
        for (String embed : embedUrls(html)) {
            String eh = OkHttp.string(embed, dramaHeaders());
            if (TextUtils.isEmpty(eh)) continue;
            String playerPath = first(eh, "data-video=\"([^\"]+)\"");
            if (TextUtils.isEmpty(playerPath)) {
                playerPath = first(eh, "<iframe[^>]+src=\"([^\"]*3rdplayer[^\"]*)\"");
            }
            if (TextUtils.isEmpty(playerPath)) continue;
            String playerUrl = playerPath.startsWith("http") ? playerPath : origin(embed) + playerPath;
            // sub 参数同样能解出 https://kk.vidbasic.top/track/xxx, 但该端点实测稳定
            // 400 Invalid request(换全套浏览器头也无效), 所以不挂字幕, 解了也白解
            String video = aesDecrypt(param(playerUrl, "key"));
            if (video.startsWith("http")) {
                if (!urls.contains(video)) urls.add(video);
                if (TextUtils.isEmpty(referer)) referer = origin(embed) + "/";
            }
        }
        if (urls.isEmpty()) return Result.error("未解出播放地址");
        if (TextUtils.isEmpty(referer)) referer = origin(urls.get(0)) + "/";

        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", referer);
        h.put("Accept", "*/*");
        Result result = Result.get().parse(0).url(urls).header(h);
        if (urls.get(0).contains(".m3u8")) result.m3u8();
        return result.string();
    }

    // ==================== 采集站 ====================

    private String cms(String query) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        return OkHttp.string(cmsApi + "?" + query, h);
    }

    private JSONObject cmsJson(String query) {
        String json = cms(query);
        if (TextUtils.isEmpty(json)) return null;
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray arr = obj.optJSONArray("list");
            return arr != null && arr.length() > 0 ? arr.getJSONObject(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<Vod> cmsList(String query) {
        List<Vod> list = new ArrayList<>();
        String json = cms(query);
        if (TextUtils.isEmpty(json)) return list;
        try {
            JSONObject obj = new JSONObject(json);
            cmsPage = obj.optInt("page", 1);
            cmsPagecount = obj.optInt("pagecount", 1);
            cmsLimit = obj.optInt("limit", PAGE_SIZE);
            cmsTotal = obj.optInt("total", 0);
            JSONArray arr = obj.optJSONArray("list");
            if (arr == null) return list;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Vod v = new Vod();
                v.setVodId(o.optString("vod_id"));
                v.setVodName(o.optString("vod_name"));
                v.setVodPic(o.optString("vod_pic"));
                v.setVodRemarks(o.optString("vod_remarks"));
                list.add(v);
            }
        } catch (Exception e) {
            // 采集站返回非 JSON(被拦/超时)时给空列表, 不让壳子崩
        }
        return list;
    }

    private String listResult(List<Vod> list) {
        return Result.string(cmsPage, cmsPagecount, cmsLimit, cmsTotal, list);
    }

    private void appendCmsSources(JSONObject cms, List<String> froms, List<String> urls) {
        String from = cms.optString("vod_play_from", "");
        String url = cms.optString("vod_play_url", "");
        if (TextUtils.isEmpty(from) || TextUtils.isEmpty(url)) return;
        String[] fs = from.split("\\$\\$\\$");
        String[] us = url.split("\\$\\$\\$");
        for (int i = 0; i < fs.length && i < us.length; i++) {
            if (TextUtils.isEmpty(fs[i].trim()) || TextUtils.isEmpty(us[i].trim())) continue;
            froms.add(fs[i].trim());
            urls.add(us[i].trim());
        }
    }

    // ==================== drama123 ====================

    private Map<String, String> dramaHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", host + "/");
        h.put("Accept-Language", "en-US,en;q=0.9");
        return h;
    }

    /** 列表/搜索页卡片: <a class="thumbnail" href="..."> + 同级的 <h2><a>标题</a></h2> */
    private List<Vod> parseCards(Document doc) {
        List<Vod> list = new ArrayList<>();
        if (doc == null) return list;
        List<String> seen = new ArrayList<>();
        for (Element a : doc.select("a.thumbnail")) {
            String href = a.attr("href");
            String path = seriesPath(href);
            if (TextUtils.isEmpty(path) || seen.contains(path)) continue;
            seen.add(path);
            Element parent = a.parent();
            String name = parent == null ? "" : text(parent.selectFirst("h2 a"));
            if (TextUtils.isEmpty(name) && parent != null) name = text(parent.selectFirst("h2"));
            if (TextUtils.isEmpty(name)) {
                Element img = a.selectFirst("img");
                name = img == null ? "" : img.attr("alt").replaceAll("(?i)\\s*cover\\s*$", "").trim();
            }
            name = name.replaceAll("(?i)\\s*episode\\s*\\d+\\s*$", "").replaceAll("(?i)\\s*ep\\s*\\d+\\s*$", "").trim();
            Vod v = new Vod();
            v.setVodId(path);
            v.setVodName(name);
            Element img = a.selectFirst("img");
            if (img != null) {
                String src = img.attr("abs:src");
                v.setVodPic(TextUtils.isEmpty(src) ? img.attr("src") : src);
            }
            StringBuilder sb = new StringBuilder();
            if (parent != null && parent.selectFirst("span.info-overlay-sub, span.label-sub") != null) sb.append("SUB");
            Matcher m = Pattern.compile("episode-(\\d+)\\.html").matcher(href);
            if (m.find()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append("Ep ").append(m.group(1));
            }
            v.setVodRemarks(sb.toString());
            list.add(v);
        }
        return list;
    }

    /** 剧集页 -> 集号 -> "Episode N$<单集页地址>" */
    private TreeMap<Integer, String> episodes(String seriesUrl) {
        TreeMap<Integer, String> eps = new TreeMap<>();
        Document doc = doc(seriesUrl);
        if (doc == null) return eps;
        for (Element a : doc.select("a")) {
            Matcher m = EPISODE.matcher(a.attr("href"));
            if (!m.find()) continue;
            int no = Integer.parseInt(m.group(2));
            if (eps.containsKey(no)) continue;
            eps.put(no, "Episode " + no + "$" + abs(a.attr("href")));
        }
        return eps;
    }

    /** 搜索结果里挑最像的那条: 优先 slug 命中翻译名的, 否则第一条 */
    private Vod pickBest(List<Vod> hits, String slug) {
        if (hits == null || hits.isEmpty()) return null;
        for (Vod v : hits) {
            String path = v.getVodId();
            if (!TextUtils.isEmpty(path) && path.contains(slug)) return v;
        }
        return hits.get(0);
    }

    /** 单集页 -> vidbasic 嵌入页地址 (videoList 里的 base64) */
    private List<String> embedUrls(String html) {
        List<String> out = new ArrayList<>();
        String arr = first(html, "videoList\\s*=\\s*'([^']*)'");
        if (TextUtils.isEmpty(arr)) return out;
        Matcher m = Pattern.compile("\"([A-Za-z0-9+/=_\\-]{16,})\"").matcher(arr);
        while (m.find()) {
            String u = b64(m.group(1));
            if (u.startsWith("//")) u = "https:" + u;
            if (u.startsWith("http") && !out.contains(u)) out.add(u);
        }
        return out;
    }

    /** AES-256-CBC 解播放器密文 (key/iv 都是 UTF-8 原串, 不是 hex) */
    private static String aesDecrypt(String cipherText) {
        if (TextUtils.isEmpty(cipherText)) return "";
        try {
            byte[] data = b64Bytes(cipherText);
            if (data.length == 0) return "";
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(PLAY_KEY.getBytes("UTF-8"), "AES"),
                    new IvParameterSpec(PLAY_IV.getBytes("UTF-8")));
            return new String(c.doFinal(data), "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    // 用 android.util.Base64 而不是 java.util.Base64: 项目 minSdk 16, 后者要 API 26
    private static byte[] b64Bytes(String s) {
        try {
            return Base64.decode(s, Base64.DEFAULT);
        } catch (Exception e) {
            try {
                return Base64.decode(s, Base64.URL_SAFE);
            } catch (Exception ignored) {
                return new byte[0];
            }
        }
    }

    private static String b64(String s) {
        byte[] b = b64Bytes(s);
        return b.length == 0 ? "" : new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 搜索 slug: 照抄站内 JS do_search() 的清洗规则 */
    private static String toSlug(String kw) {
        if (TextUtils.isEmpty(kw)) return "";
        String s = kw.toLowerCase();
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.replaceAll("[^-a-z0-9~\\s.:;+=_]", "");
        s = s.replaceAll("[\\s.:;=+]+", "-");
        s = s.replaceAll("-{2,}", "-");
        return s.replaceAll("^-+|-+$", "");
    }

    private static boolean containsChinese(String s) {
        return !TextUtils.isEmpty(s) && Pattern.compile("[\\u4e00-\\u9fa5]").matcher(s).find();
    }

    private static String seriesPath(String s) {
        if (TextUtils.isEmpty(s)) return "";
        Matcher m = SERIES_PATH.matcher(s);
        if (!m.find()) return "";
        String path = m.group(0);
        return path.endsWith("/") ? path : path + "/";
    }

    private String abs(String path) {
        if (TextUtils.isEmpty(path)) return "";
        if (path.startsWith("http")) return path;
        if (path.startsWith("/")) return host + path;
        return host + "/series/" + path + "/";
    }

    private static String origin(String url) {
        Matcher m = Pattern.compile("^(https?://[^/]+)").matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : "";
    }

    /** 取 query 参数, 带 URL 解码 (先把 '+' 转义掉, 免得被当成空格) */
    private static String param(String url, String name) {
        Matcher m = Pattern.compile("[?&]" + Pattern.quote(name) + "=([^&#]*)").matcher(url == null ? "" : url);
        if (!m.find()) return "";
        try {
            return URLDecoder.decode(m.group(1).replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return m.group(1);
        }
    }

    private static String first(String s, String regex) {
        if (TextUtils.isEmpty(s)) return "";
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static String text(Element e) {
        return e == null ? "" : e.text().trim();
    }

    /** 采集站的简介带 <p> 和 &nbsp; / &amp; 之类, 去标签 + 反转义 */
    private static String clean(String s) {
        if (TextUtils.isEmpty(s)) return "";
        return s.replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim();
    }

    private static String join(String a, String b) {
        if (TextUtils.isEmpty(a)) return b;
        if (TextUtils.isEmpty(b)) return a;
        return a + " · " + b;
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ==================== LayarAsia (server-1.layar.asia) ====================

    private Map<String, String> layarHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", LAYAR_HOST + "/");
        h.put("Accept-Language", "en-US,en;q=0.9");
        return h;
    }

    private Document docLayar(String url) {
        String html = OkHttp.string(url, layarHeaders());
        return TextUtils.isEmpty(html) ? null : Jsoup.parse(html, LAYAR_HOST);
    }

    private String absLayar(String path) {
        if (TextUtils.isEmpty(path)) return "";
        if (path.startsWith("http")) return path;
        if (path.startsWith("/")) return LAYAR_HOST + path;
        return LAYAR_HOST + "/" + path;
    }

    /** 英文关键词全文搜索 -> 第一个 series 绝对地址 */
    private String layarSearch(String kw) {
        Document doc = docLayar(LAYAR_HOST + "/?s=" + encode(kw));
        if (doc == null) return "";
        for (Element a : doc.select("a.series")) {
            String href = a.attr("href");
            if (!TextUtils.isEmpty(href)) return absLayar(href);
        }
        return "";
    }

    /** series 页 -> 集号 -> 该集播放页的各托管 iframe 源(按 data-index 顺序) */
    private TreeMap<Integer, List<String>> layarEpisodes(String seriesUrl) {
        TreeMap<Integer, List<String>> map = new TreeMap<>();
        Document doc = docLayar(seriesUrl);
        if (doc == null) return map;
        // 本剧主名: series slug 去掉末尾 -YYYY (如 the-ordinary-jackpot-2026 -> the-ordinary-jackpot)
        String base = seriesUrl.replaceAll("^https?://[^/]+/", "").replaceAll("/+$", "");
        String main = base.replaceAll("-\\d{4}$", "");
        for (Element a : doc.select("a")) {
            String path = absLayar(a.attr("href"));
            Matcher m = Pattern.compile("/(?:[^/]+/)?([^/]+)-episode-(\\d+)[^/]*/?$").matcher(path);
            if (!m.find()) continue;
            if (!m.group(1).startsWith(main)) continue; // 过滤侧栏其它剧
            int no = Integer.parseInt(m.group(2));
            if (map.containsKey(no)) continue;
            map.put(no, layarEpisodeSrcs(OkHttp.string(path, layarHeaders())));
        }
        return map;
    }

    /** 播放页 -> 各托管 iframe src. 优先取 base64 编码的 <option>(UPN/Hydra/P2P), 否则退化为页面 iframe */
    private List<String> layarEpisodeSrcs(String html) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return out;
        List<String[]> pairs = new ArrayList<>();
        Matcher om = Pattern.compile("<option\\b[^>]*>").matcher(html);
        while (om.find()) {
            String tag = om.group(0);
            String val = first(tag, "value=\"([^\"]+)\"");
            String idx = first(tag, "data-index=\"(\\d+)\"");
            if (TextUtils.isEmpty(val)) continue;
            byte[] b = b64Bytes(val);
            if (b.length == 0) continue;
            String decoded = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            if (!decoded.contains("iframe")) continue;
            String src = first(decoded, "src=\"([^\"]+)\"");
            if (!TextUtils.isEmpty(src)) pairs.add(new String[]{ idx, src });
        }
        if (!pairs.isEmpty()) {
            Collections.sort(pairs, new Comparator<String[]>() {
                @Override
                public int compare(String[] a, String[] b) {
                    int ia = TextUtils.isEmpty(a[0]) ? 0 : Integer.parseInt(a[0]);
                    int ib = TextUtils.isEmpty(b[0]) ? 0 : Integer.parseInt(b[0]);
                    return Integer.compare(ia, ib);
                }
            });
            for (String[] p : pairs) out.add(p[1]);
            return out;
        }
        Matcher im = Pattern.compile("<iframe[^>]+src=\"([^\"]+)\"").matcher(html);
        while (im.find()) out.add(im.group(1));
        return out;
    }

    private Document doc(String url) {
        String html = OkHttp.string(url, dramaHeaders());
        return TextUtils.isEmpty(html) ? null : Jsoup.parse(html, host);
    }
}
