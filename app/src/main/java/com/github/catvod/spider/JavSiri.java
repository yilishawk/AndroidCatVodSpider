package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
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

public class JavSiri extends Spider {

    private static final String HOST = "https://javsiri.cc";
    
    // 1. 密码门禁状态变量
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
        
        // 2. 调用密码门禁，若未解锁则抛出异常阻止源加载
        this.unlocked = PasswordGate.ensureUnlocked(context);
        if (!this.unlocked) {
            throw new Exception("Password verification failed. Source initialization aborted.");
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        // 3. 门禁检查
        if (!unlocked) {
            return Result.get().classes(new ArrayList<Class>()).string();
        }

        List<Class> classes = new ArrayList<Class>();
        // 精选的主要分类
        classes.add(new Class("anal-sex", "肛交"));
        classes.add(new Class("big-tits", "大奶子"));
        classes.add(new Class("ntr", "NTR"));
        classes.add(new Class("slim-pixelated", "苗条身材"));
        classes.add(new Class("blowjob", "口交"));
        classes.add(new Class("promiscuity", "淫乱"));
        classes.add(new Class("nice-tits", "好奶子"));
        classes.add(new Class("fair-skin", "白皙皮肤"));
        classes.add(new Class("mature-woman", "熟女"));
        classes.add(new Class("orgy", "群交"));
        classes.add(new Class("high-school-girl", "高中女生"));
        classes.add(new Class("slim", "苗条"));
        classes.add(new Class("uncensored", "无码"));
        classes.add(new Class("uniform", "制服"));
        classes.add(new Class("squirting", "喷水"));
        classes.add(new Class("creampie", "内射"));
        classes.add(new Class("pretty-girl", "漂亮女孩"));
        classes.add(new Class("hd", "HD高画质"));
        classes.add(new Class("masturbation", "撸管/自慰"));

        return Result.get().classes(classes).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        // 3. 门禁检查
        if (!unlocked) {
            int page = Integer.parseInt(pg);
            return Result.get().vod(new ArrayList<Vod>()).page(page, 0, 0, 0).string();
        }

        int page = Integer.parseInt(pg);
        // 拼接异步获取列表页数据的API接口，from参数传入对应页码
        String url = HOST + "/zh/tags/" + tid + "/?mode=async&function=get_block&block_id=list_videos_common_videos_list&sort_by=post_date&from=" + page + "&_=" + System.currentTimeMillis();
        String html = OkHttp.string(url, getHeaders());

        List<Vod> list = parseVideoList(html);
        
        // 改进后的翻页判断逻辑：
        // 如果当前页解析出来的视频数量大于等于10个，认为有下一页（KVS每页通常展示20-40个视频）
        int totalPage = page;
        if (list.size() >= 10) {
            totalPage = page + 1;
        }

        return Result.get().vod(list).page(page, totalPage, 20, 2000).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        // 3. 门禁检查
        if (!unlocked) {
            return Result.get().string();
        }

        String id = ids.get(0);
        String detailUrl = HOST + "/zh/video/" + id;
        String html = OkHttp.string(detailUrl, getHeaders());

        Vod vod = new Vod();
        vod.setVodId(id);
        
        // 提取视频标题
        Pattern pTitle = Pattern.compile("video_title:\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);
        Matcher mTitle = pTitle.matcher(html);
        String title = mTitle.find() ? mTitle.group(1) : "Video " + id;
        vod.setVodName(title);

        // 提取视频缩略封面图
        Pattern pPic = Pattern.compile("preview_url:\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);
        Matcher mPic = pPic.matcher(html);
        if (mPic.find()) {
            vod.setVodPic(mPic.group(1));
        }

        // 设置播放源
        vod.setVodPlayFrom("JavSiri主线");
        // 将视频ID或详情页URL传递给播放解析
        vod.setVodPlayUrl("立即播放$" + id);

        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        // 3. 门禁检查
        if (!unlocked) {
            return Result.get().vod(new ArrayList<Vod>()).string();
        }

        // 搜索采用其自带的异步搜索接口，from_videos代表视频页数，默认为1
        String url = HOST + "/zh/search/" + key + "/?mode=async&function=get_block&block_id=list_videos_videos_list_search_result&q=" + key + "&category_ids=&sort_by=post_date&from_videos=1&from_albums=1&_" + System.currentTimeMillis();
        String html = OkHttp.string(url, getHeaders());
        
        List<Vod> list = parseVideoList(html);
        return Result.get().vod(list).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 重新获取详情页获取kt_player的播放配置参数
        String detailUrl = HOST + "/zh/video/" + id;
        String html = OkHttp.string(detailUrl, getHeaders());

        String playUrl = "";

        // 1. 匹配 720p (高清晰度) 
        Pattern pAltUrl = Pattern.compile("video_alt_url:\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);
        Matcher mAltUrl = pAltUrl.matcher(html);
        if (mAltUrl.find()) {
            playUrl = mAltUrl.group(1);
        }

        // 2. 如果720p为空，则降级匹配 video_url (480p)
        if (TextUtils.isEmpty(playUrl)) {
            Pattern pUrl = Pattern.compile("video_url:\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);
            Matcher mUrl = pUrl.matcher(html);
            if (mUrl.find()) {
                playUrl = mUrl.group(1);
            }
        }

        return Result.get().url(playUrl).header(getHeaders()).string();
    }

    /**
     * 辅助解析：统一对 thumb 块的列表信息进行数据封装
     */
    private List<Vod> parseVideoList(String html) {
        List<Vod> list = new ArrayList<Vod>();
        if (TextUtils.isEmpty(html)) {
            return list;
        }

        // 匹配每个视频的大DIV容器
        Pattern pBlock = Pattern.compile("<div class=\"thumb thumb_rel item[^\"]*\">([\\s\\S]*?)</div>\\s*</div>", Pattern.CASE_INSENSITIVE);
        Matcher mBlock = pBlock.matcher(html);

        while (mBlock.find()) {
            String block = mBlock.group(1);

            // 提取详情ID路由 /zh/video/10649/nvh-008/ -> 10649/nvh-008/
            Pattern pId = Pattern.compile("href=\"https://javsiri.cc/zh/video/([^/]+/[^/]+)/\"", Pattern.CASE_INSENSITIVE);
            Matcher mId = pId.matcher(block);
            if (!mId.find()) continue;
            String id = mId.group(1);

            // 提取封面图 (data-original)
            Pattern pImg = Pattern.compile("data-preview=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher mImg = pImg.matcher(block);
            String img = mImg.find() ? mImg.group(1) : "";

            // 提取标题 (title)
            Pattern pTitle = Pattern.compile("title=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
            Matcher mTitle = pTitle.matcher(block);
            String title = mTitle.find() ? mTitle.group(1) : "Video";

            // 提取视频时长
            Pattern pDur = Pattern.compile("<div class=\"time\">([^<]+)</div>", Pattern.CASE_INSENSITIVE);
            Matcher mDur = pDur.matcher(block);
            String duration = mDur.find() ? mDur.group(1).trim() : "";

            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(title);
            vod.setVodPic(img);
            vod.setVodRemarks(duration);

            list.add(vod);
        }
        return list;
    }
}
