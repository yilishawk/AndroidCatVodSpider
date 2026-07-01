package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.AESEncryption;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.HashMap;
import java.util.List;

public class FKTV extends Spider {

    private static final String HOST = "https://fktv.me";
    private static final String API = "https://fktv.me/ysapi/movie/detail";

    private static final String AES_KEY = "39656431613636316136616237383761";

    private final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36";

    @Override
    public void init(Context context, String extend) {
    }

    // =========================
    // 首页分类
    // =========================
    @Override
    public String homeContent(boolean filter) throws Exception {

        JSONArray classes = new JSONArray();

        classes.put(new JSONObject().put("type_id", "5").put("type_name", "连续剧"));
        classes.put(new JSONObject().put("type_id", "6").put("type_name", "电影"));
        classes.put(new JSONObject().put("type_id", "4").put("type_name", "综艺"));
        classes.put(new JSONObject().put("type_id", "9").put("type_name", "短剧"));

        return new JSONObject()
                .put("class", classes)
                .toString();
    }

    // =========================
    // 分类列表
    // =========================
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {

        String url = HOST + "/category/" + tid + "/page/" + pg;

        Document doc = Jsoup.connect(url)
                .userAgent(UA)
                .get();

        Elements items = doc.select("div.item-wrap");

        JSONArray list = new JSONArray();

        for (Element it : items) {

            Element a = it.selectFirst("a[href]");
            Element img = it.selectFirst("img");

            if (a == null) continue;

            JSONObject vod = new JSONObject();

            vod.put("vod_id", HOST + a.attr("href"));
            vod.put("vod_name", a.attr("title"));
            vod.put("vod_pic", img != null ? img.attr("src") : "");

            list.put(vod);
        }

        return new JSONObject()
                .put("list", list)
                .toString();
    }

    // =========================
    // 详情页（TVBox标准拼接）
    // =========================
    @Override
    public String detailContent(List<String> ids) throws Exception {

        String url = ids.get(0);

        Document doc = Jsoup.connect(url)
                .userAgent(UA)
                .get();

        String json = doc.select("script[type=application/ld+json]").html();
        JSONObject ld = new JSONObject(json);

        String id = url.split("/movie/")[1].split("/")[0];

        String firstUrl = ld.optString("embedUrl");

        String linkId = ""; // 实际站点需解析 JS，这里占位

        StringBuilder play = new StringBuilder();

        play.append("线路1$$");
        play.append("1$").append(firstUrl);
        play.append("#2$fktv://api:").append(id).append(":").append(linkId);

        JSONObject vod = new JSONObject();
        vod.put("vod_id", url);
        vod.put("vod_name", ld.optString("name"));
        vod.put("vod_pic", ld.optJSONArray("thumbnailUrl").optString(0));
        vod.put("vod_content", ld.optString("description"));
        vod.put("vod_play_from", "线路1");
        vod.put("vod_play_url", play.toString());

        return new JSONObject()
                .put("list", new JSONArray().put(vod))
                .toString();
    }

    // =========================
    // 播放解析（核心）
    // =========================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {

        // ===== 直链 =====
        if (id.startsWith("http")) {
            return new JSONObject()
                    .put("url", id)
                    .put("parse", 0)
                    .put("header", new JSONObject().put("User-Agent", UA))
                    .toString();
        }

        // ===== API模式 =====
        String[] p = id.split(":");

        JSONObject data = new JSONObject();
        data.put("id", p[1]);
        data.put("link_id", p[2]);
        data.put("is_simple", "y");

        JSONObject req = new JSONObject();
        req.put("deviceId", "ffFrmAfy2sx5C6mSrTwX08bpi2YWn48t");
        req.put("token", "");
        req.put("domain", "fktv.me");
        req.put("data", data);

        // =========================
        // ★ 关键：使用官方 AESEncryption
        // =========================
        byte[] enc = AESEncryption.encrypt(req.toString(), AES_KEY);

        String resp = OkHttp.post(API, enc, new HashMap<String, String>() {{
            put("content-type", "application/octet-stream");
            put("user-agent", UA);
            put("origin", HOST);
            put("referer", HOST + "/");
        }});

        String url = parse(resp);

        return new JSONObject()
                .put("url", url)
                .put("parse", 0)
                .put("header", new JSONObject().put("User-Agent", UA))
                .toString();
    }

    // =========================
    // 解析返回
    // =========================
    private String parse(String resp) {
        if (resp == null) return "";
        if (resp.contains("m3u8")) {
            int s = resp.indexOf("http");
            int e = resp.indexOf(".m3u8") + 5;
            if (s >= 0 && e > s) return resp.substring(s, e);
        }
        return resp;
    }
}
