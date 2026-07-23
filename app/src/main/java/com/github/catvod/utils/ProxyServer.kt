package com.github.catvod.utils

import com.github.catvod.crawler.SpiderDebug
import com.github.catvod.net.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.util.HashMap


object ProxyServer {
    private val THREAD_NUM = Runtime.getRuntime().availableProcessors()
    private val partSize = 1024 * 1024
    private var port = 12345
    private var httpServer: AdvancedHttpServer? = null
    private val infos = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
    private val urlMap = mutableMapOf<String, String>()
    private val headerMap = mutableMapOf<String, Map<String, String>>()


    fun stop() {
        httpServer?.stop()
    }

    fun start() {
        do {
            try {
                httpServer = AdvancedHttpServer(port)
                httpServer?.addRoutes("/") { _, response ->
                    run {
                        response.setContentType("text/html")
                        response.start()
                        response.write("Hello, world!")
                    }
                }
                httpServer?.addRoutes("/proxy") { req, response ->
                    try {
                        val doAction = req.queryParams["do"]
                        val key = req.queryParams["key"]

                        // IPTV 直播源接口
                        if ("iptv" == doAction) {
                            handleIptvRoute(req, response)
                        } else if (key != null) {
                            // 原有视频代理逻辑
                            val url = urlMap[key]
                            val header = headerMap[key]
                            if (url != null && header != null) {
                                proxyAsync(url, header, req, response)
                            }
                        } else {
                            // 日志面板等
                            handleLogsRoute(req, response)
                        }
                    } catch (e: Exception) {
                        SpiderDebug.log("代理视频出错:" + e.message)
                    }
                }
                httpServer?.start()

            } catch (e: Exception) {
                e.printStackTrace()
                SpiderDebug.log("启动服务出错:" + e.message)
                port++
                httpServer?.stop()
            }
        } while (port < 20000)
        SpiderDebug.log("启动服务 on $port")
    }

    /**
     * 处理 /proxy?do=iptv 路由
     */
    private fun handleIptvRoute(req: AdvancedHttpServer.Request, response: AdvancedHttpServer.Response) {
        try {
            val tid = req.queryParams["tid"]

            // 获取缓存数据
            @Suppress("UNCHECKED_CAST")
            val data = com.github.catvod.spider.ProxyIPTV.getCacheData() as? Map<String, List<String>> ?: emptyMap()

            val txt = StringBuilder()

            // 如果指定了 tid，只返回该分组
            if (tid != null && !tid.isEmpty()) {
                val channels = data[tid]
                appendTxtGroup(txt, tid, channels)
            } else {
                // 返回所有分组
                for ((group, channels) in data) {
                    appendTxtGroup(txt, group, channels)
                }
            }

            val bytes = txt.toString().toByteArray(Charsets.UTF_8)
            response.setContentType("text/plain; charset=utf-8")
            response.setHeader("Content-Length", bytes.size.toString())
            response.start()
            response.write(bytes)
        } catch (e: Exception) {
            SpiderDebug.log("iptv route error: ${e.message}")
            e.printStackTrace()
            response.setContentType("text/plain; charset=utf-8")
            response.setStatusCode(500)
            response.start()
            response.write("Error: ${e.message}".toByteArray(Charsets.UTF_8))
        }
    }

    private fun appendTxtGroup(txt: StringBuilder, group: String?, channels: List<String>?) {
        if (channels == null || channels.isEmpty()) return
        txt.append(group).append(",#genre#\n")
        for (line in channels) {
            val item = line.replaceFirst("\\$", ",")
            txt.append(item).append("\n")
        }
    }

    private fun handleLogsRoute(req: AdvancedHttpServer.Request, response: AdvancedHttpServer.Response) {
        try {
            val doAction = req.queryParams["do"]
            val sb = com.github.catvod.spider.Proxy.sb

            when (doAction) {
                "get_logs", "logs", "kaige_debug" -> {
                    response.setContentType("text/plain; charset=utf-8")
                    response.start()
                    response.write(sb.toString().toByteArray(Charsets.UTF_8))
                }
                "clean" -> {
                    sb.setLength(0)
                    sb.append("<div style='color:red;'>--- 日志已手动清空 ---</div>")
                    response.setContentType("text/plain; charset=utf-8")
                    response.start()
                    response.write("OK".toByteArray(Charsets.UTF_8))
                }
                else -> {
                    val html = "<html><head><meta charset='utf-8'><style>" +
                            "body{background:#fff;color:#000;font-family:monospace;font-size:12px;margin:0;padding:10px;}" +
                            ".header{position:sticky;top:0;background:#fff;padding:5px;border-bottom:1px solid #000;display:flex;justify-content:space-between;z-index:9;}" +
                            ".time{color:#888;margin-right:5px;}.line{border-bottom:1px solid #eee;padding:2px 0;}" +
                            "button{background:#000;color:#fff;border:none;padding:4px 8px;border-radius:3px;}" +
                            "</style></head><body>" +
                            "<div class='header'><b>📟 凯哥监听</b><button onclick='clr()'>🧹 清空</button></div>" +
                            "<div id='logs'>正在对接矩阵数据...</div>" +
                            "<script>" +
                            "function clr(){fetch('?do=clean').then(()=>location.reload());}" +
                            "let last = '';" +
                            "setInterval(() => {" +
                            "  fetch('?do=get_logs').then(r=>r.text()).then(data=>{" +
                            "    if(data !== last) {" +
                            "      document.getElementById('logs').innerHTML = data;" +
                            "      last = data;" +
                            "      window.scrollTo(0, document.body.scrollHeight);" +
                            "    }" +
                            "  });" +
                            "}, 1000);" +
                            "</script></body></html>"
                    response.setContentType("text/html; charset=utf-8")
                    response.start()
                    response.write(html.toByteArray(Charsets.UTF_8))
                }
            }
        } catch (e: Exception) {
            response.setContentType("text/plain; charset=utf-8")
            response.setStatusCode(500)
            response.start()
            response.write("Error: ${e.message}".toByteArray(Charsets.UTF_8))
        }
    }

    private fun proxyAsync(
        url: String,
        headers: Map<String, String>,
        request: AdvancedHttpServer.Request,
        response: AdvancedHttpServer.Response
    ) {
        runBlocking {
            val channels = List(THREAD_NUM) { Channel<ByteArray>() }
            SpiderDebug.log("--proxyAsync url:  $url")

            try {
                SpiderDebug.log("--proxyMultiThread: THREAD_NUM: $THREAD_NUM")

                var rangeHeader = request.headers["Range"] ?: "bytes=0-"
                headers.toMutableMap().apply { put("Range", rangeHeader) }

                val (startPoint, endPoint) = parseRangePoint(rangeHeader)
                var info = infos[url]
                if (info == null) {
                    info = getInfo(url, headers)
                    infos[url] = info
                }

                SpiderDebug.log("startPoint: $startPoint; endPoint: $endPoint")
                val contentLength = getContentLength(info)
                SpiderDebug.log("contentLength: $contentLength")
                val finalEndPoint = if (endPoint == -1L) contentLength - 1 else endPoint
                response.setContentType("application/octet-stream")

                response.setHeader("Connection", "keep-alive")
                response.setHeader("Content-Length", (finalEndPoint - startPoint + 1).toString())
                response.setHeader("Content-Range", "bytes $startPoint-$finalEndPoint/$contentLength")
                info["Content-Type"]?.get(0)?.let { response.setHeader("Content-Type", it) }
                response.setStatusCode(206)
                response.start()

                var currentStart = startPoint
                val producerJob = mutableListOf<Job>()

                while (currentStart <= finalEndPoint) {
                    producerJob.clear()
                    for (i in 0 until THREAD_NUM) {
                        if (currentStart > finalEndPoint) break
                        val chunkStart = currentStart
                        val chunkEnd = minOf(currentStart + partSize - 1, finalEndPoint)
                        producerJob += CoroutineScope(Dispatchers.IO).launch {
                            val data = getVideoStream(chunkStart, chunkEnd, url, headers)
                            if (chunkStart == 0L) {
                                val offset = detectMaliciousPrefix(data)
                                channels[i].send(data.copyOfRange(offset, data.size))
                            } else {
                                channels[i].send(data)
                            }
                        }
                        currentStart = chunkEnd + 1
                    }
                    for ((index, _) in producerJob.withIndex()) {
                        val data = channels[index].receive()
                        SpiderDebug.log("Received chunk: ${data.size} bytes")
                        response.write(data)
                    }
                }
                channels.forEach { it.close() }
            } catch (e: Exception) {
                SpiderDebug.log("proxyAsync error: ${e.message}")
                e.printStackTrace()
                response.write("proxyAsync error: ${e.message}".toByteArray(Charsets.UTF_8))
            } finally {
                // channels.forEach { it.close() }
            }
        }
    }

    fun detectMaliciousPrefix(data: ByteArray): Int {
        val buffer = ByteArray(64)
        ByteArrayInputStream(data).use { fis ->
            fis.read(buffer)
        }

        if (isValidVideoHeader(buffer)) {
            return 0
        }

        val searchLimit = minOf(256, data.size)
        val searchBuffer = ByteArray(searchLimit)
        ByteArrayInputStream(data).use { fis ->
            fis.read(searchBuffer, 0, searchLimit)
        }

        for (offset in 1 until searchLimit - 16) {
            if (isValidVideoHeader(searchBuffer, offset)) {
                SpiderDebug.log("发现合法视频头位于偏移量 $offset，疑似被插入恶意前缀")
                return offset
            }
        }

        return 0
    }

    fun isValidVideoHeader(data: ByteArray, offset: Int = 0): Boolean {
        if (data.size - offset < 8) return false

        // MP4 / MOV: ... ftyp
        if (data[offset + 4].toInt() == 0x66.toByte().toInt() &&
            data[offset + 5].toInt() == 0x74.toByte().toInt() &&
            data[offset + 6].toInt() == 0x79.toByte().toInt() &&
            data[offset + 7].toInt() == 0x70.toByte().toInt()
        ) {
            val size = data[offset].toLong() and 0xFF shl 24 or (data[offset + 1].toLong() and 0xFF shl 16) or (data[offset + 2].toLong() and 0xFF shl 8) or (data[offset + 3].toLong() and 0xFF)
            if (size >= 8 && size <= 0x100000) return true
        }

        // AVI: RIFF
        if (data[offset] == 0x52.toByte() && data[offset + 1] == 0x49.toByte() && data[offset + 2] == 0x46.toByte() && data[offset + 3] == 0x46.toByte()) return true

        // MKV: 1A 45 DF A3
        if (data[offset] == 0x1A.toByte() && data[offset + 1] == 0x45.toByte() && data[offset + 2] == 0xDF.toByte() && data[offset + 3] == 0xA3.toByte()) return true

        // FLV: 46 4C 56 01
        if (data[offset] == 0x46.toByte() && data[offset + 1] == 0x4C.toByte() && data[offset + 2] == 0x56.toByte() && data[offset + 3] == 0x01.toByte()) return true

        return false
    }

    private fun queryToMap(query: String?): Map<String, String>? {
        if (query == null) return null
        val result: MutableMap<String, String> = HashMap()
        for (param in query.split("&").dropLastWhile { it.isEmpty() }.toTypedArray()) {
            val entry = param.split("=").dropLastWhile { it.isEmpty() }.toTypedArray()
            if (entry.size > 1) {
                result[entry[0]] = entry[1]
            } else {
                result[entry[0]] = ""
            }
        }
        return result
    }

    private fun parseRangePoint(rangeHeader: String): Pair<Long, Long> {
        val regex = """bytes=(\d+)-(\d*)""".toRegex()
        val match = regex.find(rangeHeader) ?: return 0L to -1L
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: -1L
        return start to end
    }

    private fun getInfo(
        url: String?, headers: Map<String, String>
    ): MutableMap<String, MutableList<String>> {
        val newHeaders: MutableMap<String, String> = HashMap(headers)
        newHeaders["Range"] = "bytes=0-" + (1024 * 1024 - 1)
        newHeaders["range"] = "bytes=0-" + (1024 * 1024 - 1)
        val res = OkHttp.newCall(url, newHeaders)
        res.body()?.close()
        return res.headers().toMultimap()
    }

    private fun getContentLength(info: MutableMap<String, MutableList<String>>): Long {
        return info["Content-Length"]?.get(0)?.toLong() ?: 0L
    }

    private fun getVideoStream(
        start: Long, end: Long, url: String, headers: Map<String, String>
    ): ByteArray {
        val header = headers.toMutableMap()
        SpiderDebug.log("getVideoStream: $start-$end; ")
        header["Range"] = "bytes=$start-$end"
        val res = OkHttp.newCall(url, header)
        val body = res.body()
        return body?.bytes() ?: ByteArray(0)
    }

    fun buildProxyUrl(url: String, headers: Map<String, String>): String {
        urlMap.clear()
        headerMap.clear()
        val key = Util.MD5(url)
        urlMap[key] = url
        headerMap[key] = headers

        return "http://127.0.0.1:$port/proxy?key=$key"
    }
}
