package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.AESEncryption;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.HashMap;
import java.util.List;

public class FKTV extends Spider {

    private static final String HOST = "https://fktv.me";
    private static final String API = "https://fktv.me/ysapi/movie/detail";
    private static final String AES_KEY = "39656431613636316136616237383761";

    private final String UA = "Mozilla/5.0 Chrome/120 Safari/537.36";

    @Override
    public void init(Context context, String extend) {}

    // =========================
    // 分类
    // =========================
    @Override
    public String homeContent(boolean filter) throws Exception {

        JSONArray c = new JSONArray();
        c.put(new JSONObject().put("type_id", "5").put("type_name", "连续剧"));
        c.put(new JSONObject().put("type_id", "6").put("type_name", "电影"));

        return new JSONObject().put("class", c).toString();
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

            JSONObject vod = new JSONObject();

            vod.put("vod_id", HOST + a.attr("href"));
            vod.put("vod_name", a.attr("title"));
            vod.put("vod_pic", img != null ? img.attr("src") : "");

            list.put(vod);
        }

        return new JSONObject().put("list", list).toString();
    }

    // =========================
    // 详情（🔥关键：自动 links → TVBox格式）
    // =========================
    @Override
    public String detailContent(List<String> ids) throws Exception {

        String url = ids.get(0);

        Document doc = Jsoup.connect(url).userAgent(UA).get();

        String json = doc.select("script[type=application/ld+json]").html();
        JSONObject ld = new JSONObject(json);

        String id = url.split("/movie/")[1].split("/")[0];

        // =========================
        // 关键：请求真实 detail API
        // =========================
        JSONObject req = new JSONObject();
        req.put("deviceId", "ffFrmAfy2sx5C6mSrTwX08bpi2YWn48t");
        req.put("domain", "fktv.me");
        req.put("data", new JSONObject().put("id", id));

        byte[] enc = AESEncryption.encrypt(req.toString(), AES_KEY);

        String resp = OkHttp.post(API, enc, new HashMap<String, String>() {{
            put("content-type", "application/octet-stream");
            put("user-agent", UA);
        }});

        JSONObject detail = new JSONObject(resp);

        JSONArray links = detail.getJSONArray("links");

        // =========================
        // 🔥 自动生成 TVBox播放列表
        // =========================
        StringBuilder sb = new StringBuilder();
        sb.append("线路1$$");

        for (int i = 0; i < links.length(); i++) {

            JSONObject ep = links.getJSONObject(i);

            String name = ep.getString("name"); // 第几集
            String epId = ep.getString("id");    // 关键ID

            sb.append(name)
              .append("$fktv://")
              .append(epId)
              .append("#");
        }

        if (sb.toString().endsWith("#")) {
            sb.setLength(sb.length() - 1);
        }

        JSONObject vod = new JSONObject();

        vod.put("vod_id", url);
        vod.put("vod_name", detail.optString("name"));
        vod.put("vod_pic", ld.optJSONArray("thumbnailUrl").optString(0));
        vod.put("vod_content", detail.optString("description"));

        vod.put("vod_play_from", "线路1");
        vod.put("vod_play_url", sb.toString());

        return new JSONObject().put("list", new JSONArray().put(vod)).toString();
    }

    // =========================
    // 播放
    // =========================
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {

        // 直链
        if (id.startsWith("http")) {
            return new JSONObject()
                    .put("url", id)
                    .put("parse", 0)
                    .put("header", new JSONObject().put("User-Agent", UA))
                    .toString();
        }

        // =========================
        // fktv://episodeId
        // =========================
        String epId = id.replace("fktv://", "");

        JSONObject req = new JSONObject();
        req.put("deviceId", "ffFrmAfy2sx5C6mSrTwX08bpi2YWn48t");
        req.put("domain", "fktv.me");
        req.put("data", new JSONObject().put("link_id", epId));

        byte[] enc = AESEncryption.encrypt(req.toString(), AES_KEY);

        String resp = OkHttp.post(API, enc, new HashMap<>());

        JSONObject obj = new JSONObject(resp);

        // 默认线路
        JSONObject play = obj.getJSONArray("play_links").getJSONObject(0);

        return new JSONObject()
                .put("url", play.getString("m3u8_url"))
                .put("parse", 0)
                .put("header", new JSONObject().put("User-Agent", UA))
                .toString();
    }
}
