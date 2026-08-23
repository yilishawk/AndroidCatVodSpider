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
    private static final String COMMON_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
    private static final String COOKIE = "JSESSIONID=E926A709B559AB19FDC4B3A4F5C7A1D8";

    // url3 最多拆分出的线路数
    private static final int MAX_URL3_LINES = 5;

    private Map<String, String> baseHeaders;

    @Override
    public void init(Context context, String extend) throws Exception {
        baseHeaders = new HashMap<>();
        baseHeaders.put("User-Agent", COMMON_UA);
        baseHeaders.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
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
        String[][] types = {
                {"动作", "dongzuo"}, {"爱情", "aiqing"}, {"喜剧", "xiju"}, {"科幻", "kehuan"},
                {"恐怖", "kongbu"}, {"战争", "zhanzheng"}, {"武侠", "wuxia"}, {"魔幻", "mohuan"},
                {"剧情", "juqing"}, {"动画", "donghua"}, {"惊悚", "jingsong"}, {"3D", "3d"},
                {"灾难", "zainan"}, {"悬疑", "xuanyi"}, {"警匪", "jingfei"}, {"文艺", "wenyi"},
                {"青春", "qingchun"}, {"冒险", "maoxian"}, {"犯罪", "fanzui"}, {"纪录", "jilu"},
                {"古装", "guzhuang"}, {"奇幻", "qihuan"}, {"国语", "guoyu"}, {"综艺", "zongyi"},
                {"历史", "lishi"}, {"运动", "yundong"}, {"原创压制", "yuanchuang"},
                {"美剧", "meiju"}, {"韩剧", "hanju"}, {"国产电视剧", "guoju"}, {"日剧", "riju"},
                {"英剧", "yingju"}, {"德剧", "deju"}, {"俄剧", "eju"}, {"巴剧", "baju"},
                {"加剧", "jiaju"}, {"西剧", "spanish"}, {"意大利剧", "yidaliju"}, {"泰剧", "taiju"},
                {"港台剧", "gangtaiju"}, {"法剧", "faju"}, {"澳剧", "aoju"}, {"短剧", "duanju"}
        };
        for (String[] t : types) typeValues.add(new Filter.Value(t[0], t[1]));

        List<Filter.Value> areaValues = new ArrayList<>();
        areaValues.add(new Filter.Value("全部", ""));
        String[] areas = {"中国大陆", "中国香港", "中国台湾", "美国", "英国", "日本", "韩国",
                "法国", "印度", "德国", "西班牙", "意大利", "澳大利亚", "加拿大", "俄罗斯"};
        for (String a : areas) areaValues.add(new Filter.Value(a, a));

        List<Filter.Value> yearValues = new ArrayList<>();
        yearValues.add(new Filter.Value("全部", ""));
        for (int y = 2026; y >= 2002; y--) yearValues.add(new Filter.Value(String.valueOf(y), String.valueOf(y)));

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

        String url = String.format("%ss/all/%d?type=%s", HOST, page, tid);
        if (!TextUtils.isEmpty(area)) url += "&area=" + URLEncoder.encode(area, "UTF-8");
        if (!TextUtils.isEmpty(year)) url += "&year=" + year;

        String html = OkHttp.string(url, baseHeaders);
        if (TextUtils.isEmpty(html)) {
            return Result.get().vod(new ArrayList<>()).page(page, page, 0, 0).string();
        }

        List<Vod> list = parseVideosFromHtml(html);
        int count = list.isEmpty() ? page : page + 1;
        return Result.get().vod(list).page(page, count, list.size(), 0).string();
    }

    // ====================== 搜索（仅这里使用 Proxy 代理图片 + 域名替换） ======================
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        if (TextUtils.isEmpty(key)) return Result.get().vod(new ArrayList<>()).string();

        String searchUrl = "https://kwyili.dpdns.org/bdys.php?q=" + URLEncoder.encode(key, "UTF-8");
        String json = OkHttp.string(searchUrl, new HashMap<>());
        if (TextUtils.isEmpty(json)) return Result.get().vod(new ArrayList<>()).string();

        List<Vod> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                String title = item.optString("title");
                String href = item.optString("href");
                if (TextUtils.isEmpty(title) || TextUtils.isEmpty(href)) continue;

                // 把 xl02.com.de 域名替换成爬虫域名
                href = href.replace("https://xl02.com.de", "https://v.xl.in.ua")
                           .replace("http://xl02.com.de", "https://v.xl.in.ua");

                // 只在搜索时使用 Proxy 代理图片
                String proxyPic = Proxy.getUrl() + "?do=getPoster&title=" + URLEncoder.encode(title, "UTF-8");

                Vod vod = new Vod();
                vod.setVodId(href);
                vod.setVodName(title);
                vod.setVodPic(proxyPic);
                vod.setVodRemarks("搜索");
                list.add(vod);
            }
        } catch (Exception ignored) {}
        return Result.get().vod(list).string();
    }

    // ====================== 详情（方案B：url3-1 ~ url3-5） ======================
    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.error("id 为空");
        String vodId = ids.get(0);

        String url = vodId.startsWith("http") ? vodId : HOST + vodId.replaceFirst("^/", "");

        String html = OkHttp.string(url, baseHeaders);
        if (TextUtils.isEmpty(html)) return Result.error("详情请求失败");

        Document doc = Jsoup.parse(html);

        String name = "";
        Element titleEl = doc.selectFirst("h1");
        if (titleEl != null) name = titleEl.text().trim();

        String pic = "";
        Element cover = doc.selectFirst(".cover-lg-max-25 img, .cover img, img[data-src]");
        if (cover != null) {
            pic = cover.attr("data-src");
            if (TextUtils.isEmpty(pic)) pic = cover.attr("src");
        }

        String content = "暂无简介";
        Element desc = doc.selectFirst(".desc, .plot, .summary");
        if (desc != null) content = desc.text().trim();

        String director = "", actor = "", area = "", remarks = "";
        Elements infoItems = doc.select(".info-list .info-item, .info span, .meta li");
        for (Element item : infoItems) {
            String text = item.text().trim();
            if (text.contains("导演")) director = text.replaceAll("导演[：:]*", "").trim();
            else if (text.contains("主演") || text.contains("演员")) actor = text.replaceAll("(主演|演员)[：:]*", "").trim();
            else if (text.contains("地区")) area = text.replaceAll("地区[：:]*", "").trim();
            else if (text.contains("状态") || text.contains("更新")) remarks = text.replaceAll("(状态|更新)[：:]*", "").trim();
        }

        // 解析真实分集
        List<String> episodes = new ArrayList<>();
        Elements playItems = doc.select("a[href*=/play/]");
        for (Element a : playItems) {
            String href = a.attr("href").trim();
            if (TextUtils.isEmpty(href)) continue;
            if (!href.startsWith("http")) href = HOST + href.replaceFirst("^/", "");

            String text = a.text().trim();
            if (TextUtils.isEmpty(text)) {
                Matcher m = Pattern.compile("/play/\\d+-(\\d+)\\.htm").matcher(href);
                if (m.find()) text = "第" + (Integer.parseInt(m.group(1)) + 1) + "集";
                else text = "播放";
            }
            episodes.add(text + "$" + href);
        }

        // 兜底
        if (episodes.isEmpty()) {
            String numId = vodId.replaceAll("[^0-9]", "");
            for (int i = 0; i < 12; i++) {
                episodes.add("第" + (i + 1) + "集$" + HOST + "play/" + numId + "-" + i + ".htm");
            }
        }

        String episodeStr = TextUtils.join("#", episodes);

        // 方案B：预生成 url3-1 ~ url3-5
        StringBuilder playFrom = new StringBuilder();
        StringBuilder playUrl = new StringBuilder();
        for (int i = 1; i <= MAX_URL3_LINES; i++) {
            if (i > 1) {
                playFrom.append("$$$");
                playUrl.append("$$$");
            }
            playFrom.append("url3-").append(i);
            playUrl.append(episodeStr);
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
        vod.setVodPlayFrom(playFrom.toString());
        vod.setVodPlayUrl(playUrl.toString());

        return Result.get().vod(vod).string();
    }

    // ====================== 播放（url3 拆分 + 字节跳动 CDN 请求头） ======================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        try {
            String playPage = id.startsWith("http") ? id : HOST + id.replaceFirst("^/", "");

            String html = OkHttp.string(playPage, baseHeaders);
            if (TextUtils.isEmpty(html)) return Result.get().url(playPage).parse().string();

            Matcher pidMatcher = Pattern.compile("var\\s+pid\\s*=\\s*(\\d+)").matcher(html);
            if (!pidMatcher.find()) return Result.get().url(playPage).parse().string();
            String pid = pidMatcher.group(1);

            String[] sgAndT = getSgAndT(pid);
            String apiUrl = HOST + "lines?t=" + sgAndT[1] + "&sg=" + sgAndT[0] + "&pid=" + pid;

            Map<String, String> apiHeaders = new HashMap<>();
            apiHeaders.put("User-Agent", COMMON_UA);
            apiHeaders.put("Referer", playPage);
            apiHeaders.put("X-Requested-With", "XMLHttpRequest");
            apiHeaders.put("Accept", "application/json, text/javascript, */*; q=0.01");

            String resp = OkHttp.string(apiUrl, apiHeaders);
            if (TextUtils.isEmpty(resp)) return Result.get().url(playPage).parse().string();

            JSONObject data = new JSONObject(resp);
            if (data.optInt("code") == 0 && data.has("data")) {
                JSONObject res = data.getJSONObject("data");
                String url3 = res.optString("url3");

                if (!TextUtils.isEmpty(url3)) {
                    String[] urls = url3.split(",");
                    List<String> validUrls = new ArrayList<>();
                    for (String u : urls) {
                        u = u.trim();
                        if (!TextUtils.isEmpty(u) && u.startsWith("http")) {
                            validUrls.add(u);
                        }
                    }

                    if (!validUrls.isEmpty()) {
                        int index = 0;
                        if (flag != null && flag.startsWith("url3-")) {
                            try {
                                int num = Integer.parseInt(flag.replace("url3-", "").trim());
                                index = Math.max(0, num - 1);
                            } catch (Exception ignored) {}
                        }
                        if (index >= validUrls.size()) index = validUrls.size() - 1;

                        String finalUrl = validUrls.get(index);

                        // 字节跳动 CDN 必要请求头
                        Map<String, String> header = new HashMap<>();
                        header.put("User-Agent", COMMON_UA);
                        header.put("Accept", "*/*");
                        header.put("Accept-Encoding", "identity;q=1, *;q=0");
                        header.put("Accept-Language", "zh-CN,zh;q=0.9");
                        header.put("Referer", "https://v.xl.in.ua/");
                        header.put("Origin", "https://v.xl.in.ua");
                        header.put("sec-fetch-dest", "video");
                        header.put("sec-fetch-mode", "no-cors");
                        header.put("sec-fetch-site", "cross-site");

                        return Result.get()
                                .url(finalUrl)
                                .header(header)
                                .string();
                    }
                }
            }
        } catch (Exception ignored) {}
        return Result.get().url(id).parse().string();
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
                String pic = img != null ? (TextUtils.isEmpty(img.attr("data-src")) ? img.attr("src") : img.attr("data-src")) : "";
                Element badge = a.selectFirst(".episode-badge");
                String remark = badge != null ? badge.text().trim() : "";

                Vod vod = new Vod();
                vod.setVodId(href.startsWith("/") ? href : "/" + href);
                vod.setVodName(title);
                vod.setVodPic(pic);
                vod.setVodRemarks(remark);
                list.add(vod);
            } catch (Exception ignored) {}
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
        return new String[]{bytesToHex(encrypted).toUpperCase(), currT};
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
