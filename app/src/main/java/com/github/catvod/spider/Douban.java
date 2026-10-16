package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Douban extends Spider {

    private final String siteUrl = "https://frodo.douban.com/api/v2";
    private final String apikey = "?apikey=0ac44ae016490db2204ce0a042db2916";
    private String extend;

    private Map<String, String> getHeader() {
        Map<String, String> header = new HashMap<>();
        header.put("Host", "frodo.douban.com");
        header.put("Connection", "Keep-Alive");
        header.put("Referer", "https://servicewechat.com/wx2f9b06c1de1ccfca/84/page-frame.html");
        header.put("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2785.143 Safari/537.36 MicroMessenger/7.0.9.501 NetType/WIFI MiniProgramEnv/Windows WindowsWechat");
        return header;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        this.extend = extend;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        List<String> typeIds = Arrays.asList(
                "hot_gaia",
                "tv_hot",
                "show_hot",
                "movie",
                "tv",
                "rank_list_movie",
                "rank_list_tv"
        );
        List<String> typeNames = Arrays.asList(
                "热门电影",
                "热播剧集",
                "热播综艺",
                "电影筛选",
                "电视筛选",
                "电影榜单",
                "电视剧榜单"
        );

        for (int i = 0; i < typeIds.size(); i++) {
            classes.add(new Class(typeIds.get(i), typeNames.get(i)));
        }

        String recommendUrl = "http://api.douban.com/api/v2/subject_collection/subject_real_time_hotest/items" + apikey;
        JSONObject jsonObject = new JSONObject(OkHttp.string(recommendUrl, getHeader()));
        JSONArray items = jsonObject.optJSONArray("subject_collection_items");

        return Result.string(
                classes,
                parseVodListFromJSONArray(items),
                filter ? getFilter() : null
        );
    }

    /**
     * 生成筛选配置。
     * 这里按分类分别给出筛选条件，避免所有分类共用一个 filter。
     */
    private JSONObject getFilter() {
        JSONObject filters = new JSONObject();

        try {
            // 热门电影：排序 + 地区
            filters.put("hot_gaia", buildFilter(
                    buildSelect("sort", "排序", new String[][]{
                            {"recommend", "推荐"},
                            {"hot", "热度"},
                            {"new", "最新"},
                            {"score", "评分"}
                    }, "recommend"),
                    buildSelect("area", "地区", new String[][]{
                            {"全部", "全部"},
                            {"中国大陆", "中国大陆"},
                            {"美国", "美国"},
                            {"日本", "日本"},
                            {"韩国", "韩国"},
                            {"英国", "英国"},
                            {"法国", "法国"},
                            {"德国", "德国"},
                            {"印度", "印度"},
                            {"泰国", "泰国"},
                            {"中国香港", "中国香港"},
                            {"中国台湾", "中国台湾"}
                    }, "全部")
            ));

            // 热播剧集：集合类型
            filters.put("tv_hot", buildFilter(
                    buildSelect("type", "类型", new String[][]{
                            {"tv_hot", "热播剧集"},
                            {"tv_domestic", "国产剧"},
                            {"tv_american", "美剧"},
                            {"tv_japanese", "日剧"},
                            {"tv_korean", "韩剧"},
                            {"tv_british", "英剧"},
                            {"tv_animation", "动漫"}
                    }, "tv_hot")
            ));

            // 热播综艺：集合类型
            filters.put("show_hot", buildFilter(
                    buildSelect("type", "类型", new String[][]{
                            {"show_hot", "热播综艺"},
                            {"show_domestic", "大陆综艺"},
                            {"show_korean", "韩综"},
                            {"show_american", "欧美综艺"},
                            {"show_japanese", "日综"}
                    }, "show_hot")
            ));

            // 电影筛选：排序 + 类型 + 地区 + 年代
            filters.put("movie", buildFilter(
                    buildSelect("sort", "排序", new String[][]{
                            {"T", "综合"},
                            {"U", "近期热度"},
                            {"S", "评分最高"},
                            {"R", "最新上映"}
                    }, "T"),
                    buildSelect("类型", "类型", new String[][]{
                            {"全部", "全部"},
                            {"喜剧", "喜剧"},
                            {"爱情", "爱情"},
                            {"动作", "动作"},
                            {"科幻", "科幻"},
                            {"动画", "动画"},
                            {"悬疑", "悬疑"},
                            {"惊悚", "惊悚"},
                            {"恐怖", "恐怖"},
                            {"犯罪", "犯罪"},
                            {"同性", "同性"},
                            {"音乐", "音乐"},
                            {"歌舞", "歌舞"},
                            {"历史", "历史"},
                            {"战争", "战争"},
                            {"传记", "传记"},
                            {"纪录片", "纪录片"},
                            {"短片", "短片"},
                            {"情色", "情色"}
                    }, "全部"),
                    buildSelect("地区", "地区", new String[][]{
                            {"全部", "全部"},
                            {"中国大陆", "中国大陆"},
                            {"美国", "美国"},
                            {"日本", "日本"},
                            {"韩国", "韩国"},
                            {"英国", "英国"},
                            {"法国", "法国"},
                            {"德国", "德国"},
                            {"印度", "印度"},
                            {"泰国", "泰国"},
                            {"中国香港", "中国香港"},
                            {"中国台湾", "中国台湾"}
                    }, "全部"),
                    buildSelect("年代", "年代", new String[][]{
                            {"全部", "全部"},
                            {"2025", "2025"},
                            {"2024", "2024"},
                            {"2023", "2023"},
                            {"2022", "2022"},
                            {"2021", "2021"},
                            {"2020", "2020"},
                            {"2019", "2019"},
                            {"2018", "2018"},
                            {"2017", "2017"},
                            {"2016", "2016"},
                            {"2015", "2015"},
                            {"2014", "2014"},
                            {"2013", "2013"},
                            {"2012", "2012"},
                            {"2011", "2011"},
                            {"2010", "2010"}
                    }, "全部")
            ));

            // 电视筛选：排序 + 类型 + 地区 + 年代
            filters.put("tv", buildFilter(
                    buildSelect("sort", "排序", new String[][]{
                            {"T", "综合"},
                            {"U", "近期热度"},
                            {"S", "评分最高"}
                    }, "T"),
                    buildSelect("类型", "类型", new String[][]{
                            {"全部", "全部"},
                            {"电视剧", "电视剧"},
                            {"综艺", "综艺"},
                            {"动漫", "动漫"},
                            {"纪录片", "纪录片"}
                    }, "全部"),
                    buildSelect("地区", "地区", new String[][]{
                            {"全部", "全部"},
                            {"中国大陆", "中国大陆"},
                            {"美国", "美国"},
                            {"日本", "日本"},
                            {"韩国", "韩国"},
                            {"英国", "英国"},
                            {"法国", "法国"},
                            {"德国", "德国"},
                            {"印度", "印度"},
                            {"泰国", "泰国"},
                            {"中国香港", "中国香港"},
                            {"中国台湾", "中国台湾"}
                    }, "全部"),
                    buildSelect("年代", "年代", new String[][]{
                            {"全部", "全部"},
                            {"2025", "2025"},
                            {"2024", "2024"},
                            {"2023", "2023"},
                            {"2022", "2022"},
                            {"2021", "2021"},
                            {"2020", "2020"},
                            {"2019", "2019"},
                            {"2018", "2018"},
                            {"2017", "2017"},
                            {"2016", "2016"},
                            {"2015", "2015"},
                            {"2014", "2014"},
                            {"2013", "2013"},
                            {"2012", "2012"},
                            {"2011", "2011"},
                            {"2010", "2010"}
                    }, "全部")
            ));

            // 电影榜单
            filters.put("rank_list_movie", buildFilter(
                    buildSelect("榜单", "榜单", new String[][]{
                            {"movie_real_time_hotest", "实时热门"},
                            {"movie_weekly_best", "一周口碑"},
                            {"movie_top250", "Top250"},
                            {"movie_new", "新片榜"},
                            {"movie_box_office", "票房榜"}
                    }, "movie_real_time_hotest")
            ));

            // 电视剧榜单
            filters.put("rank_list_tv", buildFilter(
                    buildSelect("榜单", "榜单", new String[][]{
                            {"tv_real_time_hotest", "实时热门"},
                            {"tv_weekly_best", "一周口碑"},
                            {"tv_chinese_best_weekly", "华语口碑"},
                            {"tv_global_best_weekly", "全球口碑"}
                    }, "tv_real_time_hotest")
            ));

        } catch (Exception e) {
            e.printStackTrace();
        }

        return filters;
    }

    /**
     * 构造一个筛选分类。
     */
    private JSONObject buildFilter(JSONObject... items) {
        JSONObject filter = new JSONObject();
        try {
            JSONArray array = new JSONArray();
            for (JSONObject item : items) array.put(item);
            filter.put("筛选", array);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return filter;
    }

    /**
     * 构造一个下拉筛选。
     */
    private JSONObject buildSelect(String key, String name, String[][] values, String defaultValue) {
        JSONObject select = new JSONObject();
        try {
            select.put("key", key);
            select.put("name", name);

            JSONArray valueArray = new JSONArray();
            for (String[] pair : values) {
                JSONObject value = new JSONObject();
                value.put("n", pair[1]);
                value.put("v", pair[0]);
                valueArray.put(value);
            }
            select.put("value", valueArray);
            select.put("def", defaultValue);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return select;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int start = (Integer.parseInt(pg) - 1) * 20;
        String cateUrl;
        String itemKey = "items";

        switch (tid) {
            case "hot_gaia": {
                String sort = getExtend(extend, "sort", "recommend");
                String area = getExtend(extend, "area", "全部");
                cateUrl = siteUrl + "/movie/hot_gaia" + apikey
                        + "&sort=" + enc(sort)
                        + "&area=" + enc(area)
                        + "&start=" + start
                        + "&count=20";
                break;
            }

            case "tv_hot": {
                String type = getExtend(extend, "type", "tv_hot");
                cateUrl = siteUrl + "/subject_collection/" + type + "/items" + apikey
                        + "&start=" + start
                        + "&count=20";
                itemKey = "subject_collection_items";
                break;
            }

            case "show_hot": {
                String type = getExtend(extend, "type", "show_hot");
                cateUrl = siteUrl + "/subject_collection/" + type + "/items" + apikey
                        + "&start=" + start
                        + "&count=20";
                itemKey = "subject_collection_items";
                break;
            }

            case "movie": {
                String sort = getExtend(extend, "sort", "T");
                String tags = getTags(extend);
                cateUrl = siteUrl + "/movie/recommend" + apikey
                        + "&sort=" + enc(sort)
                        + "&tags=" + enc(tags)
                        + "&start=" + start
                        + "&count=20";
                break;
            }

            case "tv": {
                String sort = getExtend(extend, "sort", "T");
                String tags = getTags(extend);
                cateUrl = siteUrl + "/tv/recommend" + apikey
                        + "&sort=" + enc(sort)
                        + "&tags=" + enc(tags)
                        + "&start=" + start
                        + "&count=20";
                break;
            }

            case "rank_list_movie": {
                String rank = getExtend(extend, "榜单", "movie_real_time_hotest");
                cateUrl = siteUrl + "/subject_collection/" + rank + "/items" + apikey
                        + "&start=" + start
                        + "&count=20";
                itemKey = "subject_collection_items";
                break;
            }

            case "rank_list_tv": {
                String rank = getExtend(extend, "榜单", "tv_real_time_hotest");
                cateUrl = siteUrl + "/subject_collection/" + rank + "/items" + apikey
                        + "&start=" + start
                        + "&count=20";
                itemKey = "subject_collection_items";
                break;
            }

            default: {
                String sort = getExtend(extend, "sort", "T");
                String tags = getTags(extend);
                cateUrl = siteUrl + "/movie/recommend" + apikey
                        + "&sort=" + enc(sort)
                        + "&tags=" + enc(tags)
                        + "&start=" + start
                        + "&count=20";
                break;
            }
        }

        JSONObject object = new JSONObject(OkHttp.string(cateUrl, getHeader()));
        JSONArray array = object.getJSONArray(itemKey);
        List<Vod> list = parseVodListFromJSONArray(array);

        int page = Integer.parseInt(pg);
        int count = Integer.MAX_VALUE;
        int limit = 20;
        int total = Integer.MAX_VALUE;

        return Result.get()
                .vod(list)
                .page(page, count, limit, total)
                .string();
    }

    /**
     * 只把真正的 tags 字段拼成豆瓣 tags。
     * 这里不再把 sort、area、type、榜单 等全部拼进去。
     */
    private String getTags(HashMap<String, String> extend) {
        try {
            List<String> tags = new ArrayList<>();

            addTag(tags, extend.get("类型"));
            addTag(tags, extend.get("地区"));
            addTag(tags, extend.get("年代"));

            // 兼容旧配置里可能直接使用 sort 以外的任意 key
            if (tags.isEmpty()) {
                for (String key : extend.keySet()) {
                    if ("sort".equals(key)) continue;
                    if ("area".equals(key)) continue;
                    if ("type".equals(key)) continue;
                    if ("榜单".equals(key)) continue;
                    addTag(tags, extend.get(key));
                }
            }

            return join(tags, ",");
        } catch (Exception e) {
            return "";
        }
    }

    private void addTag(List<String> tags, String value) {
        if (value == null) return;
        value = value.trim();
        if (value.isEmpty()) return;
        if ("全部".equals(value)) return;
        if (!tags.contains(value)) tags.add(value);
    }

    private String join(List<String> list, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private String getExtend(HashMap<String, String> extend, String key, String defaultValue) {
        if (extend == null) return defaultValue;
        String value = extend.get(key);
        if (value == null || value.trim().isEmpty()) return defaultValue;
        return value;
    }

    private String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private List<Vod> parseVodListFromJSONArray(JSONArray items) throws Exception {
        List<Vod> list = new ArrayList<>();
        if (items == null) return list;

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String vodId = "msearch:" + item.optString("id");
            String name = item.optString("title");
            String pic = getPic(item);
            String remark = getRating(item);
            list.add(new Vod(vodId, name, pic, remark));
        }
        return list;
    }

    private String getRating(JSONObject item) {
        try {
            return "评分：" + item.getJSONObject("rating").optString("value");
        } catch (Exception e) {
            return "";
        }
    }

    private String getPic(JSONObject item) {
        try {
            return item.getJSONObject("pic").optString("normal")
                    + "@Referer=https://api.douban.com/@User-Agent=" + Util.CHROME;
        } catch (Exception e) {
            return "";
        }
    }
}
