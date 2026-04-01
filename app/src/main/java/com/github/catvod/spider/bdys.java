package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;

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
    private static final String USER_AGENT = Util.CHROME; // 复用catvod的chrome UA
    private static final String FIXED_SESSION = "BE9F4982E7333BC81314A607392E2961";

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        return headers;
    }

    private HashMap<String, String> getHeadersWithCookie() {
        HashMap<String, String> headers = getHeaders();
        headers.put("Cookie", "JSESSIONID=" + FIXED_SESSION);
        return headers;
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("1", "电视剧"));
        classes.add(new Class("0", "电影"));
        return Result.string(classes, new ArrayList<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Integer.parseInt(pg);
        String url = HOST + "s/all/" + page + "?type=" + tid;
        String html = OkHttp.string(url, getHeaders());
        Document doc = Jsoup.parse(html);
        List<Vod> list = new ArrayList<>();
        Elements cards = doc.select(".row-cards .col-4 .card-sm");
        for (Element card : cards) {
            Element a = card.selectFirst("a");
            if (a == null) continue;
            String href = a.attr("href");
            String vodId = href.split(";")[0].trim();
            String name = card.selectFirst("h3.text-truncate") != null ? card.selectFirst("h3.text-truncate").text().trim() : "";
            String pic = card.selectFirst("img") != null ? card.selectFirst("img").attr("src") : "";
            String remark = card.selectFirst(".bg-pink") != null ? card.selectFirst(".bg-pink").text().trim() : "";
            list.add(new Vod(vodId, name, pic, remark));
        }
        return Result.string(page, 1, list.size(), Integer.MAX_VALUE, list);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        String url = id.startsWith("http") ? id : HOST + id.replaceFirst("^/", "");
        String html = OkHttp.string(url, getHeaders());
        Document doc = Jsoup.parse(html);
        String name = doc.selectFirst("h1") != null ? doc.selectFirst("h1").text().trim() : "";
        String pic = "";
        Element cover = doc.selectFirst(".cover-lg-max-25 img");
        if (cover != null) pic = cover.attr("src");
        String content = doc.selectFirst("#synopsis .card-body") != null ? doc.selectFirst("#synopsis .card-body").text().trim() : "暂无简介";

        Elements playButtons = doc.select("#play-list a.btn-square");
        List<String> playList = new ArrayList<>();
        for (Element btn : playButtons) {
            String text = btn.text().trim();
            String href = btn.attr("href").split(";")[0].trim();
            playList.add(text + "$" + href);
        }
        String playUrl = String.join("#", playList);

        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setVodContent(content);
        vod.setVodPlayFrom("量子资源");
        vod.setVodPlayUrl(playUrl);
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        // 该源没有实现搜索，返回空列表
        return Result.string(new ArrayList<>());
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : HOST + id.replaceFirst("^/", "");
        System.out.println("[解析日志] 正在解析: " + playUrl);

        // 1. 获取pid
        String pageHtml = OkHttp.string(playUrl, getHeadersWithCookie());
        Pattern pidPattern = Pattern.compile("var pid\\s*=\\s*(\\d+)");
        Matcher pidMatcher = pidPattern.matcher(pageHtml);
        if (!pidMatcher.find()) {
            // 未找到pid，直接返回原链接
            return Result.get().url(playUrl).header(getHeaders()).string();
        }
        String pid = pidMatcher.group(1);

        // 2. 生成加密参数
        Map<String, String> sgAndT = getSgAndT(pid);
        String sg = sgAndT.get("sg");
        String t = sgAndT.get("t");

        // 3. 请求API
        String apiUrl = HOST + "lines?t=" + t + "&sg=" + sg + "&pid=" + pid;
        HashMap<String, String> apiHeaders = new HashMap<>(getHeadersWithCookie());
        apiHeaders.put("Referer", playUrl);
        apiHeaders.put("X-Requested-With", "XMLHttpRequest");
        apiHeaders.put("Accept", "application/json, text/javascript, */*; q=0.01");
        String apiResp = OkHttp.string(apiUrl, apiHeaders);

        // 4. 提取JSON
        Pattern jsonPattern = Pattern.compile("(\\{.*\\})");
        Matcher jsonMatcher = jsonPattern.matcher(apiResp);
        if (jsonMatcher.find()) {
            String jsonStr = jsonMatcher.group(1);
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(jsonStr).getAsJsonObject();
            int code = json.get("code").getAsInt();
            if (code == 0 && json.has("data")) {
                com.google.gson.JsonObject data = json.getAsJsonObject("data");
                String rawUrl = null;
                if (data.has("url3") && !data.get("url3").isJsonNull()) {
                    rawUrl = data.get("url3").getAsString();
                } else if (data.has("m3u8_2") && !data.get("m3u8_2").isJsonNull()) {
                    rawUrl = data.get("m3u8_2").getAsString();
                } else if (data.has("m3u8") && !data.get("m3u8").isJsonNull()) {
                    rawUrl = data.get("m3u8").getAsString();
                }
                if (rawUrl != null && !rawUrl.isEmpty()) {
                    String finalUrl = rawUrl.split(",")[0].split("#")[0].trim();
                    System.out.println("[SUCCESS] 提取成功: " + finalUrl);
                    return Result.get().url(finalUrl).header(getHeaders()).string();
                } else {
                    System.out.println("[ERROR] 接口返回成功，但未找到播放地址字段");
                }
            } else {
                String msg = json.has("msg") ? json.get("msg").getAsString() : "未知错误";
                System.out.println("[ERROR] 接口返回状态异常: " + msg);
            }
        } else {
            System.out.println("[ERROR] 响应非有效JSON格式");
        }

        // 失败时返回原链接
        return Result.get().url(playUrl).header(getHeaders()).string();
    }

    private Map<String, String> getSgAndT(String pid) {
        long timestamp = System.currentTimeMillis();
        String t = Long.toString(timestamp);
        String plain = pid + "-" + t;
        try {
            // MD5
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] md5Bytes = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            String md5Hex = bytesToHex(md5Bytes);
            // 取前16字节作为AES密钥
            byte[] keyBytes = md5Hex.substring(0, 16).getBytes(StandardCharsets.UTF_8);
            // AES/ECB/PKCS5Padding 加密
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            String sg = bytesToHex(encrypted).toUpperCase();
            Map<String, String> result = new HashMap<>();
            result.put("sg", sg);
            result.put("t", t);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, String> result = new HashMap<>();
            result.put("sg", "");
            result.put("t", t);
            return result;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
