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

    private String host = "https://v.xl.in.ua";
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
        if (!path.startsWith("/")) path = "/" + path;
        return host + path;
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", commonUa);
        headers.put("Referer", host + "/");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("sec-ch-ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"147\", \"Google Chrome\";v=\"147\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", "document");
        headers.put("sec-fetch-mode", "navigate");
        headers.put("sec-fetch-site", "same-origin");
        headers.put("sec-fetch-user", "?1");
        headers.put("upgrade-insecure-requests", "1");
        return headers;
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

            LinkedHashMap<String, List<Filter>> filterMap = new LinkedHashMap<>();
            if (filter) {
                List<Filter> filterList = new ArrayList<>();

                // 类型
                String[] typeNames = {"全部", "动作", "爱情", "喜剧", "科幻", "恐怖", "剧情", "动画", "悬疑", "犯罪", "古装", "奇幻", "美剧", "韩剧", "国产", "日剧"};
                String[] typeValues = {"all", "dongzuo", "aiqing", "xiju", "kehuan", "kongbu", "juqing", "donghua", "xuanyi", "fanzui", "guzhuang", "qihuan", "meiju", "hanju", "guoju", "riju"};
                List<Filter.Value> typeOptions = new ArrayList<>();
                for (int i = 0; i < typeNames.length; i++) {
                    typeOptions.add(new Filter.Value(typeNames[i], typeValues[i]));
                }
                filterList.add(new Filter("type_slug", "类型", typeOptions));

                // 年份
                List<Filter.Value> yearOptions = new ArrayList<>();
                yearOptions.add(new Filter.Value("全部", ""));
                for (int y = 2026; y >= 2015; y--) {
                    yearOptions.add(new Filter.Value(String.valueOf(y), String.valueOf(y)));
                }
                filterList.add(new Filter("year", "年份", yearOptions));

                filterMap.put("0", filterList);
                filterMap.put("1", filterList);
            }

            return Result.string(classes, filterMap);
        } catch (Exception e) {
            logger("homeContent 异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        try {
            int page = Integer.parseInt(pg);
            String typeSlug = (extend != null && extend.containsKey("type_slug") && !TextUtils.isEmpty(extend.get("type_slug"))) 
                    ? extend.get("type_slug") : "all";

            String url = host + "/s/" + typeSlug + "/" + page + "?type=" + tid;
            if (extend != null && extend.containsKey("year") && !TextUtils.isEmpty(extend.get("year"))) {
                url += "&year=" + extend.get("year");
            }

            logger("分类请求: " + url);
            String html = OkHttp.string(url, getHeaders());
            if (TextUtils.isEmpty(html)) {
                logger("⚠️ 分类请求返回空 HTML");
                return Result.string(new ArrayList<>());
            }

            Document doc = Jsoup.parse(html);
            List<Vod> list = new ArrayList<>();
            
            // 兼容新版HTML的卡片节点结构
            Elements cards = doc.select(".movie-card");
            if (cards.isEmpty()) {
                cards = doc.select(".row-cards .card-sm"); // 保留旧选择器备用
            }
            logger("解析到列表节点数量: " + cards.size());

            for (Element card : cards) {
                Element a = card.selectFirst("a");
                if (a == null) continue;

                String vodId = a.attr("href");
                
                // 获取标题：优先取 card-info 里的 h4，取不到则取 <a> 标签的 title 属性
                String name = card.select(".card-info h4").text().trim();
                if (TextUtils.isEmpty(name)) {
                    name = card.select(".text-truncate").text().trim();
                }
                if (TextUtils.isEmpty(name) && a.hasAttr("title")) {
                    name = a.attr("title");
                }

                // 获取图片：优先从 data-src 读取，没有再读 src
                Element img = card.selectFirst("img");
                String pic = "";
                if (img != null) {
                    pic = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
                    pic = fixUrl(pic);
                }

                // 获取备注/集数/评分
                String remark = card.select(".episode-badge").text().trim();
                if (TextUtils.isEmpty(remark)) {
                    remark = card.select(".rating-badge").text().trim();
                }
                if (TextUtils.isEmpty(remark)) {
                    remark = card.select(".bg-pink").text().trim();
                }

                Vod vod = new Vod();
                vod.setVodId(vodId);
                vod.setVodName(name);
                vod.setVodPic(pic);
                vod.setVodRemarks(remark);
                list.add(vod);
            }

            return Result.string(list);
        } catch (Exception e) {
            logger("categoryContent 异常: " + e.getMessage());
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        try {
            String id = ids.get(0);
            String url = fixUrl(id);
            logger("详情页请求: " + url);

            String html = OkHttp.string(url, getHeaders());
            if (TextUtils.isEmpty(html)) {
                logger("⚠️ 详情页响应为空");
                return Result.string(new ArrayList<>());
            }

            Document doc = Jsoup.parse(html);

            String name = doc.select("h2.d-sm-block").text().trim();
            if (TextUtils.isEmpty(name)) name = doc.select("h1").text().trim();

            Element imgElem = doc.selectFirst(".cover-lg-max-25 img");
            if (imgElem == null) imgElem = doc.selectFirst(".movie-card img");
            
            String pic = "";
            if (imgElem != null) {
                pic = imgElem.hasAttr("data-src") ? imgElem.attr("data-src") : imgElem.attr("src");
                pic = fixUrl(pic);
            }

            String content = doc.select("#synopsis .card-body").text().trim();

            // 播放列表解析
            Elements playLinks = doc.select("#play-list a.btn-square");
            if (playLinks.isEmpty()) {
                playLinks = doc.select("#play-list a");
            }
            
            List<String> playPairs = new ArrayList<>();
            for (Element a : playLinks) {
                String epName = a.text().trim();
                String epHref = a.attr("href");
                if (!TextUtils.isEmpty(epHref)) {
                    playPairs.add(epName + "$" + epHref);
                }
            }

            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodContent(content);

            if (!playPairs.isEmpty()) {
                vod.setVodPlayFrom("哔嘀影视");
                vod.setVodPlayUrl(TextUtils.join("#", playPairs));
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
            String playUrl = fixUrl(id);
            logger("播放页请求: " + playUrl);

            String html = OkHttp.string(playUrl, getHeaders());
            
            // 匹配 pid 用于加密验签 (备用)
            Matcher m = Pattern.compile("var pid\\s*=\\s*(\\d+)").matcher(html);
            if (m.find()) {
                String pid = m.group(1);
                long t = System.currentTimeMillis();
                String plain = pid + "-" + t;
                String md5 = Util.MD5(plain);
                String sg = aesEcbEncrypt(plain, md5.substring(0, 16));
                logger("提取到 PID: " + pid + " 签名: " + sg);
            }

            // 兜底交由壳子内置播放器嗅探
            logger("交由播放器嗅探处理: " + playUrl);
            return Result.get().parse(1).url(playUrl).string();
        } catch (Exception e) {
            logger("playerContent 异常: " + e.getMessage());
            return Result.get().parse(1).url(id).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        if (TextUtils.isEmpty(key)) return Result.string(new ArrayList<>());
        try {
            String searchUrl = "https://kwyili.dpdns.org/bdys.php?q=" + URLEncoder.encode(key, "UTF-8");
            logger("搜索请求 URL: " + searchUrl);

            String jsonStr = OkHttp.string(searchUrl, getHeaders());
            if (TextUtils.isEmpty(jsonStr)) {
                logger("⚠️ 搜索接口返回数据为空");
                return Result.string(new ArrayList<>());
            }

            logger("搜索返回原文: " + jsonStr);
            JSONArray jsonArray = new JSONArray(jsonStr);
            List<Vod> resultList = new ArrayList<>();

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject item = jsonArray.getJSONObject(i);
                String title = item.optString("title");
                String image = item.optString("image");
                String href = item.optString("href");

                Vod vod = new Vod();
                vod.setVodId(href);
                vod.setVodName(title);
                vod.setVodPic(image);
                vod.setVodRemarks("");
                resultList.add(vod);
            }

            logger("搜索成功解析出 " + resultList.size() + " 条记录");
            return Result.string(resultList);
        } catch (Exception e) {
            logger("searchContent 异常: " + e.getMessage());
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
