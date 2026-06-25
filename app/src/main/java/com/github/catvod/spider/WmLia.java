package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WmLia extends Spider {

    private static final String HOST = "https://www.wmlia.com";

    private static final String[][] CHANNELS = {
            {"1", "央视"},
            {"2", "卫视"},
            {"5", "地方"},
            {"6", "海外"},
    };

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.6788.76 Safari/537.36");
        h.put("Referer", HOST + "/");
        return h;
    }

    // ------------------------------------------------------------------ 列表

    private List<Vod> parseList(String html) {
        List<Vod> vods = new ArrayList<>();
        Document  doc  = Jsoup.parse(html);
        for (Element box : doc.select(".public-list-box")) {
            // href = /viv/detail/id/528/nid/1
            String href = box.select("a.public-list-exp").attr("href");
            String id   = extractPathSeg(href, "id");
            if (id.isEmpty()) continue;

            String name = box.select("a.time-title").text().trim();
            if (name.isEmpty()) name = box.select("a.public-list-exp").attr("title");

            // 封面：data-src 是懒加载真实地址
            String pic = box.select("img[data-src]").attr("data-src");
            if (pic.startsWith("/")) pic = HOST + pic;

            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vods.add(vod);
        }
        return vods;
    }

    /** 从路径段提取值，如 /viv/detail/id/528/nid/1 → extractPathSeg(href,"id")="528" */
    private String extractPathSeg(String path, String key) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals(key)) return parts[i + 1];
        }
        return "";
    }

    /** 解析总页数：找分页里最大页码数字 */
    private int parseTotalPage(Document doc) {
        int max = 1;
        for (Element a : doc.select(".page a, .pagination a")) {
            String text = a.text().trim();
            try { int n = Integer.parseInt(text); if (n > max) max = n; } catch (Exception ignored) {}
        }
        return max;
    }

    // ------------------------------------------------------------------ 详情

    /**
     * 详情页：解析所有线路（nid）及对应播放地址。
     * 线路列表在 ul.anthology-list-play li a，href 含 nid。
     * m3u8 地址在 iframe src 的 ?url= 参数里。
     */
    private void buildDetail(Vod vod, String vodId, Document doc) throws Exception {
        Elements lis = doc.select("ul.anthology-list-play li a");
        if (lis.isEmpty()) return;

        StringBuilder fromSb = new StringBuilder();
        StringBuilder urlSb  = new StringBuilder();

        for (int i = 0; i < lis.size(); i++) {
            Element a    = lis.get(i);
            String  href = a.attr("href");                   // /viv/detail/id/528/nid/2
            String  nid  = extractPathSeg(href, "nid");
            String  name = a.select("span").text().trim();
            if (name.isEmpty()) name = "线路" + (i + 1);
            if (nid.isEmpty()) continue;

            if (i > 0) { fromSb.append("$$$"); urlSb.append("$$$"); }
            fromSb.append(name);
            // 播放 id = vodId|nid，playerContent 里再取真实 m3u8
            urlSb.append(name).append("$").append(vodId).append("|").append(nid);
        }

        vod.setVodPlayFrom(fromSb.toString());
        vod.setVodPlayUrl(urlSb.toString());
    }

    /**
     * 从详情页 HTML 里提取 m3u8：
     * <iframe src="/static/ds3/dplayer.php?url=http://xxx.m3u8">
     * 取 ?url= 后面的真实地址。
     */
    private String extractM3u8(Document doc) {
        String iframeSrc = doc.select("iframe#video, .MacPlayer iframe").attr("src");
        if (iframeSrc.isEmpty()) {
            // 兜底：正则匹配任意 iframe src 里的 ?url=
            iframeSrc = doc.select("iframe[src*=dplayer]").attr("src");
        }
        int idx = iframeSrc.indexOf("?url=");
        if (idx >= 0) return iframeSrc.substring(idx + 5);
        // 再兜底：正则从整段 HTML 里抓
        Matcher m = Pattern.compile("[\"']https?://[^\"']+\\.m3u8[^\"']*[\"']").matcher(doc.html());
        if (m.find()) {
            String s = m.group();
            return s.substring(1, s.length() - 1);
        }
        return "";
    }

    // ------------------------------------------------------------------ Spider

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        for (String[] ch : CHANNELS) classes.add(new Class(ch[0], ch[1]));

        String    html  = OkHttp.string(HOST + "/viv/type/id/1/page/1", headers());
        List<Vod> vods  = parseList(html);

        return Result.get()
                .classes(classes)
                .filters(new LinkedHashMap<>())
                .vod(vods)
                .string();
    }

    @Override
    public String homeVideoContent() throws Exception {
        String html = OkHttp.string(HOST + "/viv/type/id/1/page/1", headers());
        return Result.get().vod(parseList(html)).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        int    page = pg == null || pg.isEmpty() ? 1 : Integer.parseInt(pg);
        String html = OkHttp.string(HOST + "/viv/type/id/" + tid + "/page/" + page, headers());
        Document doc = Jsoup.parse(html);
        int totalPage = parseTotalPage(doc);
        List<Vod> vods = parseList(html);

        return Result.get()
                .page(page, totalPage, 20, totalPage * 20)
                .vod(vods)
                .string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String vodId = ids.get(0);
        // 默认用 nid=1 加载详情页
        String   url = HOST + "/viv/detail/id/" + vodId + "/nid/1";
        String   html = OkHttp.string(url, headers());
        Document doc  = Jsoup.parse(html);

        String name = doc.select("h1, .detail-title, title").first() != null
                ? doc.select("h1, .detail-title").text().trim() : vodId;
        String pic  = doc.select(".detail-pic img, .public-list-exp img").attr("src");
        if (pic.startsWith("/")) pic = HOST + pic;

        Vod vod = new Vod();
        vod.setVodId(vodId);
        vod.setVodName(name);
        vod.setVodPic(pic);

        buildDetail(vod, vodId, doc);
        return Result.get().vod(vod).string();
    }

    /**
     * id 格式：vodId|nid（如 528|2）
     * 访问对应详情页，从 iframe src 的 ?url= 提取真实 m3u8。
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String[] parts = id.split("\\|", 2);
        String   vodId = parts[0];
        String   nid   = parts.length > 1 ? parts[1] : "1";

        String   url  = HOST + "/viv/detail/id/" + vodId + "/nid/" + nid;
        String   html = OkHttp.string(url, headers());
        Document doc  = Jsoup.parse(html);
        String   m3u8 = extractM3u8(doc);

        return Result.get().url(m3u8).header(headers()).string();
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
