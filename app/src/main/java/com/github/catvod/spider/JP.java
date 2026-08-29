package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.Filter.Value;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author 九州空间 - 依赖工具类重构版（Fongmi 兼容）
 * 站点: m.9zhoukj.com
 *
 * 重构内容：
 * 1. 所有返回数据使用 Result、Vod、Class 等 Bean
 * 2. 网络请求统一使用 OkHttp.string()
 * 3. 筛选器使用 Filter、Value 构建
 * 4. 保留原签名逻辑
 * 5. 修正 Result.error 调用（该方法已返回 String）
 */
public class JP extends Spider {

    private static final String HOST = "https://m.9zhoukj.com";
    private static final String KEY = "cb808529bae6b6be45ecfab29a4889bc";
    private static final String DEVICE_ID = "7dbc13a7-7976-4d7b-89d2-c110d09d7410";
    private static final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";

    // ================== 签名工具 ==================

    private String md5(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(input.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String sha1(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(input.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String generateSign(Map<String, String> params) throws Exception {
        Map<String, String> valid = new HashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            String v = e.getValue();
            if (v != null && !v.isEmpty()) valid.put(e.getKey(), v);
        }
        List<String> keys = new ArrayList<>(valid.keySet());
        Collections.sort(keys);

        StringBuilder query = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) query.append("&");
            String k = keys.get(i);
            query.append(k).append("=").append(valid.get(k));
        }
        String t = String.valueOf(System.currentTimeMillis());
        String signStr = query.toString() + "&key=" + KEY + "&t=" + t;
        String md5hex = md5(signStr);
        String sign = sha1(md5hex);
        return t + "|" + sign;
    }

    private Map<String, String> buildHeaders(Map<String, String> params) throws Exception {
        String[] ts = generateSign(params).split("\\|");
        String t = ts[0];
        String sign = ts[1];

        Map<String, String> headers = new HashMap<>();
        headers.put("Host", "m.9zhoukj.com");
        headers.put("client-type", "3");
        headers.put("deviceId", DEVICE_ID);
        headers.put("sign", sign);
        headers.put("t", t);
        headers.put("User-Agent", UA);
        headers.put("Referer", HOST + "/");
        return headers;
    }

    // 通用请求方法：使用 OkHttp.string()
    private String fetch(String path, Map<String, String> params) throws Exception {
        StringBuilder urlBuilder = new StringBuilder(HOST).append(path);
        if (params != null && !params.isEmpty()) {
            urlBuilder.append("?");
            List<String> keys = new ArrayList<>(params.keySet());
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                urlBuilder.append(key).append("=").append(URLEncoder.encode(params.get(key), "UTF-8"));
                if (i < keys.size() - 1) {
                    urlBuilder.append("&");
                }
            }
        }
        Map<String, String> reqHeaders = buildHeaders(params != null ? params : new HashMap<>());
        return OkHttp.string(urlBuilder.toString(), reqHeaders);
    }

    // ================== 首页 ==================

    @Override
    public String homeContent(boolean filter) {
        try {
            List<Class> classes = new ArrayList<>();
            classes.add(new Class("2_14", "国产剧"));
            classes.add(new Class("2_15", "欧美剧"));
            classes.add(new Class("2_62", "日韩剧"));
            classes.add(new Class("2_16", "港台剧"));
            classes.add(new Class("1_", "电影"));
            classes.add(new Class("3_", "综艺"));

            if (filter) {
                // 构建筛选器
                LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();

                // 公共年份选项
                List<Value> yearValues = new ArrayList<>();
                yearValues.add(new Value("全部", ""));
                for (int y = 2026; y >= 2010; y--) {
                    yearValues.add(new Value(String.valueOf(y), String.valueOf(y)));
                }

                List<Value> sortValues = new ArrayList<>();
                sortValues.add(new Value("最新", "1"));
                sortValues.add(new Value("最热", "2"));

                // 电视剧/国产/欧美/日韩/港台 通用筛选
                List<Filter> tvFilters = new ArrayList<>();
                List<Value> vClassValues = new ArrayList<>();
                String[][] vClassOpts = {
                        {"全部", ""}, {"古装", "古装"}, {"战争", "战争"}, {"喜剧", "喜剧"},
                        {"家庭", "家庭"}, {"犯罪", "犯罪"}, {"动作", "动作"}, {"奇幻", "奇幻"},
                        {"剧情", "剧情"}, {"历史", "历史"}, {"短片", "短片"}
                };
                for (String[] opt : vClassOpts) {
                    vClassValues.add(new Value(opt[0], opt[1]));
                }
                tvFilters.add(new Filter("v_class", "剧情", vClassValues));

                List<Value> areaValues = new ArrayList<>();
                String[][] areaOpts = {{"全部", ""}, {"中国大陆", "中国大陆"}, {"中国香港", "中国香港"}, {"美国", "美国"}};
                for (String[] opt : areaOpts) {
                    areaValues.add(new Value(opt[0], opt[1]));
                }
                tvFilters.add(new Filter("area", "地区", areaValues));
                tvFilters.add(new Filter("year", "年代", yearValues));
                tvFilters.add(new Filter("sort", "排序", sortValues));

                // 电影筛选
                List<Filter> movieFilters = new ArrayList<>();
                List<Value> typeValues = new ArrayList<>();
                String[][] typeOpts = {
                        {"全部", ""}, {"喜剧", "22"}, {"动作", "23"}, {"科幻", "30"},
                        {"爱情", "26"}, {"悬疑", "27"}, {"奇幻", "87"}, {"剧情", "37"},
                        {"恐怖", "36"}, {"犯罪", "35"}, {"动画", "33"}, {"惊悚", "34"},
                        {"战争", "25"}, {"冒险", "31"}, {"灾难", "81"}
                };
                for (String[] opt : typeOpts) {
                    typeValues.add(new Value(opt[0], opt[1]));
                }
                movieFilters.add(new Filter("type", "类型", typeValues));

                List<Value> movieVClass = new ArrayList<>();
                String[][] movieVOpts = {
                        {"全部", ""}, {"爱情", "爱情"}, {"动作", "动作"}, {"科幻", "科幻"}, {"恐怖", "恐怖"}
                };
                for (String[] opt : movieVOpts) {
                    movieVClass.add(new Value(opt[0], opt[1]));
                }
                movieFilters.add(new Filter("v_class", "剧情", movieVClass));

                List<Value> movieArea = new ArrayList<>();
                String[][] movieAreaOpts = {
                        {"全部", ""}, {"中国大陆", "中国大陆"}, {"中国香港", "中国香港"},
                        {"中国台湾", "中国台湾"}, {"美国", "美国"}, {"日本", "日本"},
                        {"韩国", "韩国"}, {"印度", "印度"}, {"泰国", "泰国"},
                        {"英国", "英国"}, {"法国", "法国"}
                };
                for (String[] opt : movieAreaOpts) {
                    movieArea.add(new Value(opt[0], opt[1]));
                }
                movieFilters.add(new Filter("area", "地区", movieArea));
                movieFilters.add(new Filter("year", "年代", yearValues));
                movieFilters.add(new Filter("sort", "排序", sortValues));

                // 综艺筛选
                List<Filter> zyFilters = new ArrayList<>();
                List<Value> zyType = new ArrayList<>();
                String[][] zyOpts = {{"全部", ""}, {"国产综艺", "69"}, {"港台综艺", "70"}, {"日韩综艺", "72"}};
                for (String[] opt : zyOpts) {
                    zyType.add(new Value(opt[0], opt[1]));
                }
                zyFilters.add(new Filter("type", "类型", zyType));

                List<Value> zyVClass = new ArrayList<>();
                String[][] zyVOpts = {{"全部", ""}, {"真人秀", "真人秀"}, {"音乐", "音乐"}, {"脱口秀", "脱口秀"}};
                for (String[] opt : zyVOpts) {
                    zyVClass.add(new Value(opt[0], opt[1]));
                }
                zyFilters.add(new Filter("v_class", "剧情", zyVClass));
                zyFilters.add(new Filter("year", "年代", yearValues));

                // 放入filters
                filters.put("2_14", tvFilters);
                filters.put("2_15", tvFilters);
                filters.put("2_62", tvFilters);
                filters.put("2_16", tvFilters);
                filters.put("1_", movieFilters);
                filters.put("3_", zyFilters);

                return Result.get().classes(classes).filters(filters).string();
            } else {
                return Result.get().classes(classes).string();
            }
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.error("首页加载失败");
        }
    }

    // ================== 分类 ==================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            if (extend == null) extend = new HashMap<>();

            String[] parts = tid.split("_");
            String type1 = parts[0];
            String subType = parts.length > 1 ? parts[1] : "";

            Map<String, String> params = new HashMap<>();
            params.put("type1", type1);
            params.put("pageNum", pg);
            params.put("pageSize", "30");
            params.put("sort", extend.getOrDefault("sort", "1"));
            params.put("sortBy", "1");

            String finalType = extend.getOrDefault("type", subType);
            if (finalType != null && !finalType.isEmpty()) {
                params.put("type", finalType);
            }

            String area = extend.getOrDefault("area", "");
            if (!area.isEmpty()) params.put("area", area);
            String vClass = extend.getOrDefault("v_class", "");
            if (!vClass.isEmpty()) params.put("v_class", vClass);
            String year = extend.getOrDefault("year", "");
            if (!year.isEmpty()) params.put("year", year);

            String json = fetch("/api/mw-movie/anonymous/video/list", params);
            if (json == null || json.isEmpty()) {
                return Result.get().vod(new ArrayList<>()).page(Integer.parseInt(pg), 1, 0, 0).string();
            }

            JSONObject root = new JSONObject(json);
            int code = root.optInt("code", -1);
            if (code != 0 && code != 200) {
                SpiderDebug.log("[JP] API error: " + root.optString("msg"));
                return Result.get().vod(new ArrayList<>()).page(Integer.parseInt(pg), 1, 0, 0).string();
            }

            JSONObject data = root.optJSONObject("data");
            JSONArray list = data != null ? data.optJSONArray("list") : null;
            List<Vod> videos = new ArrayList<>();
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    Vod vod = new Vod();
                    vod.setVodId(item.optString("vodId"));
                    vod.setVodName(item.optString("vodName"));
                    vod.setVodPic(item.optString("vodPic"));
                    vod.setVodRemarks(item.optString("vodRemarks", ""));
                    videos.add(vod);
                }
            }

            int totalPage = 9999; // 接口未返回总页数，可估算
            int page = Integer.parseInt(pg);
            return Result.get().vod(videos).page(page, totalPage, videos.size(), totalPage * 30).string();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.error(e.getMessage());
        }
    }

    // ================== 详情 ==================

    @Override
    public String detailContent(List<String> ids) {
        try {
            String vodId = ids.get(0);
            Map<String, String> params = new HashMap<>();
            params.put("id", vodId);
            String json = fetch("/api/mw-movie/anonymous/video/detail", params);
            if (json == null || json.isEmpty()) return Result.error("详情获取失败");

            JSONObject data = new JSONObject(json).optJSONObject("data");
            if (data == null) return Result.error("数据为空");

            Vod vod = new Vod();
            vod.setVodId(data.optString("vodId", vodId));
            vod.setVodName(data.optString("vodName", ""));
            vod.setVodPic(data.optString("vodPic", ""));
            vod.setVodContent(data.optString("vodContent", ""));

            // 剧集列表
            JSONArray episodes = data.optJSONArray("episodeList");
            List<String> epList = new ArrayList<>();
            if (episodes != null && episodes.length() > 0) {
                for (int i = 0; i < episodes.length(); i++) {
                    JSONObject ep = episodes.getJSONObject(i);
                    String name = ep.optString("name", "正片");
                    String nid = ep.optString("nid", "0");
                    epList.add(name + "$" + vodId + "@@" + nid);
                }
            }

            if (epList.isEmpty()) {
                vod.setVodPlayFrom("");
                vod.setVodPlayUrl("");
            } else {
                vod.setVodPlayFrom("九州空间");
                vod.setVodPlayUrl(String.join("#", epList));
            }

            return Result.get().vod(vod).string();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.error(e.getMessage());
        }
    }

    // ================== 搜索 ==================

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("keyword", key);
            params.put("pageNum", "1");
            params.put("pageSize", "30");
            params.put("sourceCode", "1");

            String json = fetch("/api/mw-movie/anonymous/video/searchByWord", params);
            if (json == null || json.isEmpty()) return Result.error("搜索失败");

            JSONObject data = new JSONObject(json).optJSONObject("data");
            JSONObject resultObj = data != null ? data.optJSONObject("result") : null;
            JSONArray rawList = resultObj != null ? resultObj.optJSONArray("list") : null;
            List<Vod> list = new ArrayList<>();
            if (rawList != null) {
                for (int i = 0; i < rawList.length(); i++) {
                    JSONObject item = rawList.getJSONObject(i);
                    if (item.optString("vodName", "").contains(key)) {
                        Vod vod = new Vod();
                        vod.setVodId(item.optString("vodId"));
                        vod.setVodName(item.optString("vodName"));
                        vod.setVodPic(item.optString("vodPic"));
                        vod.setVodRemarks(item.optString("vodRemarks", ""));
                        list.add(vod);
                    }
                }
            }
            return Result.get().vod(list).string();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.error(e.getMessage());
        }
    }

    // ================== 播放 ==================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String[] parts = id.split("@@");
            if (parts.length < 2) {
                return Result.get().parse(1).url(id).string();
            }

            String vodId = parts[0];
            String nid = parts[1];

            Map<String, String> params = new HashMap<>();
            params.put("clientType", "3");
            params.put("id", vodId);
            params.put("nid", nid);

            String json = fetch("/api/mw-movie/anonymous/v2/video/episode/url", params);
            if (json == null || json.isEmpty()) {
                return Result.get().parse(1).url(id).string();
            }

            JSONObject data = new JSONObject(json).optJSONObject("data");
            if (data == null) {
                return Result.get().parse(1).url(id).string();
            }
            JSONArray list = data.optJSONArray("list");
            if (list == null || list.length() == 0) {
                return Result.get().parse(1).url(id).string();
            }

            String url = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject v = list.getJSONObject(i);
                if (v.optInt("resolution") == 1080) {
                    url = v.optString("url");
                    break;
                }
            }
            if (url == null) url = list.getJSONObject(0).optString("url");
            if (url == null || url.isEmpty()) {
                return Result.get().parse(1).url(id).string();
            }

            Map<String, String> header = new HashMap<>();
            header.put("User-Agent", UA);
            header.put("Referer", HOST);
            header.put("Origin", HOST);

            return Result.get().url(url).header(header).parse(0).string();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return Result.get().parse(1).url(id).string();
        }
    }
}
