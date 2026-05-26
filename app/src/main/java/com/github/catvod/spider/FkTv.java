package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class FkTv extends Spider {

    private final String siteUrl = "https://fktv.me";
    private final String UA = "Mozilla/5.0 (Linux; Android 15; 23054RA19C Build/AP3A.240905.015.A2; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/147.0.7727.137 Mobile Safari/537.36";

    private Map<String, String> getHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", siteUrl + "/");
        headers.put("Accept", "application/json, text/javascript, */*; q=0.01");
        return headers;
    }

    // AES ECB 解密图片
    private String decryptImage(String encryptedUrl) {
        try {
            String keyHex = "35323532303266393134396530363164";
            byte[] key = hexToBytes(keyHex);

            OkResult result = OkHttp.get(encryptedUrl, getHeader());
            byte[] encryptedData = result.getBodyBytes();

            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);

            byte[] decrypted = cipher.doFinal(encryptedData);
            int pad = decrypted[decrypted.length - 1];
            if (pad > 0 && pad <= 16) {
                byte[] finalData = new byte[decrypted.length - pad];
                System.arraycopy(decrypted, 0, finalData, 0, finalData.length);
                return "data:image/jpeg;base64," + android.util.Base64.encodeToString(finalData, android.util.Base64.DEFAULT);
            }
            return "data:image/jpeg;base64," + android.util.Base64.encodeToString(decrypted, android.util.Base64.DEFAULT);
        } catch (Exception e) {
            e.printStackTrace();
            return encryptedUrl;
        }
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
    }

    // 首页
    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("1", "电影"));
        classes.add(new Class("2", "电视剧"));
        classes.add(new Class("3", "综艺"));
        classes.add(new Class("8", "短剧"));

        String url = siteUrl + "/channel?cat_id=1&page=1&page_size=32";
        String html = OkHttp.string(url, getHeader());
        List<Vod> list = parseList(html);

        return Result.string(classes, list);
    }

    // 分类页
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String url = siteUrl + "/channel?page=" + pg + "&cat_id=" + tid + "&tag_id=&order=new&page_size=32";
        String html = OkHttp.string(url, getHeader());
        List<Vod> list = parseList(html);

        return Result.string(list);
    }

    // 搜索（GET请求）
    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        try {
            String url = siteUrl + "/channel?keywords=" + URLEncoder.encode(key, "UTF-8");
            String html = OkHttp.string(url, getHeader());
            List<Vod> list = parseList(html);
            return Result.string(list);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.string(new ArrayList<>());
        }
    }

    // 通用列表解析（分类 + 搜索共用）
    private List<Vod> parseList(String html) {
        List<Vod> list = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        Elements items = doc.select("div.item-wrap.vertical");
        for (Element item : items) {
            Element a = item.selectFirst("a[href^=/movie/detail]");
            if (a == null) continue;

            String href = a.attr("href");
            String name = a.attr("title");
            String pic = item.selectFirst("div.lazy-load").attr("data-src");
            String remark = item.selectFirst(".category") != null ? item.selectFirst(".category").text() : "";

            if (!href.startsWith("http")) href = siteUrl + href;

            String realPic = decryptImage(pic);

            Vod vod = new Vod();
            vod.setVodId(href);
            vod.setVodName(name);
            vod.setVodPic(realPic);
            vod.setVodRemarks(remark);
            list.add(vod);
        }
        return list;
    }

    // 详情页
    @Override
    public String detailContent(List<String> ids) throws Exception {
        String detailUrl = ids.get(0);
        String html = OkHttp.string(detailUrl, getHeader());
        Document doc = Jsoup.parse(html);

        String name = doc.selectFirst(".name") != null ? doc.selectFirst(".name").text() : "";
        String pic = "";
        Element picEl = doc.selectFirst(".thumb.lazy-load");
        if (picEl != null) pic = decryptImage(picEl.attr("data-src"));

        String type = "", content = "";
        Element desc = doc.selectFirst(".desc");
        if (desc != null) content = desc.text();

        Elements tags = doc.select(".tag-item");
        for (Element tag : tags) {
            String t = tag.text();
            if (t.contains("剧") || t.contains("电影") || t.contains("综艺")) type = t;
        }

        // 提取 movieId 和 linkId
        String movieId = extractRegex(html, "movieId\\s*=\\s*['\"](.*?)['\"]");
        String linkId = extractRegex(html, "linkId\\s*=\\s*['\"](.*?)['\"]");

        // POST 获取播放线路
        Map<String, String> postData = new HashMap<>();
        postData.put("link_id", linkId);
        postData.put("is_switch", "1");

        Map<String, String> postHeader = new HashMap<>(getHeader());
        postHeader.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        postHeader.put("X-Requested-With", "XMLHttpRequest");
        postHeader.put("Origin", siteUrl);

        OkResult postRes = OkHttp.post(detailUrl, postData, postHeader);
        String jsonStr = postRes.getBody();

        Map<String, List<String>> playMap = new LinkedHashMap<>();
        try {
            JSONObject json = new JSONObject(jsonStr);
            if ("y".equals(json.optString("status"))) {
                JSONArray playLinks = json.getJSONObject("data").getJSONArray("play_links");
                List<String> urls = new ArrayList<>();
                for (int i = 0; i < playLinks.length(); i++) {
                    JSONObject line = playLinks.getJSONObject(i);
                    String m3u8 = line.optString("m3u8_url");
                    if (m3u8.startsWith("/")) m3u8 = siteUrl + m3u8;
                    urls.add("线路" + (i + 1) + "$" + m3u8);
                }
                if (!urls.isEmpty()) {
                    playMap.put("默认线路", urls);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        Vod vod = new Vod();
        vod.setVodId(detailUrl);
        vod.setVodName(name);
        vod.setVodPic(pic);
        vod.setTypeName(type);
        vod.setVodContent(content);
        vod.setVodPlayFrom(String.join("$$$", playMap.keySet()));
        vod.setVodPlayUrl(String.join("$$$", playMap.values().stream().map(v -> String.join("#", v)).toList()));

        return Result.string(vod);
    }

    // 播放
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return Result.get().url(id).string();
    }

    private String extractRegex(String text, String regex) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
        java.util.regex.Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : "";
    }
}
