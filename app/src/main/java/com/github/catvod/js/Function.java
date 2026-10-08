package com.github.catvod.js;

import android.util.Log;

import com.whl.quickjs.wrapper.QuickJSContext;

/**
 * 宿主壳（Fongmi / TVBox 系）反射加载的钩子 —— TVBox v1 兼容层。
 *
 * 壳里 quickjs/crawler/Spider.java 的 createFun() 会执行：
 *     Class&lt;?&gt; clz = dex.loadClass("com.github.catvod.js.Function");
 *     clz.getDeclaredConstructor(QuickJSContext.class).newInstance(ctx);
 * 时机在 createCtx()（已 evaluate assets/js/lib/http.js）之后、createObj()（拉站点 JS）之前。
 * 本类就在这里把 TVBox v1 老式 JS 的调用约定补上，让老脚本不用改一行就能跑。
 *
 * 为什么要补（壳的现状）：
 *   壳的 http.js 只实现 catvod 新约定：http(url, options)，同步返回 {code, headers, content}
 *   而 TVBox v1 老脚本写的是：http({url, method, headers})，然后读 res.status / res.body / res.text()
 *   ⇒ 老脚本在壳里必崩：url 被 JS_ToCString 变成 "[object Object]"，且 res.text 不是函数
 *
 * 兼容层包了两个点（少一个都不行，见下面 JS 里的注释）：
 *   1) globalThis._http —— 同步返回的唯一闸门（req / http 最终都汇到它）
 *   2) globalThis.http  —— v1 对象式调用时 options.async 不是 false，壳会走异步分支，
 *                          不在这里强制同步的话，包了 _http 也拦不到
 *
 * 另补 fetch / atob / btoa（QuickJS 没有这三个）与 md5 / aes / des / rsa 别名。
 *
 * 注意：
 *   - 类名必须是 Function（壳里硬编码），不能改。
 *   - 本工程原本只有 Method（showToast），名字对不上 ⇒ 壳的注入一直是静默失效的。
 *   - 壳那边是 catch (Throwable ignored)，所以本类抛异常也会静默 —— 故这里自己 Log 一份。
 *   - 本类不依赖本工程任何其它类，只用 android.util.Log 与 quickjs 的 QuickJSContext。
 */
public class Function {

    private static final String TAG = "catvod-js";

    public Function(QuickJSContext ctx) {
        if (ctx == null) return;
        try {
            ctx.evaluate(COMPAT);
            Log.i(TAG, "tvbox-v1 compat layer installed");
        } catch (Throwable e) {
            Log.w(TAG, "tvbox-v1 compat layer failed", e);
        }
    }

    private static final String COMPAT;

    static {
        String[] L = {
            "/*",
            " * TVBox v1 兼容层（jar 侧 com.github.catvod.js.Function 注入）",
            " *",
            " * ── 壳的现状（quickjs/src/main/assets/js/lib/http.js）────────────────────",
            " *     let req = (url, options) => http(url, Object.assign({ async: false }, options));",
            " *     function http(url, options = {}) {",
            " *         if (options?.async === false) return _http(url, options);",
            " *         return new Promise(resolve => _http(url, Object.assign({ complete: res => resolve(res) }, options)))...",
            " *     }",
            " *   _http / req 是 Java 侧 com.fongmi.quickjs.method.Global 的 @JSMethod，",
            " *   经 setProperty 注入成 globalThis 属性，同步返回 {code, headers, content}。",
            " *",
            " * ── 两个必须包住的点（少一个都不行）────────────────────────────────────",
            " *   ① 包 _http —— 它是**唯一**能拦到同步返回值的闸门（req / http 最终都汇到它），",
            " *      在这里做「对象式调用解包 + 老字段名映射 + 返回值补形」。",
            " *   ② 包 http  —— 因为 v1 脚本写的是 `http({url, method, headers})`，",
            " *      此时 options.async 不是 false ⇒ 壳会走**异步分支**，压根不经过同步路径。",
            " *      必须在这里识别「对象式调用」并强制同步，否则包了 _http 也没用。",
            " *",
            " * ── 覆盖可行性（为什么能改）────────────────────────────────────────────",
            " *   `function http(){}` 是全局 var 绑定 ⇒ 就是 globalThis 属性 ⇒ 可读写覆盖。",
            " *   `let req` 是全局**词法绑定** ⇒ 不是 globalThis 属性 ⇒ 覆盖不了；",
            " *   但 req 的闭包体里引用 `http`，而 http 是可覆盖的 ⇒ 改 http 即改 req。实测已证。",
            " *",
            " * ── 解决什么 ───────────────────────────────────────────────────────────",
            " *   ① 对象式调用 http({url, method, headers, body})  → 壳只认 http(url, options)",
            " *   ② 返回字段名 res.status / res.body / res.text()  → 壳只给 {code, headers, content}",
            " *   ③ fetch() / atob() / btoa()                      → QuickJS 没有这三个",
            " *   ④ md5 / aes / des / rsa 别名                     → 壳叫 md5X / aesX / desX / rsaX",
            " */",
            "(function () {",
            "    var _http = globalThis._http;",
            "    if (typeof _http !== 'function') return;      // 壳没注入 _http ⇒ 退出，不破坏现场",
            "    var _httpFn = globalThis.http;                // http.js 里的 function http（可覆盖）",
            "",
            "    function clone(o) {",
            "        var r = {};",
            "        if (o && typeof o === 'object') for (var k in o) r[k] = o[k];",
            "        return r;",
            "    }",
            "",
            "    // 把壳的 {code, headers, content} 补成 v1 认得的形态",
            "    function shape(res) {",
            "        if (res === null || typeof res !== 'object') return res;",
            "        if (res.status === undefined && res.code !== undefined) res.status = res.code;",
            "        if (res.body === undefined && res.content !== undefined) res.body = res.content;",
            "        if (res.ok === undefined) res.ok = res.status >= 200 && res.status < 300;",
            "        if (typeof res.text !== 'function') res.text = function () { return this.body || ''; };",
            "        if (typeof res.json !== 'function') res.json = function () {",
            "            try { return JSON.parse(this.text()); } catch (e) { return null; }",
            "        };",
            "        if (typeof res.arrayBuffer !== 'function') res.arrayBuffer = function () { return this.body || ''; };",
            "        if (typeof res.blob !== 'function') res.blob = function () { return this.body || ''; };",
            "        return res;",
            "    }",
            "",
            "    // options 规整：老字段名 -> 壳字段名",
            "    function fix(options) {",
            "        var o = clone(options);",
            "        if (o.headers === undefined && o.header !== undefined) o.headers = o.header;",
            "        if (o.body === undefined && o.data !== undefined) o.body = o.data;",
            "        if (o.method === undefined && o.type !== undefined) o.method = o.type;",
            "        return o;",
            "    }",
            "",
            "    // ── ① 包 _http（同步闸门）",
            "    globalThis._http = function (url, options) {",
            "        if (url && typeof url === 'object') {          // _http({url, ...})",
            "            var o = clone(url);",
            "            if (o.url !== undefined) { options = o; url = o.url; }",
            "        }",
            "        options = (options === undefined || options === null) ? {}",
            "                : (typeof options !== 'object') ? { body: options }",
            "                : fix(options);",
            "        return shape(_http(url, options));",
            "    };",
            "",
            "    // ── ② 包 http",
            "    if (typeof _httpFn === 'function') {",
            "        globalThis.http = function (url, options) {",
            "            if (url && typeof url === 'object') {       // v1 对象式 ⇒ 强制同步",
            "                var o = clone(url);",
            "                if (o.url === undefined) return null;",
            "                return shape(globalThis._http(o.url, fix(o)));",
            "            }",
            "            options = (options === undefined || options === null) ? {}",
            "                    : (typeof options !== 'object') ? { body: options }",
            "                    : fix(options);",
            "            if (options.async === false) return shape(globalThis._http(url, options));",
            "            return _httpFn(url, options).then(shape);   // 真异步：只补形，不改语义",
            "        };",
            "    }",
            "",
            "    // fetch：同步返回 Response-like（await 一个非 Promise 值同样成立）",
            "    if (typeof globalThis.fetch !== 'function') {",
            "        globalThis.fetch = function (url, options) {",
            "            var o = fix(options || {});",
            "            o.async = false;",
            "            if (url && typeof url === 'object') return shape(globalThis._http(url, fix(url)));",
            "            return shape(globalThis._http(url, o));",
            "        };",
            "    }",
            "",
            "    // atob / btoa（QuickJS 没有）",
            "    var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';",
            "    if (typeof globalThis.btoa !== 'function') {",
            "        globalThis.btoa = function (s) {",
            "            s = String(s == null ? '' : s);",
            "            var out = '';",
            "            for (var i = 0; i < s.length; i += 3) {",
            "                var c1 = s.charCodeAt(i), c2 = s.charCodeAt(i + 1), c3 = s.charCodeAt(i + 2);",
            "                out += B64.charAt(c1 >> 2);",
            "                out += B64.charAt(((c1 & 3) << 4) | (isNaN(c2) ? 0 : c2 >> 4));",
            "                out += isNaN(c2) ? '=' : B64.charAt(((c2 & 15) << 2) | (isNaN(c3) ? 0 : c3 >> 6));",
            "                out += isNaN(c3) ? '=' : B64.charAt(c3 & 63);",
            "            }",
            "            return out;",
            "        };",
            "    }",
            "    if (typeof globalThis.atob !== 'function') {",
            "        globalThis.atob = function (s) {",
            "            s = String(s == null ? '' : s).replace(/[^A-Za-z0-9+/]/g, '');",
            "            var out = '', buf = 0, bits = 0;",
            "            for (var i = 0; i < s.length; i++) {",
            "                buf = (buf << 6) | B64.indexOf(s.charAt(i));",
            "                bits += 6;",
            "                if (bits >= 8) { bits -= 8; out += String.fromCharCode((buf >> bits) & 0xff); }",
            "            }",
            "            return out;",
            "        };",
            "    }",
            "",
            "    // 别名：壳用 md5X / aesX / desX / rsaX，老 JS 常找 md5 / aes / des / rsa",
            "    if (typeof globalThis.md5X === 'function') {",
            "        if (typeof globalThis.md5 !== 'function') globalThis.md5 = globalThis.md5X;",
            "        if (typeof globalThis.md5x !== 'function') globalThis.md5x = globalThis.md5X;",
            "    }",
            "    if (typeof globalThis.aesX === 'function' && typeof globalThis.aes !== 'function') globalThis.aes = globalThis.aesX;",
            "    if (typeof globalThis.desX === 'function' && typeof globalThis.des !== 'function') globalThis.des = globalThis.desX;",
            "    if (typeof globalThis.rsaX === 'function' && typeof globalThis.rsa !== 'function') globalThis.rsa = globalThis.rsaX;",
            "})();"
        };
        StringBuilder sb = new StringBuilder(8192);
        for (String s : L) sb.append(s).append('\n');
        COMPAT = sb.toString();
    }
}
