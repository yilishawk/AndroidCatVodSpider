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
        String p = path.startsWith("/") ? path.substring(1) : path;
        return host + p;
    }

    private Map<String, String> getHeaders(String referer, boolean isAjax, boolean useSearchHeaders) {
        Map<String, String> headers = new HashMap<>();
        if (useSearchHeaders) {
            headers.put("User-Agent", commonUa);
            headers.put("Accept", "application/json, text/plain, */*");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9");
            return headers;
        }
        headers.put("User-Agent", commonUa);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("sec-ch-ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"147\", \"Google Chrome\";v=\"147\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", isAjax ? "empty" : "document");
        headers.put("sec-fetch-mode", isAjax ? "cors" : "navigate");
        headers.put("sec-fetch-site", "same-origin");
        headers.put("sec-fetch-user", "?1");
        headers.put("upgrade-insecure-requests", "1");
        headers.put("Referer", TextUtils.isEmpty(referer) ? host : referer);
        if (isAjax) headers.put("X-Requested-With", "XMLHttpRequest");
        return headers;
    }

    private String fetchHtml(String url, String referer, boolean isAjax, boolean useSearchHeaders) {
        try {
            logger("请求URL: " + url);
            String html = OkHttp.string(url, getHeaders(referer, isAjax, useSearchHeaders));
            if (TextUtils.isEmpty(html)) {
                logger("⚠️ 返回内容为空");
                return null;
            }
            String lower = html.toLowerCase();
            if (lower.contains("<html") || lower.contains("<div")) {
                logger("✅ 内容解析成功");
                return html;
            }
            logger("⚠️ 返回内容格式异常");
            return null;
        } catch (Exception e) {
            logger("请求异常: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        logger("🚀 Bdys 插件初始化完成");
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        try {
            List<Class> classes = new ArrayList<>();
            classes.add(new Class("1", "电视剧"));
            classes.add(new Class("0", "电影"));

            String[][] typeOpts = {
                    {"全部", ""}, {"动作", "dongzuo"}, {"爱情", "aiqing"}, {"喜剧", "xiju"}, {"科幻", "kehuan"},
                    {"恐怖", "kongbu"}, {"战争", "zhanzheng"}, {"武侠", "wuxia"}, {"魔幻", "mohuan"}, {"剧情", "juqing"},
                    {"动画", "donghua"}, {"惊悚", "jingsong"}, {"3D", "3d"}, {"灾难", "zainan"}, {"悬疑", "xuanyi"},
                    {"警匪", "jingfei"}, {"文艺", "wenyi"}, {"青春", "qingchun"}, {"冒险", "maoxian"}, {"犯罪", "fanzui"},
                    {"纪录", "jilu"}, {"古装", "guzhuang"}, {"奇幻", "qihuan"}, {"国语", "guoyu"}, {"综艺", "zongyi"},
                    {"历史", "lishi"}, {"运动", "yundong"}, {"原创压制", "yuanchuang"}, {"美剧", "meiju"}, {"韩剧", "hanju"},
                    {"国产电视剧", "guoju"}, {"日剧", "riju"}, {"英剧", "yingju"}, {"德剧", "deju"}, {"俄剧", "eju"},
                    {"巴剧", "baju"}, {"加剧", "jiaju"}, {"西剧", "spanish"}, {"意大利剧", "yidaliju"}, {"泰剧", "taiju"},
                    {"港台剧", "gangtaiju"}, {"法剧", "faju"}, {"澳剧", "aoju"}, {"短剧", "duanju"}
            };

            String[][] areaOpts = {
                    {"全部", ""}, {"中国大陆", "中国大陆"}, {"中国香港", "中国香港"}, {"中国台湾", "中国台湾"}, {"美国", "美国"},
                    {"英国", "英国"}, {"日本", "日本"}, {"韩国", "韩国"}, {"法国", "法国"}, {"印度", "印度"},
                    {"德国", "德国"}, {"西班牙", "西班牙"}, {"意大利", "意大利"}, {"澳大利亚", "澳大利亚"}, {"加拿大", "加拿大"},
                    {"俄罗斯", "俄罗斯"}
            };

            List<Filter.Value> typeOptions = new ArrayList<>();
            for (String[] opt : typeOpts) typeOptions.add(new Filter.Value(opt[0], opt[1]));

            List<Filter.Value> areaOptions = new ArrayList<>();
            for (String[] opt : areaOpts) areaOptions.add(new Filter.Value(opt[0], opt[1]));

            List<Filter.Value> yearOptions = new ArrayList<>();
            yearOptions.add(new Filter.Value("全部", ""));
            for (int y = 2026; y >= 2002; y--) yearOptions.add(new Filter.Value(String.valueOf(y), String.valueOf(y)));

            List<Filter> filterList = new ArrayList<>();
            filterList.add(new Filter("type_slug", "影视类型", typeOptions));
            filterList.add(new Filter("area", "制片地区", areaOptions));
            filterList.add(new Filter("year", "上映时间", yearOptions));

            LinkedHashMap<String, List<Filter>> filterMap = new LinkedHashMap<>();
            filterMap.put("0", filterList);
            filterMap.put("1", filterList);

            return Result.get().classes(classes).filter(filterMap).string();
        } catch (Exception e) {
            logger("homeContent 异常: " + e.getMessage());
            return Result.get().classes(new ArrayList<>()).string();
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        try {
            int page = Integer.parseInt(pg);
            String typeSlug = extend != null ? extend.get("type_slug") : null;
            String area = extend != null ? extend.get("area") : null;
            String year = extend != null ? extend.get("year") : null;

            String slug = TextUtils.isEmpty(typeSlug) ? "all" : typeSlug;
            StringBuilder url = new StringBuilder(host).append("s/").append(slug).append("?type=").append(tid);
            if (page > 1) url.append("&page=").append(page);
            if (!TextUtils.isEmpty(area)) url.append("&area=").append(URLEncoder.encode(area, "UTF-8"));
            if (!TextUtils.isEmpty(year)) url.append("&year=").append(year);

            String html = fetchHtml(url.toString(), host, false, false);
            if (TextUtils.isEmpty(html)) {
                return Result.get().vod(new ArrayList<>()).string();
            }

            Document doc = Jsoup.parse(html);
            List<Vod> list = new ArrayList<>();
            Elements cards = doc.select(".movie-card");

            for (Element card : cards) {
                Element a = card.selectFirst("a.card-img");
                if (a == null) continue;

                String href = a.attr("href").trim();
                if (TextUtils.isEmpty(href)) continue;
                String vodId = href.startsWith("/") ? href : "/" + href;

                String name = a.attr("title").trim();
                if (TextUtils.isEmpty(name)) {
                    Element h4 = card.selectFirst(".card-info h4");
                    if (h4 != null) name = h4.text().trim();
                }
                if (TextUtils.isEmpty(name)) continue;

                Element img = a.selectFirst("img.lazy");
                String pic = "";
                if (img != null) {
                    pic = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
                }

                Element badge = a.selectFirst(".episode-badge");
                String remark = badge != null ? badge.text().trim() : "";

                Vod vod = new Vod();
                vod.setVodId(vodId);
                vod.setVodName(name);
                vod.setVodPic(pic);
                vod.setVodRemarks(remark);
                list.add(vod);
            }

            logger("成功打包给 TVBox 的有效影视条数: " + list.size());
            return Result.get().vod(list).string();
        } catch (Exception e) {
            logger("categoryContent 捕获异常: " + e.getMessage());
            return Result.get().vod(new ArrayList<>()).string();
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        try {
            String id = ids.get(0);
            String url = fixUrl(id);

            boolean useSearchHeaders = url.contains("xl02.com.de");
            String html = useSearchHeaders
                    ? fetchHtml(url, null, false, true)
                    : fetchHtml(url, host, false, false);

            if (TextUtils.isEmpty(html)) {
                return Result.get().vod(new ArrayList<>()).string();
            }

            Document doc = Jsoup.parse(html);

            String name = "";
            Element h1 = doc.selectFirst("h1");
            if (h1 != null) name = h1.text().trim();

            String pic = "";
            Element coverImg = doc.selectFirst(".cover-lg-max-25 img");
            if (coverImg != null) {
                pic = coverImg.hasAttr("src") && !coverImg.attr("src").isEmpty()
                        ? coverImg.attr("src") : coverImg.attr("data-src");
            }

            String content = "暂无简介";
            Element desc = doc.selectFirst(".desc");
            if (desc != null) content = desc.text().trim();

            String director = "", actor = "", area = "", remarks = "";
            Elements infoItems = doc.select(".info-list .info-item");
            for (Element item : infoItems) {
                Element labelElem = item.selectFirst(".info-label");
                if (labelElem == null) continue;
                String label = labelElem.text().trim().replace("：", "");

                Elements valueElems = item.select(".info-value");
                List<String> values = new ArrayList<>();
                for (Element v : valueElems) {
                    String t = v.text().trim();
                    if (!t.isEmpty()) values.add(t);
                }
                String value = TextUtils.join(", ", values);

                if (label.contains("导演")) director = value;
                else if (label.contains("主演")) actor = value;
                else if (label.contains("地区")) area = value;
                else if (label.contains("状态")) remarks = value;
            }

            List<String> playPairs = new ArrayList<>();
            Elements playItems = doc.select(".play-item");
            if (playItems.isEmpty()) playItems = doc.select(".play-list a");

            for (Element a : playItems) {
                String text = a.text().trim();
                String href = a.attr("href").trim();
                if (TextUtils.isEmpty(href)) continue;

                if (!href.startsWith("http") && !href.startsWith("//")) {
                    if (href.startsWith("/")) {
                        href = host + href.substring(1);
                    } else {
                        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
                        href = base + "/" + href;
                    }
                }
                playPairs.add(text + "$" + href);
            }

            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodContent(content);
            vod.setVodDirector(director);
            vod.setVodActor(actor);
            vod.setVodArea(area);
            vod.setVodRemarks(remarks);

            if (!playPairs.isEmpty()) {
                vod.setVodPlayFrom("量子资源");
                vod.setVodPlayUrl(TextUtils.join("#", playPairs));
            }

            return Result.get().vod(vod).string();
        } catch (Exception e) {
            logger("detailContent 异常: " + e.getMessage());
            return Result.get().vod(new ArrayList<>()).string();
        }
    }

    /** 返回 [sg, t]，对应 Python 版 get_sg_and_t */
    private String[] getSgAndT(String pid) throws Exception {
        String t = String.valueOf(System.currentTimeMillis());
        String plain = pid + "-" + t;
        String md5 = Util.MD5(plain);
        String sg = aesEcbEncrypt(plain, md5.substring(0, 16));
        return new String[]{sg, t};
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        try {
            String playUrl = id.startsWith("http") ? id : fixUrl(id);
            String html = fetchHtml(playUrl, host, false, false);
            if (TextUtils.isEmpty(html)) {
                return Result.get().parse(1).url(playUrl).string();
            }

            Matcher m = Pattern.compile("var pid\\s*=\\s*(\\d+)").matcher(html);
            if (!m.find()) {
                return Result.get().parse(1).url(playUrl).string();
            }
            String pid = m.group(1);

            String[] sgAndT = getSgAndT(pid);
            String sg = sgAndT[0];
            String t = sgAndT[1];

            String apiUrl = host + "lines?t=" + t + "&sg=" + sg + "&pid=" + pid;
            Map<String, String> apiHeaders = new HashMap<>();
            apiHeaders.put("User-Agent", commonUa);
            apiHeaders.put("Referer", playUrl);
            apiHeaders.put("X-Requested-With", "XMLHttpRequest");
            apiHeaders.put("Accept", "application/json, text/javascript, */*; q=0.01");

            String resp = OkHttp.string(apiUrl, apiHeaders);
            if (!TextUtils.isEmpty(resp)) {
                Matcher jsonMatcher = Pattern.compile("(\\{.*\\})", Pattern.DOTALL).matcher(resp);
                if (jsonMatcher.find()) {
                    JSONObject data = new JSONObject(jsonMatcher.group(1));
                    if (data.optInt("code", -1) == 0 && data.has("data")) {
                        JSONObject info = data.getJSONObject("data");
                        String rawUrl = info.optString("url3", "");
                        if (TextUtils.isEmpty(rawUrl)) rawUrl = info.optString("m3u8_2", "");
                        if (TextUtils.isEmpty(rawUrl)) rawUrl = info.optString("m3u8", "");

                        if (!TextUtils.isEmpty(rawUrl)) {
                            String finalUrl = rawUrl.split(",")[0].split("#")[0].trim();
                            Map<String, String> playHeaders = new HashMap<>();
                            playHeaders.put("User-Agent", commonUa);
                            return Result.get().url(finalUrl).header(playHeaders).string();
                        }
                    }
                }
            }

            return Result.get().parse(1).url(playUrl).string();
        } catch (Exception e) {
            logger("播放解析异常: " + e.getMessage());
            return Result.get().parse(1).url(id).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        if (TextUtils.isEmpty(key)) return Result.get().vod(new ArrayList<>()).string();
        try {
            String searchUrl = "https://kwyili.dpdns.org/bdys.php?q=" + URLEncoder.encode(key, "UTF-8");
            logger("搜索请求 URL: " + searchUrl);

            String jsonStr = OkHttp.string(searchUrl, getHeaders(null, false, true));
            if (TextUtils.isEmpty(jsonStr)) {
                logger("⚠️ 搜索接口返回数据为空");
                return Result.get().vod(new ArrayList<>()).string();
            }

            org.json.JSONArray jsonArray = new org.json.JSONArray(jsonStr);
            List<Vod> resultList = new ArrayList<>();

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject item = jsonArray.getJSONObject(i);
                String title = item.optString("title");
                String image = item.optString("image");
                String href = item.optString("href");

                if (TextUtils.isEmpty(title) || TextUtils.isEmpty(href)) continue;

                String vodId;
                try {
                    String path = new URI(href).getPath();
                    vodId = TextUtils.isEmpty(path) ? href : path;
                } catch (Exception ex) {
                    vodId = href;
                }

                Vod vod = new Vod();
                vod.setVodId(vodId);
                vod.setVodName(title);
                vod.setVodPic(image);
                vod.setVodRemarks("搜索");
                resultList.add(vod);
            }

            logger("搜索成功解析出 " + resultList.size() + " 条记录");
            return Result.get().vod(resultList).string();
        } catch (Exception e) {
            logger("搜索异常: " + e.getMessage());
            return Result.get().vod(new ArrayList<>()).string();
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
