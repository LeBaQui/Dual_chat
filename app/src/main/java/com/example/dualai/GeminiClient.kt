package com.example.dualai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object GeminiClient {

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    // Gọi streamGenerateContent (SSE) giống file gemini-chat-1.html. Chạy trên thread nền.
    fun stream(
        key: String,
        model: String,
        think: String,
        text: String,
        cancelled: () -> Boolean,
        onChunk: (String) -> Unit
    ) {
        val url = URL("$BASE/models/${URLEncoder.encode(model, "UTF-8")}:streamGenerateContent?alt=sse")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)

            val part = JSONObject().put("text", text)
            val content = JSONObject().put("role", "user").put("parts", JSONArray().put(part))
            val body = JSONObject().put("contents", JSONArray().put(content))
            if (think.isNotEmpty()) {
                body.put(
                    "generationConfig",
                    JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", think))
                )
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = (conn.errorStream ?: conn.inputStream).bufferedReader().readText()
                val msg = try {
                    JSONObject(err).getJSONObject("error").getString("message")
                } catch (_: Exception) {
                    "HTTP $code"
                }
                throw RuntimeException(msg)
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            while (true) {
                if (cancelled()) break
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val js = line.substring(5).trim()
                if (js.isEmpty()) continue
                try {
                    val parts = JSONObject(js)
                        .optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val p = parts.getJSONObject(i)
                        if (p.optBoolean("thought")) continue
                        val t = p.optString("text")
                        if (t.isNotEmpty()) onChunk(t)
                    }
                } catch (_: Exception) {
                    // bỏ qua chunk lỗi
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
