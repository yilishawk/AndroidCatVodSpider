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

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YXHAccess extends Spider {

    private static final String HOST = "https://zh.xhaccess.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final LinkedHashMap<String, String> CATEGORY_MAP = new LinkedHashMap<String, String>();

    private boolean unlocked = false;

    static {
        CATEGORY_MAP.put("亞洲", "asian");
        CATEGORY_MAP.put("日本", "japanese");
        CATEGORY_MAP.put("中國", "chinese");
        CATEGORY_MAP.put("韓國", "korean");
        CATEGORY_MAP.put("歐美", "western");
        CATEGORY_MAP.put("女優", "pornstar");
        CATEGORY_MAP.put("素人", "amateur");
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("User-Agent", UA);
        headers.put("Referer", HOST + "/");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
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
    public void init(Context context, String extend) {
        try {
            super.init(context, extend);
        } catch (Exception e) {
            // 捕獲受檢異常以符合 Java 8 繼承規範
        }
        
        // 觸發門禁解鎖
        this.unlocked = PasswordGate.ensureUnlocked(context);
    }

    @Override
    public String homeContent(boolean filter) {
        if (!unlocked) {
            return Result.string(new ArrayList<Class>(), new ArrayList<Vod>());
        }

        try {
            List<Class> classes = new ArrayList<Class>();
            for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
                classes.add(new Class(entry.getValue(), entry.getKey()));
            }

            return Result.string(classes, new ArrayList<Vod>());
        } catch (Exception e) {
            return Result.string(new ArrayList<Class>(), new ArrayList<Vod>());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        if (!unlocked) return Result.string(new ArrayList<Vod>());

        try {
            int page = Integer.parseInt(pg);
            // 分頁邏輯：第 1 頁為 /categories/asian，後續頁為 /categories/asian/2
            String url;
            if (page <= 1) {
                url = HOST + "/categories/" + tid;
            } else {
                url = HOST + "/categories/" + tid + "/" + page;
            }

            String html = get(url);
            List<Vod> list = parseVodList(html);

            return Result.string(list);
        } catch (Exception e) {
            return Result.string(new ArrayList<Vod>());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        if (!unlocked) return Result.string(new ArrayList<Vod>());

        try {
            String rawId = ids.get(0);
            String detailUrl = rawId.startsWith("http") ? rawId : HOST + rawId;
            String html = get(detailUrl);

            if (TextUtils.isEmpty(html)) {
                return Result.string(new ArrayList<Vod>());
            }

            Document doc = Jsoup.parse(html);
            
            // 提取標題
            String title = "";
            Element titleEl = doc.selectFirst("h1");
            if (titleEl != null) {
                title = titleEl.text().trim();
            }

            // 提取封面
            String pic = "";
            Element imgEl = doc.selectFirst("meta[property=og:image]");
            if (imgEl != null) {
                pic = imgEl.attr("content");
            }

            Vod vod = new Vod();
            vod.setVodId(rawId);
            vod.setVodName(TextUtils.isEmpty(title) ? "XHAccess Video" : title);
            vod.setVodPic(pic);
            vod.setVodPlayFrom("XHAccess直連");
            vod.setVodPlayUrl("播放$" + detailUrl);

            return Result.string(vod);
        } catch (Exception e) {
            return Result.string(new ArrayList<Vod>());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (!unlocked) return Result.get().string();

        try {
            String playPageUrl = id.startsWith("http") ? id : HOST + id;
            String html = get(playPageUrl);

            String realPlayUrl = "";

            // 正則精準提取源碼中的 preload .m3u8 播放直鏈
            // 匹配範例: <link rel="preload" href="(https://.*?\.m3u8)" as="fetch"
            Pattern pattern = Pattern.compile("<link\\s+rel=\"preload\"\\s+href=\"([^\"]+\\.m3u8[^\"]*)\"\\s+as=\"fetch\"", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(html);

            if (matcher.find()) {
                realPlayUrl = matcher.group(1);
            } else {
                // 降級通用匹配: 任意 .m3u8 鏈接
                Pattern pBackup = Pattern.compile("(https?://[^\"]+\\.m3u8[^\"]*)", Pattern.CASE_INSENSITIVE);
                Matcher mBackup = pBackup.matcher(html);
                if (mBackup.find()) {
                    realPlayUrl = mBackup.group(1);
                }
            }

            Map<String, String> headers = new HashMap<String, String>();
            headers.put("User-Agent", UA);
            headers.put("Referer", HOST + "/");

            return Result.get().url(realPlayUrl).header(headers).string();
        } catch (Exception e) {
            return Result.get().parse(1).url(id).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (!unlocked) return Result.string(new ArrayList<Vod>());

        if (TextUtils.isEmpty(key)) return Result.string(new ArrayList<Vod>());
        try {
            String url = HOST + "/search/" + URLEncoder.encode(key, "UTF-8");
            String html = get(url);

            List<Vod> list = parseVodList(html);
            return Result.string(list);
        } catch (Exception e) {
            return Result.string(new ArrayList<Vod>());
        }
    }

    /**
     * 解析列表頁中的視頻項目
     */
    private List<Vod> parseVodList(String html) {
        List<Vod> list = new ArrayList<Vod>();
        if (TextUtils.isEmpty(html)) return list;

        try {
            Document doc = Jsoup.parse(html);
            Elements items = doc.select("div.thumb-list__item, div.video-thumb");

            for (Element item : items) {
                // 提取鏈接與標題
                Element aTag = item.selectFirst("a.video-thumb__image-container, a[data-role=thumb-link]");
                if (aTag == null) aTag = item.selectFirst("a[href*=/videos/]");
                if (aTag == null) continue;

                String href = aTag.attr("href");
                String title = aTag.attr("aria-label");
                if (TextUtils.isEmpty(title)) {
                    title = aTag.attr("title");
                }

                // 提取封面圖片
                Element imgTag = item.selectFirst("img.thumb-image-container__image");
                String pic = "";
                if (imgTag != null) {
                    pic = imgTag.hasAttr("src") ? imgTag.attr("src") : imgTag.attr("data-src");
                }

                // 提取片長
                Element durTag = item.selectFirst("div.thumb-image-container__duration, div.tiny-fda7e");
                String duration = durTag != null ? durTag.text().trim() : "";

                if (TextUtils.isEmpty(href)) continue;

                Vod vod = new Vod();
                vod.setVodId(href);
                vod.setVodName(TextUtils.isEmpty(title) ? "Video" : title);
                vod.setVodPic(pic);
                vod.setVodRemarks(duration);
                list.add(vod);
            }
        } catch (Exception e) {
            // 静默处理解析异常
        }
        return list;
    }
}
