package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.Filter;
import com.github.catvod.net.OkHttp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EroTo extends Spider {

    private static final String HOST = "https://ero.to";
    private boolean unlocked = false;

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");
        headers.put("Referer", HOST + "/");
        return headers;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        this.unlocked = PasswordGate.ensureUnlocked(context);
        if (!this.unlocked) {
            throw new Exception("Password verification failed. Source initialization aborted.");
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        if (!unlocked) {
            return Result.get().classes(new ArrayList<Class>()).string();
        }

        List<Class> classes = new ArrayList<Class>();
        classes.add(new Class("vod", "成人视频 (VOD)"));
        classes.add(new Class("fc2", "FC2 PPV特区"));

        // 构建筛选数据
        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        
        List<Filter> filterList = new ArrayList<>();
        // 声明 Value 列表
        List<Filter.Value> values = new ArrayList<>();
        
        // 使用您依赖中定义的 public Value(String n, String v) 构造器填充数据
        values.add(new Filter.Value("全部", ""));
        values.add(new Filter.Value("单体作品", "vod_genre/%e5%8d%98%e4%bd%93%e4%bd%9c%e5%93%81"));
        values.add(new Filter.Value("中出", "vod_genre/%e4%b8%ad%e5%87%ba%e3%81%97"));
        values.add(new Filter.Value("高清/HD", "vod_genre/%e3%83%a2%e3%82%b6%e3%82%a4%e3%82%af%e9%99%a4%e5%8e%bb"));
        values.add(new Filter.Value("独占作品", "vod_genre/%e7%8b%ac%e5%8d%a0%e9%85%8d%e4%bf%a1"));
        values.add(new Filter.Value("巨乳", "vod_genre/%e5%b7%a8%e4%b9%b3"));
        values.add(new Filter.Value("4K极清", "vod_genre/4k"));
        values.add(new Filter.Value("素人/自拍", "vod_genre/%e7%b4%a0%e4%ba%ba"));
        values.add(new Filter.Value("无码去马赛克", "vod_genre/%e3%83%a2%e3%82%b6%e3%82%a4%e3%82%af%e9%99%a4%e5%8e%bb"));
        values.add(new Filter.Value("人妻・主妇", "vod_genre/%e4%ba%ba%e5%a6%bb%e3%83%bb%e4%b8%bb%e5%a9%a6"));
        values.add(new Filter.Value("美少女", "vod_genre/%e7%be%af%e5%b0%91%e5%a5%b3"));
        values.add(new Filter.Value("吹箫/口交", "vod_genre/%e3%83%95%e3%82%a7%e3%82%a9"));
        values.add(new Filter.Value("苗条/骨感", "vod_genre/%e3%82%b9%e3%83%ac%e3%83%b3%e3%83%80%e3%83%bc"));
        values.add(new Filter.Value("自拍/哈麦德", "vod_genre/%e3%83%9a%e3%83%a1%e6%92%ae%e3%82%8a"));
        values.add(new Filter.Value("熟女", "vod_genre/%e7%86%9f%e5%a5%b3"));
        values.add(new Filter.Value("超清FHD", "vod_genre/%e3%83%95%e3%83%ab%e3%83%8f%e3%82%a4%e3%83%93%e3%82%b8%e3%83%a7%e3%83%b3fhd"));
        values.add(new Filter.Value("痴女", "vod_genre/%e7%97%b4%e5%a5%b3"));
        values.add(new Filter.Value("双飞3P/4P", "vod_genre/3p%e3%83%bb4p"));
        values.add(new Filter.Value("潮吹", "vod_genre/%e6%bd%ae%e5%90%b9%e3%81%8d"));
        values.add(new Filter.Value("美乳", "vod_genre/%e7%be%8e%e4%b9%b3"));
        values.add(new Filter.Value("寝取/NTR", "vod_genre/%e5%af%9d%e5%8f%96%e3%82%8a%e3%83%bb%e5%af%9d%e5%8f%96%e3%82%8a%e3%82%8c%e3%83%bbntr"));
        values.add(new Filter.Value("淫乱/重口味", "vod_genre/%e6%b7%ab%e4%b9%b1%e3%83%bb%e3%83%8f%e3%83%bc%e3%83%89%e7%b3%bb"));
        values.add(new Filter.Value("高潮/绝顶", "vod_genre/%e3%82%a2%e3%82%af%e3%83%a1%e3%83%bb%e3%82%aa%e3%83%bc%e3%82%ac%e3%82%ba%e3%83%a0"));
        values.add(new Filter.Value("剧情/故事", "vod_genre/%e3%83%89%e3%83%a9%e3%83%9e"));
        values.add(new Filter.Value("乳交/夹乳", "vod_genre/%e3%83%91%e3%82%a4%e3%82%ba%e3%83%aa"));
        values.add(new Filter.Value("骑乘位", "vod_genre/%e9%a8%8e%e4%b9%97%e4%bd%8d"));
        values.add(new Filter.Value("颜射", "vod_genre/%e9%a1%94%e5%b0%84"));
        values.add(new Filter.Value("大屁股/巨尻", "vod_genre/%e5%b7%a1%e5%b0%bb"));
        values.add(new Filter.Value("女子校生", "vod_genre/%e5%a5%b3%e5%ad%90%e6%a0%a1%e7%94%9f"));
        values.add(new Filter.Value("白虎/无毛", "vod_genre/%e3%83%91%e3%83%a4%e3%83%91%e3%83%b3"));
        values.add(new Filter.Value("大姐姐", "vod_genre/%e3%81%8a%e5%a7%89%e3%81%95%e3%82%93"));
        values.add(new Filter.Value("角色扮演/制服", "vod_genre/%e3%82%b3%e3%82%b3%e3%83%97%e3%83%ac"));
        values.add(new Filter.Value("接吻/深吻", "vod_genre/%e3%82%ac%e3%82%b9%e3%83%bb%e6%8e%a5%e5%90%bb"));
        values.add(new Filter.Value("深喉/深插", "vod_genre/%e3%82%a4%e3%83%a9%e3%83%a1%e3%83%81%e3%82%aa"));
        values.add(new Filter.Value("女白领/OL", "vod_genre/ol"));
        values.add(new Filter.Value("制服诱惑", "vod_genre/%e5%88%b6%e6%9c%8d"));
        values.add(new Filter.Value("街头搭讪", "vod_genre/%e3%83%8a%e3%83%b3%e3%83%91"));
        values.add(new Filter.Value("群交/乱交", "vod_genre/%e4%b9%b1%e4%ba%a4"));
        values.add(new Filter.Value("精油/按摩", "vod_genre/%e3%83%ad%e3%83%bc%e3%82%b7%e3%83%a7%e3%83%b3%e3%83%bb%e3%82%aa%e3%82%a4%e3%83%ab"));
        values.add(new Filter.Value("辣妹", "vod_genre/%e3%82%ae%e3%83%a3%e3%83%ab"));
        values.add(new Filter.Value("女子大生", "vod_genre/%e5%a5%b3%e5%ad%90%e5%a4%a7%e7%94%9f"));
        values.add(new Filter.Value("手淫/自慰", "vod_genre/%e3%82%aa%e3%83%aa%e3%83%8b%e3%83%bc"));
        values.add(new Filter.Value("美臀/美尻", "vod_genre/%e7%be%a5%e5%b0%bb"));
        values.add(new Filter.Value("羞耻", "vod_genre/%e7%be%9e%e6%81%a5"));
        values.add(new Filter.Value("手交/手淫", "vod_genre/%e6%89%8b%e3%82%b3%e3%82%ad"));
        values.add(new Filter.Value("肛交", "vod_genre/%e3%82%a2%e3%83%8a%e3%83%ab"));
        values.add(new Filter.Value("温泉/旅馆", "vod_genre/%e6%b8%a9%e6%b3%89"));
        values.add(new Filter.Value("美腿/脚丫", "vod_genre/%e8%84%9a%e3%83%95%e3%82%a7%e3%83%a1"));
        values.add(new Filter.Value("SM虐恋", "vod_genre/sm"));

        // 将 values 组装入 Filter 中
        filterList.add(new Filter("genre", "类型", values));
        
        // 绑定给首选分类
        filters.put("vod", filterList);

        return Result.get().classes(classes).filters(filters).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        if (!unlocked) {
            int page = Integer.parseInt(pg);
            return Result.get().vod(new ArrayList<Vod>()).page(page, 0, 0, 0).string();
        }

        int page = Integer.parseInt(pg);
        
        // 判定是否激活了筛选
        String realTid = tid;
        if (extend != null && extend.containsKey("genre") && !TextUtils.isEmpty(extend.get("genre"))) {
            realTid = extend.get("genre"); // 获取选中的筛选子标签
        }

        // 拼接 URL 请求
        String url;
        if (page == 1) {
            url = HOST + "/" + realTid + "/";
        } else {
            url = HOST + "/" + realTid + "/page/" + page + "/";
        }

        String html = OkHttp.string(url, getHeaders());
        List<Vod> list = parseVideoList(html);

        int totalPage = page;
        if (list.size() >= 8) {
            totalPage = page + 1;
        }

        return Result.get().vod(list).page(page, totalPage, 20, 2000).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (!unlocked) {
            return Result.get().string();
        }

        String id = ids.get(0);
        String detailUrl = HOST + "/" + id + "/";
        String html = OkHttp.string(detailUrl, getHeaders());

        Vod vod = new Vod();
        vod.setVodId(id);

        Pattern pTitle = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE);
        Matcher mTitle = pTitle.matcher(html);
        String title = "Video " + id;
        if (mTitle.find()) {
            title = mTitle.group(1).replace(" - エロ動画ero.to", "").replace(" - FC2 PPVero.to", "").trim();
        }
        vod.setVodName(title);

        Pattern pPic = Pattern.compile("<img[^>]+src=\"(https://img.manyse.com/[^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Matcher mPic = pPic.matcher(html);
        if (mPic.find()) {
            vod.setVodPic(mPic.group(1));
        }

        vod.setVodPlayFrom("高清原生线路");
        vod.setVodPlayUrl("立即播放$" + id);

        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        if (!unlocked) {
            return Result.get().vod(new ArrayList<Vod>()).string();
        }

        String url = HOST + "/?s=" + key + "&post_type%5B0%5D=vod";
        String html = OkHttp.string(url, getHeaders());

        List<Vod> list = parseVideoList(html);
        return Result.get().vod(list).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String detailUrl = HOST + "/" + id + "/";
        String html = OkHttp.string(detailUrl, getHeaders());

        String playUrl = "";

        // 场景二：直接匹配 playUrls
        if (html.contains("playUrls")) {
            Pattern pUrls = Pattern.compile("const playUrls = \\[\\{\"label\":\"[^\"]+\",\"url\":\"([^\"]+)\"\\}\\];", Pattern.CASE_INSENSITIVE);
            Matcher mUrls = pUrls.matcher(html);
            if (mUrls.find()) {
                playUrl = mUrls.group(1).replace("\\/", "/");
            }
        }

        // 场景一：匹配 variables 组合
        if (TextUtils.isEmpty(playUrl)) {
            String videoLink = "";
            String pndvd = "";
            String versionSuffix = "";

            Pattern pLink = Pattern.compile("const videoLink = \"([^\"]*)\";", Pattern.CASE_INSENSITIVE);
            Matcher mLink = pLink.matcher(html);
            if (mLink.find()) videoLink = mLink.group(1).trim();

            Pattern pPndvd = Pattern.compile("const pndvd = \"([^\"]*)\";", Pattern.CASE_INSENSITIVE);
            Matcher mPndvd = pPndvd.matcher(html);
            if (mPndvd.find()) pndvd = mPndvd.group(1).trim();

            Pattern pSuffix = Pattern.compile("const versionSuffix = \"([^\"]*)\";", Pattern.CASE_INSENSITIVE);
            Matcher mSuffix = pSuffix.matcher(html);
            if (mSuffix.find()) versionSuffix = mSuffix.group(1).trim();

            if (!TextUtils.isEmpty(videoLink) && !TextUtils.isEmpty(pndvd)) {
                playUrl = "https://test.manyse.com/" + videoLink + "/" + pndvd + "/play.m3u8" + versionSuffix;
            }
        }

        return Result.get().url(playUrl).header(getHeaders()).string();
    }

    private List<Vod> parseVideoList(String html) {
        List<Vod> list = new ArrayList<Vod>();
        if (TextUtils.isEmpty(html)) {
            return list;
        }

        Pattern pCard = Pattern.compile("<div class=\"card text-white bg-dark mb-3\"[\\s\\S]*?<a href=\"https://ero.to/([^\"]+)\"[\\s\\S]*?<img [^>]*src=\"([^\"]+)\"[\\s\\S]*?<div style=\"display: -webkit-box;[^>]*>([\\s\\S]*?)</div>\\s*</div>", Pattern.CASE_INSENSITIVE);
        Matcher mCard = pCard.matcher(html);

        while (mCard.find()) {
            String id = mCard.group(1).trim();
            if (id.endsWith("/")) {
                id = id.substring(0, id.length() - 1);
            }
            String img = mCard.group(2).trim();
            String title = mCard.group(3).trim();

            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(title);
            vod.setVodPic(img);
            vod.setVodRemarks("");
            list.add(vod);
        }
        return list;
    }
}
