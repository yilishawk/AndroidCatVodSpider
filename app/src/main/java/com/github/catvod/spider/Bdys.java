package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class Bdys extends Spider {

    private String host = "https://xl02.com.de/";
    private String commonUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        JsonObject result = new JsonObject();
        JsonArray classes = new JsonArray();
        
        // 分类定义
        String[][] categories = {
            {"电视剧", "1"},
            {"电影", "0"}
        };
        
        for (String[] cat : categories) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type_name", cat[0]);
            obj.addProperty("type_id", cat[1]);
            classes.add(obj);
        }
        result.add("class", classes);

        if (filter) {
            JsonObject filters = new JsonObject();
            
            // 构建筛选列表
            JsonArray filterList = new JsonArray();
            
            // 类型
            String[] typeNames = {"全部", "动作", "爱情", "喜剧", "科幻", "恐怖", "剧情", "动画", "悬疑", "犯罪", "古装", "奇幻", "美剧", "韩剧", "国产", "日剧"};
            String[] typeValues = {"", "dongzuo", "aiqing", "xiju", "kehuan", "kongbu", "juqing", "donghua", "xuanyi", "fanzui", "guzhuang", "qihuan", "meiju", "hanju", "guoju", "riju"};
            filterList.add(createFilterGroup("type_slug", "类型", typeNames, typeValues));

            // 年份
            List<String> years = new ArrayList<>();
            years.add("全部");
            for (int y = 2026; y >= 2015; y--) years.add(String.valueOf(y));
            filterList.add(createFilterGroup("year", "年份", years.toArray(new String[0]), years.toArray(new String[0])));

            // 绑定到每个分类 ID
            filters.add("0", filterList);
            filters.add("1", filterList);
            result.add("filters", filters);
        }
        return result.toString();
    }

    private JsonObject createFilterGroup(String key, String name, String[] nList, String[] vList) {
        JsonObject group = new JsonObject();
        group.addProperty("key", key);
        group.addProperty("name", name);
        JsonArray values = new JsonArray();
        for (int i = 0; i < nList.length; i++) {
            JsonObject opt = new JsonObject();
            opt.addProperty("n", nList[i]);
            opt.addProperty("v", vList[i].equals("全部") ? "" : vList[i]);
            values.add(opt);
        }
        group.add("value", values);
        return group; // 关键修复：不再套一层 Array
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Integer.parseInt(pg);
        String typeSlug = extend != null ? extend.getOrDefault("type_slug", "all") : "all";
        if (typeSlug.isEmpty()) typeSlug = "all";
        
        String url = host + "s/" + typeSlug + "/" + page + "?type=" + tid;
        if (extend != null && extend.containsKey("year") && !extend.get("year").isEmpty()) {
            url += "&year=" + extend.get("year");
        }

        String html = OkHttp.string(url, getHeaders());
        Document doc = Jsoup.parse(html);
        JsonArray list = new JsonArray();
        Elements cards = doc.select(".row-cards .card-sm");

        for (Element card : cards) {
            Element a = card.selectFirst("a");
            if (a == null) continue;
            
            String vodId = a.attr("href");
            String name = card.select(".text-truncate").text().trim();
            String pic = card.select("img").attr("src");
            String remark = card.select(".bg-pink").text().trim();

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
        result.addProperty("pagecount", 999);
        result.addProperty("limit", 20);
        result.addProperty("total", 9999);
        return result.toString();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        String url = id.startsWith("http") ? id : host + id.replaceFirst("^/", "");
        String html = OkHttp.string(url, getHeaders());
        Document doc = Jsoup.parse(html);

        JsonObject vod = new JsonObject();
        vod.addProperty("vod_id", id);
        vod.addProperty("vod_name", doc.select("h2.d-sm-block").text().trim());
        vod.addProperty("vod_pic", doc.select(".cover-lg-max-25 img").attr("src"));
        vod.addProperty("vod_content", doc.select("#synopsis .card-body").text().trim());
        
        // 播放列表解析
        Elements playLinks = doc.select("#play-list a.btn-square");
        List<String> playPairs = new ArrayList<>();
        for (Element a : playLinks) {
            playPairs.add(a.text().trim() + "$" + a.attr("href"));
        }
        
        vod.addProperty("vod_play_from", "哔嘀影视");
        vod.addProperty("vod_play_url", TextUtils.join("#", playPairs));

        JsonArray list = new JsonArray();
        list.add(vod);
        JsonObject result = new JsonObject();
        result.add("list", list);
        return result.toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : host + id.replaceFirst("^/", "");
        String html = OkHttp.string(playUrl, getHeaders());
        
        Matcher m = Pattern.compile("var pid\\s*=\\s*(\\d+)").matcher(html);
        if (m.find()) {
            String pid = m.group(1);
            long t = System.currentTimeMillis();
            String plain = pid + "-" + t;
            String md5 = Util.MD5(plain);
            String sg = aesEcbEncrypt(plain, md5.substring(0, 16));
            
            // 此处逻辑取决于对方 API 连通性，如果失败则返回原网页尝试嗅探
            JsonObject result = new JsonObject();
            result.addProperty("parse", 1); // 推荐先使用 1 (嗅探)
            result.addProperty("url", playUrl);
            return result.toString();
        }

        JsonObject result = new JsonObject();
        result.addProperty("parse", 1);
        result.addProperty("url", playUrl);
        return result.toString();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return ""; 
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", commonUa);
        return headers;
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
