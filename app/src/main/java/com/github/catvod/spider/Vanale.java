package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.net.OkHttp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Vanale extends Spider {

    private static final String HOST = "https://a.v-anale.best";

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, George) Chrome/114.0.0.0 Safari/537.36");
        headers.put("Referer", HOST + "/");
        return headers;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<Class>();
        // 根据提供的内容， 精选常用分类并翻译为中文
        classes.add(new Class("asian", "亚洲"));
        classes.add(new Class("rimming", "毒龙/舔肛"));
        classes.add(new Class("granny", "熟女/祖母"));
        classes.add(new Class("bondage", "捆绑/BDSM"));
        classes.add(new Class("pregnant", "孕妇"));
        classes.add(new Class("blonde", "金发女郎"));
        classes.add(new Class("big-ass", "大屁股"));
        classes.add(new Class("big-tits", "大胸"));
        classes.add(new Class("big-dick", "大器粗活"));
        classes.add(new Class("brazzers", "Brazers"));
        classes.add(new Class("brunette", "黑发"));
        classes.add(new Class("stockings", "丝袜"));
        classes.add(new Class("hairy", "多毛"));
        classes.add(new Class("threesome", "三人行"));
        classes.add(new Class("deep-anal", "深度肛交"));
        classes.add(new Class("rough", "粗暴"));
        classes.add(new Class("gangbang", "群交"));
        classes.add(new Class("double-anal", "双洞肛交"));
        classes.add(new Class("dildo", "假阳具"));
        classes.add(new Class("homemade", "自拍自制"));
        classes.add(new Class("hardcore", "硬核硬核"));
        classes.add(new Class("mature", "熟女"));
        classes.add(new Class("toys", "性玩具"));
        classes.add(new Class("ass-to-mouth", "肛转口"));
        classes.add(new Class("cuckold", "绿帽"));
        classes.add(new Class("anal-creampie", "肛内射精"));
        classes.add(new Class("cosplay", "角色扮演"));
        classes.add(new Class("beautiful", "唯美"));
        classes.add(new Class("babe", "美女"));
        classes.add(new Class("pussy-licking", "舔阴"));
        classes.add(new Class("latina", "拉丁"));
        classes.add(new Class("lesbian", "拉拉"));
        classes.add(new Class("milf", "人妻熟女"));
        classes.add(new Class("massage", "按摩"));
        classes.add(new Class("masturbation", "自慰"));
        classes.add(new Class("stepmom", "继母"));
        classes.add(new Class("blowjob", "口交"));
        classes.add(new Class("young", "年轻"));
        classes.add(new Class("outdoor", "户外"));
        classes.add(new Class("ebony", "黑人女"));
        classes.add(new Class("pov", "第一视角"));
        classes.add(new Class("first-anal", "首次尝试"));
        classes.add(new Class("russian", "俄罗斯"));
        classes.add(new Class("wife", "妻子"));
        classes.add(new Class("bbc", "巨根黑人"));
        classes.add(new Class("sister", "姐妹"));
        classes.add(new Class("family", "乱伦家庭"));
        classes.add(new Class("squirt", "潮吹"));
        classes.add(new Class("solo", "独奏"));
        classes.add(new Class("student", "学生妹"));
        classes.add(new Class("tiny", "娇小玲珑"));
        classes.add(new Class("amateur", "业余"));
        classes.add(new Class("teen", "少女"));
        classes.add(new Class("japanese", "日本"));

        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Integer.parseInt(pg);
        String url = HOST + "/" + tid + "?page=" + page;
        String html = OkHttp.string(url, getHeaders());

        List<Vod> list = new ArrayList<Vod>();
        if (TextUtils.isEmpty(html)) {
            return Result.get().vod(list).page(page, 0, 0, 0).string();
        }

        // 解析视频列表核心块
        Pattern pVideo = Pattern.compile("<div class=\"video\">([\\s\\S]*?)</div>\\s*</div>", Pattern.CASE_INSENSITIVE);
        Matcher mVideo = pVideo.matcher(html);

        while (mVideo.find()) {
            String block = mVideo.group(1);

            // 提取 id (data-id)
            Pattern pId = Pattern.compile("data-id=\"(\\d+)\"", Pattern.CASE_INSENSITIVE);
            Matcher mId = pId.matcher(block);
            if (!mId.find()) continue;
            String id = mId.group(1);

            // 提取图片 (src)
            Pattern pImg = Pattern.compile("<img[^>]+src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher mImg = pImg.matcher(block);
            String img = mImg.find() ? mImg.group(1) : "";
            if (img.startsWith("/")) {
                img = HOST + img;
            }

            // 提取标题 (alt属性通常可用作标题)
            Pattern pTitle = Pattern.compile("<img[^>]+alt=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher mTitle = pTitle.matcher(block);
            String title = mTitle.find() ? mTitle.group(1) : "Video " + id;

            // 提取时长 (duration)
            Pattern pDur = Pattern.compile("<span class=\"duration\">[^<]*</span>\\s*([^<]+)</span>", Pattern.CASE_INSENSITIVE);
            Matcher mDur = pDur.matcher(block);
            String duration = mDur.find() ? mDur.group(1).trim() : "";

            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(title);
            vod.setVodPic(img);
            vod.setVodRemarks(duration);

            list.add(vod);
        }

        // 判断是否有下一页（简单通过包含"?page=当前页+1"字符来做预判）
        int nextPg = page + 1;
        int totalPage = html.contains("page=" + nextPg) ? nextPg : page;

        return Result.get().vod(list).page(page, totalPage, 20, 2000).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);

        // 规范：详情页也是播放页，在 detail 里将信息填完整
        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName("视频详情 #" + id);
        vod.setVodPic(HOST + "/images/" + id + ".jpg"); // 缺省封面图规律预测
        vod.setVodPlayFrom("Vanale高速线");
        // 播放ID直接使用视频的ID
        vod.setVodPlayUrl("播放播放$" + id); 

        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        // 如果该网站支持搜索，可在此处拼装搜索URL。现预留符合规范的空返回。
        List<Vod> list = new ArrayList<Vod>();
        return Result.get().vod(list).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 1. 请求 /get/{id} 接口获取真实 m3u8 一级地址
        String targetUrl = HOST + "/get/" + id;
        String m3u8MainUrl = OkHttp.string(targetUrl, getHeaders());

        if (TextUtils.isEmpty(m3u8MainUrl)) {
            return Result.get().url("").string();
        }
        m3u8MainUrl = m3u8MainUrl.trim();

        // 2. 请求一级 m3u8 获取多码率列表，进行重排
        String m3u8Content = OkHttp.string(m3u8MainUrl, getHeaders());
        if (TextUtils.isEmpty(m3u8Content)) {
            // 如果拿不到内容，直接退回一级m3u8地址
            return Result.get().url(m3u8MainUrl).header(getHeaders()).string();
        }

        // 解析二级 m3u8 线路并按高清晰度向下重排
        String baseUrl = m3u8MainUrl.substring(0, m3u8MainUrl.lastIndexOf("/") + 1);
        
        // 分别抓取对应档位的子m3u8链接
        String p1080 = findSubM3u8(m3u8Content, "1080p");
        String p720 = findSubM3u8(m3u8Content, "720p");
        String p480 = findSubM3u8(m3u8Content, "480p");
        String p360 = findSubM3u8(m3u8Content, "360p");
        String p250 = findSubM3u8(m3u8Content, "250p");

        String finalPlayUrl = m3u8MainUrl; // 缺省
        
        // 严格遵循高到低排列：有1080p先用1080p，以此类推
        if (!p1080.isEmpty()) {
            finalPlayUrl = p1080.startsWith("http") ? p1080 : baseUrl + p1080;
        } else if (!p720.isEmpty()) {
            finalPlayUrl = p720.startsWith("http") ? p720 : baseUrl + p720;
        } else if (!p480.isEmpty()) {
            finalPlayUrl = p480.startsWith("http") ? p480 : baseUrl + p480;
        } else if (!p360.isEmpty()) {
            finalPlayUrl = p360.startsWith("http") ? p360 : baseUrl + p360;
        } else if (!p250.isEmpty()) {
            finalPlayUrl = p250.startsWith("http") ? p250 : baseUrl + p250;
        }

        return Result.get().url(finalPlayUrl).header(getHeaders()).string();
    }

    // 辅助提取方法：通过NAME寻找对应的hls文件文件名
    private String findSubM3u8(String content, String resolutionName) {
        try {
            Pattern pattern = Pattern.compile("RESOLUTION=[^,\\n\\r]+,NAME=\"" + resolutionName + "\"\\s*\\n?([^\\#\\n\\r]+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (Exception e) {
            SpiderDebug.log(e);
        }
        return "";
    }
}
