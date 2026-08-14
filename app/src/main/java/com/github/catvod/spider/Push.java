package com.github.catvod.spider;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.github.catvod.bean.Result;
import com.github.catvod.bean.Sub;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Image;
import com.github.catvod.utils.Util;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Push extends Spider {

    private Cloud cloud;

    public Push() {
        cloud = new Cloud();
    }

    @Override
    public void init(Context context, String extend) {
        try {
            cloud.init(context, extend);
        } catch (Exception e) {
            SpiderDebug.log("Cloud init error: " + e.getMessage());
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String url = ids.get(0);

        // 优先让 Cloud 处理网盘链接
        String cloudResult = cloud.detailContent(ids);
        if (cloudResult != null && !cloudResult.isEmpty()) {
            return cloudResult;
        }

        // 普通链接走本地逻辑
        return Result.string(vod(url));
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            // 优先让 Cloud 处理网盘播放
            String cloudResult = cloud.playerContent(flag, id, vipFlags);
            if (cloudResult != null && !cloudResult.isEmpty()) {
                return cloudResult;
            }
        } catch (Exception e) {
            SpiderDebug.log("Cloud playerContent error: " + e.getMessage());
        }

        // 普通链接处理
        if (id.startsWith("http") && id.contains("***")) {
            id = id.replace("***", "#");
        }

        // 判断是否为迅雷链接
        if (Util.isThunder(id)) {
            return Result.get().url(id).string();
        }

        // 根据线路标志处理
        switch (flag) {
            case "直連":
            case "直连":
                return Result.get().url(id).subs(getSubs(id)).string();
            case "解析":
                return Result.get().parse().jx().url(id).string();
            case "嗅探":
                return Result.get().parse().url(id).string();
            case "迅雷":
                return Result.get().url(id).string();
            default:
                // 默认直连
                return Result.get().url(id).subs(getSubs(id)).string();
        }
    }

    private Vod vod(String url) {
        Vod vod = new Vod();
        vod.setVodId(url);
        vod.setVodPic(Image.PUSH);
        vod.setTypeName("FongMi");
        vod.setVodName(url.startsWith("file://") ? new File(url).getName() : url);

        // 处理 URL 中的 # 符号
        if (url.startsWith("http") && url.contains("#")) {
            url = url.replace("#", "***");
        }

        // 判断是否为迅雷链接
        if (Util.isThunder(url)) {
            vod.setVodPlayUrl("播放$" + url);
            vod.setVodPlayFrom("迅雷");
            return vod;
        }

        // 判断是否为多集格式（包含 $ 或换行符）
        if (url.contains("$") || url.contains("\n")) {
            String[] episodes = url.contains("\n") ? url.split("\n") : url.split("\\$");
            StringBuilder playUrl = new StringBuilder();
            StringBuilder playFrom = new StringBuilder();
            
            // 构建三条线路：直连、嗅探、解析
            for (int i = 0; i < 3; i++) {
                if (i > 0) {
                    playUrl.append("$$$");
                    playFrom.append("$$$");
                }
                List<String> episodeList = new ArrayList<>();
                for (String ep : episodes) {
                    String trimmed = ep.trim();
                    if (!trimmed.isEmpty()) {
                        String name = "第" + (episodeList.size() + 1) + "集";
                        episodeList.add(name + "$" + trimmed);
                    }
                }
                playUrl.append(TextUtils.join("#", episodeList));
                
                if (i == 0) playFrom.append("直連");
                else if (i == 1) playFrom.append("嗅探");
                else playFrom.append("解析");
            }
            
            vod.setVodPlayUrl(playUrl.toString());
            vod.setVodPlayFrom(playFrom.toString());
            return vod;
        }

        // 单链接 → 三条线路（直连、嗅探、解析）
        List<String> playUrls = new ArrayList<>();
        List<String> playFroms = Arrays.asList("直連", "嗅探", "解析");
        
        for (String from : playFroms) {
            playUrls.add("播放$" + url);
        }
        
        vod.setVodPlayUrl(TextUtils.join("$$$", playUrls));
        vod.setVodPlayFrom(TextUtils.join("$$$", playFroms));
        return vod;
    }

    private List<Sub> getSubs(String url) {
        List<Sub> subs = new ArrayList<>();
        if (url.startsWith("file://")) setFileSub(url, subs);
        if (url.startsWith("http://") || url.startsWith("https://")) setHttpSub(url, subs);
        return subs;
    }

    private void setHttpSub(String url, List<Sub> subs) {
        List<String> vodTypes = Arrays.asList("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm");
        List<String> subTypes = Arrays.asList("srt", "ass", "ssa", "vtt");
        
        String ext = Util.getExt(url);
        if (!vodTypes.contains(ext)) return;
        
        for (String subExt : subTypes) {
            detectSub(url, subExt, subs);
        }
    }

    private void detectSub(String url, String ext, List<Sub> subs) {
        String subUrl = Util.removeExt(url).concat(".").concat(ext);
        try {
            String content = OkHttp.string(subUrl);
            if (content.length() < 100) return;
            String name = Uri.parse(subUrl).getLastPathSegment();
            if (name != null) {
                subs.add(Sub.create().name(name).ext(ext).url(subUrl));
            }
        } catch (Exception ignored) {
            // 字幕不存在或请求失败，静默忽略
        }
    }

    private void setFileSub(String url, List<Sub> subs) {
        File file = new File(url.replace("file://", ""));
        if (file.getParentFile() == null) return;
        File[] files = file.getParentFile().listFiles();
        if (files == null) return;
        for (File f : files) {
            String ext = Util.getExt(f.getName());
            if (Util.isSub(ext)) {
                subs.add(Sub.create()
                        .name(Util.removeExt(f.getName()))
                        .ext(ext)
                        .url("file://" + f.getAbsolutePath()));
            }
        }
    }
}
