package com.github.catvod.spider;

import android.text.TextUtils;
import android.util.Log;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;





import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class Danmu extends Spider {
    public static Object[] AppDanmu(Map<String, String> map) {
        SpiderDebug.log("开始获取弹幕");
        Object[] objArr = {200, "application/xml", new ByteArrayInputStream("".getBytes())};
        String str = map.get("vodName");
        if (TextUtils.isEmpty(str)) {
            return objArr;
        }
        String realName = getRealName(str);
        int d = d(map.get("vodIndex"));
        boolean z = false;
        String a = C0646k.a(C0646k.c("/config.json"));
        if (!TextUtils.isEmpty(a) && "彩色".equals(new JSONObject(a).optString("danmu"))) {
            z = true;
        }
        String b = K.b(realName, d);
        if (TextUtils.isEmpty(b)) {
            b = getDanmuFromOK360(realName, d);
        }
        if (TextUtils.isEmpty(b)) {
            b = getDanmuFromOKJinchan(realName, d);
        }
        if (TextUtils.isEmpty(b)) {
            b = getDanmuFromJinchanZY(realName, d);
        }
        if (TextUtils.isEmpty(b)) {
            b = getDanmuFrom1314(realName, d);
        }
        if (TextUtils.isEmpty(b)) {
            C0634I.i("弹幕加载失败");
            return objArr;
        }
        String formatDanmuUrl2 = formatDanmuUrl2(b);
        String updateDanmuColors = z ? updateDanmuColors(formatDanmuUrl2) : updateDanmuWhite(formatDanmuUrl2);
        if (!TextUtils.isEmpty(updateDanmuColors)) {
            C0634I.i("弹幕加载成功");
        }
        C0647l.a("searchvodname", "");
        objArr[2] = new ByteArrayInputStream(updateDanmuColors.getBytes());
        return objArr;
    }

    public static Object[] Danmu(Map<String, String> map) {
        boolean z = false;
        Object[] objArr = {200, "application/xml", new ByteArrayInputStream("".getBytes())};
        String a = C0646k.a(C0646k.c("/config.json"));
        if (!TextUtils.isEmpty(a) && "彩色".equals(new JSONObject(a).optString("danmu"))) {
            z = true;
        }
        String str = map.get("site");
        String str2 = map.get("url");
        if (!TextUtils.isEmpty(str2) && !str2.startsWith("http")) {
            str2 = URLDecoder.decode(str2);
        }
        String str3 = "";
        if ("js".equals(str)) {
            String formatDanmuUrl2 = formatDanmuUrl2(str2);
            str3 = z ? updateDanmuColors(formatDanmuUrl2) : updateDanmuWhite(formatDanmuUrl2);
            if (TextUtils.isEmpty(str3)) {
                C0634I.i("弹幕加载失败");
            } else {
                C0634I.i("弹幕加载成功");
            }
        } else if ("wangpan".equals(str)) {
            String str4 = getRealName(C0647l.b("danmuvodname")).split(" ")[0];
            int d = d(C0647l.b("danmuvodindex"));
            String b = K.b(str4, d);
            if (TextUtils.isEmpty(b)) {
                b = getDanmuFromPanOK360(str4, d);
            }
            if (TextUtils.isEmpty(b)) {
                b = getDanmuFromOKJinchanSpace(str4, d);
            }
            if (TextUtils.isEmpty(b)) {
                b = getDanmuFromJinchanZY(str4, d);
            }
            if (TextUtils.isEmpty(b)) {
                b = getDanmuFrom1314(str4, d);
            }
            if (TextUtils.isEmpty(b)) {
                str3 = "";
                C0634I.i("弹幕加载失败");
            } else {
                String formatDanmuUrl22 = formatDanmuUrl2(b);
                str3 = z ? updateDanmuColors(formatDanmuUrl22) : updateDanmuWhite(formatDanmuUrl22);
                if (!TextUtils.isEmpty(str3)) {
                    C0634I.i("弹幕加载成功");
                }
            }
        }
        C0647l.a("searchvodname", "");
        objArr[2] = new ByteArrayInputStream(str3.getBytes());
        return objArr;
    }

    public static Object[] DiyDanmu(Map<String, String> map) {
        SpiderDebug.log("开始获取弹幕");
        Object[] objArr = {200, "application/xml", new ByteArrayInputStream("".getBytes())};
        JSONObject jSONObject = new JSONObject(C0619b.l("http://127.0.0.1:9978/media", new HashMap()));
        String realName = getRealName(jSONObject.optString("title"));
        String optString = jSONObject.optString("artist");
        String str = !TextUtils.isEmpty(optString) ? optString : realName;
        if (TextUtils.isEmpty(realName)) {
            return objArr;
        }
        int d = d(str);
        boolean z = false;
        String a = C0646k.a(C0646k.c("/config.json"));
        if (!TextUtils.isEmpty(a) && "彩色".equals(new JSONObject(a).optString("danmu"))) {
            z = true;
        }
        String b = K.b(realName, d);
        if (TextUtils.isEmpty(b)) {
            b = getDanmuFromOK360(realName, d);
        }
        if (TextUtils.isEmpty(b)) {
            b = getDanmuFromOKJinchan(realName, d);
        }
        if (TextUtils.isEmpty(b)) {
            b = getDanmuFromJinchanZY(realName, d);
        }
        if (TextUtils.isEmpty(b)) {
            b = getDanmuFrom1314(realName, d);
        }
        if (TextUtils.isEmpty(b)) {
            C0634I.i("弹幕加载失败");
            return objArr;
        }
        String formatDanmuUrl2 = formatDanmuUrl2(b);
        String updateDanmuColors = z ? updateDanmuColors(formatDanmuUrl2) : updateDanmuWhite(formatDanmuUrl2);
        if (!TextUtils.isEmpty(updateDanmuColors)) {
            C0634I.i("弹幕加载成功");
        }
        C0647l.a("searchvodname", "");
        objArr[2] = new ByteArrayInputStream(updateDanmuColors.getBytes());
        return objArr;
    }

    private static String a(String str) {
        try {
            if (TextUtils.isEmpty(str)) {
                return "";
            }
            Document newDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            newDocument.setXmlStandalone(true);
            Element createElement = newDocument.createElement("i");
            newDocument.appendChild(createElement);
            Element createElement2 = newDocument.createElement("chatserver");
            createElement2.setTextContent("");
            createElement.appendChild(createElement2);
            Element createElement3 = newDocument.createElement("chatid");
            createElement3.setTextContent("0");
            createElement.appendChild(createElement3);
            Element createElement4 = newDocument.createElement("mission");
            createElement4.setTextContent("0");
            createElement.appendChild(createElement4);
            Element createElement5 = newDocument.createElement("maxlimit");
            createElement5.setTextContent("1500");
            createElement.appendChild(createElement5);
            Element createElement6 = newDocument.createElement("state");
            createElement6.setTextContent("0");
            createElement.appendChild(createElement6);
            Element createElement7 = newDocument.createElement("real_name");
            createElement7.setTextContent("0");
            createElement.appendChild(createElement7);
            Element createElement8 = newDocument.createElement("source");
            createElement8.setTextContent("k-v");
            createElement.appendChild(createElement8);
            Element createElement9 = newDocument.createElement("d");
            createElement9.setAttribute("p", "30,1,25,16711680");
            createElement9.setTextContent("");
            createElement.appendChild(createElement9);
            JSONArray optJSONArray = new JSONObject(str).optJSONArray("danmuku");
            for (int i = 0; i < optJSONArray.length(); i++) {
                JSONArray optJSONArray2 = optJSONArray.optJSONArray(i);
                String optString = optJSONArray2.optString(4);
                if (!optString.contains("请遵守弹幕礼仪") && !optString.contains("官方弹幕库") && !optString.contains("未传入链接调用") && !optString.contains("弹幕列队") && !optString.contains("火花剧场") && !optString.contains("云烟小助手") && !optString.contains("微信公众号")) {
                    optJSONArray2.optString(2);
                    String format = String.format("%s,1,25,%s", optJSONArray2.optString(0), generateCombinedRGB());
                    Element createElement10 = newDocument.createElement("d");
                    createElement10.setAttribute("p", format);
                    createElement10.setTextContent(optString);
                    createElement.appendChild(createElement10);
                }
            }
            Transformer newTransformer = TransformerFactory.newInstance().newTransformer();
            newTransformer.setOutputProperty("encoding", "UTF-8");
            newTransformer.setOutputProperty("indent", "yes");
            DOMSource dOMSource = new DOMSource(newDocument);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            newTransformer.transform(dOMSource, new StreamResult(byteArrayOutputStream));
            return byteArrayOutputStream.toString();
        } catch (Exception e) {
            SpiderDebug.log("生成弹幕出错:" + e);
            return "";
        }
    }

    private static String b(String str) {
        try {
            if (TextUtils.isEmpty(str)) {
                return "";
            }
            Document newDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            newDocument.setXmlStandalone(true);
            Element createElement = newDocument.createElement("i");
            newDocument.appendChild(createElement);
            Element createElement2 = newDocument.createElement("chatserver");
            createElement2.setTextContent("");
            createElement.appendChild(createElement2);
            Element createElement3 = newDocument.createElement("chatid");
            createElement3.setTextContent("0");
            createElement.appendChild(createElement3);
            Element createElement4 = newDocument.createElement("mission");
            createElement4.setTextContent("0");
            createElement.appendChild(createElement4);
            Element createElement5 = newDocument.createElement("maxlimit");
            createElement5.setTextContent("1500");
            createElement.appendChild(createElement5);
            Element createElement6 = newDocument.createElement("state");
            createElement6.setTextContent("0");
            createElement.appendChild(createElement6);
            Element createElement7 = newDocument.createElement("real_name");
            createElement7.setTextContent("0");
            createElement.appendChild(createElement7);
            Element createElement8 = newDocument.createElement("source");
            createElement8.setTextContent("k-v");
            createElement.appendChild(createElement8);
            Element createElement9 = newDocument.createElement("d");
            createElement9.setAttribute("p", "30,1,25,16711680");
            createElement9.setTextContent("");
            createElement.appendChild(createElement9);
            JSONArray optJSONArray = new JSONObject(str).optJSONArray("danmuku");
            for (int i = 0; i < optJSONArray.length(); i++) {
                JSONArray optJSONArray2 = optJSONArray.optJSONArray(i);
                String optString = optJSONArray2.optString(4);
                if (!optString.contains("请遵守弹幕礼仪") && !optString.contains("官方弹幕库") && !optString.contains("未传入链接调用") && !optString.contains("弹幕列队") && !optString.contains("火花剧场") && !optString.contains("云烟小助手") && !optString.contains("微信公众号")) {
                    optJSONArray2.optString(2);
                    String format = String.format("%s,1,25,%s", optJSONArray2.optString(0), generateCombinedWhite());
                    Element createElement10 = newDocument.createElement("d");
                    createElement10.setAttribute("p", format);
                    createElement10.setTextContent(optString);
                    createElement.appendChild(createElement10);
                }
            }
            Transformer newTransformer = TransformerFactory.newInstance().newTransformer();
            newTransformer.setOutputProperty("encoding", "UTF-8");
            newTransformer.setOutputProperty("indent", "yes");
            DOMSource dOMSource = new DOMSource(newDocument);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            newTransformer.transform(dOMSource, new StreamResult(byteArrayOutputStream));
            return byteArrayOutputStream.toString();
        } catch (Exception e) {
            SpiderDebug.log("生成弹幕出错:" + e);
            return "";
        }
    }

    public static int d(String str) {
        if (str != null && !str.isEmpty()) {
            String replaceAll = str.replaceAll("\\[.*?\\]", "");
            if (replaceAll.contains("S") && replaceAll.contains("E")) {
                Matcher matcher = Pattern.compile("S\\d+E(\\d{2,3})").matcher(replaceAll);
                if (matcher.find()) {
                    return Integer.parseInt(String.valueOf(Integer.parseInt(matcher.group(1))));
                }
            } else {
                Matcher matcher2 = Pattern.compile("(\\d{4})[-._]?(\\d{2})[-._]?(\\d{2})").matcher(replaceAll);
                if (matcher2.find()) {
                    return Integer.parseInt(matcher2.group(1) + matcher2.group(2) + matcher2.group(3));
                }
                Matcher matcher3 = Pattern.compile("(\\d+)([a-zA-Z]*|(?:\\s+.*)?)").matcher(replaceAll.split("\\.")[0]);
                if (matcher3.find()) {
                    return Integer.parseInt(String.valueOf(Integer.parseInt(matcher3.group(1))));
                }
            }
        }
        return 1;
    }

    public static String formatDanmuUrl(String str) {
        if (str == null || str.length() == 0) {
            return "";
        }
        HashMap hashMap = new HashMap();
        String l = C0619b.l("https://danmu.huaqi.pro/?url=" + str, hashMap);
        if (l == null || l.length() <= 0 || !l.startsWith("{") || !l.contains("\"code\":23")) {
            String l2 = C0619b.l("https://dmku.hls.one/?ac=dm&url=" + str, hashMap);
            if (l2 == null || l2.length() <= 0 || !l2.startsWith("{") || !l2.contains("\"code\":23")) {
                String l3 = C0619b.l("https://danmu.zxz.ee/?type=json&id=" + str, hashMap);
                if (l3 == null || l3.length() <= 0 || !l3.startsWith("{") || !l3.contains("\"code\":23")) {
                    String l4 = C0619b.l("https://dm.ruyijx.com?ac=dm&url=" + str, hashMap);
                    return (l4 == null || l4.length() <= 0 || !l4.startsWith("{") || !l4.contains("\"code\":23")) ? "" : l4;
                }
                return l3;
            }
            return l2;
        }
        return l;
    }

    public static String formatDanmuUrl2(String str) {
        if (str == null || str.length() == 0) {
            return "";
        }
        if (str.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")) {
            return str;
        }
        if (str.startsWith("http")) {
            return C0619b.l("http://127.0.0.1:1314/danmu/get?url=" + str + "&format=xml", new HashMap());
        } else if (str.startsWith("vodid://")) {
            String[] split = str.substring("vodid://".length()).split("@");
            return C0619b.l("http://127.0.0.1:1314/danmu/get?url=" + split[0] + "&platform=" + split[1] + "&format=xml", new HashMap());
        } else {
            return "";
        }
    }

    public static String generateCombinedRGB() {
        String[] strArr = {"16711680", "16776960", "65280", "255", "16711935", "8388736", "16753920", "65535", "16777215", "16761087", "16777087", "8978431", "6527999", "16744447", "16756735", "8454143", "16724787", "16777215", "16752723", "16776951", "10000639", "5729279", "16645625", "16185078", "12334518", "13882321", "16777215", "16209488", "16772810", "16766758", "16777014", "16772362", "16773119", "14410239", "11835903", "16777215"};
        return strArr[new Random().nextInt(strArr.length)];
    }

    public static String generateCombinedWhite() {
        String[] strArr = {"16777215", "16777215", "16777215", "16777215", "16777215", "16777215", "16777215", "16777215", "16777215", "16777215", "16777215", "16777215", "16777215", "16777215", "16777215", "16711680", "16776960", "255", "65280", "8388736"};
        return strArr[new Random().nextInt(strArr.length)];
    }

    public static String getDanmuFrom1314(String str, int i) {
        try {
            String k = C0619b.k("http://127.0.0.1:1314/danmu/auto?name=" + URLEncoder.encode(str, "UTF-8") + "&episode=" + i + "&format=xml");
            if (TextUtils.isEmpty(k)) {
                return "";
            }
            return (k.indexOf("<d") < 0 || k.indexOf("</d>") < 0) ? "" : k;
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public static String getDanmuFromJinchanZY(String str, int i) throws Exception {
        String replaceAll;
        boolean z;
        String l = C0619b.l("http://127.0.0.1:9978/media", null);
        if (!TextUtils.isEmpty(l) && l.startsWith("{")) {
            JSONObject jSONObject = new JSONObject(l);
            String string = jSONObject.has("title") ? jSONObject.getString("title") : "";
            String string2 = jSONObject.has("artist") ? jSONObject.getString("artist") : "";
            if (TextUtils.isEmpty(string) || TextUtils.isEmpty(string2)) {
                return "";
            }
            JSONObject jSONObject2 = new JSONObject(C0619b.l("https://zy.jinchancaiji.com/api.php/provide/vod/?ac=detail&wd=" + URLEncoder.encode(string, "UTF-8"), null));
            if (jSONObject2.getInt("code") != 1) {
                return "";
            }
            Matcher matcher = Pattern.compile("第[0-9一二三四五六七八九十百千万]+期[上中下]?").matcher(string2);
            if (matcher.find()) {
                replaceAll = removeLeadingZeroFromEpisode(matcher.group());
                z = false;
            } else {
                replaceAll = string2.replaceAll("[^0-9]", "");
                if (replaceAll.length() != 8) {
                    return "";
                }
                z = true;
            }
            if (replaceAll.isEmpty()) {
                return "";
            }
            JSONArray jSONArray = jSONObject2.getJSONArray("list");
            for (int i2 = 0; i2 < jSONArray.length(); i2++) {
                String string3 = jSONArray.getJSONObject(i2).getString("vod_play_url");
                if (!TextUtils.isEmpty(string3)) {
                    for (String str2 : string3.split("#")) {
                        String[] split = str2.split("\\$");
                        if (split.length >= 2) {
                            String str3 = split[0];
                            if (z) {
                                if (str3.equals(replaceAll)) {
                                    return split[1];
                                }
                            } else if (str3.equals(replaceAll)) {
                                return split[1];
                            }
                        }
                    }
                    continue;
                }
            }
            return "";
        }
        return "";
    }

    public static String getDanmuFromLogVar(String str, int i) {
        JSONArray optJSONArray;
        JSONArray optJSONArray2;
        int i2;
        JSONObject optJSONObject;
        try {
            String k = C0619b.k(String.format("https://pizazz.us.ci/1314/search/episodes?anime=%s", str));
            if (TextUtils.isEmpty(k) || (optJSONArray = new JSONObject(k).optJSONArray("animes")) == null || optJSONArray.length() == 0 || (optJSONArray2 = optJSONArray.optJSONObject(0).optJSONArray("episodes")) == null || optJSONArray2.length() == 0 || i - 1 < 0 || i2 >= optJSONArray2.length() || (optJSONObject = optJSONArray2.optJSONObject(i2)) == null) {
                return "";
            }
            String optString = optJSONObject.optString("episodeId");
            if (TextUtils.isEmpty(optString)) {
                return "";
            }
            return "vodid://" + optString;
        } catch (Exception e) {
            SpiderDebug.log(e.getMessage());
            e.printStackTrace();
            return "";
        }
    }

    public static String getDanmuFromOK360(String str, int i) {
        JSONObject optJSONObject;
        JSONObject optJSONObject2;
        JSONArray optJSONArray;
        JSONObject optJSONObject3;
        int indexOf;
        int indexOf2;
        int indexOf3;
        int indexOf4;
        try {
            String k = C0619b.k("http://127.0.0.1:9978/media");
            if (!TextUtils.isEmpty(k) && k.startsWith("{")) {
                long optLong = new JSONObject(k).optLong("duration");
                if (optLong == 0) {
                    return "";
                }
                long j = (optLong / 1000) / 60;
                String str2 = j < 30 ? "动漫" : j < 70 ? "电视剧" : "电影";
                String k2 = C0619b.k(String.format("https://api.so.360kan.com/index?force_v=1&kw=%s&from=&pageno=1&v_ap=1&tab=all", URLEncoder.encode(str, "UTF-8")));
                if (TextUtils.isEmpty(k2) || (optJSONObject = new JSONObject(k2).optJSONObject("data")) == null || (optJSONObject2 = optJSONObject.optJSONObject("longData")) == null || (optJSONArray = optJSONObject2.optJSONArray("rows")) == null || optJSONArray.length() == 0) {
                    return "";
                }
                for (int i2 = 0; i2 < optJSONArray.length(); i2++) {
                    JSONObject optJSONObject4 = optJSONArray.optJSONObject(i2);
                    if (str2.equals(optJSONObject4.optString("cat_name"))) {
                        String str3 = "";
                        if (str2.equals("电影")) {
                            JSONObject optJSONObject5 = optJSONObject4.optJSONObject("playlinks");
                            if (optJSONObject5 != null) {
                                String optString = optJSONObject5.optString("qq");
                                if (TextUtils.isEmpty(optString)) {
                                    String optString2 = optJSONObject5.optString("qiyi");
                                    if (TextUtils.isEmpty(optString2)) {
                                        String optString3 = optJSONObject5.optString("youku");
                                        if (TextUtils.isEmpty(optString3)) {
                                            String optString4 = optJSONObject5.optString("imgo");
                                            if (!TextUtils.isEmpty(optString4)) {
                                                str3 = optString4;
                                            }
                                        } else {
                                            str3 = optString3;
                                        }
                                    } else {
                                        str3 = optString2;
                                    }
                                } else {
                                    str3 = optString;
                                }
                            }
                        } else {
                            JSONArray optJSONArray2 = optJSONObject4.optJSONArray("seriesPlaylinks");
                            if (optJSONArray2 == null || optJSONArray2.length() == 0) {
                                return "";
                            }
                            int i3 = (i <= 0 ? 0 : i) - 1;
                            if (i3 < 0 || i3 >= optJSONArray2.length() || (optJSONObject3 = optJSONArray2.optJSONObject(i3)) == null) {
                                return "";
                            }
                            str3 = optJSONObject3.optString("url");
                        }
                        if (TextUtils.isEmpty(str3)) {
                            return "";
                        }
                        if (str3.contains("v.qq.com") && str3.contains(".html") && (indexOf4 = str3.indexOf(".html")) != -1) {
                            str3 = str3.substring(0, ".html".length() + indexOf4);
                        } else if (str3.contains("www.iqiyi.com") && str3.contains(".html") && (indexOf3 = str3.indexOf(".html")) != -1) {
                            str3 = str3.substring(0, ".html".length() + indexOf3);
                        } else if (str3.contains("www.mgtv.com") && str3.contains(".html") && (indexOf2 = str3.indexOf(".html")) != -1) {
                            str3 = str3.substring(0, ".html".length() + indexOf2);
                        } else if (str3.contains("v.youku.com") && (indexOf = str3.indexOf("vid=")) != -1) {
                            int i4 = indexOf + 4;
                            int indexOf5 = str3.indexOf("&", i4);
                            if (indexOf5 == -1) {
                                indexOf5 = str3.length();
                            }
                            String substring = str3.substring(i4, indexOf5);
                            if (!TextUtils.isEmpty(substring)) {
                                str3 = "https://v.youku.com/v_show/id_" + substring + ".html";
                            }
                        }
                        return str3;
                    }
                }
                return "";
            }
            return "";
        } catch (Exception e) {
            SpiderDebug.log(e.getMessage());
            e.printStackTrace();
            return "";
        }
    }

    public static String getDanmuFromOKJinchan(String str, int i) {
        String str2;
        String[] split;
        String l;
        long j = 0;
        try {
            l = C0619b.l("http://127.0.0.1:9978/media", new HashMap());
        } catch (Exception unused) {
        }
        if (!TextUtils.isEmpty(l) && l.startsWith("{")) {
            j = new JSONObject(l).optLong("duration");
            if (j == 0) {
                return "";
            }
            char c = 0;
            if (j > 4203712) {
                c = 1;
                str2 = "电影";
            } else if (j <= 1800000) {
                c = 2;
                str2 = "动漫";
            } else {
                str2 = "剧集";
            }
            Log.d("Danmu", "时长: " + j + "ms, " + str2);
            String replace = str.replace(" ", "");
            StringBuilder sb = new StringBuilder("https://zy.jinchancaiji.com/api.php/provide/vod/?ac=detail&wd=");
            sb.append(URLEncoder.encode(replace, "UTF-8"));
            String sb2 = sb.toString();
            HashMap hashMap = new HashMap();
            hashMap.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            try {
                JSONObject jSONObject = new JSONObject(C0619b.l(sb2, hashMap));
                if (jSONObject.optInt("code") != 1) {
                    Log.e("Danmu", "搜索失败: " + sb2);
                    return "";
                }
                JSONArray optJSONArray = jSONObject.optJSONArray("list");
                if (optJSONArray == null || optJSONArray.length() == 0) {
                    return "";
                }
                ArrayList arrayList = new ArrayList();
                for (int i2 = 0; i2 < optJSONArray.length(); i2++) {
                    JSONObject optJSONObject = optJSONArray.optJSONObject(i2);
                    if (optJSONObject != null && replace.equals(optJSONObject.optString("vod_name"))) {
                        arrayList.add(optJSONObject);
                    }
                }
                if (arrayList.size() == 0) {
                    return "";
                }
                JSONObject jSONObject2 = null;
                for (int i3 = 0; i3 < arrayList.size(); i3++) {
                    JSONObject jSONObject3 = (JSONObject) arrayList.get(i3);
                    String optString = jSONObject3.optString("type_name");
                    if (c == 1) {
                        if (Pattern.compile(".*片$").matcher(optString).matches()) {
                            jSONObject2 = jSONObject3;
                        }
                    } else if (c == 2) {
                        if (Pattern.compile(".*漫$").matcher(optString).matches()) {
                            jSONObject2 = jSONObject3;
                        }
                    } else if (Pattern.compile(".*剧$").matcher(optString).matches()) {
                        jSONObject2 = jSONObject3;
                    }
                }
                if (jSONObject2 == null) {
                    jSONObject2 = (JSONObject) arrayList.get(0);
                }
                String optString2 = jSONObject2.optString("vod_play_url");
                if (TextUtils.isEmpty(optString2)) {
                    return "";
                }
                if (c != 1) {
                    for (String str3 : optString2.split("#")) {
                        String[] split2 = str3.split("\\$");
                        if (split2.length >= 2 && split2[0].replaceAll("\\D", "").equals(String.valueOf(i))) {
                            return split2[1];
                        }
                    }
                    return "";
                }
                for (String str4 : optString2.split("\\$\\$\\$")) {
                    if (!str4.contains("正片")) {
                        break;
                    }
                    int indexOf = str4.indexOf(36);
                    if (indexOf >= 0) {
                        return str4.substring(indexOf + 1);
                    }
                }
                return "";
            } catch (Exception e) {
                Log.e("Danmu", "请求失败: " + sb2, e);
                return "";
            }
        }
        return "";
    }

    public static String getDanmuFromOKJinchanSpace(String str, int i) {
        String str2;
        String[] split;
        String l;
        String originalVideoName = getOriginalVideoName(str);
        long j = 0;
        try {
            l = C0619b.l("http://127.0.0.1:9978/media", new HashMap());
        } catch (Exception unused) {
        }
        if (!TextUtils.isEmpty(l) && l.startsWith("{")) {
            j = new JSONObject(l).optLong("duration");
            if (j == 0) {
                return "";
            }
            char c = 0;
            if (j > 4203712) {
                c = 1;
                str2 = "电影";
            } else if (j <= 1800000) {
                c = 2;
                str2 = "动漫";
            } else {
                str2 = "剧集";
            }
            Log.d("Danmu", "时长: " + j + "ms, " + str2);
            String replace = originalVideoName.replace(" ", "");
            StringBuilder sb = new StringBuilder("https://zy.jinchancaiji.com/api.php/provide/vod/?ac=detail&wd=");
            sb.append(URLEncoder.encode(replace, "UTF-8"));
            String sb2 = sb.toString();
            HashMap hashMap = new HashMap();
            hashMap.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            try {
                JSONObject jSONObject = new JSONObject(C0619b.l(sb2, hashMap));
                if (jSONObject.optInt("code") != 1) {
                    Log.e("Danmu", "搜索失败: " + sb2);
                    return "";
                }
                JSONArray optJSONArray = jSONObject.optJSONArray("list");
                if (optJSONArray == null || optJSONArray.length() == 0) {
                    return "";
                }
                ArrayList arrayList = new ArrayList();
                for (int i2 = 0; i2 < optJSONArray.length(); i2++) {
                    JSONObject optJSONObject = optJSONArray.optJSONObject(i2);
                    if (optJSONObject != null && replace.equals(optJSONObject.optString("vod_name"))) {
                        arrayList.add(optJSONObject);
                    }
                }
                if (arrayList.size() == 0) {
                    return "";
                }
                JSONObject jSONObject2 = null;
                for (int i3 = 0; i3 < arrayList.size(); i3++) {
                    JSONObject jSONObject3 = (JSONObject) arrayList.get(i3);
                    String optString = jSONObject3.optString("type_name");
                    if (c == 1) {
                        if (Pattern.compile(".*片$").matcher(optString).matches()) {
                            jSONObject2 = jSONObject3;
                        }
                    } else if (c == 2) {
                        if (Pattern.compile(".*漫$").matcher(optString).matches()) {
                            jSONObject2 = jSONObject3;
                        }
                    } else if (Pattern.compile(".*剧$").matcher(optString).matches()) {
                        jSONObject2 = jSONObject3;
                    }
                }
                if (jSONObject2 == null) {
                    jSONObject2 = (JSONObject) arrayList.get(0);
                }
                String optString2 = jSONObject2.optString("vod_play_url");
                if (TextUtils.isEmpty(optString2)) {
                    return "";
                }
                if (c != 1) {
                    for (String str3 : optString2.split("#")) {
                        String[] split2 = str3.split("\\$");
                        if (split2.length >= 2 && split2[0].replaceAll("\\D", "").equals(String.valueOf(i))) {
                            return split2[1];
                        }
                    }
                    return "";
                }
                for (String str4 : optString2.split("\\$\\$\\$")) {
                    if (!str4.contains("正片")) {
                        break;
                    }
                    int indexOf = str4.indexOf(36);
                    if (indexOf >= 0) {
                        return str4.substring(indexOf + 1);
                    }
                }
                return "";
            } catch (Exception e) {
                Log.e("Danmu", "请求失败: " + sb2, e);
                return "";
            }
        }
        return "";
    }

    public static String getDanmuFromPanOK360(String str, int i) {
        JSONObject optJSONObject;
        JSONObject optJSONObject2;
        JSONArray optJSONArray;
        JSONObject optJSONObject3;
        int indexOf;
        int indexOf2;
        int indexOf3;
        int indexOf4;
        try {
            String k = C0619b.k("http://127.0.0.1:9978/media");
            if (!TextUtils.isEmpty(k) && k.startsWith("{")) {
                long optLong = new JSONObject(k).optLong("duration");
                if (optLong == 0) {
                    return "";
                }
                long j = (optLong / 1000) / 60;
                String str2 = j < 30 ? "动漫" : j < 70 ? "电视剧" : "电影";
                String k2 = C0619b.k(String.format("https://api.so.360kan.com/index?force_v=1&kw=%s&from=&pageno=1&v_ap=1&tab=all", URLEncoder.encode(str, "UTF-8")));
                if (TextUtils.isEmpty(k2) || (optJSONObject = new JSONObject(k2).optJSONObject("data")) == null || (optJSONObject2 = optJSONObject.optJSONObject("longData")) == null || (optJSONArray = optJSONObject2.optJSONArray("rows")) == null || optJSONArray.length() == 0) {
                    return "";
                }
                for (int i2 = 0; i2 < optJSONArray.length(); i2++) {
                    JSONObject optJSONObject4 = optJSONArray.optJSONObject(i2);
                    String optString = optJSONObject4.optString("titleTxt");
                    if ((TextUtils.isEmpty(optString) || optString.contains(str)) && str2.equals(optJSONObject4.optString("cat_name"))) {
                        String str3 = "";
                        if (str2.equals("电影")) {
                            JSONObject optJSONObject5 = optJSONObject4.optJSONObject("playlinks");
                            if (optJSONObject5 != null) {
                                String optString2 = optJSONObject5.optString("qq");
                                if (TextUtils.isEmpty(optString2)) {
                                    String optString3 = optJSONObject5.optString("qiyi");
                                    if (TextUtils.isEmpty(optString3)) {
                                        String optString4 = optJSONObject5.optString("youku");
                                        if (TextUtils.isEmpty(optString4)) {
                                            String optString5 = optJSONObject5.optString("imgo");
                                            if (!TextUtils.isEmpty(optString5)) {
                                                str3 = optString5;
                                            }
                                        } else {
                                            str3 = optString4;
                                        }
                                    } else {
                                        str3 = optString3;
                                    }
                                } else {
                                    str3 = optString2;
                                }
                            }
                        } else {
                            JSONArray optJSONArray2 = optJSONObject4.optJSONArray("seriesPlaylinks");
                            if (optJSONArray2 == null || optJSONArray2.length() == 0) {
                                return "";
                            }
                            int i3 = (i <= 0 ? 0 : i) - 1;
                            if (i3 < 0 || i3 >= optJSONArray2.length() || (optJSONObject3 = optJSONArray2.optJSONObject(i3)) == null) {
                                return "";
                            }
                            str3 = optJSONObject3.optString("url");
                        }
                        if (TextUtils.isEmpty(str3)) {
                            return "";
                        }
                        if (str3.startsWith("https://v.qq.com/") && str3.contains(".html") && (indexOf4 = str3.indexOf(".html")) != -1) {
                            str3 = str3.substring(0, ".html".length() + indexOf4);
                        } else if (str3.startsWith("https://www.iqiyi.com/") && str3.contains(".html") && (indexOf3 = str3.indexOf(".html")) != -1) {
                            str3 = str3.substring(0, ".html".length() + indexOf3);
                        } else if (str3.startsWith("http://www.mgtv.com/") && str3.contains(".html") && (indexOf2 = str3.indexOf(".html")) != -1) {
                            str3 = str3.substring(0, ".html".length() + indexOf2);
                        } else if (str3.startsWith("https://v.youku.com") && (indexOf = str3.indexOf("vid=")) != -1) {
                            int i4 = indexOf + 4;
                            int indexOf5 = str3.indexOf("&", i4);
                            if (indexOf5 == -1) {
                                indexOf5 = str3.length();
                            }
                            String substring = str3.substring(i4, indexOf5);
                            if (!TextUtils.isEmpty(substring)) {
                                str3 = "https://v.youku.com/v_show/id_" + substring + ".html";
                            }
                        }
                        return str3;
                    }
                }
                return "";
            }
            return "";
        } catch (Exception e) {
            SpiderDebug.log(e.getMessage());
            e.printStackTrace();
            return "";
        }
    }

    private static String getOriginalVideoName(String str) {
        String b = C0647l.b("danmuvodname");
        if (TextUtils.isEmpty(b)) {
            return b;
        }
        String b2 = C0647l.b("searchvodname");
        if (!TextUtils.isEmpty(b2) && !"获取视频名称失败".equals(b2)) {
            String b3 = C0647l.b("danmucache");
            if (TextUtils.isEmpty(b3)) {
                b3 = "{}";
            }
            JSONObject jSONObject = new JSONObject(b3);
            if (!jSONObject.has(b)) {
                jSONObject.put(b, b2);
            }
            if (jSONObject.length() > 20) {
                Iterator<String> keys = jSONObject.keys();
                ArrayList arrayList = new ArrayList();
                while (keys.hasNext()) {
                    arrayList.add(keys.next());
                }
                JSONObject jSONObject2 = new JSONObject();
                for (int size = arrayList.size() - 20; size < arrayList.size(); size++) {
                    String str2 = (String) arrayList.get(size);
                    jSONObject2.put(str2, jSONObject.getString(str2));
                }
                jSONObject = jSONObject2;
            }
            C0647l.a("danmucache", jSONObject.toString());
            C0647l.a("searchvodname", "");
        }
        String b4 = C0647l.b("danmucache");
        if (TextUtils.isEmpty(b4)) {
            return b;
        }
        String optString = new JSONObject(b4).optString(b);
        return !TextUtils.isEmpty(optString) ? optString : b;
    }

    public static String getRealName(String str) {
        return Pattern.compile("[（(【<][臻真]彩[）)】>]").matcher(str).replaceAll("").trim();
    }

    public static String removeLeadingZeroFromEpisode(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        Matcher matcher = Pattern.compile("(第|EP|ep|Ep)(0*)(\\d+)(期|集|话)([上下中]+)?[^.]*").matcher(str);
        if (matcher.find()) {
            String group = matcher.group(1);
            String group2 = matcher.group(2);
            String group3 = matcher.group(3);
            String group4 = matcher.group(4);
            if (group2.isEmpty()) {
                return str;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(group);
            sb.append(group3);
            sb.append(group4);
            String group5 = matcher.group(5);
            if (group5 != null) {
                sb.append(group5);
            }
            return sb.toString();
        }
        return str;
    }

    public static String updateDanmuColors(String str) {
        if (str == null || str.length() == 0) {
            return str;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (true) {
            int indexOf = str.indexOf("<d p=\"", i);
            if (indexOf < 0) {
                sb.append(str.substring(i));
                return sb.toString();
            }
            int indexOf2 = str.indexOf("\">", indexOf);
            if (indexOf2 <= 0) {
                sb.append(str.substring(i));
                return sb.toString();
            }
            sb.append(str.substring(i, indexOf));
            String[] split = str.substring(indexOf + 6, indexOf2).split(",");
            if (split.length < 4) {
                sb.append("<d p=\"");
                sb.append(split);
                sb.append("\">");
                i = indexOf2 + 2;
            } else {
                split[3] = generateCombinedRGB();
                sb.append("<d p=\"");
                sb.append(split[0]);
                for (int i2 = 1; i2 < split.length; i2++) {
                    sb.append(",");
                    sb.append(split[i2]);
                }
                sb.append("\">");
                i = indexOf2 + 2;
            }
        }
    }

    public static String updateDanmuWhite(String str) {
        if (str == null || str.length() == 0) {
            return str;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (true) {
            int indexOf = str.indexOf("<d p=\"", i);
            if (indexOf < 0) {
                sb.append(str.substring(i));
                return sb.toString();
            }
            int indexOf2 = str.indexOf("\">", indexOf);
            if (indexOf2 <= 0) {
                sb.append(str.substring(i));
                return sb.toString();
            }
            sb.append(str.substring(i, indexOf));
            String[] split = str.substring(indexOf + 6, indexOf2).split(",");
            if (split.length < 4) {
                sb.append("<d p=\"");
                sb.append(split);
                sb.append("\">");
                i = indexOf2 + 2;
            } else {
                split[3] = generateCombinedWhite();
                sb.append("<d p=\"");
                sb.append(split[0]);
                for (int i2 = 1; i2 < split.length; i2++) {
                    sb.append(",");
                    sb.append(split[i2]);
                }
                sb.append("\">");
                i = indexOf2 + 2;
            }
        }
    }


// ========== 自动合并依赖（唯一结构·全static） ==========
static class AB {
static class o {
            public final class K {
    static String a = "";

    private static String a(String str) {
        if (str == null || str.isEmpty()) {
            return null;
        }
        int indexOf = str.indexOf("{");
        int lastIndexOf = str.lastIndexOf("}");
        if (indexOf == -1 || lastIndexOf == -1 || indexOf >= lastIndexOf) {
            return null;
        }
        return str.substring(indexOf, lastIndexOf + 1);
    }

    static String b(String str, int i) {
        try {
            SpiderDebug.log("getDanmuUrl vodName: " + str);
            SpiderDebug.log("getDanmuUrl vodIndex: " + i);
            String b = G.b("danmukey");
            SpiderDebug.log("getDanmuUrl danmu: " + b);
            if (b.isEmpty()) {
                return "";
            }
            JSONObject jSONObject = new JSONObject(b);
            if (str.contains(jSONObject.getString("searchKey"))) {
                JSONArray jSONArray = jSONObject.getJSONArray("details");
                return jSONArray.length() == 0 ? "" : jSONArray.getString(i - 1).split("\\|")[1];
            }
            return "";
        } catch (Exception e) {
            SpiderDebug.log(e);
            return "";
        }
    }

    static List<String> bilibili(String str) {
        ArrayList arrayList = new ArrayList();
        try {
            JSONArray optJSONArray = new JSONObject(com.github.catvod.spider.merge.AB.m.c.b("http://127.0.0.1:1314/danmu/search?keywords=" + URLEncoder.encode(str, "UTF-8"), new HashMap()).a()).optJSONArray("bilibili");
            if (optJSONArray == null) {
                return arrayList;
            }
            int i = 0;
            while (i < optJSONArray.length()) {
                JSONObject optJSONObject = optJSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("vod_name");
                    String optString2 = optJSONObject.optString("vod_id");
                    if (!TextUtils.isEmpty(optString) && !TextUtils.isEmpty(optString2)) {
                        String str2 = "哔哩|" + optString + "|" + optString2 + "@bilibili";
                        if (optString.equals(str)) {
                            arrayList.add(0, str2);
                        } else {
                            arrayList.add(str2);
                        }
                        i++;
                    }
                }
                i++;
            }
            return arrayList;
        } catch (Exception e) {
            e.printStackTrace();
            return arrayList;
        }
    }

    static List<String> hanjutv(String str) {
        ArrayList arrayList = new ArrayList();
        try {
            JSONArray optJSONArray = new JSONObject(com.github.catvod.spider.merge.AB.m.c.b("http://127.0.0.1:1314/danmu/search?keywords=" + URLEncoder.encode(str, "UTF-8"), new HashMap()).a()).optJSONArray("hanjutv");
            if (optJSONArray == null) {
                return arrayList;
            }
            int i = 0;
            while (i < optJSONArray.length()) {
                JSONObject optJSONObject = optJSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("vod_name");
                    String optString2 = optJSONObject.optString("vod_id");
                    if (!TextUtils.isEmpty(optString) && !TextUtils.isEmpty(optString2)) {
                        String str2 = "韩剧|" + optString + "|" + optString2 + "@hanjutv";
                        if (optString.equals(str)) {
                            arrayList.add(0, str2);
                        } else {
                            arrayList.add(str2);
                        }
                        i++;
                    }
                }
                i++;
            }
            return arrayList;
        } catch (Exception e) {
            e.printStackTrace();
            return arrayList;
        }
    }

    static List<String> iqiyi(String str) {
        ArrayList arrayList = new ArrayList();
        try {
            JSONArray optJSONArray = new JSONObject(com.github.catvod.spider.merge.AB.m.c.b("http://127.0.0.1:1314/danmu/search?keywords=" + URLEncoder.encode(str, "UTF-8"), new HashMap()).a()).optJSONArray("iqiyi");
            if (optJSONArray == null) {
                return arrayList;
            }
            int i = 0;
            while (i < optJSONArray.length()) {
                JSONObject optJSONObject = optJSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("vod_name");
                    String optString2 = optJSONObject.optString("vod_id");
                    if (!TextUtils.isEmpty(optString) && !TextUtils.isEmpty(optString2)) {
                        String str2 = "爱奇艺|" + optString + "|" + optString2 + "@iqiyi";
                        if (optString.equals(str)) {
                            arrayList.add(0, str2);
                        } else {
                            arrayList.add(str2);
                        }
                        i++;
                    }
                }
                i++;
            }
            return arrayList;
        } catch (Exception e) {
            e.printStackTrace();
            return arrayList;
        }
    }

    static List<String> juhe(String str) {
        ArrayList arrayList = new ArrayList();
        try {
            String[] split = str.split("\\|");
            String str2 = split[1];
            String str3 = split[2];
            String str4 = str2.split(" - ")[0];
            String[] split2 = str3.split("@");
            JSONArray jSONArray = new JSONArray(com.github.catvod.spider.merge.AB.m.c.b("http://127.0.0.1:1314/danmu?name=" + URLEncoder.encode(str4, "UTF-8") + "&epid=" + split2[0] + "&platform=" + split2[1], null).a());
            if (jSONArray == null) {
                return arrayList;
            }
            for (int i = 0; i < jSONArray.length(); i++) {
                JSONObject optJSONObject = jSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("name");
                    String optString2 = optJSONObject.optString("url");
                    String optString3 = optJSONObject.optString("platform");
                    if (!TextUtils.isEmpty(optString) && !TextUtils.isEmpty(optString2)) {
                        arrayList.add(optString + "\n|vodid://" + optString2 + "@" + optString3);
                    }
                }
            }
            return arrayList;
        } catch (Exception e) {
            e.printStackTrace();
            return arrayList;
        }
    }

    static String[] l(JSONArray jSONArray) {
        if (jSONArray == null || jSONArray.length() == 0) {
            return new String[0];
        }
        int length = jSONArray.length();
        int i = (length + 29) / 30;
        String[] strArr = new String[i];
        for (int i2 = 0; i2 < i; i2++) {
            StringBuilder sb = new StringBuilder();
            int i3 = i2 * 30;
            int min = Math.min(i3 + 30, length);
            while (i3 < min) {
                sb.append(jSONArray.optString(i3));
                if (i3 < min - 1) {
                    sb.append(",");
                }
                i3++;
            }
            strArr[i2] = sb.toString();
        }
        return strArr;
    }

    static List<String> leshi(String str) {
        ArrayList arrayList = new ArrayList();
        try {
            JSONArray optJSONArray = new JSONObject(com.github.catvod.spider.merge.AB.m.c.b("http://127.0.0.1:1314/danmu/search?keywords=" + URLEncoder.encode(str, "UTF-8"), new HashMap()).a()).optJSONArray("leshi");
            if (optJSONArray == null) {
                return arrayList;
            }
            int i = 0;
            while (i < optJSONArray.length()) {
                JSONObject optJSONObject = optJSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("vod_name");
                    String optString2 = optJSONObject.optString("vod_id");
                    if (!TextUtils.isEmpty(optString) && !TextUtils.isEmpty(optString2)) {
                        String str2 = "乐视|" + optString + "|" + optString2 + "@leshi";
                        if (optString.equals(str)) {
                            arrayList.add(0, str2);
                        } else {
                            arrayList.add(str2);
                        }
                        i++;
                    }
                }
                i++;
            }
            return arrayList;
        } catch (Exception e) {
            e.printStackTrace();
            return arrayList;
        }
    }

    static List<String> maiduidui(String str) {
        ArrayList arrayList = new ArrayList();
        try {
            JSONArray optJSONArray = new JSONObject(com.github.catvod.spider.merge.AB.m.c.b("http://127.0.0.1:1314/danmu/search?keywords=" + URLEncoder.encode(str, "UTF-8"), new HashMap()).a()).optJSONArray("maiduidui");
            if (optJSONArray == null) {
                return arrayList;
            }
            int i = 0;
            while (i < optJSONArray.length()) {
                JSONObject optJSONObject = optJSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("vod_name");
                    String optString2 = optJSONObject.optString("vod_id");
                    if (!TextUtils.isEmpty(optString) && !TextUtils.isEmpty(optString2)) {
                        String str2 = "埋堆堆|" + optString + "|" + optString2 + "@maiduidui";
                        if (optString.equals(str)) {
                            arrayList.add(0, str2);
                        } else {
                            arrayList.add(str2);
                        }
                        i++;
                    }
                }
                i++;
            }
            return arrayList;
        } catch (Exception e) {
            e.printStackTrace();
            return arrayList;
        }
    }

    static List<String> mango(String str) {
        ArrayList arrayList = new ArrayList();
        try {
            JSONArray optJSONArray = new JSONObject(com.github.catvod.spider.merge.AB.m.c.b("http://127.0.0.1:1314/danmu/search?keywords=" + URLEncoder.encode(str, "UTF-8"), new HashMap()).a()).optJSONArray("mango");
            if (optJSONArray == null) {
                return arrayList;
            }
            int i = 0;
            while (i < optJSONArray.length()) {
                JSONObject optJSONObject = optJSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("vod_name");
                    String optString2 = optJSONObject.optString("vod_id");
                    if (!TextUtils.isEmpty(optString) && !TextUtils.isEmpty(optString2)) {
                        String str2 = "芒果|" + optString + "|" + optString2 + "@mango";
                        if (optString.equals(str)) {
                            arrayList.add(0, str2);
                        } else {
                            arrayList.add(str2);
                        }
                        i++;
                    }
                }
                i++;
            }
            return arrayList;
        } catch (Exception e) {
            e.printStackTrace();
            return arrayList;
        }
    }

    static List<String> renren(String str) {
        ArrayList arrayList = new ArrayList();
        try {
            JSONArray optJSONArray = new JSONObject(com.github.catvod.spider.merge.AB.m.c.b("http://127.0.0.1:1314/danmu/search?keywords=" + URLEncoder.encode(str, "UTF-8"), new HashMap()).a()).optJSONArray("renren");
            if (optJSONArray == null) {
                return arrayList;
            }
            int i = 0;
            while (i < optJSONArray.length()) {
                JSONObject optJSONObject = optJSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("vod_name");
                    String optString2 = optJSONObject.optString("vod_id");
                    if (!TextUtils.isEmpty(optString) && !TextUtils.isEmpty(optString2)) {
                        String str2 = "人人|" + optString + "|" + optString2 + "@renren";
                        if (optString.equals(str)) {
                            arrayList.add(0, str2);
                        } else {
                            arrayList.add(str2);
                        }
                        i++;
                    }
                }
                i++;
            }
            return arrayList;
        } catch (Exception e) {
            e.printStackTrace();
            return arrayList;
        }
    }

    static List<String> tencent(String str) {
        ArrayList arrayList = new ArrayList();
        try {
            JSONArray optJSONArray = new JSONObject(com.github.catvod.spider.merge.AB.m.c.b("http://127.0.0.1:1314/danmu/search?keywords=" + URLEncoder.encode(str, "UTF-8"), new HashMap()).a()).optJSONArray("tencent");
            if (optJSONArray == null) {
                return arrayList;
            }
            int i = 0;
            while (i < optJSONArray.length()) {
                JSONObject optJSONObject = optJSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("vod_name");
                    String optString2 = optJSONObject.optString("vod_id");
                    if (!TextUtils.isEmpty(optString) && !TextUtils.isEmpty(optString2)) {
                        String str2 = "腾讯|" + optString + "|" + optString2 + "@tencent";
                        if (optString.equals(str)) {
                            arrayList.add(0, str2);
                        } else {
                            arrayList.add(str2);
                        }
                        i++;
                    }
                }
                i++;
            }
            return arrayList;
        } catch (Exception e) {
            e.printStackTrace();
            return arrayList;
        }
    }

    static List<String> xigua(String str) {
        ArrayList arrayList = new ArrayList();
        try {
            JSONArray optJSONArray = new JSONObject(com.github.catvod.spider.merge.AB.m.c.b("http://127.0.0.1:1314/danmu/search?keywords=" + URLEncoder.encode(str, "UTF-8"), new HashMap()).a()).optJSONArray("xigua");
            if (optJSONArray == null) {
                return arrayList;
            }
            int i = 0;
            while (i < optJSONArray.length()) {
                JSONObject optJSONObject = optJSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("vod_name");
                    String optString2 = optJSONObject.optString("vod_id");
                    if (!TextUtils.isEmpty(optString) && !TextUtils.isEmpty(optString2)) {
                        String str2 = "西瓜|" + optString + "|" + optString2 + "@xigua";
                        if (optString.equals(str)) {
                            arrayList.add(0, str2);
                        } else {
                            arrayList.add(str2);
                        }
                        i++;
                    }
                }
                i++;
            }
            return arrayList;
        } catch (Exception e) {
            e.printStackTrace();
            return arrayList;
        }
    }

    static List<String> youku(String str) {
        ArrayList arrayList = new ArrayList();
        try {
            JSONArray optJSONArray = new JSONObject(com.github.catvod.spider.merge.AB.m.c.b("http://127.0.0.1:1314/danmu/search?keywords=" + URLEncoder.encode(str, "UTF-8"), new HashMap()).a()).optJSONArray("youku");
            if (optJSONArray == null) {
                return arrayList;
            }
            int i = 0;
            while (i < optJSONArray.length()) {
                JSONObject optJSONObject = optJSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("vod_name");
                    String optString2 = optJSONObject.optString("vod_id");
                    if (!TextUtils.isEmpty(optString) && !TextUtils.isEmpty(optString2)) {
                        String str2 = "优酷|" + optString + "|" + optString2 + "@youku";
                        if (optString.equals(str)) {
                            arrayList.add(0, str2);
                        } else {
                            arrayList.add(str2);
                        }
                        i++;
                    }
                }
                i++;
            }
            return arrayList;
        } catch (Exception e) {
            e.printStackTrace();
            return arrayList;
        }
    }
}
}
}
static class k {
        /* renamed from: com.github.catvod.spider.merge.k.b  reason: case insensitive filesystem */
public final class C0619b {
    private OkHttpClient a;

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: com.github.catvod.spider.merge.k.b$a */
    /* loaded from: classes.dex */
    static class a {
        static volatile C0619b a = new C0619b();
    }

    static String a(String str, Map<String, String> map) {
        String str2 = a().newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(new Request.Builder().url(str).headers(Headers.of(map)).build()).execute().headers().get("Location");
        if (str2 == null) {
            return null;
        }
        return str2;
    }

    static OkHttpClient a() {
        if (a.a.a != null) {
            return a.a.a;
        }
        C0619b c0619b = a.a;
        OkHttpClient.Builder dns = new OkHttpClient.Builder().addInterceptor(new C0622e()).dns(Spider.safeDns());
        TimeUnit timeUnit = TimeUnit.SECONDS;
        OkHttpClient build = dns.connectTimeout(30L, timeUnit).readTimeout(30L, timeUnit).writeTimeout(30L, timeUnit).hostnameVerifier(new HostnameVerifier() { // from class: com.github.catvod.spider.merge.k.a
            @Override // javax.net.ssl.HostnameVerifier
            public final boolean verify(String str, SSLSession sSLSession) {
                return true;
            }
        }).sslSocketFactory(new f(), f.d).build();
        c0619b.a = build;
        return build;
    }

    /* JADX WARN: Code restructure failed: missing block: B:9:0x003c, code lost:
        if (r2.containsKey("Location") != false) goto L7;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    static String b(String str, Map<String, String> map) {
        Map multimap = e().newCall(new Request.Builder().url(str).headers(Headers.of(map)).build()).execute().headers().toMultimap();
        if (multimap != null) {
            String str2 = multimap.containsKey("location") ? "location" : "Location";
            return (String) ((List) multimap.get(str2)).get(0);
        }
        return null;
    }

    static Response c(String str) {
        return a().newCall(new Request.Builder().url(str).build()).execute();
    }

    static Response d(String str, Map<String, String> map) {
        return a().newCall(new Request.Builder().url(str).headers(Headers.of(map)).build()).execute();
    }

    static OkHttpClient e() {
        return a().newBuilder().followRedirects(false).followSslRedirects(false).build();
    }

    static C0621d f(String str, String str2, Map<String, String> map) {
        return new C0620c(str, str2, map).a(a());
    }

    static C0621d g(String str, Map<String, String> map, Map<String, String> map2) {
        return new C0620c(com.github.catvod.spider.merge.AB.m.c.b, str, map, map2).a(a());
    }

    static String h(String str, String str2) {
        return f(str, str2, null).a();
    }

    static String i(Map map) {
        return new C0620c(com.github.catvod.spider.merge.AB.m.c.b, "https://passport.aliyundrive.com/newlogin/qrcode/query.do?appName=aliyun_drive&fromSite=52&_bx-v=2.2.3", map, (Map<String, String>) null).a(a()).a();
    }

    static C0621d j(OkHttpClient okHttpClient, String str, Map map, Map map2, Map map3) {
        C0620c c0620c = new C0620c(str, map, map2, map3);
        c0620c.b();
        return c0620c.a(okHttpClient);
    }

    static String k(String str) {
        return l(str, null);
    }

    static String l(String str, Map<String, String> map) {
        return str.startsWith("http") ? new C0620c(com.github.catvod.spider.merge.AB.m.c.c, str, (Map<String, String>) null, map).a(a()).a() : "";
    }
}
}
static class m {
        /* renamed from: com.github.catvod.spider.merge.m.l  reason: case insensitive filesystem */
public final class C0647l {
    private static SharedPreferences a() {
        Application context = Init.context();
        return context.getSharedPreferences(Init.context().getPackageName() + "_preferences", 0);
    }

    static void a(String str, String str2) {
        SharedPreferences.Editor edit = a().edit();
        edit.putString(str, str2);
        edit.apply();
    }

    static String b(String str) {
        return a().getString(str, "");
    }

    static void c(String str, Object obj) {
        SharedPreferences.Editor putLong;
        if (obj == null) {
            return;
        }
        if (obj instanceof String) {
            putLong = a().edit().putString(str, (String) obj);
        } else if (obj instanceof Boolean) {
            putLong = a().edit().putBoolean(str, ((Boolean) obj).booleanValue());
        } else if (obj instanceof Float) {
            putLong = a().edit().putFloat(str, ((Float) obj).floatValue());
        } else if (obj instanceof Integer) {
            putLong = a().edit().putInt(str, ((Integer) obj).intValue());
        } else if (!(obj instanceof Long)) {
            return;
        } else {
            putLong = a().edit().putLong(str, ((Long) obj).longValue());
        }
        putLong.apply();
    }
}
}
static class C {
        /* renamed from: com.github.catvod.spider.merge.C.ۣ۟ۦۧ  reason: contains not printable characters */
class C0007 {

    /* renamed from: ۨ۟ۤ۟  reason: not valid java name and contains not printable characters */
    static int f6 = -970;

    /* renamed from: ۟ۤۥۤۨ  reason: not valid java name and contains not printable characters */
    static String m10(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        for (int i4 = 0; i4 < byteArray.length; i4 = "".length() + 1) {
        }
        return new String(byteArray);
    }

    /* renamed from: ۟ۦۣۡ۠  reason: not valid java name and contains not printable characters */
    static String m11(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۟ۧ۟ۧۧ  reason: not valid java name and contains not printable characters */
    static int m12() {
        return 37 ^ C0011.f10;
    }

    /* renamed from: ۣ۠ۨۢ  reason: not valid java name and contains not printable characters */
    static int m13(Object obj) {
        return obj.hashCode();
    }
}
}
static class G {
        /* renamed from: com.github.catvod.spider.merge.G.ۥۧۡۢ  reason: contains not printable characters */
class C0012 {

    /* renamed from: ۟ۦ۟ۢۥ  reason: not valid java name and contains not printable characters */
    static int f11 = 381;

    /* renamed from: ۣ۟۟ۡۦ  reason: not valid java name and contains not printable characters */
    static int m31(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۥۦۡۦ  reason: contains not printable characters */
    static int m32() {
        return 888 ^ C0033.f24;
    }

    /* renamed from: ۦ۟ۥ۠  reason: contains not printable characters */
    static String m33(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        do {
        } while (str2.length() > 0);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        return new String(byteArray);
    }

    /* renamed from: ۦۥۥۨ  reason: contains not printable characters */
    static String m34(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }
}
}
static class a {
        /* renamed from: com.github.catvod.spider.merge.a.c  reason: case insensitive filesystem */
public final /* synthetic */ class C0575c {
    static String a(String str, String str2) {
        return str + str2;
    }
}
}
static class U {
        /* renamed from: com.github.catvod.spider.merge.U.۟ۢۦۥۧ  reason: contains not printable characters */
class C0030 {

    /* renamed from: ۟ۥ۠ۢۡ  reason: not valid java name and contains not printable characters */
    static int f23 = -588;

    /* renamed from: ۟ۢۦۨۥ  reason: not valid java name and contains not printable characters */
    static String m79(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۟ۥۦۤ۟  reason: not valid java name and contains not printable characters */
    static int m80(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۣۢۡۤ  reason: not valid java name and contains not printable characters */
    static String m81(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        do {
        } while (str2.length() > 0);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        return new String(byteArray);
    }

    /* renamed from: ۣۧۧۤ  reason: not valid java name and contains not printable characters */
    static int m82() {
        return (-30) ^ f23;
    }
}
}
static class x {
        /* renamed from: com.github.catvod.spider.merge.x.ۥۨۨۤ  reason: contains not printable characters */
class C0051 {

    /* renamed from: ۣۣ۟ۧۧ  reason: not valid java name and contains not printable characters */
    static int f47 = 724;

    /* renamed from: ۟۟ۢۤ  reason: not valid java name and contains not printable characters */
    static int m152(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۟ۤ۠ۨ  reason: not valid java name and contains not printable characters */
    static String m153(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۟ۤۦۤۨ  reason: not valid java name and contains not printable characters */
    static int m154() {
        return 347 ^ C0038.f35;
    }

    /* renamed from: ۟ۦۦۣۨ  reason: not valid java name and contains not printable characters */
    static String m155(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        while (length > 0) {
            byteArray[-1] = (byte) (byteArray[-1] ^ str3.charAt((-1) % length2));
        }
        for (int i3 = 0; i3 < byteArray.length; i3 = "".length() + 1) {
        }
        return new String(byteArray);
    }
}
}
static class E {
        /* renamed from: com.github.catvod.spider.merge.E.ۥۨۧۧ  reason: contains not printable characters */
class C0010 {

    /* renamed from: ۣۢۡۧ  reason: not valid java name and contains not printable characters */
    static int f9 = -158;

    /* renamed from: ۟ۧۤۦۢ  reason: not valid java name and contains not printable characters */
    static int m22(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۟ۨ۠ۤ  reason: not valid java name and contains not printable characters */
    static String m23(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        do {
        } while (str2.length() > 0);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        return new String(byteArray);
    }

    /* renamed from: ۣ۠۟۟  reason: not valid java name and contains not printable characters */
    static int m24() {
        return 146 ^ C0051.f47;
    }

    /* renamed from: ۨۨۥۢ  reason: not valid java name and contains not printable characters */
    static String m25(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }
}
}
static class h {
        /* renamed from: com.github.catvod.spider.merge.h.۟ۦۣۧ۟  reason: contains not printable characters */
class C0039 {

    /* renamed from: ۥ۠ۦۦ  reason: contains not printable characters */
    static int f36 = 842;

    /* renamed from: ۟ۢۦۦ  reason: not valid java name and contains not printable characters */
    static int m106(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۣ۟۠ۥ۟  reason: not valid java name and contains not printable characters */
    static String m107(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        do {
        } while (str2.length() > 0);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        return new String(byteArray);
    }

    /* renamed from: ۣ۟ۤۡ۟  reason: not valid java name and contains not printable characters */
    static String m108(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۣ۠ۧۢ  reason: not valid java name and contains not printable characters */
    static int m109() {
        return (-659) ^ C0044.f41;
    }
}
}
static class J {
        /* renamed from: com.github.catvod.spider.merge.J.ۦۡۡۥ  reason: contains not printable characters */
class C0021 {

    /* renamed from: ۟ۡ۟ۦۧ  reason: not valid java name and contains not printable characters */
    static int f15 = -778;

    /* renamed from: ۟ۦۥۧ  reason: not valid java name and contains not printable characters */
    static int m47() {
        return 672 ^ C0023.f16;
    }

    /* renamed from: ۟ۦۨ۟ۦ  reason: not valid java name and contains not printable characters */
    static String m48(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۢۤۨ۟  reason: not valid java name and contains not printable characters */
    static int m49(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۣ۠ۥ۟  reason: not valid java name and contains not printable characters */
    static String m50(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        do {
        } while (str2.length() > 0);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        return new String(byteArray);
    }
}
}
static class D {
        /* renamed from: com.github.catvod.spider.merge.D.۟ۡۥۡۥ  reason: contains not printable characters */
class C0009 {

    /* renamed from: ۣ۟ۢۡ۠  reason: not valid java name and contains not printable characters */
    static int f8 = 603;

    /* renamed from: ۟۠ۥۧۡ  reason: not valid java name and contains not printable characters */
    static int m18() {
        return (-371) ^ C0020.f14;
    }

    /* renamed from: ۟ۦۦ۟۟  reason: not valid java name and contains not printable characters */
    static String m19(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        String str4 = "a";
        while (str4.length() > 0) {
            str4 = "";
            if ("".length() == 0) {
                str4 = "a";
            }
        }
        int length = str4.length();
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        for (int i4 = 0; i4 < byteArray.length; i4 = "".length() + 1) {
        }
        return new String(byteArray);
    }

    /* renamed from: ۣۣۢۢ  reason: not valid java name and contains not printable characters */
    static String m20(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۣۤ۠ۨ  reason: not valid java name and contains not printable characters */
    static int m21(Object obj) {
        return obj.hashCode();
    }
}
}
static class Q {
        /* renamed from: com.github.catvod.spider.merge.Q.۟ۢۤ۟  reason: contains not printable characters */
class C0027 {

    /* renamed from: ۟ۧ۟۟۟  reason: not valid java name and contains not printable characters */
    static int f20 = -903;

    /* renamed from: ۟ۧۦۤ۠  reason: not valid java name and contains not printable characters */
    static int m67(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۟ۨ۠ۢ  reason: not valid java name and contains not printable characters */
    static int m68() {
        return 768 ^ C0051.f47;
    }

    /* renamed from: ۦ۟ۧۨ  reason: contains not printable characters */
    static String m69(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۦ۟ۨۥ  reason: contains not printable characters */
    static String m70(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        while (str.length() > 0) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(-2)) << 4) | str2.indexOf(str.charAt(-1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        for (int i2 = 0; i2 < length; i2++) {
            byteArray[i2] = (byte) (byteArray[i2] ^ str3.charAt(i2 % length2));
        }
        return new String(byteArray);
    }
}
}
static class i {
        /* renamed from: com.github.catvod.spider.merge.i.ۣۣ۟ۤ  reason: contains not printable characters */
class C0041 {

    /* renamed from: ۟ۢۤۢ  reason: not valid java name and contains not printable characters */
    static int f37 = -403;

    /* renamed from: ۟ۡۤ۟ۧ  reason: not valid java name and contains not printable characters */
    static String m110(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۟ۡۨ۠ۢ  reason: not valid java name and contains not printable characters */
    static int m111() {
        return 470 ^ C0038.f35;
    }

    /* renamed from: ۣ۟ۤۨۦ  reason: not valid java name and contains not printable characters */
    static int m112(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۣۨ۠  reason: not valid java name and contains not printable characters */
    static String m113(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        while (length > 0) {
            byteArray[-1] = (byte) (byteArray[-1] ^ str3.charAt((-1) % length2));
        }
        for (int i3 = 0; i3 < byteArray.length; i3 = "".length() + 1) {
        }
        return new String(byteArray);
    }
}
}
static class H {
        /* renamed from: com.github.catvod.spider.merge.H.۟ۧۥ۠ۡ  reason: contains not printable characters */
class C0015 {

    /* renamed from: ۟ۥۤۧۤ  reason: not valid java name and contains not printable characters */
    static int f12 = 846;

    /* renamed from: ۡۢۡۡ  reason: not valid java name and contains not printable characters */
    static int m35(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۤۦۦ  reason: not valid java name and contains not printable characters */
    static String m36(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۤۦۦۦ  reason: not valid java name and contains not printable characters */
    static String m37(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        String str4 = "a";
        while (str4.length() > 0) {
            str4 = "";
            if ("".length() == 0) {
                str4 = "a";
            }
        }
        int length = str4.length();
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        for (int i4 = 0; i4 < byteArray.length; i4 = "".length() + 1) {
        }
        return new String(byteArray);
    }

    /* renamed from: ۥۡ۠۟  reason: contains not printable characters */
    static int m38() {
        return 464 ^ C0030.f23;
    }
}
}
static class I {
        /* renamed from: com.github.catvod.spider.merge.I.ۦۡ۟  reason: contains not printable characters */
class C0019 {

    /* renamed from: ۣ۟ۥ۟ۥ  reason: not valid java name and contains not printable characters */
    static int f13 = 827;

    /* renamed from: ۣ۟ۡۢۢ  reason: not valid java name and contains not printable characters */
    static String m39(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        String str4 = "a";
        while (str4.length() > 0) {
            str4 = "";
            if ("".length() == 0) {
                str4 = "a";
            }
        }
        int length = str4.length();
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        for (int i4 = 0; i4 < byteArray.length; i4 = "".length() + 1) {
        }
        return new String(byteArray);
    }

    /* renamed from: ۟ۤۨۢ۠  reason: not valid java name and contains not printable characters */
    static int m40(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۨ۟۟ۢ  reason: not valid java name and contains not printable characters */
    static String m41(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۨۥۣۡ  reason: not valid java name and contains not printable characters */
    static int m42() {
        return (-182) ^ C0009.f8;
    }
}
}
static class M {
        /* renamed from: com.github.catvod.spider.merge.M.۟۟ۤۧ۠  reason: contains not printable characters */
class C0023 {

    /* renamed from: ۣۡۤۡ  reason: not valid java name and contains not printable characters */
    static int f16 = -445;

    /* renamed from: ۟ۥۥۥ  reason: not valid java name and contains not printable characters */
    static String m51(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۣ۟ۧۢۦ  reason: not valid java name and contains not printable characters */
    static int m52() {
        return 408 ^ C0041.f37;
    }

    /* renamed from: ۡۢۤۤ  reason: not valid java name and contains not printable characters */
    static String m53(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        while (length > 0) {
            byteArray[-1] = (byte) (byteArray[-1] ^ str3.charAt((-1) % length2));
        }
        for (int i3 = 0; i3 < byteArray.length; i3 = "".length() + 1) {
        }
        return new String(byteArray);
    }

    /* renamed from: ۨۤ  reason: not valid java name and contains not printable characters */
    static int m54(Object obj) {
        return obj.hashCode();
    }
}
}
static class O {
        /* renamed from: com.github.catvod.spider.merge.O.۟ۦۨ۟ۢ  reason: contains not printable characters */
class C0025 {

    /* renamed from: ۟ۥۣۡۧ  reason: not valid java name and contains not printable characters */
    static int f18 = 128;

    /* renamed from: ۡۤۦۣ  reason: not valid java name and contains not printable characters */
    static int m59() {
        return 614 ^ C0015.f12;
    }

    /* renamed from: ۣۧۤۡ  reason: not valid java name and contains not printable characters */
    static int m60(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۨ۟۟ۡ  reason: not valid java name and contains not printable characters */
    static String m61(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۣۨ۠ۡ  reason: not valid java name and contains not printable characters */
    static String m62(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        for (int i4 = 0; i4 < byteArray.length; i4 = "".length() + 1) {
        }
        return new String(byteArray);
    }
}
}
static class P {
        /* renamed from: com.github.catvod.spider.merge.P.۟ۦۧۤۢ  reason: contains not printable characters */
class C0026 {

    /* renamed from: ۟ۤۦۥ  reason: not valid java name and contains not printable characters */
    static int f19 = 882;

    /* renamed from: ۟۠ۤۦۢ  reason: not valid java name and contains not printable characters */
    static int m63(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۟ۤۥۥۦ  reason: not valid java name and contains not printable characters */
    static String m64(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        String str4 = "a";
        while (str4.length() > 0) {
            str4 = "";
            if ("".length() == 0) {
                str4 = "a";
            }
        }
        int length = str4.length();
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        for (int i4 = 0; i4 < byteArray.length; i4 = "".length() + 1) {
        }
        return new String(byteArray);
    }

    /* renamed from: ۣۥۧۤ  reason: not valid java name and contains not printable characters */
    static String m65(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۨۦ  reason: not valid java name and contains not printable characters */
    static int m66() {
        return (-516) ^ C0049.f45;
    }
}
}
static class w {
        /* renamed from: com.github.catvod.spider.merge.w.۟ۢۥۤۢ  reason: contains not printable characters */
class C0049 {

    /* renamed from: ۥۣۦ۟  reason: contains not printable characters */
    static int f45 = -431;

    /* renamed from: ۟۠ۡۧ۠  reason: not valid java name and contains not printable characters */
    static String m144(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        String str4 = "a";
        while (str4.length() > 0) {
            str4 = "";
            if ("".length() == 0) {
                str4 = "a";
            }
        }
        int length = str4.length();
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        for (int i4 = 0; i4 < byteArray.length; i4 = "".length() + 1) {
        }
        return new String(byteArray);
    }

    /* renamed from: ۟۠ۥۦ۠  reason: not valid java name and contains not printable characters */
    static String m145(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۣ۟ۡۡۦ  reason: not valid java name and contains not printable characters */
    static int m146() {
        return (-590) ^ C0015.f12;
    }

    /* renamed from: ۣۡۡۢ  reason: not valid java name and contains not printable characters */
    static int m147(Object obj) {
        return obj.hashCode();
    }
}
}
static class T {
        /* renamed from: com.github.catvod.spider.merge.T.ۣۣۥۢ  reason: contains not printable characters */
class C0029 {

    /* renamed from: ۤۥۣۣ  reason: not valid java name and contains not printable characters */
    static int f22 = 559;

    /* renamed from: ۣ۟۟ۤ۟  reason: not valid java name and contains not printable characters */
    static String m75(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        do {
        } while (str2.length() > 0);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        return new String(byteArray);
    }

    /* renamed from: ۟ۧۢ۠ۥ  reason: not valid java name and contains not printable characters */
    static String m76(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۠ۡۡۧ  reason: not valid java name and contains not printable characters */
    static int m77(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۦۨ۟  reason: contains not printable characters */
    static int m78() {
        return (-916) ^ C0049.f45;
    }
}
}
static class b {
        /* renamed from: com.github.catvod.spider.merge.b.h  reason: case insensitive filesystem */
public final /* synthetic */ class C0588h {
    static String a(String str, C0596c c0596c, int i, int i2, int i3, ArrayList arrayList) {
        c0596c.i(Integer.valueOf(str).intValue(), i, i2, i3);
        c0596c.w(arrayList);
        return c0596c.toString();
    }

    static String a(String str, String str2, String str3, String str4, String str5) {
        return str + str2 + str3 + str4 + str5;
    }

    static String b(StringBuilder sb, String str, String str2) {
        sb.append(str);
        sb.append(str2);
        return sb.toString();
    }

    static HashMap c(String str, String str2, String str3, String str4) {
        HashMap hashMap = new HashMap();
        hashMap.put(str, str2);
        hashMap.put(str3, str4);
        return hashMap;
    }
}
}
static class f {
        /* renamed from: com.github.catvod.spider.merge.f.ۨۥ۟۠  reason: contains not printable characters */
class C0037 {

    /* renamed from: ۟۠ۧ۠ۧ  reason: not valid java name and contains not printable characters */
    static int f34 = -121;

    /* renamed from: ۟۠ۦۦۣ  reason: not valid java name and contains not printable characters */
    static String m98(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        String str4 = "a";
        while (str4.length() > 0) {
            str4 = "";
            if ("".length() == 0) {
                str4 = "a";
            }
        }
        int length = str4.length();
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        for (int i4 = 0; i4 < byteArray.length; i4 = "".length() + 1) {
        }
        return new String(byteArray);
    }

    /* renamed from: ۟ۡۥۧ۠  reason: not valid java name and contains not printable characters */
    static int m99(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۦۤۦۡ  reason: contains not printable characters */
    static String m100(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۦۨ۟ۦ  reason: contains not printable characters */
    static int m101() {
        return (-386) ^ C0043.f39;
    }
}
}
static class l {
        /* renamed from: com.github.catvod.spider.merge.l.۟ۡۦۧۨ  reason: contains not printable characters */
class C0043 {

    /* renamed from: ۟ۤۨۥ  reason: not valid java name and contains not printable characters */
    static int f39 = 528;

    /* renamed from: ۣ۟ۥۦۡ  reason: not valid java name and contains not printable characters */
    static String m118(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        for (int i4 = 0; i4 < byteArray.length; i4 = "".length() + 1) {
        }
        return new String(byteArray);
    }

    /* renamed from: ۡۤۥۣ  reason: not valid java name and contains not printable characters */
    static int m119(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۤۨ۠ۧ  reason: not valid java name and contains not printable characters */
    static String m120(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۥۣۢ۟  reason: contains not printable characters */
    static int m121() {
        return 7 ^ C0023.f16;
    }
}
}
static class q {
        /* renamed from: com.github.catvod.spider.merge.q.ۥۧۦ۠  reason: contains not printable characters */
class C0045 {

    /* renamed from: ۣۧۢ۠  reason: not valid java name and contains not printable characters */
    static int f42 = 116;

    /* renamed from: ۣ۟ۡۡ۠  reason: not valid java name and contains not printable characters */
    static int m132() {
        return (-110) ^ C0023.f16;
    }

    /* renamed from: ۟ۦۦ۟ۤ  reason: not valid java name and contains not printable characters */
    static int m133(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۦۦۡ  reason: contains not printable characters */
    static String m134(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۧۢ۠ۥ  reason: not valid java name and contains not printable characters */
    static String m135(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        for (int i4 = 0; i4 < byteArray.length; i4 = "".length() + 1) {
        }
        return new String(byteArray);
    }
}
}
static class j {
        /* renamed from: com.github.catvod.spider.merge.j.ۣ۟۟ۢۢ  reason: contains not printable characters */
class C0042 {

    /* renamed from: ۦۥۤۧ  reason: contains not printable characters */
    static int f38 = -376;

    /* renamed from: ۟ۢۢۧۦ  reason: not valid java name and contains not printable characters */
    static int m114() {
        return (-717) ^ C0044.f41;
    }

    /* renamed from: ۟ۤۤۧ  reason: not valid java name and contains not printable characters */
    static int m115(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۢۧۥ۠  reason: not valid java name and contains not printable characters */
    static String m116(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        String str4 = "a";
        while (str4.length() > 0) {
            str4 = "";
            if ("".length() == 0) {
                str4 = "a";
            }
        }
        int length = str4.length();
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        for (int i4 = 0; i4 < byteArray.length; i4 = "".length() + 1) {
        }
        return new String(byteArray);
    }

    /* renamed from: ۨۦۢ۟  reason: not valid java name and contains not printable characters */
    static String m117(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }
}
}
static class t {
        /* renamed from: com.github.catvod.spider.merge.t.ۣ۟ۤۤۤ  reason: contains not printable characters */
class C0046 {

    /* renamed from: ۤ۟ۥۤ  reason: not valid java name and contains not printable characters */
    static int f43 = 691;

    /* renamed from: ۣ۟ۨۦۨ  reason: not valid java name and contains not printable characters */
    static int m136(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۣ۟ۧۡۤ  reason: not valid java name and contains not printable characters */
    static String m137(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۣۢۦۣ  reason: not valid java name and contains not printable characters */
    static String m138(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        while (length > 0) {
            byteArray[-1] = (byte) (byteArray[-1] ^ str3.charAt((-1) % length2));
        }
        for (int i3 = 0; i3 < byteArray.length; i3 = "".length() + 1) {
        }
        return new String(byteArray);
    }

    /* renamed from: ۥۣ۟ۡ  reason: contains not printable characters */
    static int m139() {
        return 770 ^ C0023.f16;
    }
}
}
static class u {
        /* renamed from: com.github.catvod.spider.merge.u.ۣۣۣ۟ۧ  reason: contains not printable characters */
class C0048 {

    /* renamed from: ۣ۟ۤۨۨ  reason: not valid java name and contains not printable characters */
    static int f44 = 150;

    /* renamed from: ۟ۧۡۤ۟  reason: not valid java name and contains not printable characters */
    static String m140(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        do {
        } while (str2.length() > 0);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        return new String(byteArray);
    }

    /* renamed from: ۡ۟ۡ۠  reason: not valid java name and contains not printable characters */
    static int m141(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۣۢ۟ۤ  reason: not valid java name and contains not printable characters */
    static String m142(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۧۧ۟  reason: not valid java name and contains not printable characters */
    static int m143() {
        return (-236) ^ C0028.f21;
    }
}
}
static class z {
        /* renamed from: com.github.catvod.spider.merge.z.ۨۧۢ۟  reason: contains not printable characters */
class C0054 {

    /* renamed from: ۣ۟ۧۥ  reason: not valid java name and contains not printable characters */
    static int f49 = 627;

    /* renamed from: ۣۣ۟ۧ۟  reason: not valid java name and contains not printable characters */
    static int m160() {
        return 538 ^ C0020.f14;
    }

    /* renamed from: ۣۤۥۧ  reason: not valid java name and contains not printable characters */
    static int m161(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۣۧ۠ۤ  reason: not valid java name and contains not printable characters */
    static String m162(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۦۦۦۧ  reason: contains not printable characters */
    static String m163(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        do {
        } while (str2.length() > 0);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        int length = byteArray.length;
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        return new String(byteArray);
    }
}
}
static class c {
        /* renamed from: com.github.catvod.spider.merge.c.c  reason: case insensitive filesystem */
public final class C0596c {
    @SerializedName("class")
    private List<C0594a> a;
    @SerializedName("list")
    private List<C0598e> b;
    @SerializedName("filters")
    private LinkedHashMap<String, List<C0595b>> c;
    @SerializedName("header")
    private String d;
    @SerializedName("format")
    private String e;
    @SerializedName("danmaku")
    private String f;
    @SerializedName("url")
    private Object g;
    @SerializedName("subs")
    private List<C0597d> h;
    @SerializedName("parse")
    private int i;
    @SerializedName("jx")
    private int j;
    @SerializedName("page")
    private Integer k;
    @SerializedName("pagecount")
    private Integer l;
    @SerializedName("limit")
    private Integer m;
    @SerializedName("total")
    private Integer n;
    @SerializedName("msg")
    private String o;
    @SerializedName("errMsg")
    private String p;

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.github.catvod.spider.merge.c.c$a */
    /* loaded from: classes.dex */
    class a extends TypeToken<LinkedHashMap<String, List<C0595b>>> {
        a() {
        }
    }

    static String c(String str) {
        C0596c c0596c = new C0596c();
        c0596c.b = Collections.emptyList();
        c0596c.o = str;
        return c0596c.toString();
    }

    static C0596c e() {
        return new C0596c();
    }

    static String l(String str) {
        C0596c c0596c = new C0596c();
        c0596c.i = 0;
        c0596c.g = "";
        c0596c.o = str;
        c0596c.p = str;
        return c0596c.toString();
    }

    static String m(C0598e c0598e) {
        C0596c c0596c = new C0596c();
        c0596c.b = Arrays.asList(c0598e);
        return c0596c.toString();
    }

    static String m(Integer num, Integer num2, Integer num3, Integer num4, List list) {
        C0596c c0596c = new C0596c();
        c0596c.m96i(num.intValue(), num2.intValue(), num3.intValue(), num4.intValue());
        c0596c.b = list;
        return c0596c.toString();
    }

    static String n(String str) {
        try {
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("parse", 0);
            jSONObject.put("url", "");
            jSONObject.put("msg", str);
            jSONObject.put("errMsg", str);
            return jSONObject.toString();
        } catch (Exception unused) {
            return str;
        }
    }

    static String n(List<C0598e> list) {
        C0596c c0596c = new C0596c();
        c0596c.b = list;
        return c0596c.toString();
    }

    static String o(List<C0594a> list, LinkedHashMap<String, List<C0595b>> linkedHashMap) {
        C0596c c0596c = new C0596c();
        c0596c.a = list;
        c0596c.c = linkedHashMap;
        return c0596c.toString();
    }

    static String p(List<C0594a> list, List<C0598e> list2) {
        C0596c c0596c = new C0596c();
        c0596c.a = list;
        c0596c.b = list2;
        return c0596c.toString();
    }

    static String q(ArrayList arrayList, List list, LinkedHashMap linkedHashMap) {
        C0596c c0596c = new C0596c();
        c0596c.a = arrayList;
        c0596c.b = list;
        c0596c.c = linkedHashMap;
        return c0596c.toString();
    }

    static String q(List<C0594a> list, List<C0598e> list2, LinkedHashMap<String, List<C0595b>> linkedHashMap) {
        C0596c c0596c = new C0596c();
        c0596c.a = list;
        c0596c.b = list2;
        c0596c.c = linkedHashMap;
        return c0596c.toString();
    }

    static String r(List<C0594a> list, List<C0598e> list2, JSONObject jSONObject) {
        C0596c c0596c = new C0596c();
        c0596c.a = list;
        c0596c.b = list2;
        c0596c.d(jSONObject);
        return c0596c.toString();
    }

    static String s(List<C0594a> list, JSONObject jSONObject) {
        C0596c c0596c = new C0596c();
        c0596c.a = list;
        c0596c.d(jSONObject);
        return c0596c.toString();
    }

    public final C0596c a(String str) {
        this.f = str;
        return this;
    }

    public final C0596c b() {
        this.e = "application/dash+xml";
        return this;
    }

    public final C0596c d(JSONObject jSONObject) {
        if (jSONObject == null) {
            return this;
        }
        this.c = (LinkedHashMap) new Gson().fromJson(jSONObject.toString(), new a().getType());
        return this;
    }

    public final C0596c e(Map<String, String> map) {
        if (map.isEmpty()) {
            return this;
        }
        this.d = new Gson().toJson(map);
        return this;
    }

    /* renamed from: e  reason: collision with other method in class */
    public final void m95e(Map map) {
        if (map.isEmpty()) {
            return;
        }
        this.d = new Gson().toJson(map);
    }

    public final C0596c f() {
        this.j = 1;
        return this;
    }

    public final C0596c g() {
        this.e = "application/x-mpegURL";
        return this;
    }

    public final C0596c h() {
        this.e = "application/octet-stream";
        return this;
    }

    public final C0596c hh() {
        this.e = "video/x-iso";
        return this;
    }

    public final C0596c i(int i, int i2, int i3, int i4) {
        if (i <= 0) {
            i = Integer.MAX_VALUE;
        }
        this.k = Integer.valueOf(i);
        if (i3 <= 0) {
            i3 = Integer.MAX_VALUE;
        }
        this.m = Integer.valueOf(i3);
        if (i4 <= 0) {
            i4 = Integer.MAX_VALUE;
        }
        this.n = Integer.valueOf(i4);
        if (i2 <= 0) {
            i2 = Integer.MAX_VALUE;
        }
        this.l = Integer.valueOf(i2);
        return this;
    }

    /* renamed from: i  reason: collision with other method in class */
    public final void m96i(int i, int i2, int i3, int i4) {
        if (i <= 0) {
            i = Integer.MAX_VALUE;
        }
        this.k = Integer.valueOf(i);
        if (i3 <= 0) {
            i3 = Integer.MAX_VALUE;
        }
        this.m = Integer.valueOf(i3);
        if (i4 <= 0) {
            i4 = Integer.MAX_VALUE;
        }
        this.n = Integer.valueOf(i4);
        if (i2 <= 0) {
            i2 = Integer.MAX_VALUE;
        }
        this.l = Integer.valueOf(i2);
    }

    public final C0596c j() {
        this.i = 1;
        return this;
    }

    public final C0596c k(int i) {
        this.i = i;
        return this;
    }

    public final String o() {
        return toString();
    }

    public final C0596c t(List<C0597d> list) {
        this.h = list;
        return this;
    }

    public final String toString() {
        return new Gson().newBuilder().disableHtmlEscaping().create().toJson(this);
    }

    public final C0596c u(String str) {
        this.g = str;
        return this;
    }

    public final C0596c v(List<String> list) {
        this.g = list;
        return this;
    }

    public final C0596c w(List<C0598e> list) {
        this.b = list;
        return this;
    }

    public final void w(String str) {
        this.g = str;
    }

    public final void y(ArrayList arrayList) {
        this.b = arrayList;
    }
}
}
    /* renamed from: com.github.catvod.spider.merge.۟ۢۧۦ  reason: contains not printable characters */
class C0055 {

    /* renamed from: ۟ۤۡ۠  reason: not valid java name and contains not printable characters */
    static int f50 = -53;

    /* renamed from: ۟۟ۥۥۨ  reason: not valid java name and contains not printable characters */
    static int m164(Object obj) {
        return obj.hashCode();
    }

    /* renamed from: ۣ۟ۢۦۥ  reason: not valid java name and contains not printable characters */
    static String m165(String str) {
        String str2 = "";
        String str3 = "";
        for (int i = 0; i < 15; i++) {
            str2 = new StringBuffer().append(str2).append(Integer.toHexString(i)).toString();
            str3 = new StringBuffer().append(str3).append(((int) (Math.random() * 10)) ^ i).toString();
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(str.length() / 2);
        for (int i2 = 0; i2 < str.length(); i2 += 2) {
            byteArrayOutputStream.write((str2.indexOf(str.charAt(i2)) << 4) | str2.indexOf(str.charAt(i2 + 1)));
        }
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        String str4 = "a";
        while (str4.length() > 0) {
            str4 = "";
            if ("".length() == 0) {
                str4 = "a";
            }
        }
        int length = str4.length();
        int length2 = str3.length();
        for (int i3 = 0; i3 < length; i3++) {
            byteArray[i3] = (byte) (byteArray[i3] ^ str3.charAt(i3 % length2));
        }
        for (int i4 = 0; i4 < byteArray.length; i4 = "".length() + 1) {
        }
        return new String(byteArray);
    }

    /* renamed from: ۟ۥۨۨۥ  reason: not valid java name and contains not printable characters */
    static String m166(short[] sArr, int i, int i2, int i3) {
        char[] cArr = new char[i2];
        for (int i4 = 0; i4 < i2; i4++) {
            cArr[i4] = (char) (sArr[i + i4] ^ i3);
        }
        return new String(cArr);
    }

    /* renamed from: ۦۦۧۡ  reason: contains not printable characters */
    static int m167() {
        return (-160) ^ C0050.f46;
    }
}
}
