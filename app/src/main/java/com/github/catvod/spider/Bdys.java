package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;
import com.github.catvod.utils.Json;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.OkHttpClient;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

public class Bdys extends Spider {

    private String host = "https://v.xlys.ltd.ua/";
    private String commonUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private OkHttpClient client;

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        // 使用带 Cookie 管理的 OkHttpClient
        client = new OkHttpClient.Builder()
                .cookieJar(CookieJar.NO_COOKIES) // 如需保存 Cookie 可自定义，这里简单处理
                .build();
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        JsonObject result = new JsonObject();
        JsonArray classes = new JsonArray();
        JsonObject tv = new JsonObject();
        tv.addProperty("type_name", "电视剧");
        tv.addProperty("type_id", "1");
        JsonObject movie = new JsonObject();
        movie.addProperty("type_name", "电影");
        movie.addProperty("type_id", "0");
        classes.add(tv);
        classes.add(movie);
        result.add("class", classes);

        if (filter) {
            // 构建筛选器
            JsonObject filters = new JsonObject();
            // 类型选项
            JsonArray typeOptions = new JsonArray();
            String[] typeNames = {"全部", "动作", "爱情", "喜剧", "科幻", "恐怖", "战争", "武侠", "魔幻", "剧情", "动画", "惊悚", "3D", "灾难", "悬疑", "警匪", "文艺", "青春", "冒险", "犯罪", "纪录", "古装", "奇幻", "国语", "综艺", "历史", "运动", "原创", "压制", "美剧", "韩剧", "国产电视剧", "日剧", "英剧", "德剧", "俄剧", "巴剧", "加剧", "西剧", "意大利剧", "泰剧", "港台剧", "法剧", "澳剧", "短剧"};
            String[] typeValues = {"", "dongzuo", "aiqing", "xiju", "kehuan", "kongbu", "zhanzheng", "wuxia", "mohuan", "juqing", "donghua", "jingsong", "3d", "zainan", "xuanyi", "jingfei", "wenyi", "qingchun", "maoxian", "fanzui", "jilu", "guzhuang", "qihuan", "guoyu", "zongyi", "lishi", "yundong", "yuanchuang", "yazhi", "meiju", "hanju", "guoju", "riju", "yingju", "deju", "eju", "baju", "jiaju", "spanish", "yidaliju", "taiju", "gangtaiju", "faju", "aoju", "duanju"};
            for (int i = 0; i < typeNames.length; i++) {
                JsonObject opt = new JsonObject();
                opt.addProperty("n", typeNames[i]);
                opt.addProperty("v", typeValues[i]);
                typeOptions.add(opt);
            }
            // 地区选项
            JsonArray areaOptions = new JsonArray();
            String[] areaNames = {"全部", "中国大陆", "中国台湾", "东南亚", "欧美", "英国", "日本", "韩国", "香港", "台湾", "法国", "西班牙", "新加坡", "澳大利亚", "其他", "非洲", "印度", "马来西亚", "俄罗斯"};
            String[] areaValues = {"", "中国大陆", "中国台湾", "东南亚", "欧美", "英国", "日本", "韩国", "香港", "台湾", "法国", "西班牙", "新加坡", "澳大利亚", "其他", "非洲", "印度", "马来西亚", "俄罗斯"};
            for (int i = 0; i < areaNames.length; i++) {
                JsonObject opt = new JsonObject();
                opt.addProperty("n", areaNames[i]);
                opt.addProperty("v", areaValues[i]);
                areaOptions.add(opt);
            }
            // 年份选项
            JsonArray yearOptions = new JsonArray();
            yearOptions.add(createFilterOption("全部", ""));
            for (int y = 2012; y <= 2026; y++) {
                yearOptions.add(createFilterOption(String.valueOf(y), String.valueOf(y)));
            }

            JsonArray filterList = new JsonArray();
            filterList.add(createFilterGroup("type_slug", "影视类型", typeOptions));
            filterList.add(createFilterGroup("area", "地区", areaOptions));
            filterList.add(createFilterGroup("year", "年份", yearOptions));

            filters.add("0", filterList);
            filters.add("1", filterList);
            result.add("filters", filters);
        }
        return result.toString();
    }

    private JsonObject createFilterOption(String name, String value) {
        JsonObject opt = new JsonObject();
        opt.addProperty("n", name);
        opt.addProperty("v", value);
        return opt;
    }

    private JsonArray createFilterGroup(String key, String name, JsonArray values) {
        JsonObject group = new JsonObject();
        group.addProperty("key", key);
        group.addProperty("name", name);
        group.add("value", values);
        JsonArray arr = new JsonArray();
        arr.add(group);
        return arr;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Integer.parseInt(pg);
        String typeSlug = extend != null ? extend.getOrDefault("type_slug", "").trim() : "";
        String area = extend != null ? extend.getOrDefault("area", "").trim() : "";
        String year = extend != null ? extend.getOrDefault("year", "").trim() : "";

        String slug = typeSlug.isEmpty() ? "all" : typeSlug;
        String path = "s/" + slug + "/" + page;
        List<String> params = new ArrayList<>();
        params.add("type=" + tid);
        if (!area.isEmpty()) params.add("area=" + area);
        if (!year.isEmpty()) params.add("year=" + year);

        String url = host + path;
        if (!params.isEmpty()) {
            url += "?" + TextUtils.join("&", params);
        }

        Request request = new Request.Builder().url(url).header("User-Agent", commonUa).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return emptyCategoryResult(page);
            String html = response.body() != null ? response.body().string() : "";
            Document doc = Jsoup.parse(html);
            Elements cards = doc.select(".row-cards .col-4 .card-sm");
            JsonArray list = new JsonArray();
            for (Element card : cards) {
                Element a = card.selectFirst("a");
                if (a == null) continue;
                String vodId = a.attr("href").split(";")[0].trim();
                Element nameElem = card.selectFirst("h3.text-truncate");
                String name = nameElem != null ? nameElem.text().trim() : "";
                Element imgElem = card.selectFirst("img");
                String pic = imgElem != null ? imgElem.attr("src") : "";
                Element remarkElem = card.selectFirst(".bg-pink");
                String remark = remarkElem != null ? remarkElem.text().trim() : "";
                JsonObject vod = new JsonObject();
                vod.addProperty("vod_id", vodId);
                vod.addProperty("vod_name", name);
                vod.addProperty("vod_pic", pic);
                vod.addProperty("vod_remarks", remark);
                list.add(vod);
            }
            JsonObject result = new JsonObject();
            result.add("list", list);
            result.addProperty("page", page);
            result.addProperty("pagecount", 99);
            result.addProperty("limit", 30);
            result.addProperty("total", 9999);
            return result.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return emptyCategoryResult(page);
        }
    }

    private String emptyCategoryResult(int page) {
        JsonObject result = new JsonObject();
        result.add("list", new JsonArray());
        result.addProperty("page", page);
        result.addProperty("pagecount", 1);
        result.addProperty("limit", 0);
        result.addProperty("total", 0);
        return result.toString();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        String url = id.startsWith("http") ? id : host + id.replaceFirst("^/", "");
        Request request = new Request.Builder().url(url).header("User-Agent", commonUa).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return "{\"list\":[]}";
            String html = response.body() != null ? response.body().string() : "";
            Document doc = Jsoup.parse(html);

            Element nameElem = doc.selectFirst("h2.d-sm-block.d-md-none");
            String name = nameElem != null ? nameElem.text().trim() : "";

            Element picElem = doc.selectFirst(".cover-lg-max-25 img");
            String pic = picElem != null ? picElem.attr("src") : "";

            Element contentElem = doc.selectFirst("#synopsis .card-body");
            String content = contentElem != null ? contentElem.text().trim() : "";

            String director = "", actor = "", area = "", lang = "", remarks = "";
            Element infoContainer = doc.selectFirst("div.col.mb-2");
            if (infoContainer != null) {
                for (Element p : infoContainer.select("p")) {
                    Element strong = p.selectFirst("strong");
                    if (strong == null) continue;
                    String label = strong.text().trim().replaceAll("：$", "");
                    Element pClone = p.clone();
                    pClone.select("strong").remove();
                    String value = pClone.text().trim();
                    if (label.equals("导演")) {
                        Elements directorLinks = p.select("a");
                        if (!directorLinks.isEmpty()) {
                            List<String> dirs = new ArrayList<>();
                            for (Element a : directorLinks) dirs.add(a.text().trim());
                            director = TextUtils.join(", ", dirs);
                        } else {
                            director = value;
                        }
                    } else if (label.equals("主演")) {
                        Elements actorLinks = p.select("a");
                        if (!actorLinks.isEmpty()) {
                            List<String> acts = new ArrayList<>();
                            for (Element a : actorLinks) acts.add(a.text().trim());
                            actor = TextUtils.join(", ", acts);
                        } else {
                            actor = value;
                        }
                    } else if (label.equals("制片国家/地区")) {
                        area = value.replaceAll("[\\[\\]]", "");
                    } else if (label.equals("语言")) {
                        lang = value;
                    } else if (label.equals("集数")) {
                        remarks = value;
                    }
                }
            }

            Elements playLinks = doc.select("#play-list a.btn-square");
            List<String> playPairs = new ArrayList<>();
            for (Element a : playLinks) {
                String title = a.text().trim();
                String href = a.attr("href").split(";")[0].trim();
                playPairs.add(title + "$" + href);
            }
            String playUrl = TextUtils.join("#", playPairs);

            JsonObject vod = new JsonObject();
            vod.addProperty("vod_id", id);
            vod.addProperty("vod_name", name);
            vod.addProperty("vod_pic", pic);
            vod.addProperty("vod_content", content);
            vod.addProperty("vod_play_from", "哔嘀影视");
            vod.addProperty("vod_play_url", playUrl);
            vod.addProperty("vod_director", director);
            vod.addProperty("vod_actor", actor);
            vod.addProperty("vod_area", area);
            vod.addProperty("vod_lang", lang);
            vod.addProperty("vod_remarks", remarks);
            JsonArray list = new JsonArray();
            list.add(vod);
            JsonObject result = new JsonObject();
            result.add("list", list);
            return result.toString();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        // 该源未实现搜索，返回空列表
        JsonObject result = new JsonObject();
        result.add("list", new JsonArray());
        result.addProperty("page", 1);
        return result.toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : host + id.replaceFirst("^/", "");
        // 第一步：访问播放页获取 pid
        OkHttpClient tempClient = new OkHttpClient.Builder().build();
        Request pageReq = new Request.Builder().url(playUrl).header("User-Agent", commonUa).build();
        try (Response pageRes = tempClient.newCall(pageReq).execute()) {
            if (!pageRes.isSuccessful()) return simplePlayerResult(playUrl);
            String html = pageRes.body() != null ? pageRes.body().string() : "";
            Pattern pidPattern = Pattern.compile("var pid\\s*=\\s*(\\d+)");
            Matcher m = pidPattern.matcher(html);
            if (!m.find()) return simplePlayerResult(playUrl);
            String pid = m.group(1);

            // 计算 sg 和 t
            long currT = System.currentTimeMillis();
            String t = String.valueOf(currT);
            String plain = pid + "-" + t;
            String md5 = Util.MD5(plain);
            String aesKey = md5.substring(0, 16);
            String sg = aesEcbEncrypt(plain, aesKey);

            // 请求 API
            String apiUrl = host + "lines?t=" + t + "&sg=" + sg + "&pid=" + pid;
            Request apiReq = new Request.Builder().url(apiUrl)
                    .header("User-Agent", commonUa)
                    .header("Referer", playUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .build();
            try (Response apiRes = tempClient.newCall(apiReq).execute()) {
                if (apiRes.isSuccessful() && apiRes.body() != null) {
                    String json = apiRes.body().string();
                    // 提取 JSON 对象
                    Pattern jsonPattern = Pattern.compile("(\\{.*\\})", Pattern.DOTALL);
                    Matcher jsonMatcher = jsonPattern.matcher(json);
                    if (jsonMatcher.find()) {
                        JsonObject obj = Json.parse(jsonMatcher.group(1)).getAsJsonObject();
                        if (obj.has("code") && obj.get("code").getAsInt() == 0 && obj.has("data")) {
                            JsonObject data = obj.getAsJsonObject("data");
                            String rawUrl = "";
                            if (data.has("url3") && !data.get("url3").isJsonNull()) rawUrl = data.get("url3").getAsString();
                            else if (data.has("m3u8_2") && !data.get("m3u8_2").isJsonNull()) rawUrl = data.get("m3u8_2").getAsString();
                            else if (data.has("m3u8") && !data.get("m3u8").isJsonNull()) rawUrl = data.get("m3u8").getAsString();
                            if (!rawUrl.isEmpty()) {
                                String finalUrl = rawUrl.split(",")[0].split("#")[0].trim();
                                return buildPlayerResult(0, finalUrl, commonUa);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return simplePlayerResult(playUrl);
    }

    private String simplePlayerResult(String url) {
        return buildPlayerResult(1, url, null);
    }

    private String buildPlayerResult(int parse, String url, String ua) {
        JsonObject result = new JsonObject();
        result.addProperty("parse", parse);
        result.addProperty("url", url);
        if (ua != null) {
            JsonObject header = new JsonObject();
            header.addProperty("User-Agent", ua);
            result.add("header", header);
        }
        return result.toString();
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
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
