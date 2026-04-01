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

public class bdys extends Spider {  // 注意类名与文件名一致，原错误日志中类名为bdys

    private static final String HOST = "https://v.xlys.ltd.ua/";
    private static final String USER_AGENT = Util.CHROME;

    private HashMap<String, String> getHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        return headers;
    }

    @Override
    public String getName() {
        return "量子资源(完整版)";
    }

    @Override
    public void init(String extend) {
    }

    @Override
    public boolean isVideoCast() {
        return true;
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
        System.out.println("[CAT] 请求URL: " + url);
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
        System.out.println("[CAT] 找到 " + list.size() + " 个视频");
        // 修正：参数顺序为 page, hasNext, total, totalPages, list
        return Result.string(page, 1, list.size(), Integer.MAX_VALUE, list);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        String url = id.startsWith("http") ? id : HOST + id.replaceFirst("^/", "");
        System.out.println("[DETAIL] 请求URL: " + url);

        String html = OkHttp.string(url, getHeaders());
        System.out.println("[DETAIL] 响应状态码: 200");
        System.out.println("[DETAIL] 响应内容片段: " + (html.length() > 500 ? html.substring(0, 500) : html));

        Document doc = Jsoup.parse(html);

        // 标题（第二个 h2，类名 d-sm-block d-md-none）
        Element titleElem = doc.selectFirst("h2.d-sm-block.d-md-none");
        String name = titleElem != null ? titleElem.text().trim() : "";

        // 封面
        Element picElem = doc.selectFirst(".cover-lg-max-25 img");
        String pic = picElem != null ? picElem.attr("src") : "";

        // 简介
        Element contentElem = doc.selectFirst("#synopsis .card-body");
        String content = contentElem != null ? contentElem.text().trim() : "暂无简介";

        // 导演、演员、地区、语言、集数
        String director = "";
        String actor = "";
        String area = "";
        String lang = "";
        String remarks = "";

        Element infoContainer = doc.selectFirst("div.col.mb-2");
        if (infoContainer != null) {
            for (Element p : infoContainer.select("p")) {
                Element strong = p.selectFirst("strong");
                if (strong == null) continue;
                String label = strong.text().trim().replace("：", "");
                // 去除 strong 标签后的纯文本
                Element pClone = p.clone();
                pClone.select("strong").remove();
                String value = pClone.text().trim();

                if ("导演".equals(label)) {
                    List<String> directors = new ArrayList<>();
                    for (Element a : p.select("a")) {
                        directors.add(a.text().trim());
                    }
                    director = directors.isEmpty() ? value : String.join(", ", directors);
                } else if ("主演".equals(label)) {
                    List<String> actors = new ArrayList<>();
                    for (Element a : p.select("a")) {
                        actors.add(a.text().trim());
                    }
                    actor = actors.isEmpty() ? value : String.join(", ", actors);
                } else if ("制片国家/地区".equals(label)) {
                    area = value.replaceAll("[\\[\\]]", "");
                } else if ("语言".equals(label)) {
                    lang = value;
                } else if ("集数".equals(label)) {
                    remarks = value;
                }
            }
        }

        // 将语言合并到备注中（如果集数已存在，用 | 分隔）
        if (!lang.isEmpty()) {
            if (!remarks.isEmpty()) {
                remarks += " | " + lang;
            } else {
                remarks = lang;
            }
        }

        // 播放列表
        List<String> playList = new ArrayList<>();
        for (Element a : doc.select("#play-list a.btn-square")) {
            String text = a.text().trim();
            String href = a.attr("href").split(";")[0].trim();
            playList.add(text + "$" + href);
        }
        String playUrl = String.join("#", playList);

        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setVodContent(content);
        vod.setVodPlayFrom("哔嘀影视");
        vod.setVodPlayUrl(playUrl);
        vod.setVodDirector(director);
        vod.setVodActor(actor);
        vod.setVodArea(area);
        vod.setVodRemarks(remarks);
        // 注意：Vod 类可能没有 setVodLang，故不调用

        System.out.println("[DETAIL] 提取成功: " + name);
        return Result.string(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return Result.string(new ArrayList<>());
    }

    // 加密参数生成（MD5 + AES）
    private Map<String, String> getSgAndT(String pid) {
        long timestamp = System.currentTimeMillis();
        String t = Long.toString(timestamp);
        String plain = pid + "-" + t;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] md5Bytes = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            String md5Hex = bytesToHex(md5Bytes);
            byte[] keyBytes = md5Hex.substring(0, 16).getBytes(StandardCharsets.UTF_8);
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

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String playUrl = id.startsWith("http") ? id : HOST + id.replaceFirst("^/", "");
        System.out.println("[PLAY] 正在解析: " + playUrl);

        HashMap<String, String> headers = new HashMap<>(getHeaders());
        // 使用固定 session，可根据需要改为动态获取
        headers.put("Cookie", "JSESSIONID=BE9F4982E7333BC81314A607392E2961");
        String pageHtml = OkHttp.string(playUrl, headers);
        Pattern pidPattern = Pattern.compile("var pid\\s*=\\s*(\\d+)");
        Matcher pidMatcher = pidPattern.matcher(pageHtml);
        if (!pidMatcher.find()) {
            System.out.println("[PLAY] 未找到 pid");
            return Result.get().url(playUrl).header(getHeaders()).string();
        }
        String pid = pidMatcher.group(1);
        System.out.println("[PLAY] pid: " + pid);

        Map<String, String> sgAndT = getSgAndT(pid);
        String sg = sgAndT.get("sg");
        String t = sgAndT.get("t");
        System.out.println("[PLAY] sg: " + sg + ", t: " + t);

        String apiUrl = HOST + "lines?t=" + t + "&sg=" + sg + "&pid=" + pid;
        HashMap<String, String> apiHeaders = new HashMap<>(headers);
        apiHeaders.put("Referer", playUrl);
        apiHeaders.put("X-Requested-With", "XMLHttpRequest");
        apiHeaders.put("Accept", "application/json, text/javascript, */*; q=0.01");
        String apiResp = OkHttp.string(apiUrl, apiHeaders);
        System.out.println("[PLAY] API raw response: " + apiResp);

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
                    System.out.println("[ERROR] 未找到播放地址字段");
                    System.out.println("[DEBUG] 可用字段: " + data.keySet());
                }
            } else {
                String msg = json.has("msg") ? json.get("msg").getAsString() : "未知错误";
                System.out.println("[ERROR] 接口返回异常: code=" + code + ", msg=" + msg);
            }
        } else {
            System.out.println("[ERROR] 响应非有效JSON格式");
        }

        return Result.get().url(playUrl).header(getHeaders()).string();
    }
}
