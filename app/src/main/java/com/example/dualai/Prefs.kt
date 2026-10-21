package com.example.dualai

import android.content.Context

data class PromptItem(val name: String, val text: String)

object Prefs {

    const val DEFAULT_PROMPTS = """Sửa ngữ pháp
Bạn là giáo viên tiếng Anh. Văn bản dưới đây là tin nhắn tôi gõ khi chơi game nhập vai bằng tiếng Anh. Hãy sửa lỗi ngữ pháp, chính tả và cách dùng từ. Trả lời gọn: dòng đầu là câu đã sửa (giữ nguyên ý và giọng văn), các dòng sau liệt kê từng lỗi kèm lý do ngắn bằng tiếng Việt. Nếu không có lỗi thì chỉ ghi "Đúng rồi" kèm một cách nói tự nhiên hơn.
===
Dịch sang tiếng Việt
Dịch văn bản sau sang tiếng Việt tự nhiên. Chỉ trả về bản dịch.
===
Dịch sang tiếng Anh
Dịch văn bản sau sang tiếng Anh tự nhiên, giọng nói chuyện. Chỉ trả về bản dịch."""

    // Mặc định theo dõi cả 4 AI: Claude, ChatGPT, Grok, Gemini (app + giao diện assistant của Google)
    const val DEFAULT_PKG =
        "com.anthropic.claude,com.openai.chatgpt,ai.x.grok," +
        "com.google.android.apps.bard,com.google.android.googlequicksearchbox"

    // Giá trị mặc định cũ (chỉ Claude): gặp lại thì tự nâng lên mặc định mới
    private const val OLD_DEFAULT_PKG = "com.anthropic.claude"

    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun key(c: Context): String = sp(c).getString("key", "") ?: ""
    fun model(c: Context): String = sp(c).getString("model", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite"
    fun think(c: Context): String = sp(c).getString("think", "") ?: ""

    fun pkg(c: Context): String {
        val v = (sp(c).getString("pkg", null) ?: "").trim()
        return if (v.isEmpty() || v == OLD_DEFAULT_PKG) DEFAULT_PKG else v
    }

    fun ctxN(c: Context): Int = sp(c).getInt("ctxN", 0)
    fun rawPrompts(c: Context): String = sp(c).getString("prompts", DEFAULT_PROMPTS) ?: DEFAULT_PROMPTS
    fun activeIdx(c: Context): Int = sp(c).getInt("active", 0)

    fun setActiveIdx(c: Context, i: Int) {
        sp(c).edit().putInt("active", i).apply()
    }

    // Chỉ lưu riêng model (dùng khi chọn model từ danh sách mà chưa bấm Lưu cả form)
    fun setModel(c: Context, model: String) {
        sp(c).edit().putString("model", model).apply()
    }

    fun save(c: Context, key: String, model: String, think: String, pkg: String, ctxN: Int, prompts: String) {
        sp(c).edit()
            .putString("key", key)
            .putString("model", model)
            .putString("think", think)
            .putString("pkg", pkg)
            .putInt("ctxN", ctxN)
            .putString("prompts", prompts)
            .apply()
    }

    fun prompts(c: Context): List<PromptItem> = parse(rawPrompts(c))

    // Mỗi prompt cách nhau bằng 1 dòng chỉ có ===. Dòng đầu của khối = tên, phần còn lại = nội dung prompt.
    fun parse(raw: String): List<PromptItem> {
        val list = raw.split(Regex("(?m)^\\s*===\\s*$")).mapNotNull { block ->
            val lines = block.trim().lines()
            if (lines.isEmpty() || lines[0].isBlank()) null
            else PromptItem(lines[0].trim(), lines.drop(1).joinToString("\n").trim())
        }
        return if (list.isEmpty()) parse(DEFAULT_PROMPTS) else list
    }
}
