package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SiniTV extends Spider {

    private static final String HOST = "https://sinitv.cc";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final LinkedHashMap<String, String> CATEGORY_MAP = new LinkedHashMap<>();

    static {
        CATEGORY_MAP.put("电视剧", "1");
        CATEGORY_MAP.put("电影", "2");
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", HOST + "/");
        return headers;
    }

    private String get(String url) {
        try {
            return OkHttp.string(url, getHeaders());
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            List<Class> classes = new ArrayList<>();
            for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
                classes.add(new Class(entry.getValue(), entry.getKey()));
            }

            // 获取首页视频数据
            String html = get(HOST);
            List<Vod> vodList = parseVodList(html);

            // 参考 Czzyv 标准返回：Result.string(classes, vodList)
            return Result.string(classes, vodList);
        } catch (Exception e) {
            return Result.string(new ArrayList<>(), new ArrayList<>());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            int page = Integer.parseInt(pg);
            String url = HOST + "/vodshow/" + tid + "--------" + page + "---.html";
            String html = get(url);

            List<Vod> list = parseVodList(html);
            return Result.string(list);
        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String vodId = ids.get(0);
            String url = vodId.startsWith("http") ? vodId : HOST + vodId;
            String html = get(url);

            if (TextUtils.isEmpty(html)) {
                return Result.string(new ArrayList<>());
            }

            Document doc = Jsoup.parse(html);
            
            // 标题
            String name = "";
            Element titleElem = doc.selectFirst(".this-desc-title");
            if (titleElem != null) name = titleElem.text().trim();

            // 图片
            String pic = "";
            Element imgElem = doc.selectFirst(".this-pic-bj img");
            if (imgElem != null) pic = imgElem.attr("src").trim();

            // 演员
            String actor = "";
            Element actorElem = doc.selectFirst(".this-info");
            if (actorElem != null) {
                actor = actorElem.text().replace("Pemeran:", "").trim();
            }

            // 简介
            String content = "";
            Element descElem = doc.selectFirst("#height_limit");
            if (descElem != null) {
                content = descElem.text().replace("Deskripsi:", "").trim();
            }

            // 解析播放线路名称与播放列表
            List<String> playFromList = new ArrayList<>();
            Elements fromElems = doc.select(".anthology-tab .swiper-slide");
            for (Element from : fromElems) {
                String fromName = from.text().trim();
                if (!TextUtils.isEmpty(fromName)) {
                    playFromList.add(fromName);
                }
            }
            if (playFromList.isEmpty()) {
                playFromList.add("XP");
            }

            List<String> playGroupList = new ArrayList<>();
            Elements listBoxes = doc.select(".anthology-list-play");
            for (Element box : listBoxes) {
                Elements links = box.select("a");
                List<String> epList = new ArrayList<>();
                for (Element link : links) {
                    String epName = link.text().trim();
                    String epHref = link.attr("href").trim();
                    epList.add(epName + "$" + epHref);
                }
                playGroupList.add(TextUtils.join("#", epList));
            }

            Vod vod = new Vod();
            vod.setVodId(vodId);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodActor(actor);
            vod.setVodContent(content);
            vod.setVodPlayFrom(TextUtils.join("$$$", playFromList));
            vod.setVodPlayUrl(TextUtils.join("$$$", playGroupList));

            return Result.string(vod);
        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String playPageUrl = id.startsWith("http") ? id : HOST + id;
            String html = get(playPageUrl);

            String realPlayUrl = "";
            // 解析 player_aaaa 中的 m3u8 地址
            Pattern pUrl = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher mUrl = pUrl.matcher(html);
            if (mUrl.find()) {
                realPlayUrl = mUrl.group(1).replace("\\/", "/");
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Referer", HOST + "/");

            return Result.get().url(realPlayUrl).header(headers).string();
        } catch (Exception e) {
            return Result.get().parse(1).url(id).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (TextUtils.isEmpty(key)) return Result.string(new ArrayList<>());
        try {
            String url = HOST + "/vodsearch/-" + key + "------------.html";
            String html = get(url);

            List<Vod> list = parseVodList(html);
            return Result.string(list);
        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }

    /**
     * 针对 SiniTV 专门提取列表数据的解析逻辑
     */
    private List<Vod> parseVodList(String html) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return list;

        Document doc = Jsoup.parse(html);
        Elements items = doc.select(".public-list-box");

        for (Element item : items) {
            Element link = item.selectFirst("a.public-list-exp");
            if (link == null) link = item.selectFirst("a");
            if (link == null) continue;

            String href = link.attr("href").trim();
            if (TextUtils.isEmpty(href)) continue;

            String name = link.attr("title").trim();
            Element img = link.selectFirst("img");
            String pic = "";
            if (img != null) {
                pic = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
            }

            Element remarkElem = link.selectFirst(".public-list-prb");
            String remarks = remarkElem != null ? remarkElem.text().trim() : "";

            Vod vod = new Vod();
            vod.setVodId(href);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodRemarks(remarks);
            list.add(vod);
        }
        return list;
    }
}
