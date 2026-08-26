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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JiuSe extends Spider {

    private static final String HOST = "https://dsc.jiuse20.asia";
    private static final String UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.3 Mobile/15E148 Safari/604.1";
    private static final String VOD_SOURCE = "九色云播";
    private static final LinkedHashMap<String, String> CATEGORY_MAP = new LinkedHashMap<>();

    static {
        CATEGORY_MAP.put("舔逼", "舔逼");
        CATEGORY_MAP.put("双洞", "三人"); // 兼容 PHP 中的映射逻辑
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
    public String homeContent(boolean filter) {
        try {
            List<Class> classes = new ArrayList<>();
            for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
                classes.add(new Class(entry.getValue(), entry.getKey()));
            }
            return Result.string(classes, new ArrayList<>());
        } catch (Exception e) {
            return Result.string(new ArrayList<>(), new ArrayList<>());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            String keyword = tid;
            String displayType = "三人".equals(tid) ? "双洞" : tid;
            String url = HOST + "/search?keywords=" + URLEncoder.encode(keyword, "UTF-8") + "&page=" + pg;
            
            String html = get(url);
            List<Vod> list = parseVodList(html, displayType);
            
            return Result.string(list);
        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try {
            String rawId = ids.get(0);
            String url = rawId.startsWith("http") ? rawId : HOST + rawId;

            // 从带参数的 URL 中提取预传的 name 与 type
            Uri uri = Uri.parse(url);
            String vName = uri.getQueryParameter("name");
            if (TextUtils.isEmpty(vName)) vName = "未知标题";

            String html = get(url);
            if (TextUtils.isEmpty(html)) {
                return Result.string(new ArrayList<>());
            }

            // 匹配字符串: $avdt = { ... };
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
        try {
            // 匹配到的播放链接为直接可播的 m3u8，直接追加 Request Headers 返回
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
        if (TextUtils.isEmpty(key)) return Result.string(new ArrayList<>());
        try {
            String url = HOST + "/search?keywords=" + URLEncoder.encode(key, "UTF-8") + "&page=1";
            String html = get(url);

            List<Vod> list = parseVodList(html, "搜索:" + key);
            return Result.string(list);
        } catch (Exception e) {
            return Result.string(new ArrayList<>());
        }
    }

    /**
     * 对应 PHP 中的正则匹配解析逻辑
     */
    private List<Vod> parseVodList(String html, String displayType) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return list;

        // 原 PHP 正则：card-image.*?href="(.*?)".*?alt="(.*?)".*?src="(.*?)".*?duration">(.*?)<\/span>
        Pattern pattern = Pattern.compile("card-image.*?href=\"(.*?)\".*?alt=\"(.*?)\".*?src=\"(.*?)\".*?duration\">(.*?)<\\/span>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);

        while (matcher.find()) {
            String link = matcher.group(1).trim();
            String vTitle = matcher.group(2).trim();
            String pic = matcher.group(3).trim();
            String duration = matcher.group(4).trim();

            // 拼接自定义 query 参数，与 PHP parse_str 传递参数逻辑一致
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
        return list;
    }
}
