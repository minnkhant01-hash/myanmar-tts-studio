package com.myanmartts.studio

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal Microsoft Edge TTS client over WebSocket, mirroring the behaviour
 * of the Python `edge-tts` fork (HarryXiaoCN, sec-ms-gec-v2 branch).
 *
 * Protocol summary:
 *  1. Open wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1
 *     with TrustedClientToken, ConnectionId, Sec-MS-GEC and Sec-MS-GEC-Version
 *     query parameters plus browser-like headers and a `muid` cookie.
 *  2. Send a `speech.config` text message, then an `ssml` text message.
 *  3. Collect binary `audio` messages until a text message with `Path:turn.end`.
 */
class EdgeTtsClient {

    data class Voice(val shortName: String, val locale: String, val gender: String)

    companion object {
        private const val BASE_URL = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
        private const val WSS_URL =
            "wss://$BASE_URL/edge/v1?TrustedClientToken=${Drm.TRUSTED_CLIENT_TOKEN}"
        private const val VOICE_LIST_URL =
            "https://$BASE_URL/voices/list?trustedclienttoken=${Drm.TRUSTED_CLIENT_TOKEN}"
        private const val SEC_MS_GEC_VERSION = "2-143.0.3650.75"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
                " (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

        private fun dateToString(): String {
            val fmt = SimpleDateFormat(
                "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
                Locale.US,
            )
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return fmt.format(Date())
        }

        private fun connectId(): String = UUID.randomUUID().toString().replace("-", "")

        /** Mirrors xml.sax.saxutils.escape: & < > */
        fun escapeXml(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                when (ch) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        /** Mirrors edge-tts remove_incompatible_characters. */
        fun removeIncompatibleCharacters(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                val code = ch.code
                if (code in 0..8 || code in 11..12 || code in 14..31) sb.append(' ')
                else sb.append(ch)
            }
            return sb.toString()
        }

        fun buildSsml(text: String, voice: String, rate: String): String {
            val clean = escapeXml(removeIncompatibleCharacters(text))
            return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='$voice'>" +
                "<prosody pitch='+0Hz' rate='$rate' volume='+0%'>$clean</prosody>" +
                "</voice></speak>"
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------
    // Voice list
    // ------------------------------------------------------------------

    @Throws(Exception::class)
    fun fetchVoices(): List<Voice> {
        val url = "$VOICE_LIST_URL&Sec-MS-GEC=${Drm.generateSecMsGec()}" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Authority", "speech.platform.bing.com")
            .header("Sec-CH-UA", "\" Not;A Brand\";v=\"99\", \"Microsoft Edge\";v=\"143\", \"Chromium\";v=\"143\"")
            .header("Sec-CH-UA-Mobile", "?0")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
            .header("Cookie", "muid=${Drm.generateMuid()};")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Voice list HTTP ${response.code}")
            val body = response.body?.string() ?: throw Exception("Empty voice list")
            val result = mutableListOf<Voice>()
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    Voice(
                        shortName = o.optString("ShortName"),
                        locale = o.optString("Locale"),
                        gender = o.optString("Gender"),
                    )
                )
            }
            return result
        }
    }

    // ------------------------------------------------------------------
    // Synthesis (blocking — call off the UI thread)
    // ------------------------------------------------------------------

    @Throws(Exception::class)
    fun synthesize(text: String, voice: String, rate: String): ByteArray {
        val connectionId = connectId()
        val url = "$WSS_URL&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=${Drm.generateSecMsGec()}" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${Drm.generateMuid()};")
            .build()

        val audioOut = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)
        var audioReceived = false

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val timestamp = dateToString()
                // 1. speech.config
                webSocket.send(
                    "X-Timestamp:$timestamp\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                        "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                        "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                )
                // 2. ssml
                val requestId = connectId()
                val ssml = buildSsml(text, voice, rate)
                webSocket.send(
                    "X-RequestId:$requestId\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${dateToString()}Z\r\n" +
                        "Path:ssml\r\n\r\n$ssml"
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val sep = text.indexOf("\r\n\r\n")
                val headerBlock = if (sep >= 0) text.substring(0, sep) else text
                var path: String? = null
                for (line in headerBlock.split("\r\n")) {
                    val kv = line.split(":", limit = 2)
                    if (kv.size == 2 && kv[0].trim().equals("Path", ignoreCase = true)) {
                        path = kv[1].trim()
                    }
                }
                when (path) {
                    "turn.end" -> {
                        webSocket.close(1000, null)
                        done.countDown()
                    }
                    "turn.start", "response", "audio.metadata" -> { /* ignore */ }
                    else -> { /* ignore unknown text paths */ }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) {
                    error.compareAndSet(null, Exception("Binary message too short"))
                    return
                }
                val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                if (headerLength + 2 > data.size) {
                    error.compareAndSet(null, Exception("Bad binary header length"))
                    return
                }
                val headerBlock = String(data, 2, headerLength, Charsets.UTF_8)
                var path: String? = null
                var contentType: String? = null
                for (line in headerBlock.split("\r\n")) {
                    val kv = line.split(":", limit = 2)
                    if (kv.size == 2) {
                        when (kv[0].trim().lowercase(Locale.US)) {
                            "path" -> path = kv[1].trim()
                            "content-type" -> contentType = kv[1].trim()
                        }
                    }
                }
                if (path != "audio") return
                val audioStart = headerLength + 2
                if (contentType == null) {
                    // End-of-stream marker carries no audio; anything else is unexpected.
                    if (audioStart < data.size) {
                        error.compareAndSet(null, Exception("Audio without Content-Type"))
                    }
                    return
                }
                if (contentType != "audio/mpeg") {
                    error.compareAndSet(null, Exception("Unexpected Content-Type: $contentType"))
                    return
                }
                audioOut.write(data, audioStart, data.size - audioStart)
                audioReceived = true
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                error.compareAndSet(null, t)
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        }

        val socket = http.newWebSocket(request, listener)
        try {
            if (!done.await(120, TimeUnit.SECONDS)) {
                throw Exception("TTS timed out")
            }
        } finally {
            socket.cancel()
        }
        error.get()?.let { throw Exception("TTS failed: ${it.message}", it) }
        val bytes = audioOut.toByteArray()
        if (!audioReceived || bytes.size < 100) {
            throw Exception("Edge TTS returned empty audio")
        }
        return bytes
    }
}
