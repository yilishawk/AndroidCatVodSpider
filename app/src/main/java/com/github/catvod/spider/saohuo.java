package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;

public class saohuo extends Spider {

    private String host = "https://shdy3.com"; // 默认备用域名
    private final HashMap<String, String> headers = new HashMap<>();

    public saohuo() {
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 12; V2196A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.5060.129 Mobile Safari/537.36");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("Accept-Encoding", "gzip, deflate");
    }

    public String getName() {
        return "骚火电影[首页秒开版]";
    }

    public void init(String extend) {
        try {
            String html = OkHttp.string("http://shapp.us", headers);
            Pattern pattern = Pattern.compile("(https://.*?\\.com).*?最新网址");
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                host = matcher.group(1).trim();
            }
        } catch (Exception e) {
            System.out.println("[骚火] 域名解析失败: " + e.getMessage());
        }
    }

    public boolean isVideoCast() {
        return true;
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("20", "国产剧"));
        classes.add(new Class("1", "电影"));
        classes.add(new Class("2", "电视剧"));
        classes.add(new Class("4", "动漫"));

        Map<String, List<Map<String, Object>>> filters = new HashMap<>();

        // 电视剧筛选
        List<Map<String, Object>> tvFilters = new ArrayList<>();
        Map<String, Object> tvType = new HashMap<>();
        tvType.put("key", "cateId");
        tvType.put("name", "类型");
        List<Map<String, String>> tvValues = new ArrayList<>();
        tvValues.add(createFilterValue("全部", "2"));
        tvValues.add(createFilterValue("大陆", "20"));
        tvValues.add(createFilterValue("TVB", "21"));
        tvValues.add(createFilterValue("韩剧", "22"));
        tvValues.add(createFilterValue("美剧", "23"));
        tvType.put("value", tvValues);
        tvFilters.add(tvType);

        // 电影筛选
        List<Map<String, Object>> movieFilters = new ArrayList<>();
        Map<String, Object> movieType = new HashMap<>();
        movieType.put("key", "cateId");
        movieType.put("name", "类型");
        List<Map<String, String>> movieValues = new ArrayList<>();
        movieValues.add(createFilterValue("全部", "1"));
        movieValues.add(createFilterValue("喜剧", "6"));
        movieValues.add(createFilterValue("爱情", "7"));
        movieValues.add(createFilterValue("动作", "9"));
        movieValues.add(createFilterValue("科幻", "10"));
        movieValues.add(createFilterValue("剧情", "15"));
        movieType.put("value", movieValues);
        movieFilters.add(movieType);

        // 动漫筛选
        List<Map<String, Object>> animeFilters = new ArrayList<>();
        Map<String, Object> animeType = new HashMap<>();
        animeType.put("key", "cateId");
        animeType.put("name", "类型");
        List<Map<String, String>> animeValues = new ArrayList<>();
        animeValues.add(createFilterValue("全部", "4"));
        animeType.put("value", animeValues);
        animeFilters.add(animeType);

        filters.put("20", tvFilters);
        filters.put("1", movieFilters);
        filters.put("2", tvFilters);
        filters.put("4", animeFilters);

        Map<String, Object> result = new HashMap<>();
        result.put("class", classes);
        result.put("filters", filters);
        return new Gson().toJson(result);
    }

    private Map<String, String> createFilterValue(String name, String value) {
        Map<String, String> item = new HashMap<>();
        item.put("n", name);
        item.put("v", value);
        return item;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        int page = Integer.parseInt(pg);
        String cateId = extend.getOrDefault("cateId", tid);
        String url = host + "/list/" + cateId + "-" + page + ".html";
        try {
            String html = OkHttp.string(url, headers);
            Document doc = Jsoup.parse(html);
            List<Vod> list = parseList(doc);
            return Result.string(page, 1, list.size(), 999, list);
        } catch (Exception e) {
            return Result.string(page, 0, 0, 0, new ArrayList<Vod>());
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        String url = ids.get(0);
        try {
            String html = OkHttp.string(url, headers);
            Document doc = Jsoup.parse(html);

            Element vInfo = doc.selectFirst(".v_info_box");
            String infoText = vInfo != null ? vInfo.select("p").text() : "";

            String area = "";
            String year = "";
            String type = "";
            String director = "";
            String actor = "";

            Matcher areaMatcher = Pattern.compile("^(.*?)\\s*/").matcher(infoText);
            if (areaMatcher.find()) area = areaMatcher.group(1).trim();
            Matcher yearMatcher = Pattern.compile("(\\d{4})").matcher(infoText);
            if (yearMatcher.find()) year = yearMatcher.group(1);
            Matcher typeMatcher = Pattern.compile("\\d{4}\\s*/\\s*(.*?)\\s*/").matcher(infoText);
            if (typeMatcher.find()) type = typeMatcher.group(1).trim();
            Matcher directorMatcher = Pattern.compile("导演:(.*?)(?= / 主演:|$)").matcher(infoText);
            if (directorMatcher.find()) director = directorMatcher.group(1).trim();
            Matcher actorMatcher = Pattern.compile("主演:(.*?)$").matcher(infoText);
            if (actorMatcher.find()) actor = actorMatcher.group(1).trim();

            Element titleElem = vInfo != null ? vInfo.selectFirst("h1.v_title a") : null;
            String name = titleElem != null ? titleElem.text() : "";

            Element imgElem = doc.selectFirst(".v_img img");
            String pic = "";
            if (imgElem != null) {
                pic = imgElem.attr("data-original");
                if (pic.isEmpty()) pic = imgElem.attr("src");
            }

            Element contentElem = doc.selectFirst(".p_txt.show_part");
            String content = contentElem != null ? contentElem.text().replace("剧情介绍", "").trim() : "";

            Elements playFromElems = doc.select(".play_from ul li");
            List<String> playFromList = new ArrayList<>();
            for (Element li : playFromElems) {
                playFromList.add(li.text());
            }
            String playFrom = String.join("$$$", playFromList);

            Elements playLinkGroups = doc.select("#play_link li");
            List<String> playUrlGroups = new ArrayList<>();

            for (Element group : playLinkGroups) {
                List<Map.Entry<String, String>> links = new ArrayList<>();
                for (Element a : group.select("a")) {
                    String text = a.text();
                    String href = a.attr("href");
                    String fullUrl = href.startsWith("http") ? href : host + href;
                    links.add(new AbstractMap.SimpleEntry<>(text, fullUrl));
                }
                links.sort((o1, o2) -> naturalCompare(o1.getKey(), o2.getKey()));
                List<String> formatted = new ArrayList<>();
                for (Map.Entry<String, String> entry : links) {
                    formatted.add(entry.getKey() + "$" + entry.getValue());
                }
                playUrlGroups.add(String.join("#", formatted));
            }
            String playUrl = String.join("$$$", playUrlGroups);

            Vod vod = new Vod();
            vod.setVodId(url);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodRemarks(type); // 类型放入备注
            vod.setVodArea(area);
            vod.setVodYear(year);
            vod.setVodDirector(director);
            vod.setVodActor(actor);
            vod.setVodContent(content);
            vod.setVodPlayFrom(playFrom);
            vod.setVodPlayUrl(playUrl);

            return Result.string(vod);
        } catch (Exception e) {
            System.out.println("详情页解析错误: " + e.getMessage());
            return Result.string(new ArrayList<Vod>());
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try {
            String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8.name());
            String url = host + "/s----------.html?wd=" + encodedKey;
            String html = OkHttp.string(url, headers);
            Document doc = Jsoup.parse(html);
            List<Vod> list = parseList(doc);
            return Result.string(list);
        } catch (Exception e) {
            return Result.string(new ArrayList<Vod>());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            String html = OkHttp.string(id, headers);

            Matcher iframeMatcher = Pattern.compile("iframe src=\"(.*?)\"").matcher(html);
            if (!iframeMatcher.find()) {
                return Result.get().url(id).header(headers).string();
            }
            String jxUrl = iframeMatcher.group(1);

            HashMap<String, String> jxHeaders = new HashMap<>(headers);
            jxHeaders.put("Referer", host + "/");
            String jxHtml = OkHttp.string(jxUrl, jxHeaders);

            Matcher urlMatcher = Pattern.compile("var url = \"(.*?)\";").matcher(jxHtml);
            Matcher tMatcher = Pattern.compile("var t = \"(.*?)\";").matcher(jxHtml);
            Matcher keyMatcher = Pattern.compile("var key = OKOK\\(\"(.*?)\"\\);").matcher(jxHtml);
            if (!urlMatcher.find() || !tMatcher.find() || !keyMatcher.find()) {
                return Result.get().url(id).header(headers).string();
            }
            String urlVal = urlMatcher.group(1);
            String tVal = tMatcher.group(1);
            String encodedKey = keyMatcher.group(1);

            Matcher eeMatcher = Pattern.compile("const ee = (\\{.*?\\}) ;").matcher(jxHtml);
            Map<String, String> eeDict = new HashMap<>();
            if (eeMatcher.find()) {
                String eeJson = eeMatcher.group(1);
                com.google.gson.JsonObject eeObj = com.google.gson.JsonParser.parseString(eeJson).getAsJsonObject();
                for (Map.Entry<String, com.google.gson.JsonElement> entry : eeObj.entrySet()) {
                    eeDict.put(entry.getKey(), entry.getValue().getAsString());
                }
            } else {
                return Result.get().url(id).header(headers).string();
            }

            String realKey = decodeKey(encodedKey, eeDict);

            String apiUrl = "https://hhjx.hhplayer.com/api.php";
            Map<String, String> payload = new HashMap<>();
            payload.put("url", urlVal);
            payload.put("t", tVal);
            payload.put("key", realKey);
            payload.put("act", "0");
            payload.put("play", "1");

            HashMap<String, String> apiHeaders = new HashMap<>(headers);
            apiHeaders.put("Origin", "https://hhjx.hhplayer.com");
            apiHeaders.put("Referer", jxUrl);
            apiHeaders.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            apiHeaders.put("X-Requested-With", "XMLHttpRequest");

            String postBody = buildPostBody(payload);
            OkResult okResult = OkHttp.post(apiUrl, postBody, apiHeaders);
            String apiResp = okResult.string();  // 关键：调用 string() 获取响应体

            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(apiResp).getAsJsonObject();
            if (json.get("code").getAsInt() == 200) {
                String videoUrl = json.get("url").getAsString();
                if (!videoUrl.startsWith("http")) {
                    videoUrl = "https://hhjx.hhplayer.com" + videoUrl;
                }
                HashMap<String, String> finalHeader = new HashMap<>();
                finalHeader.put("User-Agent", headers.get("User-Agent"));
                finalHeader.put("Origin", "https://hhjx.hhplayer.com");
                return Result.get().url(videoUrl).header(finalHeader).string();
            }
        } catch (Exception e) {
            System.out.println("Player Error: " + e.getMessage());
        }
        return Result.get().url(id).header(headers).string();
    }

    // ----------------- 辅助方法 -----------------

    private List<Vod> parseList(Document doc) {
        List<Vod> list = new ArrayList<>();
        Elements items = doc.select("ul.v_list li");
        for (Element item : items) {
            Element a = item.selectFirst(".v_img a");
            if (a == null) continue;
            String link = a.attr("href");
            String fullId = link.startsWith("http") ? link : host + link;
            Element img = item.selectFirst(".v_img img");
            String pic = img != null ? img.attr("data-original") : "";
            if (pic.isEmpty() && img != null) pic = img.attr("src");
            String name = item.selectFirst(".v_title a") != null ? item.selectFirst(".v_title a").text() : "";
            String remark = item.selectFirst(".v_note") != null ? item.selectFirst(".v_note").text() : "";
            list.add(new Vod(fullId, name, pic, remark));
        }
        return list;
    }

    private int naturalCompare(String s1, String s2) {
        Pattern numPattern = Pattern.compile("(\\d+)");
        Matcher m1 = numPattern.matcher(s1);
        Matcher m2 = numPattern.matcher(s2);
        if (m1.find() && m2.find()) {
            int num1 = Integer.parseInt(m1.group(1));
            int num2 = Integer.parseInt(m2.group(1));
            if (num1 != num2) return Integer.compare(num1, num2);
        }
        return s1.compareTo(s2);
    }

    private String decodeKey(String encodedStr, Map<String, String> eeDict) {
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(encodedStr);
            String decoded = new String(decodedBytes, StandardCharsets.UTF_8);
            List<String> keys = new ArrayList<>(eeDict.keySet());
            keys.sort((a, b) -> Integer.compare(b.length(), a.length()));

            StringBuilder result = new StringBuilder();
            int i = 0;
            while (i < decoded.length()) {
                boolean matched = false;
                for (String k : keys) {
                    if (decoded.startsWith(k, i)) {
                        result.append(eeDict.get(k));
                        i += k.length();
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    result.append(decoded.charAt(i));
                    i++;
                }
            }
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String buildPostBody(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(entry.getKey()).append("=").append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
