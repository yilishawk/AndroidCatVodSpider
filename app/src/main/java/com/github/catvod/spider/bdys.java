package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class bdys extends Spider {

    private static final String HOST = "https://v.xlys.ltd.ua/";

    public String getName() {
        return "量子资源";
    }

    public void init(String extend) {
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("1", "电视剧"));
        classes.add(new Class("0", "电影"));
        return Result.string(classes, new ArrayList<Vod>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Integer.parseInt(pg);
        String url = HOST + "s/all/" + page + "?type=" + tid;
        String html = OkHttp.string(url, null);
        Document doc = Jsoup.parse(html);
        List<Vod> list = new ArrayList<>();
        Elements cards = doc.select(".row-cards .col-4 .card-sm");
        
        for (Element card : cards) {
            Element a = card.selectFirst("a");
            if (a == null) continue;
            String vodId = a.attr("href");
            String name = card.selectFirst("h3") != null ? card.selectFirst("h3").text().trim() : "";
            String pic = card.selectFirst("img") != null ? card.selectFirst("img").attr("src") : "";
            String remark = card.selectFirst(".bg-pink") != null ? card.selectFirst(".bg-pink").text().trim() : "";
            list.add(new Vod(vodId, name, pic, remark));
        }

        // --- 核心修复：必须传入 5 个参数 ---
        // 参数顺序：当前页(Integer), 总页数(Integer), 每页条数(Integer), 总记录数(Integer), 数据列表(List<Vod>)
        return Result.string(page, page + 1, list.size(), 1000, list);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        String url = id.startsWith("http") ? id : HOST + id.replaceAll("^/+", "");
        String html = OkHttp.string(url, null);
        Document doc = Jsoup.parse(html);

        Vod vod = new Vod();
        vod.setVodId(id);
        Element titleElem = doc.selectFirst("h2.d-sm-block.d-md-none");
        vod.setVodName(titleElem != null ? titleElem.text().trim() : "未知");
        Element picElem = doc.selectFirst(".cover-lg-max-25 img");
        vod.setVodPic(picElem != null ? picElem.attr("src") : "");

        List<String> playList = new ArrayList<>();
        for (Element a : doc.select("#play-list a.btn-square")) {
            playList.add(a.text().trim() + "$" + a.attr("href"));
        }
        vod.setVodPlayFrom("量子播放器");
        vod.setVodPlayUrl(String.join("#", playList));

        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : HOST + id.replaceAll("^/+", "");
        String pageHtml = OkHttp.string(playUrl, null);
        
        Matcher matcher = Pattern.compile("var pid\\s*=\\s*(\\d+)").matcher(pageHtml);
        if (!matcher.find()) return Result.get().url(playUrl).string();
        String pid = matcher.group(1);

        Map<String, String> sgAndT = getSgAndT(pid);
        String apiUrl = HOST + "lines?t=" + sgAndT.get("t") + "&sg=" + sgAndT.get("sg") + "&pid=" + pid;
        
        HashMap<String, String> apiHeaders = new HashMap<>();
        apiHeaders.put("Referer", playUrl);
        apiHeaders.put("X-Requested-With", "XMLHttpRequest");
        
        String apiResp = OkHttp.string(apiUrl, apiHeaders);
        JsonObject json = JsonParser.parseString(apiResp).getAsJsonObject();
        
        if (json.get("code").getAsInt() == 0) {
            JsonObject data = json.getAsJsonObject("data");
            String url = data.has("url3") ? data.get("url3").getAsString() : (data.has("m3u8") ? data.get("m3u8").getAsString() : "");
            if (!url.isEmpty()) {
                return Result.get().url(url.split(",")[0]).string();
            }
        }
        return Result.get().url(playUrl).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String url = HOST + "search?text=" + key;
        String html = OkHttp.string(url, null);
        Document doc = Jsoup.parse(html);
        List<Vod> list = new ArrayList<>();
        for (Element card : doc.select(".row-cards .col-4 .card-sm")) {
            Element a = card.selectFirst("a");
            if (a == null) continue;
            list.add(new Vod(a.attr("href"), card.selectFirst("h3").text(), card.selectFirst("img").attr("src"), ""));
        }
        return Result.string(list);
    }

    private Map<String, String> getSgAndT(String pid) {
        long timestamp = System.currentTimeMillis();
        String t = Long.toString(timestamp);
        Map<String, String> result = new HashMap<>();
        try {
            String plain = pid + "-" + t;
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] md5Bytes = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            String md5Hex = bytesToHex(md5Bytes);
            byte[] keyBytes = md5Hex.substring(0, 16).getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            result.put("sg", bytesToHex(encrypted).toUpperCase());
            result.put("t", t);
        } catch (Exception e) {
            result.put("sg", "");
            result.put("t", t);
        }
        return result;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
