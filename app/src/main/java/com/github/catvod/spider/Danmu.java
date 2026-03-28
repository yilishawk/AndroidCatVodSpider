package com.github.catvod.spider;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.widget.Toast;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.debug.MainActivity;
import com.github.catvod.js.C0000;
import com.github.catvod.spider.C0056;
import com.github.catvod.spider.Config;
import com.github.catvod.spider.Init;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import np.protect.C0058;
import np.protect.C0060;
import np.protect.C0062;
import np.protect.C0064;
import np.protect.C0065;
import np.protect.C0066;
import np.protect.C0068;
import np.protect.C0069;
import np.protect.C0070;
import np.protect.C0072;
import np.protect.C0074;
import np.protect.C0076;
import np.protect.C0077;
import np.protect.C0078;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
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

    // --- 拓扑融合区 ---
    static class AB {

        static class o {

            public final static class K {
                public static String a = "";

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

                public static String b(String str, int i) {
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

                public static List<String> bilibili(String str) {
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

                public static List<String> hanjutv(String str) {
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

                public static List<String> iqiyi(String str) {
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

                public static List<String> juhe(String str) {
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

                public static String[] l(JSONArray jSONArray) {
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

                public static List<String> leshi(String str) {
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

                public static List<String> maiduidui(String str) {
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

                public static List<String> mango(String str) {
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

                public static List<String> renren(String str) {
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

                public static List<String> tencent(String str) {
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

                public static List<String> xigua(String str) {
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

                public static List<String> youku(String str) {
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

    static class m {

        /* renamed from: com.github.catvod.spider.merge.m.l  reason: case insensitive filesystem */
        public final static class C0647l {
            private static SharedPreferences a() {
                Application context = Init.context();
                return context.getSharedPreferences(Init.context().getPackageName() + "_preferences", 0);
            }

            public static void a(String str, String str2) {
                SharedPreferences.Editor edit = a().edit();
                edit.putString(str, str2);
                edit.apply();
            }

            public static String b(String str) {
                return a().getString(str, "");
            }

            public static void c(String str, Object obj) {
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

        /* renamed from: com.github.catvod.spider.merge.m.k  reason: case insensitive filesystem */
        public final static class C0646k {
            public static String a(File file) {
                String str = "";
                try {
                    FileInputStream fileInputStream = new FileInputStream(file);
                    try {
                        byte[] bArr = new byte[fileInputStream.available()];
                        fileInputStream.read(bArr);
                        fileInputStream.close();
                        str = new String(bArr, "UTF-8");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } catch (Exception unused) {
                }
                return str;
            }

            public static File b(String str) {
                if (!str.startsWith(".")) {
                    str = C0575c.a(".", str);
                }
                StringBuilder sb = new StringBuilder();
                sb.append(Environment.getExternalStorageDirectory());
                File file = new File(C0588h.b(sb, File.separator, "TVBox"));
                if (!file.exists()) {
                    file.mkdirs();
                }
                return new File(file, str);
            }

            public static File c(File file, String str) {
                byte[] bytes = str.getBytes();
                try {
                    FileOutputStream fileOutputStream = new FileOutputStream(file);
                    fileOutputStream.write(bytes);
                    fileOutputStream.flush();
                    fileOutputStream.close();
                    try {
                        Runtime runtime = Runtime.getRuntime();
                        runtime.exec("chmod 777 " + file).waitFor();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } catch (Exception e2) {
                    e2.printStackTrace();
                }
                return file;
            }

            public static File c(String str) {
                if (!str.startsWith(".")) {
                    str = C0575c.a(".", str);
                }
                return new File(Init.context().getFilesDir(), str);
            }

            /* renamed from: c  reason: collision with other method in static class */
            private static void m127c(File file, String str) {
                try {
                    FileOutputStream fileOutputStream = new FileOutputStream(file);
                    OutputStreamWriter outputStreamWriter = new OutputStreamWriter(fileOutputStream, "UTF-8");
                    outputStreamWriter.write(str);
                    outputStreamWriter.close();
                    fileOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        /* renamed from: com.github.catvod.spider.merge.m.I  reason: case insensitive filesystem */
        public final static class C0634I {
            public static final List<String> a;
            public static final Pattern b;
            public static final List<String> c;
            public static final List<String> d;
            public static final List<String> e;
            public static final List<String> f;
            public static final List<String> g;
            public static final List<String> h;
            private static final Random i;

            /* renamed from: short  reason: not valid java name */
            private static final short[] f40short;

            static {
                String str;
                String str2;
                String str3;
                int m115 = C0042.m115("ۥۤۦ");
                while (true) {
                    switch (m115) {
                        case 56295:
                            Object[] objArr = new Object[1];
                            C0062.n(39600, null, new Object[]{(short[]) C0070.n(68248), 496, 24, 2953});
                            Object[] objArr2 = new Object[1];
                            C0062.n(14097, null, new Object[]{(short[]) C0070.n(68248), 520, 24, 939});
                            f = (List) C0074.n(23138, null, new Object[]{new String[]{"UC原画", "UC普画"}});
                            if (C0055.f50 >= 0) {
                            }
                            m115 = C0029.m77("ۣۢۡ");
                        case 1746812:
                            d = (List) C0074.n(23138, null, new Object[]{new String[]{(String) C0072.n(10418, null, new Object[]{(String) C0065.n(70747, null, new Object[]{(short[]) C0070.n(68248), 424, 24, 2648})}), (String) C0072.n(10418, null, new Object[]{(String) C0065.n(33693, null, new Object[]{(short[]) C0070.n(68248), 448, 24, 1607})})}});
                            if (C0030.f23 >= 0) {
                                C0011.m29();
                                m115 = C0042.m115("ۡۢۤ");
                            } else {
                                str3 = "ۡۢۤ";
                                m115 = C0053.m157(str3);
                            }
                        case 1747714:
                            c = (List) C0074.n(23138, null, new Object[]{new String[]{(String) C0072.n(10418, null, new Object[]{(String) C0058.n(79755, null, new Object[]{(short[]) C0070.n(68248), 376, 24, 2661})}), (String) C0072.n(10418, null, new Object[]{(String) C0065.n(63139, null, new Object[]{(short[]) C0070.n(68248), 400, 24, 2037})})}});
                            if ((C0009.f8 | (C0038.f35 % 7322)) <= 0) {
                                C0009.m18();
                                str = "ۣ۠ۡ";
                                m115 = C0033.m85(str);
                            }
                        case 1748617:
                            return;
                        case 1748707:
                            e = (List) C0074.n(23138, null, new Object[]{new String[]{(String) C0072.n(10418, null, new Object[]{(String) C0062.n(39070, null, new Object[]{(short[]) C0070.n(68248), 472, 24, 3163})})}});
                            if (C0053.f48 <= 0) {
                                C0030.m82();
                                str2 = "ۧۡۤ";
                                m115 = C0010.m22(str2);
                            } else {
                                m115 = C0049.m147("۟ۦ");
                            }
                        case 1749636:
                            g = (List) C0074.n(23138, null, new Object[]{new String[]{(String) C0072.n(10418, null, new Object[]{(String) C0065.n(75313, null, new Object[]{(short[]) C0070.n(68248), 544, 24, 634})})}});
                            if (C0026.m66() <= 0) {
                            }
                            m115 = C0025.m60("ۧۦۡ");
                        case 1751558:
                            i = new Random();
                            if (C0024.m57() <= 0) {
                                C0049.f45 = 86;
                                m115 = C0037.m99("۟ۦ");
                            } else {
                                m115 = C0043.m119("ۡ۟ۧ");
                            }
                        case 1752615:
                            f40short = new short[]{2029, 2024, 2028, 2023, 2026, 1948, 2298, 2303, 2298, 2299, 2297, 2189, 2596, 2643, 2597, 2594, 2598, 2642, 1812, 1893, 1812, 1810, 1815, 1891, 430, 478, 431, 473, 428, 429, 1444, 1441, 1445, 1454, 1443, 1492, 1186, 1234, 1186, 1193, 1184, 1234, 2920, 2841, 2920, 2926, 2922, 2915, 2920, 2927, 839, 834, 834, 822, 837, 844, 1505, 1505, 1504, 1424, 1506, 1424, 1504, 1509, 1510, 1507, 1510, 1425, 1508, 1510, 1509, 1514, 1507, 1507, 1504, 1505, 1507, 1424, 1507, 1504, 1509, 1504, 1509, 1509, 1511, 1506, 1504, 1431, 1511, 1514, 1511, 1506, 1509, 1509, 1504, 1510, 1510, 1508, 1506, 1426, 1510, 1509, 1510, 1425, 1505, 1508, 1508, 1425, 1506, 1431, 1508, 1430, 1507, 1511, 1506, 1430, 1504, 1426, 1508, 1424, 1506, 1511, 1505, 1507, 1506, 1511, 1507, 1510, 1504, 1509, 1505, 1514, 1506, 1430, 1505, 1429, 1507, 1511, 1506, 1430, 1505, 1506, 1504, 1430, 1506, 1511, 1504, 1511, 1506, 1510, 1507, 1429, 1504, 1431, 1505, 1510, 1506, 1430, 1504, 1426, 1506, 1510, 1507, 1504, 1505, 1431, 1504, 1511, 1507, 1510, 1508, 1505, 1506, 1514, 1507, 1429, 1505, 1508, 1504, 1515, 1510, 1425, 1509, 1429, 1505, 1511, 1511, 1424, 1509, 1511, 1509, 1505, 1506, 1511, 1505, 1430, 1507, 1424, 1507, 1508, 1504, 1426, 1509, 1507, 1511, 1507, 1508, 1514, 1510, 1514, 1506, 1425, 1504, 1430, 1504, 1424, 1506, 1515, 1509, 1429, 1510, 1509, 1510, 1426, 1504, 1506, 1508, 1514, 1510, 1426, 1509, 1426, 1507, 1510, 1505, 1429, 1509, 1511, 1509, 1507, 1507, 1510, 1508, 1510, 1507, 1431, 1511, 1425, 1504, 1509, 1505, 1510, 1506, 1515, 1508, 1505, 1507, 1511, 1506, 1510, 1505, 1509, 1504, 1430, 1506, 1511, 1505, 1508, 1507, 1430, 1506, 1426, 1504, 1509, 1505, 1510, 1507, 1504, 1504, 1507, 1507, 1511, 1507, 1506, 1505, 1508, 1504, 1511, 1506, 1429, 1505, 1425, 1507, 1430, 1507, 1429, 1505, 1508, 1504, 1515, 1507, 1429, 1504, 1426, 1506, 1510, 1511, 1508, 1505, 1425, 1504, 1511, 1507, 1510, 1504, 1509, 1511, 1425, 1510, 1426, 1504, 1509, 1505, 1507, 1506, 1424, 1504, 1505, 1507, 1515, 1510, 1425, 1509, 1505, 1508, 1508, 1511, 1514, 1505, 1430, 1507, 1424, 1507, 1508, 1504, 1426, 1509, 1506, 1511, 1509, 1509, 1429, 1510, 1505, 1511, 1424, 1504, 1424, 1505, 1506, 1507, 1424, 1505, 1504, 1506, 1508, 1510, 1424, 1504, 1430, 1505, 1508, 1506, 1425, 1509, 1424, 2596, 2595, 2592, 2598, 2593, 2645, 2596, 2646, 2595, 2593, 2595, 2653, 2596, 2595, 2598, 2643, 2595, 2642, 2596, 2644, 2592, 2598, 2598, 2653, 1972, 1971, 1968, 1974, 1969, 1989, 1972, 1990, 1971, 1969, 1971, 1997, 1972, 1990, 1968, 1972, 1968, 1974, 1972, 1968, 1969, 1971, 1971, 1975, 2585, 2667, 2588, 2664, 2588, 2671, 2585, 2590, 2590, 2590, 2590, 2590, 2585, 2590, 2587, 2670, 2590, 2671, 2585, 2665, 2589, 2587, 2587, 2656, 1542, 1652, 1539, 1655, 1539, 1648, 1542, 1537, 1537, 1537, 1537, 1537, 1542, 1540, 1539, 1654, 1540, 1649, 1542, 1654, 1538, 1540, 1540, 1663, 3098, 3103, 3103, 3178, 3103, 3181, 3098, 3176, 3096, 3177, 3103, 3182, 3098, 3101, 3096, 3181, 3101, 3180, 3098, 3178, 3102, 3096, 3096, 3171, 3016, 3020, 3023, 3005, 3023, 3001, 3016, 3020, 3021, 3023, 3023, 3004, 3016, 3023, 3018, 3007, 3023, 3006, 3016, 3000, 3020, 3018, 3018, 2993, 1002, 1006, 1005, 927, 1005, 923, 1002, 1006, 1007, 1005, 1005, 926, 1002, 920, 1006, 1002, 1006, 1000, 1002, 1006, 1007, 1005, 1005, 1001, 571, 572, 575, 569, 569, 587, 571, 587, 569, 589, 569, 572, 571, 572, 569, 588, 572, 589, 571, 587, 575, 569, 569, 578, 2125, 2104, 2125, 2107, 2127, 2104, 2107, 2120, 2105, 2120, 2111, 2120, 2107, 2110, 2110, 2121, 2108, 2122, 2107, 2121, 2108, 2124, 2111, 2105, 2107, 2110, 2110, 2105, 2110, 2121, 1595, 1612, 1595, 1609, 1592, 1615, 1596, 1608, 1598, 1594, 957, 954, 957, 974, 952, 969, 2827, 2941, 1957, 1957, 1957, 2004, 1959, 1956, 1957, 2000, 1958, 1956, 1954, 1958, 1953, 2007, 1953, 1966, 1955, 1966, 2421, 2311, 2421, 2308, 2423, 2311, 2421, 2423, 2422, 2305, 2422, 2416, 2421, 2305, 2421, 2421, 2423, 2420, 2421, 2310, 2422, 2422, 2422, 2307, 2421, 2418, 2421, 2419, 2423, 2418, 2420, 2419, 2423, 2428, 2423, 2420, 2420, 2428, 2420, 2310, 2422, 2305, 2420, 2421, 2423, 2307, 2423, 2311, 2420, 2422, 2420, 2423, 2421, 2428, 2423, 2417, 2420, 2311, 2420, 2418, 2423, 2307, 2423, 2304, 2421, 2307, 2423, 2304, 2420, 2420, 2420, 2428, 2423, 2420, 2423, 2417, 2421, 2416, 2423, 2429, 2420, 2418, 2421, 2422, 2422, 2311, 2422, 2308, 2420, 2311, 2422, 2423, 2421, 2305, 2421, 2416, 2422, 2305, 2422, 2421, 2420, 2420, 2422, 2310, 2417, 2429, 2417, 2423, 2418, 2429, 2418, 2311, 2416, 2310, 2418, 2422, 2417, 2304, 2417, 2417, 2418, 2423, 2418, 2420, 2860, 2908, 2860, 2860, 2863, 2907, 2856, 2858, 2863, 2910, 2858, 2858, 1272, 1164, 1273, 1160, 336, 292, 336, 289, 2038, 2033, 2038, 1927, 887, 886, 887, 774, 3042, 2962, 3042, 2962, 3041, 2963, 3042, 2966, 3041, 3047, 3041, 2964, 2619, 2638, 2619, 2633, 2616, 2620, 2623, 2634, 2617, 2616, 2616, 2623, 2619, 2637, 2619, 2608, 444, 446, 440, 440, 443, 456, 3213, 3215, 3209, 3321, 3210, 3321, 3208, 3214, 1326, 1324, 1322, 1320, 1321, 1371, 1322, 1370, 1321, 1324, 996, 997, 997, 1007, 998, 999, 997, 912, 999, 999, 995, 914, 996, 1007, 996, 993, 998, 995, 2386, 2338, 2391, 2391, 2384, 2392, 2386, 2390, 2388, 2391, 2384, 2385, 2387, 2388, 2387, 2388, 1339, 1339, 1338, 1343, 1337, 1356, 1338, 1356, 1336, 1356, 1341, 1356, 1338, 1329, 1338, 1343, 1336, 1341, 2856, 2860, 2856, 2910, 2862, 2860, 2856, 2863, 2859, 2861, 2859, 2911, 2084, 2130, 2084, 2131, 2087, 2132, 2085, 2094, 2087, 2131, 2082, 2131, 2085, 2094, 2085, 2080, 2087, 2082, 1947, 1950, 1947, 2031, 1944, 2026, 1946, 1945, 1948, 1951, 1944, 1945, 1947, 1948, 1947, 1948, 2683, 2673, 2682, 2687, 2680, 2680, 2683, 2683, 2685, 2686, 2681, 2680, 2682, 2685, 2682, 2685, 1137, 1025, 1137, 1025, 1139, 1030, 1136, 1136, 1138, 1141, 1142, 1031, 1137, 1146, 1137, 1141, 1522, 1528, 1522, 1521, 1520, 1524, 1522, 1414, 1521, 1409, 1521, 1409, 1522, 1526, 1522, 1521, 1524, 1526, 1522, 1525, 1521, 1527, 1521, 1413, 2222, 2212, 2222, 2213, 2220, 2219, 2222, 2220, 
                            2221, 2264, 2221, 2264, 2222, 2264, 2218, 2218, 2220, 2270, 2222, 2213, 2221, 2217, 3230, 3308, 3230, 3221, 3228, 3310, 3230, 3229, 3224, 3227, 3228, 3229, 3231, 3224, 3231, 3224, 3196, 3194, 2803, 2811, 2802, 2802, 2800, 2811, 2804, 2688, 2806, 2811, 1193, 1185, 1192, 1192, 1194, 1185, 1198, 1242, 1196, 1241, 1196, 1198, 1199, 1243, 675, 681, 674, 672, 672, 681, 675, 676, 673, 724, 675, 721, 674, 725, 411, 490, 411, 414, 409, 413, 411, 411, 408, 493, 409, 408, 1239, 1245, 1238, 1236, 1236, 1245, 1239, 1232, 1237, 1184, 1238, 1239, 1239, 1184, 1238, 1191, 421, 431, 420, 422, 422, 431, 421, 418, 423, 466, 421, 471, 420, 467, 2798, 2719, 2798, 2795, 2796, 2792, 2798, 2798, 2797, 2712, 2796, 2797, 1882, 1832, 1883, 1872, 1880, 1832, 1883, 1882, 1880, 1887, 1881, 1886, 1881, 1882, 1883, 1834, 937, 984, 937, 938, 939, 943, 937, 936, 937, 984, 938, 984, 937, 938, 937, 991, 939, 941, 2272, 2282, 2273, 2275, 2275, 2282, 2272, 2279, 2274, 2199, 2273, 2272, 2272, 2199, 2273, 2192, 2289, 2177, 1596, 1594, 2733, 2733};
                            m115 = C0051.f47 <= 0 ? C0012.m31("ۣ۟۠") : (C0053.f48 % C0043.f39) + 1754514;
                        case 1753449:
                            b = (Pattern) C0072.n(32423, null, new Object[]{(String) C0072.n(10418, null, new Object[]{(String) C0062.n(42496, null, new Object[]{(short[]) C0070.n(68248), 56, 320, 1491})})});
                            str2 = "ۣ۠ۡ";
                            m115 = C0010.m22(str2);
                        case 1754442:
                            str3 = C0028.f21 <= 0 ? "۠ۨۨ" : "ۥۤۦ";
                            m115 = C0053.m157(str3);
                        case 1754594:
                            h = (List) C0074.n(23138, null, new Object[]{new String[]{(String) C0072.n(10418, null, new Object[]{(String) C0072.n(84470, null, new Object[]{(short[]) C0070.n(68248), 568, 30, 2170})})}});
                            if (C0042.f38 - (C0010.f9 + 4584) >= 0) {
                                m115 = C0019.m40("ۣۤۡ");
                            } else {
                                str = "ۣۤۡ";
                                m115 = C0033.m85(str);
                            }
                        case 1754597:
                            a = (List) C0074.n(23138, null, new Object[]{new String[]{(String) C0072.n(10418, null, new Object[]{(String) C0072.n(29305, null, new Object[]{(short[]) C0070.n(68248), 0, 6, 2015})}), (String) C0072.n(10418, null, new Object[]{(String) C0072.n(81708, null, new Object[]{(short[]) C0070.n(68248), 6, 6, 2248})}), (String) C0072.n(10418, null, new Object[]{(String) C0058.n(93783, null, new Object[]{(short[]) C0070.n(68248), 12, 6, 2583})}), (String) C0072.n(10418, null, new Object[]{(String) C0065.n(62584, null, new Object[]{(short[]) C0070.n(68248), 18, 6, 1830})}), (String) C0072.n(10418, null, new Object[]{(String) C0062.n(50173, null, new Object[]{(short[]) C0070.n(68248), 24, 6, 412})}), (String) C0072.n(10418, null, new Object[]{(String) C0062.n(39600, null, new Object[]{(short[]) C0070.n(68248), 30, 6, 1430})}), (String) C0072.n(10418, null, new Object[]{(String) C0072.n(17470, null, new Object[]{(short[]) C0070.n(68248), 36, 6, 1168})}), (String) C0072.n(10418, null, new Object[]{(String) C0065.n(70747, null, new Object[]{(short[]) C0070.n(68248), 42, 8, 2906})}), (String) C0072.n(10418, null, new Object[]{(String) C0065.n(17921, null, new Object[]{(short[]) C0070.n(68248), 50, 6, 885})})}});
                            if ((C0026.f19 ^ (C0044.f41 % 6454)) >= 0) {
                                C0038.f35 = 95;
                            }
                            m115 = C0011.m28("ۦۣ۠");
                    }
                }
            }

            public static String a(String str) {
                try {
                    StringBuilder sb = new StringBuilder(new BigInteger(1, MessageDigest.getInstance("MD5").digest(str.getBytes("UTF-8"))).toString(16));
                    while (sb.length() < 32) {
                        sb.insert(0, "0");
                    }
                    return sb.toString().toLowerCase();
                } catch (Exception unused) {
                    return "";
                }
            }

            public static String b(String str) {
                StringBuilder sb;
                String str2;
                String str3;
                int i2;
                String str4;
                String str5;
                int i3 = 0;
                StringBuilder sb2 = null;
                int i4 = 0;
                int i5 = 0;
                String str6 = null;
                int m54 = C0023.m54("ۣۦ۠");
                while (true) {
                    switch (m54) {
                        case 56506:
                        case 1749700:
                            m54 = C0049.f45 - (C0039.f36 + 7783) >= 0 ? C0045.m133("ۡۧ۠") : (C0010.f9 ^ C0041.f37) ^ 1754894;
                        case 1746942:
                            return (String) C0072.n(16125, sb2, new Object[0]);
                        case 1747744:
                            if (C0029.f22 <= 0) {
                                str2 = "ۧ۟";
                                m54 = C0008.m14(str2);
                            } else {
                                m54 = (C0029.f22 * C0026.f19) ^ 1913651;
                            }
                        case 1747837:
                            String str7 = new String((byte[]) C0074.n(26675, null, new Object[]{str, 0}));
                            if (C0000.m2() >= 0) {
                                C0015.m38();
                                str6 = str7;
                                m54 = C0023.m54("ۢۨ۠");
                            } else {
                                str6 = str7;
                                m54 = C0008.m14("ۡۨۨ");
                            }
                        case 1748897:
                            sb = new StringBuilder();
                            if (C0012.f11 > 0) {
                                sb2 = sb;
                                m54 = (-1749083) ^ (C0025.f18 + C0008.f7);
                            } else {
                                str5 = "ۢ۠۠";
                                sb2 = sb;
                                m54 = C0030.m80(str5);
                            }
                        case 1749602:
                            StringBuilder sb3 = (StringBuilder) C0069.n(58973, sb2, new Object[]{Character.valueOf((char) (((Character) C0078.n(30628, str6, new Object[]{Integer.valueOf(i5)})).charValue() - ((Character) C0078.n(30628, (String) C0072.n(10418, null, new Object[]{(String) C0072.n(84470, null, new Object[]{(short[]) C0070.n(68248), 616, 18, 1942})}), new Object[]{Integer.valueOf(i5 % 9)})).charValue()))});
                            m54 = C0008.f7 + C0042.f38 + 1756796;
                        case 1749850:
                            if (C0023.f16 * (C0034.f33 ^ (-8339)) >= 0) {
                                C0010.m24();
                                m54 = C0029.m77("۠ۢۢ");
                                i5 = i3;
                            } else {
                                str4 = "ۧۧۡ";
                                i2 = i3;
                                i5 = i3;
                                i3 = i2;
                                m54 = C0027.m67(str4);
                            }
                        case 1750749:
                            i2 = 0;
                            str4 = "۠ۥۢ";
                            i3 = i2;
                            m54 = C0027.m67(str4);
                        case 1752515:
                            if (C0021.m47() >= 0) {
                                C0048.f44 = 2;
                                m54 = C0020.m44("ۥۡ۟");
                                i5 = i4;
                            } else {
                                str3 = "ۣۨۨ";
                                i5 = i4;
                                m54 = C0019.m40(str3);
                            }
                        case 1754439:
                            str2 = "۟ۧۦ";
                            m54 = C0008.m14(str2);
                        case 1754625:
                            if (i5 >= ((Integer) C0076.n(35419, str6, new Object[0])).intValue()) {
                                str2 = "۟ۧۦ";
                                m54 = C0008.m14(str2);
                            } else if ((C0041.f37 ^ (C0048.f44 - 7233)) <= 0) {
                                m54 = C0020.m44("ۣۦ۠");
                            } else {
                                sb = sb2;
                                str5 = "ۢ۠۠";
                                sb2 = sb;
                                m54 = C0030.m80(str5);
                            }
                        case 1755523:
                            i4 = i5 + 1;
                            if (C0039.f36 / (C0019.f13 ^ (-8646)) != 0) {
                                str4 = "ۦ۠";
                                i2 = i3;
                                i3 = i2;
                                m54 = C0027.m67(str4);
                            } else {
                                str5 = "ۥۡ۟";
                                sb = sb2;
                                sb2 = sb;
                                m54 = C0030.m80(str5);
                            }
                        case 1755619:
                            if (C0051.m154() <= 0) {
                                C0037.m101();
                                str3 = "۠ۥۢ";
                                m54 = C0019.m40(str3);
                            } else {
                                str2 = "ۣۢۥ";
                                m54 = C0008.m14(str2);
                            }
                    }
                }
            }

            public static int c(int i2) {
                return (int) TypedValue.applyDimension(1, i2, Init.context().getResources().getDisplayMetrics());
            }

            public static String d() {
                int i2;
                int intValue;
                String str;
                String str2;
                int i3 = 0;
                int i4 = 0;
                int i5 = 0;
                int i6 = 0;
                int i7 = 0;
                StringBuilder sb = null;
                int m170 = C0056.m170("ۦۤۤ");
                while (true) {
                    switch (m170) {
                        case 56390:
                        case 1755370:
                            m170 = C0037.m99("ۤۦۧ");
                        case 1746938:
                            m170 = (C0048.f44 / C0025.f18) + 1753573;
                        case 1747811:
                            return (String) C0072.n(16125, sb, new Object[0]);
                        case 1748674:
                            if (C0044.m131() <= 0) {
                                i2 = i7;
                                str2 = "ۤۦۧ";
                                m170 = C0010.m22(str2);
                                i7 = i2;
                            } else {
                                m170 = (C0009.f8 ^ C0015.f12) ^ 1755647;
                            }
                        case 1749671:
                            StringBuilder sb2 = new StringBuilder(i3);
                            if (C0043.m121() >= 0) {
                                C0021.f15 = 44;
                                sb = sb2;
                                m170 = C0000.m1("ۨ۠ۢ");
                            } else {
                                sb = sb2;
                                m170 = (C0000.f0 - C0038.f35) + 1752142;
                            }
                        case 1749702:
                            i2 = i4;
                            str2 = "ۤۦۧ";
                            m170 = C0010.m22(str2);
                            i7 = i2;
                        case 1750811:
                            i4 = 0;
                            if (C0027.f20 - (C0015.f12 - 3001) <= 0) {
                                C0030.f23 = 0;
                                m170 = C0041.m112("ۦۤۤ");
                            } else {
                                m170 = (C0007.f6 - C0039.f36) ^ (-1750486);
                            }
                        case 1751687:
                            i5 = ((Integer) C0064.n(55720, (Random) C0064.n(28132), new Object[]{62})).intValue();
                            str = "ۦۣۡ";
                            intValue = i3;
                            i3 = intValue;
                            m170 = C0000.m1(str);
                        case 1751717:
                            if (i7 >= i3) {
                                m170 = (C0011.f10 % C0011.f10) + 1747811;
                            } else if (C0020.f14 * (C0009.f8 / 6360) != 0) {
                                C0038.f35 = 52;
                                m170 = C0019.m40("ۡۡۢ");
                            } else {
                                m170 = (C0045.f42 * C0055.f50) + 1757835;
                            }
                        case 1753417:
                            str2 = "ۡۡۢ";
                            i2 = i6;
                            m170 = C0010.m22(str2);
                            i7 = i2;
                        case 1753480:
                            StringBuilder sb3 = (StringBuilder) C0069.n(58973, sb, new Object[]{Character.valueOf(((Character) C0078.n(30628, (String) C0072.n(10418, null, new Object[]{(String) C0062.n(50173, null, new Object[]{(short[]) C0070.n(68248), 634, 124, 2373})}), new Object[]{Integer.valueOf(i5)})).charValue())});
                            m170 = (C0048.f44 - C0056.f51) ^ 1755375;
                        case 1753574:
                            intValue = ((Integer) C0064.n(55720, (Random) C0064.n(28132), new Object[]{3})).intValue() + 10;
                            if (C0027.f20 + (C0056.f51 - 1535) >= 0) {
                                C0012.m32();
                                str = "ۦ۟ۢ";
                                i3 = intValue;
                                m170 = C0000.m1(str);
                            } else {
                                i3 = intValue;
                                m170 = C0026.f19 + C0053.f48 + 1748178;
                            }
                        case 1754656:
                            m170 = (C0011.f10 % C0011.f10) + 1747811;
                        case 1755342:
                            i6 = i7 + 1;
                            if (C0042.f38 >= 0) {
                                C0048.f44 = 69;
                                m170 = C0021.m49("ۣۢۧ");
                            } else {
                                intValue = i3;
                                str = "ۦ۟ۢ";
                                i3 = intValue;
                                m170 = C0000.m1(str);
                            }
                    }
                }
            }

            public static String e(double d2) {
                return d2 <= 0.0d ? "" : d2 > 1.099511627776E12d ? String.format(Locale.getDefault(), "%.2f%s", Double.valueOf(d2 / 1.099511627776E12d), "TB") : d2 > 1.073741824E9d ? String.format(Locale.getDefault(), "%.2f%s", Double.valueOf(d2 / 1.073741824E9d), "GB") : d2 > 1048576.0d ? String.format(Locale.getDefault(), "%.2f%s", Double.valueOf(d2 / 1048576.0d), "MB") : String.format(Locale.getDefault(), "%.2f%s", Double.valueOf(d2 / 1024.0d), "KB");
            }

            public static boolean f() {
                for (Method method : Spider.class.getDeclaredMethods()) {
                    if ("action".equals(method.getName())) {
                        return true;
                    }
                }
                return false;
            }

            /* JADX WARN: Removed duplicated region for block: B:15:0x0083  */
            /* JADX WARN: Removed duplicated region for block: B:17:0x008a  */
            /*
                Code decompiled incorrectly, please refer to instructions dump.
            */
            public static boolean g(String str) {
                String str2;
                String str3;
                int m164 = C0055.m164("ۡۧۡ");
                while (true) {
                    switch (m164) {
                        case 1748859:
                            if (((Boolean) C0076.n(84095, str, new Object[]{(String) C0072.n(10418, null, new Object[]{(String) C0062.n(39600, null, new Object[]{(short[]) C0070.n(68248), 798, 16, 2568})})})).booleanValue()) {
                                m164 = C0037.m99(C0033.f24 / (C0039.f36 + 875) == 0 ? "۠ۨۡ" : "ۤۨۥ");
                            } else {
                                str2 = "ۣۡۨ";
                                m164 = C0020.m44(str2);
                            }
                        case 1749573:
                            if (!((Boolean) C0076.n(84095, str, new Object[]{(String) C0072.n(10418, null, new Object[]{(String) C0065.n(75313, null, new Object[]{(short[]) C0070.n(68248), 828, 10, 1304})})})).booleanValue()) {
                                m164 = (C0049.f45 | C0043.f39) + 1751245;
                            } else if (C0020.f14 - (C0055.f50 - 5538) <= 0) {
                                C0009.m18();
                                str2 = "ۣۡۨ";
                                m164 = C0020.m44(str2);
                            } else {
                                m164 = (C0049.f45 - C0024.f17) + 1751183;
                            }
                        case 1750602:
                            if (((Boolean) C0076.n(84095, str, new Object[]{(String) C0072.n(10418, null, new Object[]{(String) C0072.n(66986, null, new Object[]{(short[]) C0070.n(68248), 814, 6, 394})})})).booleanValue()) {
                                m164 = C0037.m99(C0033.f24 / (C0039.f36 + 875) == 0 ? "۠ۨۡ" : "ۤۨۥ");
                            } else {
                                m164 = (C0048.f44 | C0023.f16) + 1755668;
                            }
                            break;
                        case 1750664:
                            m164 = (C0049.f45 | C0043.f39) + 1751245;
                        case 1750814:
                            return ((Boolean) C0065.n(21092, (Matcher) C0065.n(76834, (Pattern) C0064.n(15889), new Object[]{str}), new Object[0])).booleanValue();
                        case 1751716:
                            m164 = (C0055.f50 * C0012.f11) + 1774730;
                        case 1751777:
                            return false;
                        case 1754537:
                        case 1754659:
                            m164 = C0037.m99(C0033.f24 / (C0039.f36 + 875) == 0 ? "۠ۨۡ" : "ۤۨۥ");
                            break;
                        case 1755371:
                            if (((Boolean) C0076.n(84095, str, new Object[]{(String) C0072.n(10418, null, new Object[]{(String) C0072.n(76232, null, new Object[]{(short[]) C0070.n(68248), 820, 8, 3259})})})).booleanValue()) {
                                m164 = C0037.m99(C0033.f24 / (C0039.f36 + 875) == 0 ? "۠ۨۡ" : "ۤۨۥ");
                            } else if (C0049.f45 + (C0053.f48 / (-6295)) >= 0) {
                                m164 = C0029.m77("ۢ۟ۢ");
                            } else {
                                str2 = "ۢ۟ۢ";
                                m164 = C0020.m44(str2);
                            }
                            break;
                        case 1755525:
                            if (C0008.f7 >= 0) {
                                C0056.m168();
                                str3 = "ۨۨۨ";
                            } else {
                                str3 = "ۡۧۡ";
                            }
                            m164 = C0021.m49(str3);
                    }
                }
            }

            /* JADX WARN: Removed duplicated region for block: B:12:0x003f  */
            /* JADX WARN: Removed duplicated region for block: B:14:0x004b  */
            /* JADX WARN: Removed duplicated region for block: B:34:0x0380  */
            /* JADX WARN: Removed duplicated region for block: B:35:0x0387  */
            /*
                Code decompiled incorrectly, please refer to instructions dump.
            */
            public static boolean h(String str) {
                String str2;
                String str3;
                String str4;
                Iterator it = null;
                int m129 = C0044.m129("ۤۦۦ");
                while (true) {
                    switch (m129) {
                        case 56476:
                            if (!((Boolean) C0058.n(61475, it, new Object[0])).booleanValue()) {
                                if (C0011.m29() > 0) {
                                    C0051.m154();
                                    str4 = "ۧۨۤ";
                                } else {
                                    str4 = "ۥ۟۠";
                                }
                                m129 = C0028.m71(str4);
                            } else if (C0056.f51 - (C0056.f51 + 4805) >= 0) {
                                C0042.m114();
                                m129 = C0041.m112("ۨۧۧ");
                            } else {
                                str4 = "۟ۤ۟";
                                m129 = C0028.m71(str4);
                            }
                        case 1746842:
                            if (((Boolean) C0076.n(84095, str, new Object[]{(String) C0062.n(31553, it, new Object[0])})).booleanValue()) {
                                str2 = "ۨۤۡ";
                                m129 = C0056.m170(str2);
                            } else {
                                if (C0020.f14 + (C0056.f51 - 7305) < 0) {
                                    C0024.f17 = 28;
                                    str3 = "ۣۣۨ";
                                } else {
                                    str3 = "ۥۡ";
                                }
                                m129 = C0029.m77(str3);
                            }
                        case 1746906:
                            if (C0046.m139() >= 0) {
                                C0021.f15 = 6;
                                str2 = "ۧۧۥ";
                                m129 = C0056.m170(str2);
                            } else {
                                m129 = (C0049.f45 % C0029.f22) + 1752147;
                            }
                        case 1751716:
                            Iterator it2 = (Iterator) C0058.n(30861, (List) C0074.n(23138, null, new Object[]{new String[]{(String) C0072.n(10418, null, new Object[]{(String) C0072.n(29305, null, new Object[]{(short[]) C0070.n(68248), 838, 18, 982})}), (String) C0072.n(10418, null, new Object[]{(String) C0058.n(23638, null, new Object[]{(short[]) C0070.n(68248), 856, 16, 2401})}), (String) C0072.n(10418, null, new Object[]{(String) C0058.n(93783, null, new Object[]{(short[]) C0070.n(68248), 872, 18, 1288})}), (String) C0072.n(10418, null, new Object[]{(String) C0072.n(81708, null, new Object[]{(short[]) C0070.n(68248), 890, 12, 2842})}), (String) C0072.n(10418, null, new Object[]{(String) C0062.n(39600, null, new Object[]{(short[]) C0070.n(68248), 902, 18, 2071})}), (String) C0072.n(10418, null, new Object[]{(String) C0065.n(63139, null, new Object[]{(short[]) C0070.n(68248), 920, 16, 1961})}), (String) C0072.n(10418, null, new Object[]{(String) C0062.n(97790, null, new Object[]{(short[]) C0070.n(68248), 936, 16, 2632})}), (String) C0072.n(10418, null, new Object[]{(String) C0072.n(18473, null, new Object[]{(short[]) C0070.n(68248), 952, 16, 1091})}), (String) C0072.n(10418, null, new Object[]{(String) C0062.n(49552, null, new Object[]{(short[]) C0070.n(68248), 968, 24, 1472})}), (String) C0072.n(10418, null, new Object[]{(String) C0072.n(81708, null, new Object[]{(short[]) C0070.n(68248), 992, 22, 2204})}), (String) C0072.n(10418, null, new Object[]{(String) C0058.n(64585, null, new Object[]{(short[]) C0070.n(68248), 1014, 16, 3245})})}}), new Object[0]);
                            if (C0053.f48 + C0027.f20 + 2736 <= 0) {
                                C0007.f6 = 61;
                                m129 = C0027.m67("ۨۤۡ");
                                it = it2;
                            } else {
                                m129 = 55909 + (C0055.f50 - C0000.f0);
                                it = it2;
                            }
                        case 1752454:
                            return false;
                        case 1755493:
                            return true;
                        case 1755554:
                            if (C0011.m29() > 0) {
                            }
                            m129 = C0028.m71(str4);
                            break;
                        case 1755592:
                            if (C0020.f14 + (C0056.f51 - 7305) < 0) {
                            }
                            m129 = C0029.m77(str3);
                            break;
                    }
                }
            }

            public static void i(String str) {
                if (str.equals(".aliyun")) {
                    str = "已清除阿里Token";
                } else if (str.equals("quark_cookie.txt")) {
                    str = "已清除夸克Cookie";
                } else if (str.equals("uc_cookie.txt")) {
                    str = "已清除UC Cookie";
                } else if (str.equals("uc_token.txt")) {
                    str = "已清除UC TV Token";
                } else if (str.equals("cloud189.txt")) {
                    str = "已清除天翼Cookie";
                } else if (str.equals("cloud123.txt")) {
                    str = "已清除123 Cookie";
                } else if (str.equals("baidu.txt")) {
                    str = "已清除百度Cookie";
                } else if (str.equals("bili_cookie.txt")) {
                    str = "已清除哔哩Cookie";
                }
                if (TextUtils.isEmpty(str)) {
                    return;
                }
                Init.run(new RunnableC0587g(str, 4));
            }

            /* renamed from: i  reason: collision with other method in static class */
            public static boolean m126i(String str) {
                return a.contains(m(str));
            }

            private static HashMap<String, String> j(String str) {
                String[] split;
                HashMap<String, String> hashMap = new HashMap<>();
                for (String str2 : str.split(";")) {
                    int indexOf = str2.indexOf(61);
                    if (indexOf != -1) {
                        hashMap.put(str2.substring(0, indexOf).trim(), str2.substring(indexOf + 1).trim());
                    }
                }
                return hashMap;
            }

            public static String k(String str) {
                try {
                    byte[] digest = MessageDigest.getInstance("SHA-1").digest(str.getBytes());
                    StringBuilder sb = new StringBuilder();
                    for (byte b2 : digest) {
                        String hexString = Integer.toHexString(b2 & 255);
                        if (hexString.length() == 1) {
                            sb.append('0');
                        }
                        sb.append(hexString);
                    }
                    return sb.toString();
                } catch (Exception e2) {
                    e2.printStackTrace();
                    return "";
                }
            }

            /* JADX WARN: Removed duplicated region for block: B:28:0x0082  */
            /* JADX WARN: Removed duplicated region for block: B:29:0x008a  */
            /*
                Code decompiled incorrectly, please refer to instructions dump.
            */
            public static String l(String str) {
                String str2;
                StringBuilder sb;
                String str3;
                String str4;
                BigInteger bigInteger;
                byte[] bArr = null;
                BigInteger bigInteger2 = null;
                StringBuilder sb2 = null;
                String str5 = null;
                int m157 = C0053.m157("ۨۤۨ");
                while (true) {
                    switch (m157) {
                        case 1746785:
                        case 1754471:
                            if (C0030.m82() <= 0) {
                                C0050.f46 = 6;
                                m157 = C0054.m161("۠ۡۤ");
                            } else {
                                m157 = (C0024.f17 / C0026.f19) ^ (-1751657);
                            }
                        case 1746973:
                            return str5;
                        case 1748672:
                            StringBuilder sb3 = (StringBuilder) C0064.n(54222, sb2, new Object[]{0, '0'});
                            if ((C0050.f46 ^ (C0053.f48 % 7244)) >= 0) {
                            }
                            m157 = C0045.m133("ۥۦۢ");
                        case 1748673:
                            if (C0039.m109() <= 0) {
                                C0039.m109();
                                str2 = "ۢۡۡ";
                                sb = sb2;
                                sb2 = sb;
                                m157 = C0030.m80(str2);
                            } else {
                                m157 = (C0025.f18 / C0027.f20) + 1755500;
                            }
                        case 1748678:
                            String str6 = (String) C0072.n(16125, sb2, new Object[0]);
                            if (C0027.f20 >= 0) {
                                C0038.m102();
                                str5 = str6;
                                m157 = C0034.m93("ۥ۟ۧ");
                            } else {
                                str5 = str6;
                                m157 = C0020.m44("۟ۨۦ");
                            }
                        case 1748860:
                            sb = new StringBuilder((String) C0070.n(37794, bigInteger2, new Object[]{16}));
                            if (C0034.f33 % (C0051.f47 | 9957) >= 0) {
                                C0029.f22 = 22;
                                sb2 = sb;
                                m157 = C0049.m147("ۡۡۦ");
                            } else {
                                str2 = "ۤۤۨ";
                                sb2 = sb;
                                m157 = C0030.m80(str2);
                            }
                        case 1751493:
                            m157 = (C0043.f39 * C0026.f19) + 1288965;
                        case 1751656:
                            if (((Integer) C0064.n(29317, sb2, new Object[0])).intValue() < 64) {
                                m157 = C0008.f7 - (C0039.f36 * 5834) >= 0 ? C0008.m14("ۤۤۨ") : (C0046.f43 / C0045.f42) ^ 1748677;
                            } else {
                                if (C0055.f50 - (C0053.f48 * 977) < 0) {
                                    C0033.f24 = 14;
                                    str3 = "ۣۧۦ";
                                } else {
                                    str3 = "ۡۡۦ";
                                }
                                m157 = C0038.m103(str3);
                            }
                        case 1751686:
                            try {
                                bigInteger = new BigInteger(1, bArr);
                                if (C0041.f37 >= 0) {
                                    C0048.m143();
                                    bigInteger2 = bigInteger;
                                    m157 = C0021.m49("ۡۧۢ");
                                } else {
                                    str4 = "ۡۧۢ";
                                    bigInteger2 = bigInteger;
                                    m157 = C0024.m58(str4);
                                }
                            } catch (Exception e2) {
                                C0060.n(39227, e2, new Object[0]);
                                return (String) C0062.n(87933);
                            }
                        case 1752461:
                            if (C0026.f19 <= 0) {
                                str4 = "ۣۢ";
                                bigInteger = bigInteger2;
                                bigInteger2 = bigInteger;
                                m157 = C0024.m58(str4);
                            } else {
                                str3 = "۟ۨۦ";
                                m157 = C0038.m103(str3);
                            }
                        case 1752673:
                            if (C0041.m111() <= 0) {
                                C0056.f51 = 9;
                                m157 = C0048.m141("ۨۢۥ");
                            } else {
                                m157 = (C0027.f20 ^ C0028.f21) ^ (-1746483);
                            }
                        case 1754661:
                            str3 = "ۤۥۧ";
                            bArr = (byte[]) C0070.n(81238, (MessageDigest) C0070.n(95540, null, new Object[]{(String) C0072.n(10418, null, new Object[]{(String) C0062.n(39600, null, new Object[]{(short[]) C0070.n(68248), 1042, 14, 1176})})}), new Object[]{(byte[]) C0060.n(74406, str, new Object[0])});
                            m157 = C0038.m103(str3);
                        case 1755435:
                            if (C0055.f50 - (C0053.f48 * 977) < 0) {
                            }
                            m157 = C0038.m103(str3);
                            break;
                        case 1755500:
                            m157 = C0008.f7 - (C0037.f34 / (-3726)) >= 0 ? C0049.m147("ۤ۟۠") : (C0038.f35 * C0041.f37) + 2041194;
                    }
                }
            }

            public static String m(String str) {
                return str.contains(".") ? str.substring(str.lastIndexOf(".") + 1) : str;
            }

            public static String m(String str, String str2) {
                int m99 = C0037.m99("ۨۧۢ");
                while (true) {
                    switch (m99) {
                        case 1748829:
                            m99 = (C0038.f35 + C0046.f43) ^ 1756345;
                        case 1755587:
                            return (String) C0060.n(48860, new SimpleDateFormat(str2), new Object[]{new Date(((Long) C0077.n(58872, new Long(str), new Object[0])).longValue() * 1000)});
                    }
                }
            }

            public static String n(String str, String str2, String str3) {
                try {
                    JSONArray jSONArray = new JSONArray();
                    JSONObject jSONObject = new JSONObject();
                    jSONObject.put("shareId", str);
                    jSONObject.put("folder", str2);
                    if (!TextUtils.isEmpty(str3)) {
                        jSONObject.put("sharePwd", str3);
                    }
                    jSONArray.put(jSONObject);
                    return jSONArray.toString();
                } catch (Exception unused) {
                    return "";
                }
            }

            public static String o(String str, String str2, String str3, String str4) {
                try {
                    JSONArray jSONArray = new JSONArray();
                    JSONObject jSONObject = new JSONObject();
                    jSONObject.put("shareId", str);
                    jSONObject.put("folder", str2);
                    jSONObject.put("parentId", str3);
                    jSONObject.put("fileToken", str4);
                    if (!TextUtils.isEmpty("")) {
                        jSONObject.put("sharePwd", "");
                    }
                    jSONArray.put(jSONObject);
                    return jSONArray.toString();
                } catch (Exception unused) {
                    return "";
                }
            }

            /* JADX WARN: Removed duplicated region for block: B:149:0x01e9 A[SYNTHETIC] */
            /* JADX WARN: Removed duplicated region for block: B:151:0x02f6 A[SYNTHETIC] */
            /* JADX WARN: Removed duplicated region for block: B:154:0x03d2 A[SYNTHETIC] */
            /* JADX WARN: Removed duplicated region for block: B:156:0x03c5 A[SYNTHETIC] */
            /* JADX WARN: Removed duplicated region for block: B:170:0x02f2 A[SYNTHETIC] */
            /* JADX WARN: Removed duplicated region for block: B:171:0x01f2 A[SYNTHETIC] */
            /*
                Code decompiled incorrectly, please refer to instructions dump.
            */
            public static String p(String str) {
                String str2;
                StringBuffer stringBuffer;
                String str3;
                int i2;
                String str4;
                String str5;
                int i3;
                int i4;
                String str6;
                StringBuffer stringBuffer2 = null;
                int i5 = 0;
                int i6 = 0;
                int i7 = 0;
                int i8 = 0;
                int i9 = 0;
                int i10 = 0;
                int i11 = 0;
                int i12 = 0;
                int i13 = 0;
                int m85 = C0033.m85("ۣ۠ۡ");
                while (true) {
                    switch (m85) {
                        case 56327:
                            if (C0015.f12 * (C0033.f24 + 1508) > 0) {
                                str6 = "ۣۡۦ";
                                stringBuffer = stringBuffer2;
                                stringBuffer2 = stringBuffer;
                                m85 = C0024.m58(str6);
                            }
                        case 1746689:
                            m85 = C0041.f37 + C0025.f18 + 1747992;
                            i12 = i11;
                        case 1746784:
                            if (C0012.f11 <= 0) {
                                C0055.f50 = 5;
                                m85 = C0037.m99("ۥۨۡ");
                                i10 = i7;
                            } else {
                                i3 = i8;
                                i4 = i7;
                                m85 = C0026.m63("ۨۢۢ");
                                i8 = i3;
                                i10 = i4;
                            }
                        case 1746937:
                            int i14 = i6 + 6;
                            if (C0010.f9 * C0024.f17 * 6601 <= 0) {
                                i13 = i14;
                                m85 = C0021.m49("۟ۧۥ");
                                i10 = i8;
                            } else {
                                i13 = i14;
                                m85 = (C0033.f24 ^ C0050.f46) ^ (-1749170);
                                i10 = i8;
                            }
                        case 1746941:
                            i5 = 0;
                            str5 = "ۥۢۡ";
                            m85 = C0024.m58(str5);
                        case 1747714:
                            stringBuffer2 = new StringBuffer();
                            C0064.n(18877, stringBuffer2, new Object[]{Integer.valueOf(((Integer) C0076.n(35419, str, new Object[0])).intValue())});
                            if ((C0015.f12 | (C0011.f10 % 4990)) >= 0) {
                                stringBuffer = stringBuffer2;
                                str6 = "ۢۢۨ";
                                stringBuffer2 = stringBuffer;
                                m85 = C0024.m58(str6);
                            } else {
                                m85 = C0008.m14("۟ۧۥ");
                            }
                        case 1747717:
                            if (C0020.m43() <= 0) {
                                C0000.f0 = 6;
                                m85 = C0051.m152("ۣ۠ۢ");
                            } else {
                                m85 = (C0050.f46 % C0012.f11) + 1755911;
                            }
                        case 1747745:
                        case 1754501:
                            if (C0027.f20 % (C0034.f33 - 5813) >= 0) {
                                C0042.m114();
                                str5 = "ۣ۠ۥ";
                                m85 = C0024.m58(str5);
                            } else {
                                str4 = "ۨۢۢ";
                                m85 = C0041.m112(str4);
                            }
                        case 1747897:
                            StringBuffer stringBuffer3 = (StringBuffer) C0064.n(41397, stringBuffer2, new Object[]{(String) C0066.n(92679, str, new Object[]{Integer.valueOf(i12), Integer.valueOf(i6)})});
                            m85 = C0038.f35 <= 0 ? C0054.m161("۠ۡۦ") : (C0000.f0 - C0054.f49) ^ (-1756209);
                        case 1748643:
                            m85 = (C0012.f11 ^ C0034.f33) ^ (-1754441);
                        case 1748734:
                        case 1755339:
                        case 1755587:
                            str2 = "ۢۢۨ";
                            m85 = C0007.m13(str2);
                        case 1748740:
                        case 1748830:
                            str4 = "۟۟ۡ";
                            m85 = C0041.m112(str4);
                        case 1748888:
                            if (C0026.f19 / (C0021.f15 - 3362) != 0) {
                                C0023.f16 = 52;
                                str2 = "ۣۢۦ";
                            } else {
                                str2 = "ۣ۠ۡ";
                            }
                            m85 = C0007.m13(str2);
                        case 1749672:
                            if (i12 < ((Integer) C0076.n(35419, str, new Object[0])).intValue()) {
                                i6 = ((Integer) C0064.n(16133, str, new Object[]{(String) C0072.n(10418, null, new Object[]{(String) C0072.n(84470, null, new Object[]{(short[]) C0070.n(68248), 1174, 2, 2247})}), Integer.valueOf(i12)})).intValue();
                                if (C0054.f49 % (C0007.f6 * 3447) <= 0) {
                                    C0034.m92();
                                }
                                m85 = C0043.m119("ۤۧۦ");
                            } else if (C0023.f16 < 0) {
                                C0033.f24 = 61;
                                m85 = C0024.m58("ۧۢ۠");
                            } else {
                                m85 = (C0048.f44 % C0019.f13) + 1753295;
                            }
                        case 1749703:
                            if (C0053.f48 > 0) {
                                m85 = C0050.m150("ۥۦۢ");
                            } else {
                                str4 = "ۥۨۡ";
                                m85 = C0041.m112(str4);
                            }
                        case 1749731:
                            if (C0024.f17 >= 0) {
                                C0053.m158();
                                m85 = C0026.m63("ۣۡ۠");
                                i9 = i13;
                            } else {
                                m85 = (C0025.f18 * C0029.f22) + 1677091;
                                i9 = i13;
                            }
                        case 1750687:
                            if (C0056.f51 + (C0046.f43 / (-7428)) > 0) {
                                str4 = "ۢۨ۠";
                                m85 = C0041.m112(str4);
                            } else {
                                str2 = "ۥۦۣ";
                                m85 = C0007.m13(str2);
                            }
                        case 1751709:
                            str4 = "۠ۧ۠";
                            m85 = C0041.m112(str4);
                        case 1751747:
                            if (i6 == i12) {
                                i7 = i6 + 1;
                                m85 = C0034.m92() >= 0 ? C0045.m133("ۣۧۡ") : C0000.m1("ۥۡ۟");
                            } else if (C0053.f48 > 0) {
                            }
                            break;
                        case 1752458:
                            m85 = C0053.f48 * (C0024.f17 | 9598) >= 0 ? C0011.m28("ۣۢۨ") : (C0028.f21 - C0008.f7) + 1753717;
                        case 1752490:
                            if (C0023.f16 < 0) {
                            }
                            break;
                        case 1752515:
                            if (((Character) C0078.n(30628, str, new Object[]{Integer.valueOf(i7)})).charValue() == 'u') {
                                i3 = i6 + 2;
                                if (C0000.f0 % (C0025.f18 % 3496) >= 0) {
                                    C0019.f13 = 89;
                                    i4 = i10;
                                    m85 = C0026.m63("ۨۢۢ");
                                    i8 = i3;
                                    i10 = i4;
                                } else {
                                    i8 = i3;
                                    m85 = (C0025.f18 - C0050.f46) ^ 1746493;
                                }
                            } else if (C0056.f51 + (C0046.f43 / (-7428)) > 0) {
                            }
                            break;
                        case 1752548:
                            stringBuffer = stringBuffer2;
                            i12 = i5;
                            str6 = "ۢۢۨ";
                            stringBuffer2 = stringBuffer;
                            m85 = C0024.m58(str6);
                        case 1752674:
                            int i15 = i6 + 3;
                            if (C0029.m78() <= 0) {
                                m85 = C0030.m80("ۦ۠۟");
                                i9 = i15;
                            } else {
                                m85 = (C0008.f7 | C0056.f51) + 1747681;
                                i9 = i15;
                            }
                        case 1752734:
                            if (i6 == -1) {
                                StringBuffer stringBuffer4 = (StringBuffer) C0064.n(41397, stringBuffer2, new Object[]{(String) C0066.n(23388, str, new Object[]{Integer.valueOf(i12)})});
                                if (C0029.f22 <= 0) {
                                    str3 = "ۥۢۡ";
                                    i2 = i11;
                                    m85 = C0008.m14(str3);
                                    i11 = i2;
                                } else {
                                    m85 = C0021.m49("ۨۥۡ");
                                }
                            } else {
                                str4 = "۠ۧ۠";
                                m85 = C0041.m112(str4);
                            }
                        case 1753445:
                            return (String) C0064.n(33877, stringBuffer2, new Object[0]);
                        case 1755374:
                            if (C0009.m18() <= 0) {
                                m85 = C0049.m147("۟ۧۡ");
                                i11 = i6;
                            } else {
                                str3 = "۟۟ۡ";
                                i2 = i6;
                                m85 = C0008.m14(str3);
                                i11 = i2;
                            }
                        case 1755432:
                            StringBuffer stringBuffer5 = (StringBuffer) C0064.n(58555, stringBuffer2, new Object[]{Character.valueOf((char) ((Integer) C0068.n(43330, null, new Object[]{(String) C0066.n(92679, str, new Object[]{Integer.valueOf(i10), Integer.valueOf(i9)}), 16})).intValue())});
                            if (C0015.f12 + (C0009.f8 | (-670)) <= 0) {
                                m85 = C0021.m49("ۥۦۣ");
                                i11 = i9;
                            } else {
                                m85 = (C0023.f16 + C0020.f14) ^ (-57265);
                                i11 = i9;
                            }
                        case 1755524:
                            i12 = ((Integer) C0076.n(35419, str, new Object[0])).intValue();
                            if (C0020.f14 * (C0020.f14 / (-1384)) != 0) {
                                C0042.m114();
                                m85 = C0011.m28("ۡۦۣ");
                            } else {
                                m85 = (C0048.f44 | C0056.f51) + 1752211;
                            }
                    }
                }
            }

            public static String q(String str, String str2) {
                try {
                    HashMap<String, String> r = r(str);
                    r.putAll(r(str2.split(";")[0]));
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, String> entry : r.entrySet()) {
                        if (sb.length() > 0) {
                            sb.append(";");
                        }
                        sb.append(entry.getKey());
                        sb.append("=");
                        sb.append(entry.getValue());
                    }
                    return sb.toString();
                } catch (Exception unused) {
                    return "";
                }
            }

            private static HashMap<String, String> r(String str) {
                String[] split;
                HashMap<String, String> hashMap = new HashMap<>();
                for (String str2 : str.split(";")) {
                    int indexOf = str2.indexOf(61);
                    if (indexOf != -1) {
                        hashMap.put(str2.substring(0, indexOf).trim(), str2.substring(indexOf + 1).trim());
                    }
                }
                return hashMap;
            }
        }

    }

    static class k {

        /* renamed from: com.github.catvod.spider.merge.k.b  reason: case insensitive filesystem */
        public final static class C0619b {
            private OkHttpClient a;

            /* JADX INFO: Access modifiers changed from: private */
            /* renamed from: com.github.catvod.spider.merge.k.b$a */
            /* loaded from: classes.dex */
            static class a {
                static volatile C0619b a = new C0619b();
            }

            public static String a(String str, Map<String, String> map) {
                String str2 = a().newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(new Request.Builder().url(str).headers(Headers.of(map)).build()).execute().headers().get("Location");
                if (str2 == null) {
                    return null;
                }
                return str2;
            }

            public static OkHttpClient a() {
                if (a.a.a != null) {
                    return a.a.a;
                }
                C0619b c0619b = a.a;
                OkHttpClient.Builder dns = new OkHttpClient.Builder().addInterceptor(new C0622e()).dns(Spider.safeDns());
                TimeUnit timeUnit = TimeUnit.SECONDS;
                OkHttpClient build = dns.connectTimeout(30L, timeUnit).readTimeout(30L, timeUnit).writeTimeout(30L, timeUnit).hostnameVerifier(new HostnameVerifier() { // from static class: com.github.catvod.spider.merge.k.a
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
            public static String b(String str, Map<String, String> map) {
                Map multimap = e().newCall(new Request.Builder().url(str).headers(Headers.of(map)).build()).execute().headers().toMultimap();
                if (multimap != null) {
                    String str2 = multimap.containsKey("location") ? "location" : "Location";
                    return (String) ((List) multimap.get(str2)).get(0);
                }
                return null;
            }

            public static Response c(String str) {
                return a().newCall(new Request.Builder().url(str).build()).execute();
            }

            public static Response d(String str, Map<String, String> map) {
                return a().newCall(new Request.Builder().url(str).headers(Headers.of(map)).build()).execute();
            }

            public static OkHttpClient e() {
                return a().newBuilder().followRedirects(false).followSslRedirects(false).build();
            }

            public static C0621d f(String str, String str2, Map<String, String> map) {
                return new C0620c(str, str2, map).a(a());
            }

            public static C0621d g(String str, Map<String, String> map, Map<String, String> map2) {
                return new C0620c(com.github.catvod.spider.merge.AB.m.c.b, str, map, map2).a(a());
            }

            public static String h(String str, String str2) {
                return f(str, str2, null).a();
            }

            public static String i(Map map) {
                return new C0620c(com.github.catvod.spider.merge.AB.m.c.b, "https://passport.aliyundrive.com/newlogin/qrcode/query.do?appName=aliyun_drive&fromSite=52&_bx-v=2.2.3", map, (Map<String, String>) null).a(a()).a();
            }

            public static C0621d j(OkHttpClient okHttpClient, String str, Map map, Map map2, Map map3) {
                C0620c c0620c = new C0620c(str, map, map2, map3);
                c0620c.b();
                return c0620c.a(okHttpClient);
            }

            public static String k(String str) {
                return l(str, null);
            }

            public static String l(String str, Map<String, String> map) {
                return str.startsWith("http") ? new C0620c(com.github.catvod.spider.merge.AB.m.c.c, str, (Map<String, String>) null, map).a(a()).a() : "";
            }
        }

    }

    static class a {

        /* renamed from: com.github.catvod.spider.merge.a.c  reason: case insensitive filesystem */
        public final /* synthetic */ static class C0575c {
            public static String a(String str, String str2) {
                return str + str2;
            }
        }

        /* renamed from: com.github.catvod.spider.merge.a.ۣۧۢۡ  reason: contains not printable characters */
        static class C0033 {

            /* renamed from: ۢۤ۟ۦ  reason: not valid java name and contains not printable characters */
            public static int f24 = 785;

            /* renamed from: ۟ۡۢۥۤ  reason: not valid java name and contains not printable characters */
            public static String m84(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۣ۟ۢۡ۠  reason: not valid java name and contains not printable characters */
            public static int m85(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۟ۥۣۦ۠  reason: not valid java name and contains not printable characters */
            public static int m86() {
                return 138 ^ C0030.f23;
            }

            /* renamed from: ۧۤۧۥ  reason: not valid java name and contains not printable characters */
            public static String m87(String str) {
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

    static class b {

        /* renamed from: com.github.catvod.spider.merge.b.h  reason: case insensitive filesystem */
        public final /* synthetic */ static class C0588h {
            public static String a(String str, C0596c c0596c, int i, int i2, int i3, ArrayList arrayList) {
                c0596c.i(Integer.valueOf(str).intValue(), i, i2, i3);
                c0596c.w(arrayList);
                return c0596c.toString();
            }

            public static String a(String str, String str2, String str3, String str4, String str5) {
                return str + str2 + str3 + str4 + str5;
            }

            public static String b(StringBuilder sb, String str, String str2) {
                sb.append(str);
                sb.append(str2);
                return sb.toString();
            }

            public static HashMap c(String str, String str2, String str3, String str4) {
                HashMap hashMap = new HashMap();
                hashMap.put(str, str2);
                hashMap.put(str3, str4);
                return hashMap;
            }
        }

        /* renamed from: com.github.catvod.spider.merge.b.۟ۥۧ۟۟  reason: contains not printable characters */
        static class C0034 {

            /* renamed from: ۟ۦۣۢۢ  reason: not valid java name and contains not printable characters */
            public static int f33 = -433;

            /* renamed from: ۟ۤۤۤ۟  reason: not valid java name and contains not printable characters */
            public static String m91(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۠ۦۧۡ  reason: not valid java name and contains not printable characters */
            public static int m92() {
                return 560 ^ C0023.f16;
            }

            /* renamed from: ۡ۠ۢۦ  reason: not valid java name and contains not printable characters */
            public static int m93(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۦۥ۠ۤ  reason: contains not printable characters */
            public static String m94(String str) {
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

        /* renamed from: com.github.catvod.spider.merge.b.g  reason: case insensitive filesystem */
        public final /* synthetic */ static class RunnableC0587g implements Runnable {
            public final /* synthetic */ int a;
            public final /* synthetic */ Object b;

            public /* synthetic */ RunnableC0587g(Object obj, int i) {
                this.a = i;
                this.b = obj;
            }

            @Override // java.lang.Runnable
            public final void run() {
                switch (this.a) {
                    case 0:
                        C0589i.a((C0589i) this.b);
                        return;
                    case 1:
                        ((MainActivity) this.b).m();
                        return;
                    case 2:
                        Config.c((Config) this.b);
                        return;
                    default:
                        List<String> list = C0634I.a;
                        Toast.makeText(Init.context(), (String) this.b, 1).show();
                        return;
                }
            }
        }

    }

    static class C {

        /* renamed from: com.github.catvod.spider.merge.C.ۣ۟ۦۧ  reason: contains not printable characters */
        static class C0007 {

            /* renamed from: ۨ۟ۤ۟  reason: not valid java name and contains not printable characters */
            public static int f6 = -970;

            /* renamed from: ۟ۤۥۤۨ  reason: not valid java name and contains not printable characters */
            public static String m10(String str) {
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
            public static String m11(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۟ۧ۟ۧۧ  reason: not valid java name and contains not printable characters */
            public static int m12() {
                return 37 ^ C0011.f10;
            }

            /* renamed from: ۣ۠ۨۢ  reason: not valid java name and contains not printable characters */
            public static int m13(Object obj) {
                return obj.hashCode();
            }
        }

    }

    /* renamed from: com.github.catvod.spider.merge.۟ۢۧۦ  reason: contains not printable characters */
    static class C0055 {

        /* renamed from: ۟ۤۡ۠  reason: not valid java name and contains not printable characters */
        public static int f50 = -53;

        /* renamed from: ۟۟ۥۥۨ  reason: not valid java name and contains not printable characters */
        public static int m164(Object obj) {
            return obj.hashCode();
        }

        /* renamed from: ۣ۟ۢۦۥ  reason: not valid java name and contains not printable characters */
        public static String m165(String str) {
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
        public static String m166(short[] sArr, int i, int i2, int i3) {
            char[] cArr = new char[i2];
            for (int i4 = 0; i4 < i2; i4++) {
                cArr[i4] = (char) (sArr[i + i4] ^ i3);
            }
            return new String(cArr);
        }

        /* renamed from: ۦۦۧۡ  reason: contains not printable characters */
        public static int m167() {
            return (-160) ^ C0050.f46;
        }
    }

    static class D {

        /* renamed from: com.github.catvod.spider.merge.D.۟۠ۦ۟ۥ  reason: contains not printable characters */
        static class C0008 {

            /* renamed from: ۣ۟ۧۧ  reason: not valid java name and contains not printable characters */
            public static int f7 = -897;

            /* renamed from: ۣ۟ۢۤ۠  reason: not valid java name and contains not printable characters */
            public static int m14(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۣ۟ۨۢ۠  reason: not valid java name and contains not printable characters */
            public static String m15(String str) {
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

            /* renamed from: ۣۧ۟  reason: not valid java name and contains not printable characters */
            public static int m16() {
                return (-427) ^ C0028.f21;
            }

            /* renamed from: ۧۥۥۨ  reason: not valid java name and contains not printable characters */
            public static String m17(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }
        }

        /* renamed from: com.github.catvod.spider.merge.D.۟ۡۥۡۥ  reason: contains not printable characters */
        static class C0009 {

            /* renamed from: ۣ۟ۢۡ۠  reason: not valid java name and contains not printable characters */
            public static int f8 = 603;

            /* renamed from: ۟۠ۥۧۡ  reason: not valid java name and contains not printable characters */
            public static int m18() {
                return (-371) ^ C0020.f14;
            }

            /* renamed from: ۟ۦۦ۟۟  reason: not valid java name and contains not printable characters */
            public static String m19(String str) {
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
            public static String m20(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۣۤ۠ۨ  reason: not valid java name and contains not printable characters */
            public static int m21(Object obj) {
                return obj.hashCode();
            }
        }

    }

    static class E {

        /* renamed from: com.github.catvod.spider.merge.E.ۥۨۧۧ  reason: contains not printable characters */
        static class C0010 {

            /* renamed from: ۣۢۡۧ  reason: not valid java name and contains not printable characters */
            public static int f9 = -158;

            /* renamed from: ۟ۧۤۦۢ  reason: not valid java name and contains not printable characters */
            public static int m22(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۟ۨ۠ۤ  reason: not valid java name and contains not printable characters */
            public static String m23(String str) {
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
            public static int m24() {
                return 146 ^ C0051.f47;
            }

            /* renamed from: ۨۨۥۢ  reason: not valid java name and contains not printable characters */
            public static String m25(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }
        }

    }

    static class G {

        /* renamed from: com.github.catvod.spider.merge.G.۟۠ۡۦۡ  reason: contains not printable characters */
        static class C0011 {

            /* renamed from: ۣۧۨۨ  reason: not valid java name and contains not printable characters */
            public static int f10 = -834;

            /* renamed from: ۟ۦۣۧۢ  reason: not valid java name and contains not printable characters */
            public static String m27(String str) {
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

            /* renamed from: ۡۦۥۤ  reason: not valid java name and contains not printable characters */
            public static int m28(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۢۡۤۤ  reason: not valid java name and contains not printable characters */
            public static int m29() {
                return 702 ^ C0033.f24;
            }

            /* renamed from: ۣ۟ۧۨ  reason: not valid java name and contains not printable characters */
            public static String m30(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }
        }

        /* renamed from: com.github.catvod.spider.merge.G.ۥۧۡۢ  reason: contains not printable characters */
        static class C0012 {

            /* renamed from: ۟ۦ۟ۢۥ  reason: not valid java name and contains not printable characters */
            public static int f11 = 381;

            /* renamed from: ۣ۟۟ۡۦ  reason: not valid java name and contains not printable characters */
            public static int m31(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۥۦۡۦ  reason: contains not printable characters */
            public static int m32() {
                return 888 ^ C0033.f24;
            }

            /* renamed from: ۦ۟ۥ۠  reason: contains not printable characters */
            public static String m33(String str) {
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
            public static String m34(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }
        }

    }

    static class H {

        /* renamed from: com.github.catvod.spider.merge.H.۟ۧۥ۠ۡ  reason: contains not printable characters */
        static class C0015 {

            /* renamed from: ۟ۥۤۧۤ  reason: not valid java name and contains not printable characters */
            public static int f12 = 846;

            /* renamed from: ۡۢۡۡ  reason: not valid java name and contains not printable characters */
            public static int m35(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۤۦۦ  reason: not valid java name and contains not printable characters */
            public static String m36(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۤۦۦۦ  reason: not valid java name and contains not printable characters */
            public static String m37(String str) {
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
            public static int m38() {
                return 464 ^ C0030.f23;
            }
        }

    }

    static class I {

        /* renamed from: com.github.catvod.spider.merge.I.ۦۡ۟  reason: contains not printable characters */
        static class C0019 {

            /* renamed from: ۣ۟ۥ۟ۥ  reason: not valid java name and contains not printable characters */
            public static int f13 = 827;

            /* renamed from: ۣ۟ۡۢۢ  reason: not valid java name and contains not printable characters */
            public static String m39(String str) {
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
            public static int m40(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۨ۟۟ۢ  reason: not valid java name and contains not printable characters */
            public static String m41(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۨۥۣۡ  reason: not valid java name and contains not printable characters */
            public static int m42() {
                return (-182) ^ C0009.f8;
            }
        }

    }

    static class J {

        /* renamed from: com.github.catvod.spider.merge.J.۟ۡۥۥ۠  reason: contains not printable characters */
        static class C0020 {

            /* renamed from: ۢۢۨۥ  reason: not valid java name and contains not printable characters */
            public static int f14 = -507;

            /* renamed from: ۣ۟ۢۧۥ  reason: not valid java name and contains not printable characters */
            public static int m43() {
                return (-399) ^ C0030.f23;
            }

            /* renamed from: ۣ۟ۧۡۢ  reason: not valid java name and contains not printable characters */
            public static int m44(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۢۦۣۡ  reason: not valid java name and contains not printable characters */
            public static String m45(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۦۦۡۦ  reason: contains not printable characters */
            public static String m46(String str) {
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

        /* renamed from: com.github.catvod.spider.merge.J.ۦۡۡۥ  reason: contains not printable characters */
        static class C0021 {

            /* renamed from: ۟ۡ۟ۦۧ  reason: not valid java name and contains not printable characters */
            public static int f15 = -778;

            /* renamed from: ۟ۦۥۧ  reason: not valid java name and contains not printable characters */
            public static int m47() {
                return 672 ^ C0023.f16;
            }

            /* renamed from: ۟ۦۨ۟ۦ  reason: not valid java name and contains not printable characters */
            public static String m48(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۢۤۨ۟  reason: not valid java name and contains not printable characters */
            public static int m49(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۣ۠ۥ۟  reason: not valid java name and contains not printable characters */
            public static String m50(String str) {
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

    static class M {

        /* renamed from: com.github.catvod.spider.merge.M.۟۟ۤۧ۠  reason: contains not printable characters */
        static class C0023 {

            /* renamed from: ۣۡۤۡ  reason: not valid java name and contains not printable characters */
            public static int f16 = -445;

            /* renamed from: ۟ۥۥۥ  reason: not valid java name and contains not printable characters */
            public static String m51(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۣ۟ۧۢۦ  reason: not valid java name and contains not printable characters */
            public static int m52() {
                return 408 ^ C0041.f37;
            }

            /* renamed from: ۡۢۤۤ  reason: not valid java name and contains not printable characters */
            public static String m53(String str) {
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
            public static int m54(Object obj) {
                return obj.hashCode();
            }
        }

    }

    static class O {

        /* renamed from: com.github.catvod.spider.merge.O.۟ۤۥۡ  reason: contains not printable characters */
        static class C0024 {

            /* renamed from: ۟ۥۣۧۦ  reason: not valid java name and contains not printable characters */
            public static int f17 = -964;

            /* renamed from: ۟ۢۢۥ  reason: not valid java name and contains not printable characters */
            public static String m55(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۣ۟ۤ۟۟  reason: not valid java name and contains not printable characters */
            public static String m56(String str) {
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

            /* renamed from: ۟ۨ۠ۨ  reason: not valid java name and contains not printable characters */
            public static int m57() {
                return (-573) ^ C0055.f50;
            }

            /* renamed from: ۤۢۨۨ  reason: not valid java name and contains not printable characters */
            public static int m58(Object obj) {
                return obj.hashCode();
            }
        }

        /* renamed from: com.github.catvod.spider.merge.O.۟ۦۨ۟ۢ  reason: contains not printable characters */
        static class C0025 {

            /* renamed from: ۟ۥۣۡۧ  reason: not valid java name and contains not printable characters */
            public static int f18 = 128;

            /* renamed from: ۡۤۦۣ  reason: not valid java name and contains not printable characters */
            public static int m59() {
                return 614 ^ C0015.f12;
            }

            /* renamed from: ۣۧۤۡ  reason: not valid java name and contains not printable characters */
            public static int m60(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۨ۟۟ۡ  reason: not valid java name and contains not printable characters */
            public static String m61(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۣۨ۠ۡ  reason: not valid java name and contains not printable characters */
            public static String m62(String str) {
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
        static class C0026 {

            /* renamed from: ۟ۤۦۥ  reason: not valid java name and contains not printable characters */
            public static int f19 = 882;

            /* renamed from: ۟۠ۤۦۢ  reason: not valid java name and contains not printable characters */
            public static int m63(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۟ۤۥۥۦ  reason: not valid java name and contains not printable characters */
            public static String m64(String str) {
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
            public static String m65(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۨۦ  reason: not valid java name and contains not printable characters */
            public static int m66() {
                return (-516) ^ C0049.f45;
            }
        }

    }

    static class Q {

        /* renamed from: com.github.catvod.spider.merge.Q.۟ۢۤ۟  reason: contains not printable characters */
        static class C0027 {

            /* renamed from: ۟ۧ۟۟۟  reason: not valid java name and contains not printable characters */
            public static int f20 = -903;

            /* renamed from: ۟ۧۦۤ۠  reason: not valid java name and contains not printable characters */
            public static int m67(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۟ۨ۠ۢ  reason: not valid java name and contains not printable characters */
            public static int m68() {
                return 768 ^ C0051.f47;
            }

            /* renamed from: ۦ۟ۧۨ  reason: contains not printable characters */
            public static String m69(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۦ۟ۨۥ  reason: contains not printable characters */
            public static String m70(String str) {
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

        /* renamed from: com.github.catvod.spider.merge.Q.ۦۨۥۤ  reason: contains not printable characters */
        static class C0028 {

            /* renamed from: ۣ۠ۤۡ  reason: not valid java name and contains not printable characters */
            public static int f21 = 725;

            /* renamed from: ۣ۟۟ۦۨ  reason: not valid java name and contains not printable characters */
            public static int m71(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۟ۤۥۧۡ  reason: not valid java name and contains not printable characters */
            public static String m72(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۟ۥۧۤۢ  reason: not valid java name and contains not printable characters */
            public static String m73(String str) {
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

            /* renamed from: ۤۢ۠ۤ  reason: not valid java name and contains not printable characters */
            public static int m74() {
                return (-72) ^ C0041.f37;
            }
        }

    }

    static class T {

        /* renamed from: com.github.catvod.spider.merge.T.ۣۣۥۢ  reason: contains not printable characters */
        static class C0029 {

            /* renamed from: ۤۥۣۣ  reason: not valid java name and contains not printable characters */
            public static int f22 = 559;

            /* renamed from: ۣ۟۟ۤ۟  reason: not valid java name and contains not printable characters */
            public static String m75(String str) {
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
            public static String m76(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۠ۡۡۧ  reason: not valid java name and contains not printable characters */
            public static int m77(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۦۨ۟  reason: contains not printable characters */
            public static int m78() {
                return (-916) ^ C0049.f45;
            }
        }

    }

    static class U {

        /* renamed from: com.github.catvod.spider.merge.U.۟ۢۦۥۧ  reason: contains not printable characters */
        static class C0030 {

            /* renamed from: ۟ۥ۠ۢۡ  reason: not valid java name and contains not printable characters */
            public static int f23 = -588;

            /* renamed from: ۟ۢۦۨۥ  reason: not valid java name and contains not printable characters */
            public static String m79(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۟ۥۦۤ۟  reason: not valid java name and contains not printable characters */
            public static int m80(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۣۢۡۤ  reason: not valid java name and contains not printable characters */
            public static String m81(String str) {
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
            public static int m82() {
                return (-30) ^ f23;
            }
        }

    }

    static class f {

        /* renamed from: com.github.catvod.spider.merge.f.ۨۥ۟۠  reason: contains not printable characters */
        static class C0037 {

            /* renamed from: ۟۠ۧ۠ۧ  reason: not valid java name and contains not printable characters */
            public static int f34 = -121;

            /* renamed from: ۟۠ۦۦۣ  reason: not valid java name and contains not printable characters */
            public static String m98(String str) {
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
            public static int m99(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۦۤۦۡ  reason: contains not printable characters */
            public static String m100(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۦۨ۟ۦ  reason: contains not printable characters */
            public static int m101() {
                return (-386) ^ C0043.f39;
            }
        }

    }

    static class h {

        /* renamed from: com.github.catvod.spider.merge.h.۟ۦ۠۠ۤ  reason: contains not printable characters */
        static class C0038 {

            /* renamed from: ۦۤۤۦ  reason: contains not printable characters */
            public static int f35 = 711;

            /* renamed from: ۣ۟ۢ۟۟  reason: not valid java name and contains not printable characters */
            public static int m102() {
                return 1021 ^ C0020.f14;
            }

            /* renamed from: ۣ۟ۢ  reason: not valid java name and contains not printable characters */
            public static int m103(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۣۣ۟ۤۡ  reason: not valid java name and contains not printable characters */
            public static String m104(String str) {
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

            /* renamed from: ۣ۟ۥ۠ۦ  reason: not valid java name and contains not printable characters */
            public static String m105(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }
        }

        /* renamed from: com.github.catvod.spider.merge.h.۟ۦۣۧ۟  reason: contains not printable characters */
        static class C0039 {

            /* renamed from: ۥ۠ۦۦ  reason: contains not printable characters */
            public static int f36 = 842;

            /* renamed from: ۟ۢۦۦ  reason: not valid java name and contains not printable characters */
            public static int m106(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۣ۟۠ۥ۟  reason: not valid java name and contains not printable characters */
            public static String m107(String str) {
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
            public static String m108(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۣ۠ۧۢ  reason: not valid java name and contains not printable characters */
            public static int m109() {
                return (-659) ^ C0044.f41;
            }
        }

    }

    static class i {

        /* renamed from: com.github.catvod.spider.merge.i.ۣۣ۟ۤ  reason: contains not printable characters */
        static class C0041 {

            /* renamed from: ۟ۢۤۢ  reason: not valid java name and contains not printable characters */
            public static int f37 = -403;

            /* renamed from: ۟ۡۤ۟ۧ  reason: not valid java name and contains not printable characters */
            public static String m110(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۟ۡۨ۠ۢ  reason: not valid java name and contains not printable characters */
            public static int m111() {
                return 470 ^ C0038.f35;
            }

            /* renamed from: ۣ۟ۤۨۦ  reason: not valid java name and contains not printable characters */
            public static int m112(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۣۨ۠  reason: not valid java name and contains not printable characters */
            public static String m113(String str) {
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

    static class j {

        /* renamed from: com.github.catvod.spider.merge.j.ۣ۟۟ۢۢ  reason: contains not printable characters */
        static class C0042 {

            /* renamed from: ۦۥۤۧ  reason: contains not printable characters */
            public static int f38 = -376;

            /* renamed from: ۟ۢۢۧۦ  reason: not valid java name and contains not printable characters */
            public static int m114() {
                return (-717) ^ C0044.f41;
            }

            /* renamed from: ۟ۤۤۧ  reason: not valid java name and contains not printable characters */
            public static int m115(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۢۧۥ۠  reason: not valid java name and contains not printable characters */
            public static String m116(String str) {
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
            public static String m117(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }
        }

    }

    static class l {

        /* renamed from: com.github.catvod.spider.merge.l.۟ۡۦۧۨ  reason: contains not printable characters */
        static class C0043 {

            /* renamed from: ۟ۤۨۥ  reason: not valid java name and contains not printable characters */
            public static int f39 = 528;

            /* renamed from: ۣ۟ۥۦۡ  reason: not valid java name and contains not printable characters */
            public static String m118(String str) {
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
            public static int m119(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۤۨ۠ۧ  reason: not valid java name and contains not printable characters */
            public static String m120(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۥۣۢ۟  reason: contains not printable characters */
            public static int m121() {
                return 7 ^ C0023.f16;
            }
        }

    }

    static class q {

        /* renamed from: com.github.catvod.spider.merge.q.۟ۥۥۢ۠  reason: contains not printable characters */
        static class C0044 {

            /* renamed from: ۣۣ۟ۢۧ  reason: not valid java name and contains not printable characters */
            public static int f41 = -566;

            /* renamed from: ۡۢ۠ۤ  reason: not valid java name and contains not printable characters */
            public static String m128(String str) {
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

            /* renamed from: ۥۣ۟  reason: contains not printable characters */
            public static int m129(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۥۨۨ  reason: contains not printable characters */
            public static String m130(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۧ۠ۦۡ  reason: not valid java name and contains not printable characters */
            public static int m131() {
                return (-968) ^ C0030.f23;
            }
        }

        /* renamed from: com.github.catvod.spider.merge.q.ۥۧۦ۠  reason: contains not printable characters */
        static class C0045 {

            /* renamed from: ۣۧۢ۠  reason: not valid java name and contains not printable characters */
            public static int f42 = 116;

            /* renamed from: ۣ۟ۡۡ۠  reason: not valid java name and contains not printable characters */
            public static int m132() {
                return (-110) ^ C0023.f16;
            }

            /* renamed from: ۟ۦۦ۟ۤ  reason: not valid java name and contains not printable characters */
            public static int m133(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۦۦۡ  reason: contains not printable characters */
            public static String m134(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۧۢ۠ۥ  reason: not valid java name and contains not printable characters */
            public static String m135(String str) {
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

    static class t {

        /* renamed from: com.github.catvod.spider.merge.t.ۣ۟ۤۤۤ  reason: contains not printable characters */
        static class C0046 {

            /* renamed from: ۤ۟ۥۤ  reason: not valid java name and contains not printable characters */
            public static int f43 = 691;

            /* renamed from: ۣ۟ۨۦۨ  reason: not valid java name and contains not printable characters */
            public static int m136(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۣ۟ۧۡۤ  reason: not valid java name and contains not printable characters */
            public static String m137(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۣۢۦۣ  reason: not valid java name and contains not printable characters */
            public static String m138(String str) {
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
            public static int m139() {
                return 770 ^ C0023.f16;
            }
        }

    }

    static class u {

        /* renamed from: com.github.catvod.spider.merge.u.ۣۣۣ۟ۧ  reason: contains not printable characters */
        static class C0048 {

            /* renamed from: ۣ۟ۤۨۨ  reason: not valid java name and contains not printable characters */
            public static int f44 = 150;

            /* renamed from: ۟ۧۡۤ۟  reason: not valid java name and contains not printable characters */
            public static String m140(String str) {
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
            public static int m141(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۣۢ۟ۤ  reason: not valid java name and contains not printable characters */
            public static String m142(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۧۧ۟  reason: not valid java name and contains not printable characters */
            public static int m143() {
                return (-236) ^ C0028.f21;
            }
        }

    }

    static class w {

        /* renamed from: com.github.catvod.spider.merge.w.۟ۢۥۤۢ  reason: contains not printable characters */
        static class C0049 {

            /* renamed from: ۥۣۦ۟  reason: contains not printable characters */
            public static int f45 = -431;

            /* renamed from: ۟۠ۡۧ۠  reason: not valid java name and contains not printable characters */
            public static String m144(String str) {
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
            public static String m145(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۣ۟ۡۡۦ  reason: not valid java name and contains not printable characters */
            public static int m146() {
                return (-590) ^ C0015.f12;
            }

            /* renamed from: ۣۡۡۢ  reason: not valid java name and contains not printable characters */
            public static int m147(Object obj) {
                return obj.hashCode();
            }
        }

    }

    static class x {

        /* renamed from: com.github.catvod.spider.merge.x.ۤۤ۟ۨ  reason: contains not printable characters */
        static class C0050 {

            /* renamed from: ۟ۡۢۡ۠  reason: not valid java name and contains not printable characters */
            public static int f46 = -324;

            /* renamed from: ۟۟۠ۢۧ  reason: not valid java name and contains not printable characters */
            public static int m148() {
                return 592 ^ C0010.f9;
            }

            /* renamed from: ۟ۧۦۦۧ  reason: not valid java name and contains not printable characters */
            public static String m149(String str) {
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

            /* renamed from: ۠ۧۥۣ  reason: not valid java name and contains not printable characters */
            public static int m150(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۣۣۤۨ  reason: not valid java name and contains not printable characters */
            public static String m151(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }
        }

        /* renamed from: com.github.catvod.spider.merge.x.ۥۨۨۤ  reason: contains not printable characters */
        static class C0051 {

            /* renamed from: ۣۣ۟ۧۧ  reason: not valid java name and contains not printable characters */
            public static int f47 = 724;

            /* renamed from: ۟۟ۢۤ  reason: not valid java name and contains not printable characters */
            public static int m152(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۟ۤ۠ۨ  reason: not valid java name and contains not printable characters */
            public static String m153(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۟ۤۦۤۨ  reason: not valid java name and contains not printable characters */
            public static int m154() {
                return 347 ^ C0038.f35;
            }

            /* renamed from: ۟ۦۦۣۨ  reason: not valid java name and contains not printable characters */
            public static String m155(String str) {
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

    static class z {

        /* renamed from: com.github.catvod.spider.merge.z.۟ۦۢۢۦ  reason: contains not printable characters */
        static class C0053 {

            /* renamed from: ۟۟ۥۢۤ  reason: not valid java name and contains not printable characters */
            public static int f48 = 611;

            /* renamed from: ۣ۟ۨۡۤ  reason: not valid java name and contains not printable characters */
            public static String m156(String str) {
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

            /* renamed from: ۟ۤۤۧۦ  reason: not valid java name and contains not printable characters */
            public static int m157(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۣۢ۠ۤ  reason: not valid java name and contains not printable characters */
            public static int m158() {
                return (-942) ^ C0042.f38;
            }

            /* renamed from: ۦۢۨ  reason: contains not printable characters */
            public static String m159(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }
        }

        /* renamed from: com.github.catvod.spider.merge.z.ۨۧۢ۟  reason: contains not printable characters */
        static class C0054 {

            /* renamed from: ۣ۟ۧۥ  reason: not valid java name and contains not printable characters */
            public static int f49 = 627;

            /* renamed from: ۣۣ۟ۧ۟  reason: not valid java name and contains not printable characters */
            public static int m160() {
                return 538 ^ C0020.f14;
            }

            /* renamed from: ۣۤۥۧ  reason: not valid java name and contains not printable characters */
            public static int m161(Object obj) {
                return obj.hashCode();
            }

            /* renamed from: ۣۧ۠ۤ  reason: not valid java name and contains not printable characters */
            public static String m162(short[] sArr, int i, int i2, int i3) {
                char[] cArr = new char[i2];
                for (int i4 = 0; i4 < i2; i4++) {
                    cArr[i4] = (char) (sArr[i + i4] ^ i3);
                }
                return new String(cArr);
            }

            /* renamed from: ۦۦۦۧ  reason: contains not printable characters */
            public static String m163(String str) {
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
        public final static class C0596c {
            @SerializedName("static class")
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
            static class a extends TypeToken<LinkedHashMap<String, List<C0595b>>> {
                a() {
                }
            }

            public static String c(String str) {
                C0596c c0596c = new C0596c();
                c0596c.b = Collections.emptyList();
                c0596c.o = str;
                return c0596c.toString();
            }

            public static C0596c e() {
                return new C0596c();
            }

            public static String l(String str) {
                C0596c c0596c = new C0596c();
                c0596c.i = 0;
                c0596c.g = "";
                c0596c.o = str;
                c0596c.p = str;
                return c0596c.toString();
            }

            public static String m(C0598e c0598e) {
                C0596c c0596c = new C0596c();
                c0596c.b = Arrays.asList(c0598e);
                return c0596c.toString();
            }

            public static String m(Integer num, Integer num2, Integer num3, Integer num4, List list) {
                C0596c c0596c = new C0596c();
                c0596c.m96i(num.intValue(), num2.intValue(), num3.intValue(), num4.intValue());
                c0596c.b = list;
                return c0596c.toString();
            }

            public static String n(String str) {
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

            public static String n(List<C0598e> list) {
                C0596c c0596c = new C0596c();
                c0596c.b = list;
                return c0596c.toString();
            }

            public static String o(List<C0594a> list, LinkedHashMap<String, List<C0595b>> linkedHashMap) {
                C0596c c0596c = new C0596c();
                c0596c.a = list;
                c0596c.c = linkedHashMap;
                return c0596c.toString();
            }

            public static String p(List<C0594a> list, List<C0598e> list2) {
                C0596c c0596c = new C0596c();
                c0596c.a = list;
                c0596c.b = list2;
                return c0596c.toString();
            }

            public static String q(ArrayList arrayList, List list, LinkedHashMap linkedHashMap) {
                C0596c c0596c = new C0596c();
                c0596c.a = arrayList;
                c0596c.b = list;
                c0596c.c = linkedHashMap;
                return c0596c.toString();
            }

            public static String q(List<C0594a> list, List<C0598e> list2, LinkedHashMap<String, List<C0595b>> linkedHashMap) {
                C0596c c0596c = new C0596c();
                c0596c.a = list;
                c0596c.b = list2;
                c0596c.c = linkedHashMap;
                return c0596c.toString();
            }

            public static String r(List<C0594a> list, List<C0598e> list2, JSONObject jSONObject) {
                C0596c c0596c = new C0596c();
                c0596c.a = list;
                c0596c.b = list2;
                c0596c.d(jSONObject);
                return c0596c.toString();
            }

            public static String s(List<C0594a> list, JSONObject jSONObject) {
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

            /* renamed from: e  reason: collision with other method in static class */
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

            /* renamed from: i  reason: collision with other method in static class */
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
}
