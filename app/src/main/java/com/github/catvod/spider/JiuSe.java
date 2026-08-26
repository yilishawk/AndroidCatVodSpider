package com.github.catvod.spider;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
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

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JiuSe extends Spider {

    private static final String HOST = "https://dsc.jiuse20.asia";
    private static final String UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.3 Mobile/15E148 Safari/604.1";
    private static final String VOD_SOURCE = "九色云播";
    private static final LinkedHashMap<String, String> CATEGORY_MAP = new LinkedHashMap<>();

    private Context appContext;

    static {
        CATEGORY_MAP.put("舔逼", "舔逼");
        CATEGORY_MAP.put("双洞", "三人");
        CATEGORY_MAP.put("自慰", "自慰");
        CATEGORY_MAP.put("群交", "群交");
        CATEGORY_MAP.put("肛交", "肛交");
        CATEGORY_MAP.put("家庭摄像头", "家庭摄像头");
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
    public void init(Context context, String extend) {
        super.init(context, extend);
        this.appContext = context;
    }

    private boolean checkGate() {
        if (appContext == null) return true;
        return PasswordGate.ensureUnlocked(appContext);
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            List<Class> classes = new ArrayList<>();
            for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
                classes.add(new Class(entry.getValue(), entry.getKey()));
            }

            if (!checkGate()) {
                return Result.string(new ArrayList<>(), new ArrayList<>());
            }

            // 只返回分类列表，不返回推荐列表，极大加快进入应用和加载主页的速度
            return Result.string(classes, new ArrayList<>());
        } catch (Exception e) {
            return Result.string(new ArrayList<>(), new ArrayList<>());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        if (!checkGate()) return Result.string(new ArrayList<>());

        try {
            String keyword = tid;
            String displayType = "三人".equals(tid) ? "双洞" : tid;
            String url = HOST + "/search?keywords=" + URLEncoder.encode(keyword, "UTF-8") + "&page=" + pg;

            String html = get(url);
            List<Vod> list = parseVodListWithJsoup(html, displayType);

            return Result.string(list);
        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        if (!checkGate()) return Result.string(new ArrayList<>());

        try {
            String rawId = ids.get(0);
            String url = rawId.startsWith("http") ? rawId : HOST + rawId;

            Uri uri = Uri.parse(url);
            String vName = uri.getQueryParameter("name");
            if (TextUtils.isEmpty(vName)) vName = "未知标题";

            String html = get(url);
            if (TextUtils.isEmpty(html)) {
                return Result.string(new ArrayList<>());
            }

            // 使用字符串切片替代正则过滤，提速明显
            String startStr = "$avdt = ";
            String endStr = "</script>";
            int startPos = html.indexOf(startStr);

            if (startPos != -1) {
                startPos += startStr.length();
                int endPos = html.indexOf(endStr, startPos);
                if (endPos != -1) {
                    String jsonRaw = html.substring(startPos, endPos).trim();
                    if (jsonRaw.endsWith(";")) {
                        jsonRaw = jsonRaw.substring(0, jsonRaw.length() - 1).trim();
                    }

                    JSONObject data = new JSONObject(jsonRaw);
                    if (data.has("hls")) {
                        String hlsPath = data.getString("hls").replace("\\/", "/");
                        JSONArray cdns = data.optJSONArray("cdns");

                        List<String> playLines = new ArrayList<>();
                        if (cdns != null && cdns.length() > 0) {
                            for (int i = 0; i < cdns.length(); i++) {
                                String domain = cdns.getString(i).replaceAll("^/|/$", "");
                                String fullUrl = "https://" + domain + hlsPath;
                                playLines.add("线路" + (i + 1) + "$" + fullUrl);
                            }
                        }

                        Vod vod = new Vod();
                        vod.setVodId(rawId);
                        vod.setVodName(vName);
                        vod.setVodPlayFrom(VOD_SOURCE);
                        vod.setVodPlayUrl(TextUtils.join("#", playLines));

                        return Result.string(vod);
                    }
                }
            }
            return Result.string(new ArrayList<>());
        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (!checkGate()) return Result.get().string();

        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Referer", HOST + "/");

            return Result.get().url(id).header(headers).string();
        } catch (Exception e) {
            return Result.get().parse(1).url(id).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        if (!checkGate()) return Result.string(new ArrayList<>());

        if (TextUtils.isEmpty(key)) return Result.string(new ArrayList<>());
        try {
            String url = HOST + "/search?keywords=" + URLEncoder.encode(key, "UTF-8") + "&page=1";
            String html = get(url);

            List<Vod> list = parseVodListWithJsoup(html, "搜索:" + key);
            return Result.string(list);
        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }

    /**
     * 改用 Jsoup 进行 DOM 解析，效率大幅提升，避免正则死循环回溯
     */
    private List<Vod> parseVodListWithJsoup(String html, String displayType) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return list;

        try {
            Document doc = Jsoup.parse(html);
            // 根据 class 结构定位视频卡片容器
            Elements items = doc.select("a.card-image, div.card-image a");
            if (items.isEmpty()) {
                items = doc.select("a[href*=/view/]"); // 兜底选择器
            }

            for (Element item : items) {
                String link = item.attr("href");
                Element img = item.selectFirst("img");
                Element durationEl = item.selectFirst("span.duration");

                String vTitle = img != null ? img.attr("alt") : "";
                String pic = img != null ? img.attr("src") : "";
                String duration = durationEl != null ? durationEl.text() : "";

                if (TextUtils.isEmpty(link) || TextUtils.isEmpty(vTitle)) continue;

                String sep = link.contains("?") ? "&" : "?";
                String jumpId;
                try {
                    jumpId = HOST + link + sep + "name=" + URLEncoder.encode(vTitle, "UTF-8") + "&type=" + URLEncoder.encode(displayType, "UTF-8");
                } catch (Exception e) {
                    jumpId = HOST + link;
                }

                Vod vod = new Vod();
                vod.setVodId(jumpId);
                vod.setVodName(vTitle);
                vod.setVodPic(pic);
                vod.setVodRemarks(duration);
                list.add(vod);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }
}
