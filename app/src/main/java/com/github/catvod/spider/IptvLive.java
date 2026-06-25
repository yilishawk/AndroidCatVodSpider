package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class IptvLive extends Spider {

    private static final String HOST     = "https://jzbv6d9m.tjpanshan.com";
    private static final String API_LIST = HOST + "/prod-api/iptv/getIptvList";
    private static final String LOGO     = "https://upload.112114.xyz/logo/";

    private static final String[][] CHANNELS = {
            {"1", "央视"},
            {"2", "卫视"},
    };

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.6788.76 Safari/537.36");
        h.put("Referer",    HOST + "/tvs");
        return h;
    }

    private List<Vod> fetchVods(String cateId) throws Exception {
        String    url  = API_LIST + "?liveType=" + cateId + "&deviceType=1";
        String    resp = OkHttp.string(url, headers());
        JSONArray list = new JSONObject(resp).getJSONArray("list");
        List<Vod> vods = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o    = list.getJSONObject(i);
            String     name = o.optString("play_source_name");
            String     m3u8 = o.optString("play_source_url");
            if (name.isEmpty() || m3u8.isEmpty()) continue;
            Vod vod = new Vod();
            vod.setVodId(m3u8);                          // 直播地址即 ID
            vod.setVodName(name);
            vod.setVodPic(LOGO + name + ".png");
            vod.setVodPlayFrom("直播");
            vod.setVodPlayUrl(name + "$" + m3u8);        // 单集，集名=频道名
            vods.add(vod);
        }
        return vods;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        for (String[] ch : CHANNELS) classes.add(new Class(ch[0], ch[1]));

        // 首页展示央视频道列表
        List<Vod> vods = fetchVods("1");

        return Result.get()
                .classes(classes)
                .filters(new LinkedHashMap<>())
                .vod(vods)
                .string();
    }

    @Override
    public String homeVideoContent() throws Exception {
        return Result.get().vod(fetchVods("1")).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        List<Vod> vods = fetchVods(tid);
        return Result.get()
                .page(1, 1, vods.size(), vods.size())
                .vod(vods)
                .string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        // id 就是 m3u8 地址，无需额外请求
        String m3u8 = ids.get(0);
        Vod vod = new Vod();
        vod.setVodId(m3u8);
        vod.setVodPlayFrom("直播");
        vod.setVodPlayUrl("播放$" + m3u8);
        return Result.get().vod(vod).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // id 直接就是 m3u8 地址
        Map<String, String> h = headers();
        return Result.get().url(id).header(h).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return Result.get().vod(new ArrayList<>()).string();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return Result.get().vod(new ArrayList<>()).string();
    }
}
