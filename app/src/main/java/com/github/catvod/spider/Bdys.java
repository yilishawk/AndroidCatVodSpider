package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class Bdys extends Spider {

    private String host = "https://v.xl.in.ua/";
    private String commonUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.7727.56 Safari/537.36";
    private String cookie = "JSESSIONID=E926A709B559AB19FDC4B3A4F5C7A1D8";

    private void logger(String msg) {
        try {
            Proxy.log("[量子资源] " + msg);
        } catch (Exception ignored) {
        }
    }

    private String fixUrl(String path) {
        if (TextUtils.isEmpty(path)) return "";
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        if (path.startsWith("//")) return "https:" + path;
        if (!path.startsWith("/")) path = "/" + path;
        return host.substring(0, host.length() - 1) + path;
    }

    private Map<String, String> getHeaders(String referer, boolean isAjax, boolean useSearchHeaders) {
        Map<String, String> headers = new HashMap<>();
        if (useSearchHeaders) {
            headers.put("User-Agent", commonUa);
            headers.put("Accept", "application/json, text/plain, */*");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9");
            headers.put("Cache-Control", "no-cache");
            headers.put("Pragma", "no-cache");
            headers.put("Connection", "keep-alive");
            return headers;
        }

        headers.put("User-Agent", commonUa);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Cache-Control", "no-cache");
        headers.put("Pragma", "no-cache");
        headers.put("sec-ch-ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"147\", \"Google Chrome\";v=\"147\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", isAjax ? "empty" : "document");
        headers.put("sec-fetch-mode", isAjax ? "cors" : "navigate");
        headers.put("sec-fetch-site", "same-origin");
        headers.put("sec-fetch-user", "?1");
        headers.put("upgrade-insecure-requests", "1");
        headers.put("Cookie", cookie);
        headers.put("Referer", TextUtils.isEmpty(referer) ? host : referer);

        if (isAjax) {
            headers.put("X-Requested-With", "XMLHttpRequest");
        }
        return headers;
    }

    private String fetchHtml(String url, String referer, boolean isAjax, boolean useSearchHeaders) {
        try {
            logger("请求URL: " + url);
            String html = OkHttp.string(url, getHeaders(referer, isAjax, useSearchHeaders));
            if (!TextUtils.isEmpty(html) && (html.toLowerCase().contains("<html") || html.toLowerCase().contains("<div"))) {
                logger("✅ 内容解析成功");
                return html;
            } else {
                logger("⚠️ 返回内容格式异常或为空");
                return "";
            }
        } catch (Exception e) {
            logger("请求异常: " + e.getMessage());
            return "";
        }
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        logger("🚀 插件初始化完成");
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        try {
            List<Class> classes = new ArrayList<>();
            classes.add(new Class("1", "电视剧"));
            classes.add(new Class("0", "电影"));

            LinkedHashMap<String, List<Filter>> filterMap = new LinkedHashMap<>();
            if (filter) {
                List<Filter> filterList = new ArrayList<>();

                // 影视类型
                List<Filter.Value> typeOptions = new ArrayList<>();
                typeOptions.add(new Filter.Value("全部", ""));
                typeOptions.add(new Filter.Value("动作", "dongzuo"));
                typeOptions.add(new Filter.Value("爱情", "aiqing"));
                typeOptions.add(new Filter.Value("喜剧", "xiju"));
                typeOptions.add(new Filter.Value("科幻", "kehuan"));
                typeOptions.add(new Filter.Value("恐怖", "kongbu"));
                typeOptions.add(new Filter.Value("剧情", "juqing"));
                typeOptions.add(new Filter.Value("动画", "donghua"));
                typeOptions.add(new Filter.Value("悬疑", "xuanyi"));
                typeOptions.add(new Filter.Value("犯罪", "fanzui"));
                typeOptions.add(new Filter.Value("古装", "guzhuang"));
                typeOptions.add(new Filter.Value("奇幻", "qihuan"));
                typeOptions.add(new Filter.Value("美剧", "meiju"));
                typeOptions.add(new Filter.Value("韩剧", "hanju"));
                typeOptions.add(new Filter.Value("国产电视剧", "guoju"));
                typeOptions.add(new Filter.Value("日剧", "riju"));
                filterList.add(new Filter("type_slug", "影视类型", typeOptions));

                // 上映时间
                List<Filter.Value> yearOptions = new ArrayList<>();
                yearOptions.add(new Filter.Value("全部", ""));
                for (int y = 2026; y >= 2002; y--) {
                    yearOptions.add(new Filter.Value(String.valueOf(y), String.valueOf(y)));
                }
                filterList.add(new Filter("year", "上映时间", yearOptions));

                filterMap.put("0", filterList);
                filterMap.put("1", filterList);
            }

            return Result.string(classes, filterMap);
        } catch (Exception e) {
            logger("homeContent 异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    /**
     * 完全还原 Python 中的 _parse_videos_from_html 逻辑
     */
    private List<Vod> parseVideosFromHtml(String html) {
        List<Vod> videos = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(html);
            Elements movieCards = doc.select(".movie-card");

            for (Element card : movieCards) {
                try {
                    Element aTag = card.selectFirst("a.card-img");
                    if (aTag == null) continue;

                    String href = aTag.attr("href");
                    String title = aTag.attr("title");

                    if (TextUtils.isEmpty(title)) {
                        Element h4 = card.selectFirst(".card-info h4");
                        if (h4 != null) {
                            title = h4.text().trim();
                        }
                    }

                    Element img = aTag.selectFirst("img.lazy");
                    String pic = "";
                    if (img != null) {
                        pic = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
                    }

                    Element badge = aTag.selectFirst(".episode-badge");
                    String remark = badge != null ? badge.text().trim() : "";

                    if (!TextUtils.isEmpty(href) && !TextUtils.isEmpty(title)) {
                        String vodId = href.startsWith("/") ? href : "/" + href;
                        
                        Vod vod = new Vod();
                        vod.setVodId(vodId);
                        vod.setVodName(title);
                        vod.setVodPic(fixUrl(pic));
                        vod.setVodRemarks(remark);
                        videos.add(vod);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            logger("解析 HTML 视频列表失败: " + e.getMessage());
        }
        return videos;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        try {
            int page = Integer.parseInt(pg);
            String typeSlug = (extend != null && extend.containsKey("type_slug")) ? extend.get("type_slug").trim() : "";
            String year = (extend != null && extend.containsKey("year")) ? extend.get("year").trim() : "";

            String slug = !TextUtils.isEmpty(typeSlug) ? typeSlug : "all";
            StringBuilder sb = new StringBuilder(host).append("s/").append(slug).append("?type=").append(tid);
            if (page > 1) {
                sb.append("&page=").append(page);
            }
            if (!TextUtils.isEmpty(year)) {
                sb.append("&year=").append(year);
            }

            String url = sb.toString();
            String html = fetchHtml(url, host, false, false);
            if (TextUtils.isEmpty(html)) {
                return Result.string(new ArrayList<>());
            }

            List<Vod> videos = parseVideosFromHtml(html);
            return Result.string(videos);
        } catch (Exception e) {
            logger("categoryContent 异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        try {
            String vodId = ids.get(0);
            String url = vodId.startsWith("http://") || vodId.startsWith("https://") ? vodId : fixUrl(vodId);

            String html;
            if (url.contains("xl02.com.de")) {
                html = fetchHtml(url, null, false, true);
            } else {
                html = fetchHtml(url, host, false, false);
            }

            if (TextUtils.isEmpty(html)) {
                return Result.string(new ArrayList<>());
            }

            Document doc = Jsoup.parse(html);

            // 提取标题
            String name = "";
            Element titleElem = doc.selectFirst("h1");
            if (titleElem != null) name = titleElem.text().trim();

            // 提取封面
            String pic = "";
            Element coverImg = doc.selectFirst(".cover-lg-max-25 img");
            if (coverImg != null) {
                pic = coverImg.hasAttr("src") ? coverImg.attr("src") : coverImg.attr("data-src");
                pic = fixUrl(pic);
            }

            // 提取简介
            String content = "暂无简介";
            Element descElem = doc.selectFirst(".desc");
            if (descElem != null) content = descElem.text().trim();

            // 提取详细信息
            String director = "", actor = "", area = "", lang = "", remarks = "";
            Elements infoItems = doc.select(".info-list .info-item");
            for (Element item : infoItems) {
                Element labelElem = item.selectFirst(".info-label");
                if (labelElem == null) continue;
                String label = labelElem.text().trim().replaceAll("：$", "");

                Elements valueElems = item.select(".info-value");
                List<String> values = new ArrayList<>();
                for (Element v : valueElems) {
                    if (!TextUtils.isEmpty(v.text().trim())) values.add(v.text().trim());
                }
                String value = TextUtils.join(", ", values);

                if (label.contains("导演")) director = value;
                else if (label.contains("主演")) actor = value;
                else if (label.contains("地区")) area = value;
                else if (label.contains("语言")) lang = value;
                else if (label.contains("状态")) remarks = value;
            }

            // 解析播放列表 (对标 Python 兜底逻辑)
            Elements playItems = doc.select(".play-item");
            if (playItems.isEmpty()) playItems = doc.select(".play-list a");
            if (playItems.isEmpty()) playItems = doc.select(".episode a");

            List<String> links = new ArrayList<>();
            for (Element a : playItems) {
                String text = a.text().trim();
                String href = a.attr("href").trim();
                if (!TextUtils.isEmpty(href)) {
                    links.add(text + "$" + fixUrl(href));
                }
            }

            Vod vod = new Vod();
            vod.setVodId(vodId);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodContent(content);
            vod.setVodDirector(director);
            vod.setVodActor(actor);
            vod.setVodArea(area);
            vod.setVodLang(lang);
            vod.setVodRemarks(remarks);

            if (!links.isEmpty()) {
                vod.setVodPlayFrom("量子资源");
                vod.setVodPlayUrl(TextUtils.join("#", links));
            }

            return Result.string(vod);
        } catch (Exception e) {
            logger("detailContent 异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        try {
            String playUrl = id.startsWith("http") ? id : fixUrl(id);

            if (playUrl.contains("v.xl.in.ua")) {
                String html = fetchHtml(playUrl, host, false, false);
                if (TextUtils.isEmpty(html)) {
                    return Result.get().parse(1).url(playUrl).string();
                }

                Matcher pidMatch = Pattern.compile("var pid\\s*=\\s*(\\d+)").matcher(html);
                if (!pidMatch.find()) {
                    return Result.get().parse(1).url(playUrl).string();
                }
                String pid = pidMatch.group(1);

                long currT = System.currentTimeMillis();
                String plainText = pid + "-" + currT;
                String md5Hash = Util.MD5(plainText);
                String sg = aesEcbEncrypt(plainText, md5Hash.substring(0, 16));

                String apiUrl = host + "lines?t=" + currT + "&sg=" + sg + "&pid=" + pid;
                
                Map<String, String> apiHeaders = new HashMap<>();
                apiHeaders.put("User-Agent", commonUa);
                apiHeaders.put("Referer", playUrl);
                apiHeaders.put("X-Requested-With", "XMLHttpRequest");
                apiHeaders.put("Accept", "application/json, text/javascript, */*; q=0.01");

                String apiRes = OkHttp.string(apiUrl, apiHeaders);
                if (!TextUtils.isEmpty(apiRes)) {
                    JSONObject json = new JSONObject(apiRes);
                    if (json.optInt("code") == 0 && json.has("data")) {
                        JSONObject data = json.getJSONObject("data");
                        String rawUrl = data.optString("url3");
                        if (TextUtils.isEmpty(rawUrl)) rawUrl = data.optString("url2");
                        if (TextUtils.isEmpty(rawUrl)) rawUrl = data.optString("url");
                        if (TextUtils.isEmpty(rawUrl)) rawUrl = data.optString("m3u8_2");
                        if (TextUtils.isEmpty(rawUrl)) rawUrl = data.optString("m3u8");

                        if (!TextUtils.isEmpty(rawUrl)) {
                            String cleanUrl = rawUrl.split(",")[0].split("#")[0].trim();
                            Map<String, String> playHeader = new HashMap<>();
                            playHeader.put("User-Agent", commonUa);
                            return Result.get().parse(0).url(cleanUrl).header(playHeader).string();
                        }
                    }
                }
            }

            return Result.get().parse(0).url(playUrl).header(getHeaders(null, false, false)).string();
        } catch (Exception e) {
            logger("playerContent 异常: " + e.getMessage());
            return Result.get().parse(1).url(id).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        if (TextUtils.isEmpty(key) || key.trim().length() == 0) return Result.string(new ArrayList<>());
        try {
            String searchUrl = "https://kwyili.dpdns.org/bdys.php?q=" + URLEncoder.encode(key, "UTF-8");
            logger("搜索关键词: " + key);

            String jsonStr = OkHttp.string(searchUrl, getHeaders(null, false, true));
            if (TextUtils.isEmpty(jsonStr)) {
                logger("⚠️ 搜索请求失败或返回为空");
                return Result.string(new ArrayList<>());
            }

            JSONArray data = new JSONArray(jsonStr);
            List<Vod> videos = new ArrayList<>();

            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i);
                String title = item.optString("title");
                String image = item.optString("image");
                String href = item.optString("href");

                if (!TextUtils.isEmpty(title) && !TextUtils.isEmpty(href)) {
                    String vodId = href;
                    try {
                        URI uri = new URI(href);
                        if (!TextUtils.isEmpty(uri.getPath())) {
                            vodId = uri.getPath();
                        }
                    } catch (Exception ignored) {
                    }

                    Vod vod = new Vod();
                    vod.setVodId(vodId);
                    vod.setVodName(title);
                    vod.setVodPic(image);
                    vod.setVodRemarks("搜索");
                    videos.add(vod);
                }
            }

            logger("搜索完成，找到 " + videos.size() + " 个结果");
            return Result.string(videos);
        } catch (Exception e) {
            logger("搜索异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    private String aesEcbEncrypt(String plainText, String key) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(encrypted).toUpperCase();
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
