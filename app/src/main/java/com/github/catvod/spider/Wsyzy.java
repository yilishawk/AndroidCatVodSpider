package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class Wsyzy extends Spider {

    // 主接口：负责分类 + 列表 + 详情主线路
    private static final String MAIN_API = "https://api.wsyzy.net/api.php/provide/vod";

    // 副接口：按标题并发搜索补充线路，方便以后追加
    private static final String[] EXTRA_APIS = {
            "https://caiji.xgzyapi.com/api.php/provide/vod",
            "http://caiji.dyttzyapi.com/api.php/provide/vod/from/dyttm3u8"
    };

    // 主接口里这几个父分类查不到数据，homeContent 里过滤掉
    private static final Set<String> HIDE_TYPES = new HashSet<>(Arrays.asList("1", "2", "3", "4"));

    // 每个副接口的超时时间（秒）
    private static final int EXTRA_TIMEOUT = 3;

    @Override
    public String homeContent(boolean filter) throws Exception {
        JSONObject root = new JSONObject(OkHttp.string(MAIN_API + "?ac=list"));
        JSONArray classArr = root.optJSONArray("class");

        List<Class> classes = new ArrayList<>();
        if (classArr != null) {
            for (int i = 0; i < classArr.length(); i++) {
                JSONObject c = classArr.getJSONObject(i);
                String tid = c.optString("type_id");
                if (HIDE_TYPES.contains(tid)) continue;

                Class cls = new Class(tid, c.optString("type_name"));
                classes.add(cls);
            }
        }

        // 修复：对齐新版 Result 链式调用
        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        JSONObject root = new JSONObject(OkHttp.string(MAIN_API + "?ac=videolist&t=" + tid + "&pg=" + pg));
        JSONArray list = root.optJSONArray("list");

        List<Vod> vods = new ArrayList<>();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                vods.add(toVod(list.getJSONObject(i)));
            }
        }

        // 修复：对齐新版分页与列表链式调用
        return Result.get()
                .page(
                    root.optInt("page", 1),
                    root.optInt("pagecount", 1),
                    root.optInt("limit", 20), // 内部需要 int，转为 optInt 传递
                    root.optInt("total", vods.size())
                )
                .vod(vods)
                .string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        JSONObject root = new JSONObject(OkHttp.string(MAIN_API + "?ac=detail&ids=" + ids.get(0)));
        JSONArray list = root.optJSONArray("list");
        if (list == null || list.length() == 0) return Result.get().string();

        JSONObject item = list.getJSONObject(0);
        String title = item.optString("vod_name");

        Vod vod = new Vod();
        vod.setVodId(item.optString("vod_id"));
        vod.setVodName(title);
        vod.setVodPic(item.optString("vod_pic"));
        vod.setTypeName(item.optString("type_name"));
        vod.setVodYear(item.optString("vod_year"));
        vod.setVodArea(item.optString("vod_area"));
        vod.setVodDirector(item.optString("vod_director"));
        vod.setVodActor(item.optString("vod_actor"));
        vod.setVodRemarks(item.optString("vod_remarks"));
        vod.setVodContent(item.optString("vod_content"));

        // 主线路
        List<String> fromList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();
        splitPlay(item.optString("vod_play_from"), item.optString("vod_play_url"), fromList, urlList, null);

        // 并发查副接口，按标题匹配补充线路
        int poolSize = Math.max(1, EXTRA_APIS.length);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        List<Future<List<String[]>>> futures = new ArrayList<>();

        for (int i = 0; i < EXTRA_APIS.length; i++) {
            final int idx = i;
            futures.add(pool.submit((Callable<List<String[]>>) () -> searchExtra(idx, title)));
        }

        for (Future<List<String[]>> f : futures) {
            try {
                for (String[] pair : f.get(EXTRA_TIMEOUT, TimeUnit.SECONDS)) {
                    fromList.add(pair[0]);
                    urlList.add(pair[1]);
                }
            } catch (Exception e) {
                // 如果本地没有 Proxy.log 模块报错，可以换成 System.out.println
                System.out.println("⚠️ [多源] 副接口超时/失败: " + e.getMessage());
            }
        }
        pool.shutdownNow();

        vod.setVodPlayFrom(String.join("$$$", fromList));
        vod.setVodPlayUrl(String.join("$$$", urlList));

        List<Vod> resultVods = new ArrayList<>();
        resultVods.add(vod);

        // 修复：对齐 .vod() 并直接转为 JSON 字符串返回
        return Result.get().vod(resultVods).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        String encodeKey = URLEncoder.encode(key, "UTF-8");
        List<Vod> vods = new ArrayList<>();

        // 1. 尝试使用主接口进行搜索
        try {
            String url = SEARCH_API + "&wd=" + encodeKey;
            JSONObject root = new JSONObject(OkHttp.string(url));
            JSONArray list = root.optJSONArray("list");
            
            if (list != null && list.length() > 0) {
                for (int i = 0; i < list.length(); i++) {
                    vods.add(searchToVod(list.getJSONObject(i)));
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ [主接口] 搜索失败或超时，准备启用副接口补救: " + e.getMessage());
        }

        // 2. 判断：如果主接口失败或没搜到任何数据，则启用第一个副接口（EXTRA_APIS[0]）
        if (vods.isEmpty() && EXTRA_APIS.length > 0) {
            try {
                // 副接口通常是标准的采集口
                String url = EXTRA_APIS[0] + "?ac=videolist&wd=" + encodeKey;
                JSONObject root = new JSONObject(OkHttp.string(url));
                JSONArray list = root.optJSONArray("list");
                
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        vods.add(toVod(list.getJSONObject(i)));
                    }
                }
            } catch (Exception e) {
                System.out.println("❌ [备用副接口] 搜索也失败: " + e.getMessage());
            }
        }

        // 3. 返回最终的搜索结果（可能是主接口的，也可能是副接口的）
        return Result.get().vod(vods).string();
    }


    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 修复：对齐链式调用。旧版有 setPlayUrl，新版统一对齐成 .url(id) 即可
        return Result.get()
                .parse(0)
                .url(id)
                .string();
    }

    // ====================== 内部辅助方法 ======================

    private static Vod toVod(JSONObject item) {
        Vod vod = new Vod();
        vod.setVodId(item.optString("vod_id"));
        vod.setVodName(item.optString("vod_name"));
        vod.setVodPic(item.optString("vod_pic"));
        vod.setVodRemarks(item.optString("vod_remarks"));
        return vod;
    }

    // 拆分 vod_play_from / vod_play_url，过滤空项，可加前缀区分来源
    private static void splitPlay(String playFrom, String playUrl, List<String> fromOut, List<String> urlOut, String prefix) {
        if (playFrom == null || playUrl == null) return;

        String[] fromArr = playFrom.split("\\$\\$\\$");
        String[] urlArr = playUrl.split("\\$\\$\\$");
        int n = Math.min(fromArr.length, urlArr.length);

        for (int i = 0; i < n; i++) {
            String from = fromArr[i].trim();
            String url = urlArr[i].trim();
            if (from.isEmpty() || url.isEmpty()) continue;
            fromOut.add(prefix == null ? from : prefix + from);
            urlOut.add(url);
        }
    }

    // 标题标准化：去空格、去掉末尾 (年份) 之类括号注释、转小写
    private static String normalize(String name) {
        if (name == null) return "";
        String s = name.trim();
        s = s.replaceAll("[\\s　]+", "");
        s = s.replaceAll("[（(][^（）()]*[）)]\\s*$", "");
        return s.toLowerCase();
    }

    // 在第 idx 个副接口里按标题精确匹配，命中后取其播放线路（带 [备N] 前缀）
    private static List<String[]> searchExtra(int idx, String title) {
        List<String[]> out = new ArrayList<>();
        try {
            String wd = URLEncoder.encode(title, "UTF-8");
            JSONObject obj = new JSONObject(OkHttp.string(EXTRA_APIS[idx] + "?ac=videolist&wd=" + wd));
            JSONArray list = obj.optJSONArray("list");
            if (list == null) return out;

            String target = normalize(title);
            String matchId = null;
            for (int j = 0; j < list.length(); j++) {
                JSONObject it = list.getJSONObject(j);
                if (target.equals(normalize(it.optString("vod_name")))) {
                    matchId = it.optString("vod_id");
                    break;
                }
            }
            if (matchId == null) return out;

            JSONObject dObj = new JSONObject(OkHttp.string(EXTRA_APIS[idx] + "?ac=detail&ids=" + matchId));
            JSONArray dList = dObj.optJSONArray("list");
            if (dList == null || dList.length() == 0) return out;

            JSONObject extraVod = dList.getJSONObject(0);
            List<String> fromOut = new ArrayList<>();
            List<String> urlOut = new ArrayList<>();
            splitPlay(extraVod.optString("vod_play_from"), extraVod.optString("vod_play_url"), fromOut, urlOut, "[备" + (idx + 1) + "]");

            for (int k = 0; k < fromOut.size(); k++) {
                out.add(new String[]{fromOut.get(k), urlOut.get(k)});
            }
        } catch (Exception e) {
            System.out.println("❌ [多源] 副接口" + (idx + 1) + " 查询异常: " + e.getMessage());
        }
        return out;
    }
}
