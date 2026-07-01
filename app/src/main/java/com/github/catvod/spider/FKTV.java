package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.AESEncryption;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FKTV extends Spider {

    private static final String HOST = "https://fktv.me";
    private static final String API = "https://fktv.me/ysapi/movie/detail";

    private static final String AES_KEY = "39656431613636316136616237383761";

    private final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36";

    @Override
    public void init(Context context, String extend) {}

    // =========================
    // 分类
    // =========================
    @Override
    public String homeContent(boolean filter) throws Exception {

        JSONArray classes = new JSONArray();

        classes.put(new JSONObject().put("type_id", "5").put("type_name", "连续剧"));
        classes.put(new JSONObject().put("type_id", "6").put("type_name", "电影"));

        return new JSONObject()
                .put("class", classes)
                .toString();
    }

    // =========================
    // 列表
    // =========================
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {

        Document doc = Jsoup.connect(HOST + "/category/" + tid + "/page/" + pg)
                .userAgent(UA)
                .get();

        JSONArray list = new JSONArray();

        for (org.jsoup.nodes.Element e : doc.select("div.item-wrap")) {

            org.jsoup.nodes.Element a = e.selectFirst("a[href]");
            org.jsoup.nodes.Element img = e.selectFirst("img");

            if (a == null) continue;

            JSONObject vod = new JSONObject();
            vod.put("vod_id", HOST + a.attr("href"));
            vod.put("vod_name", a.attr("title"));
            vod.put("vod_pic", img != null ? img.attr("src") : "");

            list.put(vod);
        }

        return new JSONObject().put("list", list).toString();
    }

    // =========================
    // 详情
    // =========================
    @Override
    public String detailContent(List<String> ids) throws Exception {

        String url = ids.get(0);

        Document doc = Jsoup.connect(url)
                .userAgent(UA)
                .get();

        String id = url.split("/movie/")[1].split("/")[0];

        JSONObject data = new JSONObject();
        data.put("id", id);

        JSONObject req = new JSONObject();
        req.put("deviceId", "ffFrmAfy2sx5C6mSrTwX08bpi2YWn48t");
        req.put("domain", "fktv.me");
        req.put("data", data);

        // =========================
        // ✔ multiThread AES正确调用
        // =========================
        byte[] enc = AESEncryption.encrypt(
                req.toString(),
                AES_KEY,
                "",
                ""
        );

        // =========================
        // ✔ Base64转换（关键修复点）
        // =========================
        String body = Base64.encodeToString(enc, Base64.NO_WRAP);

        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/octet-stream");
        headers.put("user-agent", UA);
        headers.put("origin", HOST);
        headers.put("referer", HOST + "/");

        String resp = OkHttp.post(API, body, headers);

        JSONObject obj = new JSONObject(resp);

        JSONArray links = obj.getJSONArray("links");

        StringBuilder sb = new StringBuilder();
        sb.append("线路1$$");

        for (int i = 0; i < links.length(); i++) {

            JSONObject ep = links.getJSONObject(i);

            sb.append(ep.getString("name"))
              .append("$fktv://")
              .append(ep.getString("id"))
              .append("#");
        }

        if (sb.toString().endsWith("#")) {
            sb.setLength(sb.length() - 1);
        }

        JSONObject vod = new JSONObject();
        vod.put("vod_id", url);
        vod.put("vod_name", obj.optString("name"));
        vod.put("vod_play_from", "线路1");
        vod.put("vod_play_url", sb.toString());

        return new JSONObject()
                .put("list", new JSONArray().put(vod))
                .toString();
    }

    // =========================
    // 播放
    // =========================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {

        if (id.startsWith("http")) {
            return new JSONObject()
                    .put("url", id)
                    .put("parse", 0)
                    .put("header", new JSONObject().put("User-Agent", UA))
                    .toString();
        }

        String epId = id.replace("fktv://", "");

        JSONObject data = new JSONObject();
        data.put("link_id", epId);

        JSONObject req = new JSONObject();
        req.put("deviceId", "ffFrmAfy2sx5C6mSrTwX08bpi2YWn48t");
        req.put("domain", "fktv.me");
        req.put("data", data);

        byte[] enc = AESEncryption.encrypt(
                req.toString(),
                AES_KEY,
                "",
                ""
        );

        String body = Base64.encodeToString(enc, Base64.NO_WRAP);

        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/octet-stream");
        headers.put("user-agent", UA);

        String resp = OkHttp.post(API, body, headers);

        JSONObject obj = new JSONObject(resp);

        JSONObject play = obj.getJSONArray("play_links").getJSONObject(0);

        return new JSONObject()
                .put("url", play.getString("m3u8_url"))
                .put("parse", 0)
                .put("header", new JSONObject().put("User-Agent", UA))
                .toString();
    }
}
