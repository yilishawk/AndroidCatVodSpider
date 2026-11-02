package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 华视界（huashijie.tv）CatVod Spider —— 纯 JSON API 版
 * -------------------------------------------------------------------------
 * 【站点性质】苹果CMS（maccms）改版站，对外暴露自定义 JSON API（非官方 maccms api.php 默认路由）。
 *
 * 【已实采的 API 端点（直连抓取确认）】
 *   · 列表：/api.php/vod/get_list
 *       参数：type_id(分类) / limit(页大小) / offset(偏移,=(页-1)*limit) /
 *             vod_area(地区) / vod_year(年份) / vod_name(模糊搜索)
 *       返回：{code:1, msg, info:{offset, limit, total, rows:[{vod_id,vod_name,
 *             vod_pic,vod_actor,vod_remarks,type_id,vod_year,vod_area,...}]}}
 *   · 详情：/api.php/vod/get_detail?vod_id={id}
 *       返回：info 含 vod_name/vod_pic/vod_actor/vod_director/vod_class/vod_remarks/
 *             vod_area/vod_year/vod_content/vod_play_from 以及【结构化】
 *             vod_play_list:[{sid,from,note,player_info,urls:[{name,url}]}]
 *   · 搜索：/index.php/ajax/suggest?mid=1&wd={关键词}&limit={n}
 *       返回：{code, msg, page, pagecount, limit, total, list:[{id,name,en,pic,
 *             type_is_vip_exclusive}], url:"/index.php/vod/search/wd/..."}
 *       注：total/pagecount 恒为 0，真实条数 = list.length；该接口不分页（一次给满 limit 条）。
 *   · 分类：站点无 get_type 接口，分类表来自首页导航，硬编码于 buildClasses()。
 *
 * 【播放地址】vod_play_list[].urls[].url 已经是最终 .m3u8 直链（多 CDN：down.jsybox.com /
 *   play.hhuus.com / play.xluuss.com / play.ly166.com:65 / fengbao12.com 等），
 *   无需二次解析 —— playerContent 直接 parse(0) 返回并带 Referer。
 *
 * 【边界（如实标注）】
 *   ★ 搜索走 /index.php/ajax/suggest?mid=1&wd=关键词（独立建议接口），返回 list:[
 *     {id,name,en,pic,type_is_vip_exclusive}]，不含备注/年份/地区，点进 detail 再补全。
 *     该接口 total/pagecount 恒为 0，真实条数以 list.length 计，且不分页。
 *   ★ vod_play_list 缺失时回退解析老格式 vod_play_from / vod_play_url（$$$ 分线路、# 分集）。
 *   ★ 播放 CDN 是否校验 Referer/CORS 需在设备上验证；此处统一带 Referer=站点根，按出画结果再调。
 *   ★ 分类/筛选的合法取值（年份/地区枚举）来自常识与首页，站点改版或新增枚举需同步。
 */
public class HuaShiJie extends Spider {

    private static final String BASE = "https://huashijie.tv";
    private static final String API = BASE + "/api.php/vod";
    private static final String SEARCH = BASE + "/index.php/ajax/suggest";
    private static final int MID = 1;          // 视频模块 id（suggest 必填）
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final int PAGE_SIZE = 24;

    // ===================================================================
    // HTTP：纯 java.net（android.jar 自带 HttpURLConnection），无需第三方依赖。
    // 该 API 不经 Cloudflare 质询、无需 cookie，带浏览器 UA + Referer 即可。
    // ===================================================================
    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Referer", BASE + "/");
        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        conn.disconnect();
        return sb.toString();
    }

    private static String enc(String s) {
        if (TextUtils.isEmpty(s)) return "";
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ===================================================================
    // 分类 & 筛选（站点无 get_type 接口，取自首页导航硬编码）
    // ===================================================================
    private static List<Class> buildClasses() {
        List<Class> cs = new ArrayList<>();
        cs.add(new Class("1", "电影"));
        cs.add(new Class("2", "连续剧"));
        cs.add(new Class("13", "国产剧"));
        cs.add(new Class("16", "欧美剧"));
        cs.add(new Class("15", "日韩剧"));
        cs.add(new Class("14", "港台剧"));
        cs.add(new Class("3", "综艺"));
        cs.add(new Class("4", "动漫"));
        cs.add(new Class("37", "短剧"));
        cs.add(new Class("38", "纪录片"));
        cs.add(new Class("6", "动作片"));
        cs.add(new Class("7", "喜剧片"));
        cs.add(new Class("8", "爱情片"));
        cs.add(new Class("9", "科幻片"));
        cs.add(new Class("10", "恐怖片"));
        cs.add(new Class("20", "犯罪片"));
        cs.add(new Class("12", "战争片"));
        return cs;
    }

    private static LinkedHashMap<String, List<Filter>> buildFilters() {
        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        List<Filter.Value> years = new ArrayList<>();
        for (String y : new String[]{"2026", "2025", "2024", "2023", "2022", "2021", "2020", "更早"}) {
            years.add(new Filter.Value(y));
        }
        List<Filter.Value> areas = new ArrayList<>();
        for (String a : new String[]{"大陆", "香港", "台湾", "美国", "韩国", "日本", "泰国", "英国", "法国", "其他"}) {
            areas.add(new Filter.Value(a));
        }
        List<Filter> common = new ArrayList<>();
        common.add(new Filter("vod_year", "年份", years));
        common.add(new Filter("vod_area", "地区", areas));
        for (Class c : buildClasses()) filters.put(c.getTypeId(), common);
        return filters;
    }

    // ===================================================================
    // 列表行解析
    // ===================================================================
    private static List<Vod> parseRows(JSONArray rows) {
        List<Vod> list = new ArrayList<>();
        if (rows == null) return list;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            Vod v = new Vod();
            v.setVodId(o.optString("vod_id", "0"));
            v.setVodName(o.optString("vod_name"));
            v.setVodPic(o.optString("vod_pic"));
            String remark = o.optString("vod_remarks");
            if (TextUtils.isEmpty(remark)) remark = o.optString("vod_year");
            v.setVodRemarks(remark);
            list.add(v);
        }
        return list;
    }

    // ===================================================================
    // CatVod 接口
    // ===================================================================
    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Vod> list = new ArrayList<>();
        try {
            String raw = get(API + "/get_list?limit=" + PAGE_SIZE + "&offset=0");
            JSONObject j = new JSONObject(raw);
            if (j.optInt("code") == 1) {
                list = parseRows(j.optJSONObject("info").optJSONArray("rows"));
            }
        } catch (Exception ignored) {
            // 首页取数失败 → 仍返回分类，壳子不至于空白崩溃
        }
        if (filter) return Result.string(buildClasses(), list, buildFilters());
        return Result.string(buildClasses(), list);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {
        }
        if (page < 1) page = 1;
        String area = (extend != null) ? first(extend.get("vod_area")) : "";
        String year = (extend != null) ? first(extend.get("vod_year")) : "";
        StringBuilder url = new StringBuilder(API + "/get_list?");
        if (!TextUtils.isEmpty(tid) && !"0".equals(tid)) {
            url.append("type_id=").append(tid).append("&");
        }
        url.append("limit=").append(PAGE_SIZE).append("&offset=").append((page - 1) * PAGE_SIZE);
        if (!TextUtils.isEmpty(area)) url.append("&vod_area=").append(enc(area));
        if (!TextUtils.isEmpty(year)) url.append("&vod_year=").append(enc(year));
        String raw = get(url.toString());
        JSONObject j = new JSONObject(raw);
        List<Vod> list = new ArrayList<>();
        int total = 0;
        int pageCount = page;
        if (j.optInt("code") == 1) {
            JSONObject info = j.optJSONObject("info");
            list = parseRows(info.optJSONArray("rows"));
            total = info.optInt("total", 0);
        }
        if (total > 0) pageCount = (int) Math.ceil((double) total / PAGE_SIZE);
        return Result.get().vod(list).page(page, pageCount, PAGE_SIZE, total).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return Result.get().string();
        String id = ids.get(0);
        String raw = get(API + "/get_detail?vod_id=" + id);
        JSONObject j = new JSONObject(raw);
        if (j.optInt("code") != 1) return Result.get().string();
        JSONObject info = j.optJSONObject("info");
        Vod v = new Vod();
        v.setVodId(info.optString("vod_id", id));
        v.setVodName(info.optString("vod_name"));
        v.setVodPic(info.optString("vod_pic"));
        v.setVodRemarks(info.optString("vod_remarks"));
        v.setVodYear(info.optString("vod_year"));
        v.setVodArea(info.optString("vod_area"));
        v.setVodActor(info.optString("vod_actor"));
        v.setVodDirector(info.optString("vod_director"));
        v.setVodContent(info.optString("vod_content"));
        parsePlay(v, info);
        return Result.string(v);
    }

    /**
     * 播放列表：优先结构化 vod_play_list（note 作线路名，urls[].name/url 作选集）；
     * 缺失时回退老格式 vod_play_from / vod_play_url。
     */
    private static void parsePlay(Vod v, JSONObject info) {
        JSONArray pl = info.optJSONArray("vod_play_list");
        if (pl != null && pl.length() > 0) {
            List<String> froms = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            for (int i = 0; i < pl.length(); i++) {
                JSONObject src = pl.optJSONObject(i);
                if (src == null) continue;
                String from = TextUtils.isEmpty(src.optString("note")) ? src.optString("from") : src.optString("note");
                JSONArray eps = src.optJSONArray("urls");
                List<String> epList = new ArrayList<>();
                if (eps != null) {
                    for (int k = 0; k < eps.length(); k++) {
                        JSONObject e = eps.optJSONObject(k);
                        if (e == null) continue;
                        String link = e.optString("url");
                        if (TextUtils.isEmpty(link)) continue;
                        String name = e.optString("name");
                        if (TextUtils.isEmpty(name)) name = "第" + (k + 1) + "集";
                        epList.add(name + "$" + link);
                    }
                }
                if (epList.isEmpty()) continue;
                froms.add(from);
                urls.add(TextUtils.join("#", epList));
            }
            if (!froms.isEmpty()) {
                v.setVodPlayFrom(TextUtils.join("$$$", froms));
                v.setVodPlayUrl(TextUtils.join("$$$", urls));
                return;
            }
        }
        // 回退：老格式
        String fromStr = info.optString("vod_play_from");
        String urlStr = info.optString("vod_play_url");
        if (!TextUtils.isEmpty(fromStr) && !TextUtils.isEmpty(urlStr)) {
            v.setVodPlayFrom(fromStr);
            v.setVodPlayUrl(urlStr);
        }
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        // 标准搜索接口：/index.php/ajax/suggest?mid=1&wd={关键词}&limit={n}
        // 返回 list:[{id,name,en,pic,type_is_vip_exclusive}]；total/pagecount 恒 0，以 list.length 计。
        String raw = get(SEARCH + "?mid=" + MID + "&wd=" + enc(key) + "&limit=20");
        JSONObject j = new JSONObject(raw);
        List<Vod> list = new ArrayList<>();
        if (j.optInt("code") == 1) {
            JSONArray arr = j.optJSONArray("list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Vod v = new Vod();
                    v.setVodId(o.optString("id", "0"));
                    v.setVodName(o.optString("name"));
                    v.setVodPic(o.optString("pic"));
                    list.add(v);
                }
            }
        }
        int total = list.size();
        return Result.get().vod(list).page(1, 1, total, total).string();
    }

    /**
     * 播放：id 形如 "选集名$url"（detailContent 写入），掐头取 url；
     * 已是 http(s) 直链 → parse(0) 直接播，并带站点 Referer。
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (TextUtils.isEmpty(id)) return Result.error("播放地址为空");
        String url = id;
        int d = id.indexOf('$');
        if (d >= 0 && d < id.length() - 1) url = id.substring(d + 1);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Result.error("非直链地址");
        }
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", UA);
        header.put("Referer", BASE + "/");
        return Result.get().parse(0).m3u8().url(url).header(header).string();
    }

    private static String first(String s) {
        return TextUtils.isEmpty(s) ? "" : s;
    }
}
