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

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LiangZi extends Spider {

    private static final String HOST = "https://v.xl.in.ua/";
    private static final String COMMON_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.7727.56 Safari/537.36";
    private static final String COOKIE = "JSESSIONID=E926A709B559AB19FDC4B3A4F5C7A1D8";

    private Map<String, String> baseHeaders;

    @Override
    public void init(Context context, String extend) throws Exception {
        baseHeaders = new HashMap<>();
        baseHeaders.put("User-Agent", COMMON_UA);
        baseHeaders.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        baseHeaders.put("Accept-Language", "zh-CN,zh;q=0.9");
        baseHeaders.put("Referer", HOST);
        baseHeaders.put("Cookie", COOKIE);
    }

    // ====================== 首页分类 ======================
    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("1", "电视剧"));
        classes.add(new Class("0", "电影"));

        if (!filter) {
            return Result.get().classes(classes).string();
        }

        List<Filter.Value> typeValues = new ArrayList<>();
        typeValues.add(new Filter.Value("全部", ""));
        String[][] types = {{"动作", "dongzuo"}, {"爱情", "aiqing"}, {"喜剧", "xiju"}, {"科幻", "kehuan"},
                {"恐怖", "kongbu"}, {"战争", "zhanzheng"}, {"武侠", "wuxia"}, {"魔幻", "mohuan"},
                {"剧情", "juqing"}, {"动画", "donghua"}, {"惊悚", "jingsong"}, {"3D", "3d"},
                {"灾难", "zainan"}, {"悬疑", "xuanyi"}, {"警匪", "jingfei"}, {"文艺", "wenyi"},
                {"青春", "qingchun"}, {"冒险", "maoxian"}, {"犯罪", "fanzui"}, {"纪录", "jilu"},
                {"古装", "guzhuang"}, {"奇幻", "qihuan"}, {"国语", "guoyu"}, {"综艺", "zongyi"},
                {"历史", "lishi"}, {"运动", "yundong"}, {"原创压制", "yuanchuang"},
                {"美剧", "meiju"}, {"韩剧", "hanju"}, {"国产电视剧", "guoju"}, {"日剧", "riju"},
                {"英剧", "yingju"}, {"德剧", "deju"}, {"俄剧", "eju"}, {"巴剧", "baju"},
                {"加剧", "jiaju"}, {"西剧", "spanish"}, {"意大利剧", "yidaliju"}, {"泰剧", "taiju"},
                {"港台剧", "gangtaiju"}, {"法剧", "faju"}, {"澳剧", "aoju"}, {"短剧", "duanju"}};
        for (String[] t : types) {
            typeValues.add(new Filter.Value(t[0], t[1]));
        }

        List<Filter.Value> areaValues = new ArrayList<>();
        areaValues.add(new Filter.Value("全部", ""));
        String[] areas = {"中国大陆", "中国香港", "中国台湾", "美国", "英国", "日本", "韩国",
                "法国", "印度", "德国", "西班牙", "意大利", "澳大利亚", "加拿大", "俄罗斯"};
        for (String a : areas) {
            areaValues.add(new Filter.Value(a, a));
        }

        List<Filter.Value> yearValues = new ArrayList<>();
        yearValues.add(new Filter.Value("全部", ""));
        for (int y = 2026; y >= 2002; y--) {
            yearValues.add(new Filter.Value(String.valueOf(y), String.valueOf(y)));
        }

        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        List<Filter> filterList = new ArrayList<>();
        filterList.add(new Filter("type_slug", "影视类型", typeValues));
        filterList.add(new Filter("area", "制片地区", areaValues));
        filterList.add(new Filter("year", "上映时间", yearValues));

        filters.put("0", filterList);
        filters.put("1", filterList);

        return Result.get().classes(classes).filters(filters).string();
    }

    // ====================== 分类列表 ======================
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = TextUtils.isEmpty(pg) ? 1 : Integer.parseInt(pg);

        String typeSlug = extend != null ? extend.getOrDefault("type_slug", "").trim() : "";
        String area = extend != null ? extend.getOrDefault("area", "").trim() : "";
        String year = extend != null ? extend.getOrDefault("year", "").trim() : "";

        String slug = TextUtils.isEmpty(typeSlug) ? "all" : typeSlug;
        String url = String.format("%ss/all/%d?type=%s", HOST, page, tid);

        if (!TextUtils.isEmpty(area)) {
            url += "&area=" + URLEncoder.encode(area, "UTF-8");
        }
        if (!TextUtils.isEmpty(year)) {
            url += "&year=" + year;
        }

        String html = OkHttp.string(url, baseHeaders);
        if (TextUtils.isEmpty(html)) {
            return Result.get().vod(new ArrayList<>()).page(page, page, 0, 0).string();
        }

        List<Vod> list = parseVideosFromHtml(html);
        int count = list.isEmpty() ? page : page + 1;
        return Result.get().vod(list).page(page, count, list.size(), 0).string();
    }

    // ====================== 搜索（官方双方法支持） ======================
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        if (TextUtils.isEmpty(key)) {
            return Result.get().vod(new ArrayList<>()).string();
        }

        // 使用你指定的 kwyili 接口
        String searchUrl = "https://kwyili.dpdns.org/bdys.php?q=" + URLEncoder.encode(key, "UTF-8");

        String json = OkHttp.string(searchUrl, new HashMap<>()); // 无需额外请求头
        if (TextUtils.isEmpty(json)) {
            return Result.get().vod(new ArrayList<>()).string();
        }

        List<Vod> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                String title = item.optString("title");
                String href = item.optString("href");

                if (TextUtils.isEmpty(title) || TextUtils.isEmpty(href)) continue;

                // 提取数字 id
                String id = href.replaceAll("[^0-9]", "");

                // 图片用红牛接口 + 本地代理
                String proxyPic = Proxy.getPoster(id);

                Vod vod = new Vod();
                vod.setVodId(id);
                vod.setVodName(title);
                vod.setVodPic(proxyPic);
                vod.setVodRemarks("搜索");
                list.add(vod);
            }
        } catch (Exception ignored) {
        }

        return Result.get().vod(list).string();
    }

    // ====================== 详情 ======================
    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("id 为空");
        String vodId = ids.get(0);

        String url = vodId.startsWith("http") ? vodId : HOST + vodId.replaceFirst("^/", "");

        Map<String, String> headers = new HashMap<>(baseHeaders);
        if (url.contains("xl02.com.de")) {
            headers.remove("Cookie");
            headers.put("Referer", url);
        }

        String html = OkHttp.string(url, headers);
        if (TextUtils.isEmpty(html)) return Result.error("详情请求失败");

        Document doc = Jsoup.parse(html);

        String name = "";
        Element titleEl = doc.selectFirst("h1");
        if (titleEl != null) name = titleEl.text().trim();

        String pic = "";
        Element cover = doc.selectFirst(".cover-lg-max-25 img");
        if (cover != null) {
            pic = cover.attr("src");
            if (TextUtils.isEmpty(pic)) pic = cover.attr("data-src");
        }

        String content = "暂无简介";
        Element desc = doc.selectFirst(".desc");
        if (desc != null) content = desc.text().trim();

        String director = "", actor = "", area = "", remarks = "";
        Elements infoItems = doc.select(".info-list .info-item");
        for (Element item : infoItems) {
            Element labelEl = item.selectFirst(".info-label");
            if (labelEl == null) continue;
            String label = labelEl.text().trim().replace("：", "");
            Elements valueEls = item.select(".info-value");
            List<String> values = new ArrayList<>();
            for (Element v : valueEls) {
                String t = v.text().trim();
                if (!TextUtils.isEmpty(t)) values.add(t);
            }
            String value = TextUtils.join(", ", values);

            if (label.contains("导演")) director = value;
            else if (label.contains("主演")) actor = value;
            else if (label.contains("地区")) area = value;
            else if (label.contains("状态")) remarks = value;
        }

        List<String> playLinks = new ArrayList<>();
        Elements playItems = doc.select(".play-item");
        if (playItems.isEmpty()) playItems = doc.select(".play-list a");

        for (Element a : playItems) {
            String text = a.text().trim();
            String href = a.attr("href").trim();
            if (TextUtils.isEmpty(href)) continue;

            if (!href.startsWith("http") && !href.startsWith("//")) {
                if (href.startsWith("/")) {
                    href = HOST + href.replaceFirst("^/", "");
                } else {
                    href = url.replaceAll("/$", "") + "/" + href.replaceFirst("^/", "");
                }
            }
            playLinks.add(text + "$" + href);
        }

        Vod vod = new Vod();
        vod.setVodId(vodId);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setVodContent(content);
        vod.setVodDirector(director);
        vod.setVodActor(actor);
        vod.setVodArea(area);
        vod.setVodRemarks(remarks);
        vod.setVodPlayFrom("量子资源");
        vod.setVodPlayUrl(TextUtils.join("#", playLinks));

        return Result.get().vod(vod).string();
    }

    // ====================== 播放 ======================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        try {
            String playUrl = id.startsWith("http") ? id : HOST + id.replaceFirst("^/", "");

            String html = OkHttp.string(playUrl, baseHeaders);
            if (TextUtils.isEmpty(html)) {
                return Result.get().url(playUrl).parse().string();
            }

            Matcher pidMatcher = Pattern.compile("var\\s+pid\\s*=\\s*(\\d+)").matcher(html);
            if (!pidMatcher.find()) {
                return Result.get().url(playUrl).parse().string();
            }
            String pid = pidMatcher.group(1);

            String[] sgAndT = getSgAndT(pid);
            String sg = sgAndT[0];
            String tVal = sgAndT[1];

            String apiUrl = HOST + "lines?t=" + tVal + "&sg=" + sg + "&pid=" + pid;

            Map<String, String> apiHeaders = new HashMap<>();
            apiHeaders.put("User-Agent", COMMON_UA);
            apiHeaders.put("Referer", playUrl);
            apiHeaders.put("X-Requested-With", "XMLHttpRequest");
            apiHeaders.put("Accept", "application/json, text/javascript, */*; q=0.01");

            String resp = OkHttp.string(apiUrl, apiHeaders);
            if (!TextUtils.isEmpty(resp)) {
                Matcher jsonMatcher = Pattern.compile("(\\{.*\\})").matcher(resp);
                if (jsonMatcher.find()) {
                    JSONObject data = new JSONObject(jsonMatcher.group(1));
                    if (data.optInt("code") == 0 && data.has("data")) {
                        JSONObject resInfo = data.getJSONObject("data");
                        String rawUrl = resInfo.optString("url3");
                        if (TextUtils.isEmpty(rawUrl)) rawUrl = resInfo.optString("m3u8_2");
                        if (TextUtils.isEmpty(rawUrl)) rawUrl = resInfo.optString("m3u8");

                        if (!TextUtils.isEmpty(rawUrl)) {
                            String finalUrl = rawUrl.split(",")[0].split("#")[0].trim();
                            Map<String, String> header = new HashMap<>();
                            header.put("User-Agent", COMMON_UA);
                            return Result.get().url(finalUrl).header(header).string();
                        }
                    }
                }
            }
            return Result.get().url(playUrl).parse().string();
        } catch (Exception e) {
            return Result.get().url(id).parse().string();
        }
    }

    // ====================== 分类列表解析 ======================
    private List<Vod> parseVideosFromHtml(String html) {
        List<Vod> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Elements cards = doc.select(".movie-card");

        for (Element card : cards) {
            try {
                Element a = card.selectFirst("a.card-img");
                if (a == null) continue;

                String href = a.attr("href");
                String title = a.attr("title");
                if (TextUtils.isEmpty(title)) {
                    Element h4 = card.selectFirst(".card-info h4");
                    if (h4 != null) title = h4.text().trim();
                }
                if (TextUtils.isEmpty(title) || TextUtils.isEmpty(href)) continue;

                Element img = a.selectFirst("img.lazy");
                String pic = "";
                if (img != null) {
                    pic = img.attr("data-src");
                    if (TextUtils.isEmpty(pic)) pic = img.attr("src");
                }

                Element badge = a.selectFirst(".episode-badge");
                String remark = badge != null ? badge.text().trim() : "";

                String vodId = href.startsWith("/") ? href : "/" + href;

                Vod vod = new Vod();
                vod.setVodId(vodId);
                vod.setVodName(title);
                vod.setVodPic(pic);
                vod.setVodRemarks(remark);
                list.add(vod);
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    private String[] getSgAndT(String pid) throws Exception {
        String currT = String.valueOf(System.currentTimeMillis());
        String plainText = pid + "-" + currT;

        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] md5Bytes = md.digest(plainText.getBytes(StandardCharsets.UTF_8));
        String md5Hex = bytesToHex(md5Bytes).toLowerCase();
        String aesKey = md5Hex.substring(0, 16);

        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        SecretKeySpec keySpec = new SecretKeySpec(aesKey.getBytes(StandardCharsets.UTF_8), "AES");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        String sg = bytesToHex(encrypted).toUpperCase();
        return new String[]{sg, currT};
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
