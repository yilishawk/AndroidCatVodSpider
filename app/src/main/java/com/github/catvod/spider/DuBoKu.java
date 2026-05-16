package com.github.catvod.spider;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 独播库[全功能筛选版]
 * 站点: https://www.dbku.tv
 *
 * 修复记录:
 * 1. encrypt=2 时需要 Base64 decode → URLDecode 两步解码
 * 2. 使用 Util.base64Decode() 替代 java.util.Base64（Android 兼容）
 * 3. 使用 Util.unicodeToString() 还原 vod_data 内的 Unicode 转义字段
 * 4. User-Agent 复用 Util.CHROME 常量
 */
public class DuBoKu extends Spider {

    private static final String HOST = "https://www.dbku.tv";
    private static final Headers HEADERS = new Headers.Builder()
            .add("User-Agent", Util.CHROME)
            .add("Referer", HOST + "/")
            .build();

    private final OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    // ──────────────────────────────────────────────
    // 网络请求
    // ──────────────────────────────────────────────

    private String fetch(String url) throws Exception {
        Request request = new Request.Builder().url(url).headers(HEADERS).build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return new String(response.body().bytes(), "UTF-8");
            }
            throw new Exception("HTTP " + response.code());
        }
    }

    private String encode(String s) throws Exception {
        return s == null || s.isEmpty() ? "" : URLEncoder.encode(s, "UTF-8");
    }

    // ──────────────────────────────────────────────
    // homeContent
    // ──────────────────────────────────────────────

    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();
            String[][] classArr = {
                {"13", "陆剧"}, {"2", "电视剧"}, {"1", "电影"}, {"3", "综艺"},
                {"4", "动漫"}, {"15", "日韩剧"}, {"21", "短剧"}, {"14", "台泰剧"}, {"20", "港剧"}
            };
            for (String[] c : classArr) {
                JSONObject cls = new JSONObject();
                cls.put("type_id", c[0]);
                cls.put("type_name", c[1]);
                classes.put(cls);
            }
            result.put("class", classes);

            if (filter) {
                JSONArray tvFilter = new JSONArray();
                tvFilter.put(createFilter("class", "剧情", new String[][]{
                    {"全部", ""}, {"悬疑", "悬疑"}, {"武侠", "武侠"}, {"科幻", "科幻"},
                    {"都市", "都市"}, {"爱情", "爱情"}, {"古装", "古装"}, {"战争", "战争"},
                    {"青春", "青春"}, {"偶像", "偶像"}, {"喜剧", "喜剧"}, {"家庭", "家庭"},
                    {"奇幻", "奇幻"}, {"剧情", "剧情"}, {"乡村", "乡村"}, {"年代", "年代"},
                    {"警匪", "警匪"}, {"谍战", "谍战"}, {"历险", "历险"}, {"罪案", "罪案"},
                    {"宫廷", "宫廷"}, {"经典", "经典"}, {"动作", "动作"}, {"惊悚", "惊悚"},
                    {"历史", "历史"}, {"穿越", "穿越"}, {"同性", "同性"}
                }));
                tvFilter.put(createFilter("area", "地区", new String[][]{
                    {"全部", ""}, {"大陆", "大陆"}, {"香港", "香港"}, {"台湾", "台湾"},
                    {"韩国", "韩国"}, {"日本", "日本"}, {"新加坡", "新加坡"}, {"泰国", "泰国"}
                }));
                tvFilter.put(createFilter("year", "年份", new String[][]{
                    {"全部", ""}, {"2026", "2026"}, {"2025", "2025"}, {"2024", "2024"},
                    {"2023", "2023"}, {"2022", "2022"}, {"2021", "2021"}, {"2020", "2020"},
                    {"2019", "2019"}, {"2018", "2018"}, {"2017", "2017"}, {"更早", "更早"}
                }));
                tvFilter.put(createFilter("lang", "语言", new String[][]{
                    {"全部", ""}, {"国语", "国语"}, {"粤语", "粤语"}, {"韩语", "韩语"},
                    {"泰语", "泰语"}, {"日语", "日语"}
                }));
                tvFilter.put(createFilter("by", "排序", new String[][]{
                    {"时间", "time"}, {"人气", "hits"}, {"评分", "score"}
                }));

                JSONArray movieFilter = new JSONArray();
                movieFilter.put(createFilter("class", "剧情", new String[][]{
                    {"全部", ""}, {"喜剧", "喜剧"}, {"爱情", "爱情"}, {"恐怖", "恐怖"},
                    {"动作", "动作"}, {"科幻", "科幻"}, {"剧情", "剧情"}, {"警匪", "警匪"},
                    {"战争", "战争"}, {"犯罪", "犯罪"}, {"动画", "动画"}, {"奇幻", "奇幻"},
                    {"武侠", "武侠"}, {"冒险", "冒险"}, {"悬疑", "悬疑"}, {"惊悚", "惊悚"},
                    {"古装", "古装"}, {"同性", "同性"}
                }));
                movieFilter.put(createFilter("area", "地区", new String[][]{
                    {"全部", ""}, {"大陆", "大陆"}, {"香港", "香港"}, {"台湾", "台湾"},
                    {"韩国", "韩国"}, {"英国", "英国"}, {"法国", "法国"}, {"加拿大", "加拿大"},
                    {"澳大利亚", "澳大利亚"}
                }));
                movieFilter.put(createFilter("year", "年份", new String[][]{
                    {"全部", ""}, {"2026", "2026"}, {"2025", "2025"}, {"2024", "2024"},
                    {"2023", "2023"}, {"2022", "2022"}, {"2020", "2020"}, {"2019", "2019"}
                }));
                movieFilter.put(createFilter("lang", "语言", new String[][]{
                    {"全部", ""}, {"国语", "国语"}, {"粤语", "粤语"}, {"韩语", "韩语"},
                    {"英语", "英语"}, {"法语", "法语"}
                }));
                movieFilter.put(createFilter("by", "排序", new String[][]{
                    {"时间", "time"}, {"人气", "hits"}, {"评分", "score"}
                }));

                JSONObject filters = new JSONObject();
                for (String[] c : classArr) {
                    String tid = c[0];
                    if (tid.equals("1") || tid.equals("4")) {
                        filters.put(tid, movieFilter);
                    } else {
                        filters.put(tid, tvFilter);
                    }
                }
                result.put("filters", filters);
            }
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return "";
    }

    private JSONObject createOption(String n, String v) throws Exception {
        JSONObject o = new JSONObject();
        o.put("n", n);
        o.put("v", v);
        return o;
    }

    private JSONObject createFilter(String key, String name, String[][] options) throws Exception {
        JSONArray arr = new JSONArray();
        for (String[] opt : options) {
            arr.put(createOption(opt[0], opt[1]));
        }
        JSONObject f = new JSONObject();
        f.put("key", key);
        f.put("name", name);
        f.put("value", arr);
        return f;
    }

    // ──────────────────────────────────────────────
    // categoryContent
    // ──────────────────────────────────────────────

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            if (extend == null) extend = new HashMap<>();
            String area   = extend.getOrDefault("area", "");
            String by     = extend.getOrDefault("by", "time");
            String class_ = extend.getOrDefault("class", "");
            String lang   = extend.getOrDefault("lang", "");
            String year   = extend.getOrDefault("year", "");

            String url = HOST + "/vodshow/" + tid + "-" + encode(area) + "-" + by + "-" +
                         encode(class_) + "-" + encode(lang) + "----" + pg + "---" + year + ".html";

            SpiderDebug.log("[DuBoKu] category URL: " + url);
            String html = fetch(url);
            Document doc = Jsoup.parse(html);

            Elements items = doc.select("ul.myui-vodlist li");
            if (items.isEmpty()) {
                items = doc.select("li .myui-vodlist__thumb").parents();
                SpiderDebug.log("[DuBoKu] no items found, trying fallback selectors");
            }

            JSONArray videoList = new JSONArray();
            for (Element li : items) {
                Element a = li.selectFirst("a.myui-vodlist__thumb");
                if (a == null) a = li.selectFirst("a[data-original]");
                if (a == null) continue;

                JSONObject vod = new JSONObject();
                String href = a.attr("href");
                vod.put("vod_id", href.startsWith("/") ? href : "/" + href);
                vod.put("vod_name", a.attr("title"));
                vod.put("vod_pic", a.attr("data-original"));
                Element picText = li.selectFirst(".pic-text");
                vod.put("vod_remarks", picText != null ? picText.text().trim() : "");
                videoList.put(vod);
            }

            JSONObject result = new JSONObject();
            result.put("list", videoList);
            result.put("page", Integer.parseInt(pg));
            result.put("pagecount", 99);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return "";
    }

    // ──────────────────────────────────────────────
    // detailContent
    // ──────────────────────────────────────────────

    @Override
    public String detailContent(List<String> ids) {
        try {
            String vodId = ids.get(0);
            String url = vodId.startsWith("http") ? vodId : HOST + vodId;
            String html = fetch(url);
            Document doc = Jsoup.parse(html);

            Element detail = doc.selectFirst(".myui-content__detail");
            if (detail == null) return "{\"list\":[]}";

            Element titleElem = detail.selectFirst("h1.title");
            String title = titleElem != null ? titleElem.text().trim() : "";
            Element img = doc.selectFirst(".myui-content__thumb img");
            String pic = img != null ? img.attr("data-original") : "";
            Element sketch = doc.selectFirst(".sketch.content");
            String content = sketch != null ? sketch.text().trim() : "";

            String director = "", actor = "", typeName = "", area = "", year = "", remarks = "";
            for (Element p : detail.select("p.data")) {
                String text = p.text().trim();
                if (text.contains("导演：")) {
                    director = text.replace("导演：", "");
                } else if (text.contains("主演：")) {
                    StringBuilder sb = new StringBuilder();
                    for (Element a : p.select("a")) {
                        if (sb.length() > 0) sb.append(" / ");
                        sb.append(a.text().trim());
                    }
                    actor = sb.toString();
                } else if (text.contains("分类：")) {
                    Elements aTags = p.select("a");
                    if (aTags.size() > 0) typeName = aTags.get(0).text().trim();
                    if (aTags.size() > 1) area = aTags.get(1).text().trim();
                    if (aTags.size() > 2) year = aTags.get(2).text().trim();
                } else if (text.contains("更新：")) {
                    remarks = text.replace("更新：", "");
                }
            }

            StringBuilder playUrl = new StringBuilder();
            Elements playList = doc.select(".myui-content__list li a");
            if (playList.isEmpty()) {
                playList = doc.select(".tab-content .myui-content__list li a");
            }
            for (Element a : playList) {
                String name = a.text().trim();
                String href = a.attr("href");
                if (playUrl.length() > 0) playUrl.append("#");
                playUrl.append(name).append("$").append(href);
            }

            JSONObject vod = new JSONObject();
            vod.put("vod_id", vodId);
            vod.put("vod_name", title);
            vod.put("vod_pic", pic);
            vod.put("vod_content", content);
            vod.put("vod_director", director);
            vod.put("vod_actor", actor);
            vod.put("type_name", typeName);
            vod.put("vod_area", area);
            vod.put("vod_year", year);
            vod.put("vod_remarks", remarks);
            vod.put("vod_play_from", "独播库");
            vod.put("vod_play_url", playUrl.toString());

            JSONArray list = new JSONArray();
            list.put(vod);
            JSONObject result = new JSONObject();
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return "{\"list\":[]}";
    }

    // ──────────────────────────────────────────────
    // searchContent
    // ──────────────────────────────────────────────

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String url = HOST + "/vodsearch/-------------.html?wd=" + encode(key);
            String html = fetch(url);
            Document doc = Jsoup.parse(html);
            JSONArray videoList = new JSONArray();
            for (Element li : doc.select("#searchList li")) {
                Element a = li.selectFirst("a.myui-vodlist__thumb");
                if (a == null) continue;
                JSONObject vod = new JSONObject();
                String href = a.attr("href");
                vod.put("vod_id", href.startsWith("/") ? href : "/" + href);
                vod.put("vod_name", a.attr("title"));
                vod.put("vod_pic", a.attr("data-original"));
                Element tag = a.selectFirst(".tag");
                vod.put("vod_remarks", tag != null ? tag.text().trim() : "");
                videoList.put(vod);
            }
            JSONObject result = new JSONObject();
            result.put("list", videoList);
            return result.toString();
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return "{\"list\":[]}";
    }

    // ──────────────────────────────────────────────
    // playerContent
    // ──────────────────────────────────────────────

    /**
     * 播放解析
     * 解析优先级：player_data(encrypt) > iframe > video标签 > m3u8直链 > 嗅探兜底
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String url = id.startsWith("http") ? id : HOST + id;
            SpiderDebug.log("[DuBoKu] playerContent url: " + url);

            String html = fetch(url);
            if (html == null || html.isEmpty()) {
                SpiderDebug.log("[DuBoKu] Failed to fetch page");
                return "{\"parse\":1,\"url\":\"" + url + "\"}";
            }

            // 方式1: 优先从 player_data 提取（支持 encrypt 字段三种解码方式）
            String realUrl = extractPlayerUrl(html);
            if (realUrl != null && realUrl.startsWith("http")) {
                SpiderDebug.log("[DuBoKu] Extracted player_data URL: " + realUrl);
                JSONObject result = new JSONObject();
                result.put("parse", 0);
                result.put("url", realUrl);
                JSONObject header = new JSONObject();
                header.put("Referer", HOST + "/");
                result.put("header", header);
                return result.toString();
            }

            // 方式2: 尝试从 iframe 中提取 src
            realUrl = extractIframeUrl(html);
            if (realUrl != null && realUrl.startsWith("http")) {
                SpiderDebug.log("[DuBoKu] Extracted iframe URL: " + realUrl);
                JSONObject result = new JSONObject();
                result.put("parse", 1);
                result.put("url", realUrl);
                return result.toString();
            }

            // 方式3: 尝试从 video 标签中提取 src
            realUrl = extractVideoUrl(html);
            if (realUrl != null && realUrl.startsWith("http")) {
                SpiderDebug.log("[DuBoKu] Extracted video URL: " + realUrl);
                JSONObject result = new JSONObject();
                result.put("parse", 0);
                result.put("url", realUrl);
                return result.toString();
            }

            // 方式4: 尝试从页面中直接提取 m3u8 链接
            realUrl = extractM3u8Url(html);
            if (realUrl != null && realUrl.startsWith("http")) {
                SpiderDebug.log("[DuBoKu] Extracted m3u8 URL: " + realUrl);
                JSONObject result = new JSONObject();
                result.put("parse", 0);
                result.put("url", realUrl);
                return result.toString();
            }

        } catch (Exception e) {
            SpiderDebug.log("[DuBoKu] playerContent error: " + e.getMessage());
            e.printStackTrace();
        }

        // 兜底：交给嗅探
        String fullPlayUrl = id.startsWith("http") ? id : HOST + id;
        SpiderDebug.log("[DuBoKu] Fallback to sniffing: " + fullPlayUrl);
        return "{\"parse\":1,\"url\":\"" + fullPlayUrl + "\"}";
    }

    // ──────────────────────────────────────────────
    // 解析工具方法
    // ──────────────────────────────────────────────

    /**
     * 从 player_data 中提取真实播放地址
     *
     * 站点 player_data 结构：
     * {
     *   "flag": "play",
     *   "encrypt": 2,          // 0=明文, 1=Base64, 2=Base64+URLDecode
     *   "url": "aHR0cHMlM0Ev...",
     *   "vod_data": {
     *     "vod_name": "/u9ED1/u591C/u544A/u767D",   // Unicode 转义（/ 替代 \）
     *     "vod_actor": "/u738B/u9E64/u68E3%2C...",  // Unicode + URL 编码混合
     *     ...
     *   }
     * }
     */
    private String extractPlayerUrl(String html) {
        try {
            // 主正则：支持多行 JSON
            Pattern p = Pattern.compile(
                "var\\s+player_data\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*(?:</script>|;\\s*(?:var\\s|window\\.|</))",
                Pattern.MULTILINE
            );
            Matcher m = p.matcher(html);

            if (!m.find()) {
                // 备用正则：直接从 <script> 标签内抓取
                p = Pattern.compile(
                    "<script[^>]*>\\s*var\\s+player_data\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*;?\\s*</script>",
                    Pattern.MULTILINE
                );
                m = p.matcher(html);
                if (!m.find()) {
                    SpiderDebug.log("[DuBoKu] player_data not found in html");
                    return null;
                }
            }

            String jsonStr = m.group(1).trim();
            SpiderDebug.log("[DuBoKu] player_data raw: " + jsonStr.substring(0, Math.min(300, jsonStr.length())));

            JSONObject playerData = new JSONObject(jsonStr);

            int encrypt = playerData.optInt("encrypt", 0);
            String encUrl = playerData.optString("url", "");

            SpiderDebug.log("[DuBoKu] encrypt=" + encrypt + " url=" + encUrl.substring(0, Math.min(80, encUrl.length())));

            if (encUrl.isEmpty()) {
                SpiderDebug.log("[DuBoKu] player_data url field is empty");
                return null;
            }

            String decoded = decodeVideoUrl(encUrl, encrypt);
            if (decoded != null && decoded.startsWith("http")) {
                SpiderDebug.log("[DuBoKu] decoded url: " + decoded);
                return decoded;
            }

            SpiderDebug.log("[DuBoKu] decoded url is invalid: " + decoded);
            return null;

        } catch (Exception e) {
            SpiderDebug.log("[DuBoKu] extractPlayerUrl error: " + e.getMessage());
        }
        return null;
    }

    /**
     * 根据 encrypt 类型解码视频 URL
     *
     * encrypt=0 → 明文（或仅 URLDecode）
     * encrypt=1 → Base64 decode（复用 Util.base64Decode，内部使用 android.util.Base64）
     * encrypt=2 → Base64 decode → URLDecode（两步）
     *
     * @param encUrl  原始编码字符串
     * @param encrypt 加密类型
     * @return 解码后的 http(s) 地址，失败返回 null
     */
    private String decodeVideoUrl(String encUrl, int encrypt) {
        if (encUrl == null || encUrl.isEmpty()) return null;

        try {
            // encrypt=0: 明文
            if (encrypt == 0) {
                String plain = encUrl.startsWith("http") ? encUrl : URLDecoder.decode(encUrl, "UTF-8");
                return plain.startsWith("http") ? plain : null;
            }

            // encrypt=1 / encrypt=2: 第一步 Base64 decode
            // 复用 Util.base64Decode，内部使用 android.util.Base64，Android 全版本兼容
            String step1 = Util.base64Decode(encUrl.replaceAll("\\s", ""));
            SpiderDebug.log("[DuBoKu] after base64 decode: " + step1.substring(0, Math.min(120, step1.length())));

            // encrypt=2 或内容含 % 编码：第二步 URLDecode
            if (encrypt == 2 || step1.contains("%")) {
                String step2 = URLDecoder.decode(step1, "UTF-8");
                SpiderDebug.log("[DuBoKu] after url decode: " + step2.substring(0, Math.min(120, step2.length())));
                if (step2.startsWith("http")) return step2;
            }

            // encrypt=1 且无 URL 编码
            if (step1.startsWith("http")) return step1;

        } catch (Exception e) {
            SpiderDebug.log("[DuBoKu] decodeVideoUrl error (encrypt=" + encrypt + "): " + e.getMessage());
        }
        return null;
    }

    /**
     * 解码 vod_data 内的字段值
     *
     * 站点对 vod_data 字段使用 /uXXXX Unicode 转义（用 / 替代标准的 \）
     * 同时混合了 %2C 等 URL 编码
     *
     * 处理步骤：
     * 1. /uXXXX → \uXXXX（还原标准 Unicode 转义格式）
     * 2. URLDecode（还原 %2C 等）
     * 3. Util.unicodeToString（还原 \uXXXX 为实际汉字）
     *
     * 例："/u9ED1/u591C/u544A/u767D" → "黑夜告白"
     *     "/u738B/u9E64/u68E3%2C/u4EFB/u654F" → "王鹤棣,任敏"
     */
    private String decodeVodField(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        try {
            String step1 = raw.replace("/u", "\\u");
            String step2 = URLDecoder.decode(step1, "UTF-8");
            return Util.unicodeToString(step2);
        } catch (Exception e) {
            SpiderDebug.log("[DuBoKu] decodeVodField error: " + e.getMessage());
            return raw;
        }
    }

    /**
     * 从 iframe 中提取 src
     */
    private String extractIframeUrl(String html) {
        try {
            Document doc = Jsoup.parse(html);
            Element iframe = doc.selectFirst("iframe");
            if (iframe != null) {
                String src = iframe.attr("src");
                if (src != null && !src.isEmpty() && (src.startsWith("http") || src.startsWith("//"))) {
                    if (src.startsWith("//")) src = "https:" + src;
                    return src;
                }
            }
            // 正则兜底（应对 Jsoup 解析不到的情况）
            Pattern p = Pattern.compile("<iframe[^>]+src=[\"']([^\"']+)[\"']");
            Matcher m = p.matcher(html);
            if (m.find()) {
                String src = m.group(1);
                if (src.startsWith("//")) src = "https:" + src;
                if (src.startsWith("http")) return src;
            }
        } catch (Exception e) {
            SpiderDebug.log("[DuBoKu] extractIframeUrl error: " + e.getMessage());
        }
        return null;
    }

    /**
     * 从 video 标签中提取 src
     */
    private String extractVideoUrl(String html) {
        try {
            Document doc = Jsoup.parse(html);
            Element video = doc.selectFirst("video");
            if (video != null) {
                String src = video.attr("src");
                if (src != null && !src.isEmpty() && src.startsWith("http")) {
                    return src;
                }
                Element source = video.selectFirst("source");
                if (source != null) {
                    src = source.attr("src");
                    if (src != null && !src.isEmpty() && src.startsWith("http")) {
                        return src;
                    }
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("[DuBoKu] extractVideoUrl error: " + e.getMessage());
        }
        return null;
    }

    /**
     * 从页面中直接提取 m3u8 链接
     */
    private String extractM3u8Url(String html) {
        try {
            Pattern p = Pattern.compile("https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*");
            Matcher m = p.matcher(html);
            if (m.find()) {
                return m.group();
            }
        } catch (Exception e) {
            SpiderDebug.log("[DuBoKu] extractM3u8Url error: " + e.getMessage());
        }
        return null;
    }
}
