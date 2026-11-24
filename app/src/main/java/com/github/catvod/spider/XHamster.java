package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XHamster extends Spider {

    private String siteUrl = "https://zh.xhit.live";
    private boolean unlocked = false;

    // 主分类（每组第一个）
    private static final String[] MAIN_CLASSES = {
            "亚洲人$asian",
            "家庭自制$homemade",
            "69式$69",
            "饥渴熟女$cougar",
            "跨人种$interracial",
            "大黑屌$bbc",
            "3P$threesome",
            "Sybian性爱机器$sybian",
            "丁字裤$thong",
            "公共场合裸体$public-nudity",
            "丛林$jungle"
    };

    // 每组剩余项作为筛选（key = 主分类 type_id）
    private static final Map<String, String[]> FILTER_MAP = new LinkedHashMap<>();

    static {
        // 人种（亚洲人 之后）
        FILTER_MAP.put("asian", new String[]{
                "18岁$18-year-old", "俄罗斯人$russian", "大奶子$big-tits", "内射$creampie",
                "素人$amateur", "卡通$cartoon", "人母$mom", "人妻$milf", "美国人$american", "成人动漫$hentai"
        });
        // 生产（家庭自制 之后）
        FILTER_MAP.put("homemade", new String[]{
                "3D$3d", "Show$show", "互动$interactive", "合集$compilation", "唯美$softcore",
                "复古$vintage", "字幕$caption", "幕后花絮$behind-the-scenes", "怀旧$retro",
                "搞笑$funny", "故事$story", "无剧情$gonzo", "无码$uncensored", "日本AV$jav",
                "特写$close-up", "直播$webcam", "第一人称视角$pov", "色情MV$pmv", "色情明星$pornstar", "色情艺术$erotica"
        });
        // 玩法（69式 之后）
        FILTER_MAP.put("69", new String[]{
                "肛交$anal", "下流话$dirty-talk", "乳交$titty-fucking", "乳射$cum-on-tits", "亲吻$kissing",
                "传教士体位$missionary", "刮毛$shaving", "前列腺按摩$prostate-massage", "前戏$foreplay",
                "剪刀腿$scissoring", "双洞齐插$double-penetration", "口交$blowjob", "口爆$cum-in-mouth",
                "后入式$doggy-style", "吞精$cum-swallowing", "呻吟$moaning", "女性自慰$female-masturbation",
                "女插男$pegging", "女牛仔$cowgirl", "射精$cumshot", "尖叫$screaming", "屁股对嘴巴$ass-to-mouth",
                "快乐结局$happy-ending", "手交$handjob", "拳交$fisting", "指交$fingering", "按摩$massage",
                "摩擦$humping", "极限插入$extreme-insertion", "毒龙$rimjob", "深喉$deep-throat", "潮吹$squirting",
                "瑜伽$yoga", "电臀$twerking", "短暂露出$flashing", "粗暴性爱$rough-sex", "粗暴深喉口交$face-fuck",
                "粗暴肛交$rough-anal", "精液交换$cumswap", "群P口交$blowbang", "群交颜射$bukkake",
                "肛交自慰$anal-masturbation", "肛门扩张$gaping", "脱衣表演$striptease", "舔肛$ass-licking",
                "舔逼$cunnilingus", "舔阴$eating-pussy", "足交$footjob", "足射$cum-on-feet", "边缘控制$edging",
                "野蛮性爱$brutal-sex", "隔衣摩擦$dry-humping", "颜射$facial", "骑乘$riding", "骑脸$facesitting",
                "高潮$orgasm", "人体家具$human-furniture", "人体彩绘$body-paint", "人体烟灰缸$human-ashtray",
                "双性人$futanari", "吐口水$spitting", "吞精鼓励$cei", "哺乳$lactating", "四肢捆绑$hogtied",
                "困境束缚$predicament-bondage", "大量吞精$gokkun", "女主调教$femdom", "女同调教$lezdom",
                "妇科检查恋物癖$gyno-fetish", "娘娘腔$sissy", "宠物扮演$pet-play", "小鸡巴羞辱$small-penis-humiliation",
                "小鸡巴鼓励$small-penis-encouragement", "思维控制$mind-control", "性交围观$dogging",
                "恋体毛癖$body-hair-fetish", "恋嘴癖$mouth-fetish", "恋手癖$hand-fetish", "恋物癖$fetish",
                "恋腹癖$belly-fetish", "恋足癖$foot-fetish", "悬吊束缚$suspension-bondage", "惩罚$punishment",
                "打屁股$spanking", "抹油$oiled", "抽打$whipping", "抽烟$smoking", "拉扯内裤$wedgie",
                "挠痒痒$tickling", "捆绑$bondage", "捆绑SM$bdsm", "摔跤$wrestling", "撒尿$pissing",
                "支配$domination", "放屁$farting", "日式捆绑$shibari", "束缚$tied-up", "气球$balloon",
                "淫荡$kinky", "淹没$smothering", "湿润而脏乱$wet-messy", "滴蜡$wax-play", "牛奶$milk",
                "电击刺激$estim", "睾丸虐待$ballbusting", "种族歧视扮演$raceplay", "美脚崇拜$foot-worship",
                "羞辱$humiliation", "胶带绑缚$tape-bondage", "脸部恋物癖$face-fetish", "腋窝$armpit",
                "诡异$weird", "贞操$chastity", "踩踏$trampling", "避孕套$condom", "阿嘿颜$ahegao",
                "顺从$submissive", "食物$food", "高潮控制$orgasm-control", "高跟鞋踩油门$pedal-pumping",
                "鸡巴睾丸摧残$cbt", "双性恋$bisexual", "女同$lesbian"
        });
        // 年龄
        FILTER_MAP.put("cougar", new String[]{
                "奶奶$granny", "宝贝$babe", "成熟$mature", "老少配$old-young", "老熟女$gilf",
                "老男人$old-man", "青年$teen"
        });
        // 种族
        FILTER_MAP.put("interracial", new String[]{
                "印度$desi", "姆赞西$mzansi", "拉丁美女$latina", "欧洲人$european", "犹太人$jewish",
                "阿拉伯人$arab", "非洲人$african", "黄种男和白人女$amwf", "黑人$black"
        });
        // 身体
        FILTER_MAP.put("bbc", new String[]{
                "下垂的乳房$saggy-tits", "乳头$nipples", "侏儒$midget", "假奶子$fake-tits", "可爱$cute",
                "坚挺的乳头$puffy-nipples", "大乳头$big-nipples", "大屁股$big-ass", "大屁股白人美女$pawg",
                "大白屌$bwc", "大阴蒂$big-clit", "大鸡巴$big-cock", "天然巨乳$big-natural-tits",
                "女巨人$giantess", "女性健身者$fbb", "奶子$tits", "娇小$petite", "完美身体$perfect-body",
                "小奶子$small-tits", "屁股$ass", "巨人$giant", "异国情调$exotic", "微胖$chubby",
                "怀孕$pregnant", "怪物巨屌$monster-cock", "日光浴妹子$tan-girl", "柔软$flexible",
                "毛发浓密$hairy", "紧致阴户$tight-pussy", "紧身裤$cameltoe", "纹身$tattoo", "美女$beauty",
                "美腿$legs", "肌肉发达的女人$muscular-woman", "胖美女$bbw", "被截肢者$amputee",
                "裸体$nude", "超级肥胖的白人女性$ssbbw", "身体穿孔$piercing", "阴户$pussy", "阴蒂$clit",
                "雌雄同体$hermaphrodite", "骨感$skinny"
        });
        // 人数
        FILTER_MAP.put("threesome", new String[]{
                "4P$foursome", "单人表演$solo", "多对一群交$gangbang", "情侣$couple",
                "群交$group-sex", "群交派对$orgy"
        });
        // 性玩具
        FILTER_MAP.put("sybian", new String[]{
                "假阳具$dildo", "双头假阳具$double-dildo", "堵嘴球$ball-gagged", "性爱机器$fucking-machine",
                "成人玩具$sex-toy", "振动棒$vibrator", "灌肠$enema", "穿戴式假阳具$strapon",
                "肛塞$butt-plug", "肛门拉珠$anal-beads", "蒙眼$blindfolded", "阴道抽插$pussy-pump", "震动棒$hitachi"
        });
        // 衣服
        FILTER_MAP.put("thong", new String[]{
                "乳胶服$latex", "内裤$panties", "制服$uniform", "学生制服$school-uniform", "尼龙袜$nylon",
                "性感内衣$lingerie", "手套$gloves", "打底裤$leggings", "比基尼$bikini", "渔网装$fishnet",
                "牛仔裤$jeans", "皮衣$leather", "眼镜$glasses", "短裙$skirt", "紧身衣$spandex",
                "胸罩$bra", "蒙面$masked", "袜子$socks", "连裤袜$pantyhose", "连身鱼网装$bodystocking",
                "长筒丝袜$stockings", "高跟鞋$high-heels"
        });
        // 幻想
        FILTER_MAP.put("public-nudity", new String[]{
                "ASMR$asmr", "Cook$cook", "万圣节$halloween", "三陪$escort", "上午$morning",
                "中世纪$medieval", "临时保姆$babysitter", "书呆子$nerd", "修女$nun", "偷窥癖$voyeur",
                "兔子$bunny", "公主$princess", "公共场合性爱$public-sex", "共享妻子$wife-sharing",
                "军队$military", "出轨$cheating", "勾引$seduce", "医生$doctor", "医疗$medical",
                "卡住$stuck", "双胞胎$twins", "反向多对一群交$reverse-gangbang", "可爱哥特妹$e-girl",
                "同人作品$parody", "吸血鬼$vampire", "哥特$gothic", "啦啦队长$cheerleader", "圣诞节$xmas",
                "处女$virgin", "外星人$alien", "女人打架$catfight", "女仆$maid", "女友$girlfriend",
                "女强人$baddie", "女服务员$waitress", "女游戏玩家$gamer-girl", "女衣男裸$cfnm",
                "奴隶$slave", "妻子$wife", "娃娃$doll", "婚礼$wedding", "学生$student",
                "家庭主妇$housewife", "寻欢洞$glory-hole", "小丑$clown", "幻想$fantasy",
                "性爱指挥$sex-instruction", "怪物$monster", "恐怖$horror", "情人节$valentines-day",
                "情妇$mistress", "情绪摇滚$emo", "惊喜$surprise", "打架$fighting", "护士$nurse",
                "换妻$wife-swap", "换妻癖者$swingers", "搭讪$pick-up", "撸管指挥$joi", "教师$teacher",
                "时间停止$time-stop", "明星$celebrity", "水管工$plumber", "派对$party", "浪漫$romantic",
                "淫娃$nympho", "游戏$game", "漫画$comic", "激情$passionate", "爸爸$daddy",
                "生日$birthday", "男女同校$coed", "男衣女裸$cmnf", "真心话大冒险$truth-or-dare",
                "禁忌$taboo", "秘书$secretary", "第一次$first-time", "经纪人$agent", "绿帽奴$cuckold",
                "老板$boss", "裙底风光$upskirt", "裸体主义$nudist", "角色扮演$cosplay", "角色扮演$role-play",
                "警察$police", "试镜$casting", "超级英雄$superhero", "跳舞$dance", "身体交换$body-swap",
                "运动$sport", "邻居$neighbor", "采访$interview", "重口味$hardcore", "陌生人$stranger",
                "面试$audition", "骚$naughty", "黑人聚居区$ghetto"
        });
        // 地点
        FILTER_MAP.put("jungle", new String[]{
                "健身$fitness", "健身房$gym", "公交车$bus", "农场$farm", "出租车$taxi", "办公室$office",
                "医院$hospital", "厕所$toilet", "厨房$kitchen", "大学$college", "户外$outdoor",
                "旅馆$hotel", "村庄$village", "桑拿$sauna", "水下$underwater", "汽车$car",
                "泳池$pool", "浴室$bathroom", "海滩$beach", "淋浴$shower", "火车$train", "监狱$prison"
        });
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        if (!TextUtils.isEmpty(extend)) {
            siteUrl = extend.startsWith("http") ? extend : "https://" + extend;
        }
        unlocked = PasswordGate.ensureUnlocked(context);
        if (!unlocked) {
            throw new Exception("密码错误或已取消，源未加载");
        }
        tryRefreshDomain();
    }

    private void tryRefreshDomain() {
        try {
            String json = OkHttp.string("https://xhamster.world/pwa/domains", getHeader());
            if (TextUtils.isEmpty(json)) return;
            JSONObject obj = new JSONObject(json);
            JSONArray arr = obj.optJSONArray("domains");
            if (arr == null) return;
            for (int i = 0; i < Math.min(arr.length(), 25); i++) {
                String d = arr.optString(i);
                if (TextUtils.isEmpty(d) || d.contains("onion")) continue;
                String test = "https://zh." + d;
                String html = OkHttp.string(test + "/categories/asian", getHeader());
                if (!TextUtils.isEmpty(html) && (html.contains("videoThumbProps") || html.contains("xhamster"))) {
                    siteUrl = test;
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private Map<String, String> getHeader() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        h.put("Referer", siteUrl + "/");
        return h;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        if (!unlocked) return Result.get().string();
        List<Class> classes = new ArrayList<>();
        for (String s : MAIN_CLASSES) {
            String[] sp = s.split("\\$");
            classes.add(new Class(sp[1], sp[0]));
        }
        // 构建筛选
        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        if (filter) {
            for (Map.Entry<String, String[]> entry : FILTER_MAP.entrySet()) {
                List<Filter> fl = new ArrayList<>();
                List<Filter.Value> values = new ArrayList<>();
                values.add(new Filter.Value("全部", ""));
                for (String item : entry.getValue()) {
                    String[] sp = item.split("\\$");
                    values.add(new Filter.Value(sp[0], sp[1]));
                }
                fl.add(new Filter("tag", "筛选", values));
                filters.put(entry.getKey(), fl);
            }
        }
        return Result.get().classes(classes).filters(filters).string();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        if (!unlocked) return Result.get().vod(new ArrayList<Vod>()).page(Integer.parseInt(pg), 0, 0, 0).string();
        int page = Integer.parseInt(pg);
        // 如果选了筛选，就用筛选的 type_id 作为分类
        String realTid = tid;
        if (extend != null && extend.containsKey("tag") && !TextUtils.isEmpty(extend.get("tag"))) {
            realTid = extend.get("tag");
        }
        String url = siteUrl + "/categories/" + realTid;
        if (page > 1) url += "/" + page;
        String html = OkHttp.string(url, getHeader());
        List<Vod> list = parseVideoList(html);
        int pageCount = list.isEmpty() ? page : page + 1;
        return Result.get().vod(list).page(page, pageCount, 24, Integer.MAX_VALUE).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (!unlocked) return Result.get().vod(new Vod()).string();
        String id = ids.get(0);
        String url = id.startsWith("http") ? id : siteUrl + "/videos/" + id;
        String html = OkHttp.string(url, getHeader());
        Vod vod = new Vod();
        vod.setVodId(id);
        Matcher mTitle = Pattern.compile("<title[^>]*>([^<]+)</title>").matcher(html);
        if (mTitle.find()) {
            String t = mTitle.group(1).replace(" — xHamster", "").replace("| xHamster", "").trim();
            vod.setVodName(t);
        }
        Matcher mPic = Pattern.compile("\"imageURL\"\\s*:\\s*\"(https:[^\"]+)\"").matcher(html);
        if (mPic.find()) {
            vod.setVodPic(mPic.group(1).replace("\\u002F", "/"));
        }
        String playUrl = extractM3u8(html);
        if (TextUtils.isEmpty(playUrl)) playUrl = url;
        vod.setVodPlayFrom("xhamster");
        vod.setVodPlayUrl(playUrl);
        return Result.get().vod(vod).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        if (!unlocked) return Result.get().vod(new ArrayList<Vod>()).string();
        String url = siteUrl + "/search/" + URLEncoder.encode(key, "UTF-8") + "?page=1";
        String html = OkHttp.string(url, getHeader());
        List<Vod> list = parseVideoList(html);
        return Result.get().vod(list).page(1, 1, 24, list.size()).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (!unlocked) return Result.get().url("").string();
        Map<String, String> header = getHeader();
        header.put("Referer", siteUrl + "/");
        return Result.get().url(id).header(header).string();
    }

    private List<Vod> parseVideoList(String html) {
        List<Vod> list = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return list;
        try {
            int start = html.indexOf("\"videoThumbProps\":[");
            if (start < 0) start = html.indexOf("videoThumbProps\":[");
            if (start < 0) return list;
            start = html.indexOf('[', start);
            int end = findMatchingBracket(html, start);
            if (end <= start) return list;
            String arrStr = html.substring(start, end + 1);
            JSONArray arr = new JSONArray(arrStr);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String pageURL = item.optString("pageURL");
                String title = item.optString("title");
                String thumb = item.optString("thumbURL");
                int duration = item.optInt("duration", 0);
                if (TextUtils.isEmpty(pageURL) || TextUtils.isEmpty(title)) continue;
                String vodId = pageURL.contains("/videos/") ?
                        pageURL.substring(pageURL.lastIndexOf("/videos/") + 8) : pageURL;
                Vod vod = new Vod();
                vod.setVodId(vodId);
                vod.setVodName(title);
                vod.setVodPic(thumb);
                if (duration > 0) {
                    vod.setVodRemarks(String.format("%d:%02d", duration / 60, duration % 60));
                }
                list.add(vod);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private String extractM3u8(String html) {
        if (TextUtils.isEmpty(html)) return "";
        Matcher m1 = Pattern.compile("href=\"(https://video-nss\\.xhcdn\\.com/[^\"]+?\\.m3u8)\"").matcher(html);
        if (m1.find()) return m1.group(1);
        Matcher m2 = Pattern.compile("(https://video-nss\\.xhcdn\\.com/[^\"'\\s]+?\\.m3u8)").matcher(html);
        if (m2.find()) return m2.group(1);
        return "";
    }

    private int findMatchingBracket(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
