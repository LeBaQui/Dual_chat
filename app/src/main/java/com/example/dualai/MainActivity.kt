package com.example.dualai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusTv: TextView
    private lateinit var keyEt: EditText
    private lateinit var modelEt: EditText
    private lateinit var thinkSp: Spinner
    private lateinit var pkgEt: EditText
    private lateinit var ctxEt: EditText
    private lateinit var promptsEt: EditText

    private val gemmaLevels = listOf("" to "Thinking: mặc định", "MINIMAL" to "Minimal", "HIGH" to "High")
    private val geminiLevels = listOf("" to "Thinking: mặc định", "LOW" to "Low", "MEDIUM" to "Medium", "HIGH" to "High")
    private var levels = geminiLevels
    private var isGemma = false

    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshStatus()
            h.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        val sv = ScrollView(this)
        sv.addView(col)

        col.addView(TextView(this).apply {
            text = "Dual AI — Gemini sửa ngữ pháp / dịch"
            textSize = 20f
        })

        statusTv = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(statusTv)

        col.addView(button("1. Mở cài đặt Trợ năng (bật Dual AI)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("Mở thông tin app (⋮ > Cho phép cài đặt bị hạn chế)") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })

        col.addView(label("API key"))
        keyEt = edit("API key Google AI Studio", Prefs.key(this))
        keyEt.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        col.addView(keyEt)

        col.addView(label("Model"))
        modelEt = edit("Tên model", Prefs.model(this))
        col.addView(modelEt)

        thinkSp = Spinner(this)
        col.addView(thinkSp)
        isGemma = Prefs.model(this).trim().lowercase().startsWith("gemma")
        refreshLevels(Prefs.think(this))
        modelEt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val g = (s?.toString() ?: "").trim().lowercase().startsWith("gemma")
                if (g != isGemma) {
                    isGemma = g
                    refreshLevels("")
                }
            }
        })

        col.addView(label("Package app AI chính (mặc định app Claude)"))
        pkgEt = edit("com.anthropic.claude", Prefs.pkg(this))
        col.addView(pkgEt)

        col.addView(label("Số tin nhắn trước gửi kèm làm ngữ cảnh (0 = tắt)"))
        ctxEt = edit("0", Prefs.ctxN(this).toString())
        ctxEt.inputType = InputType.TYPE_CLASS_NUMBER
        col.addView(ctxEt)

        col.addView(label("Prompt (mỗi prompt cách nhau 1 dòng chỉ có ===; dòng đầu của khối là tên)"))
        promptsEt = edit("", Prefs.rawPrompts(this))
        promptsEt.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        promptsEt.minLines = 12
        promptsEt.gravity = Gravity.TOP
        col.addView(promptsEt)

        col.addView(button("Lưu") {
            save()
            toast("Đã lưu")
        })
        col.addView(button("Thử popup (cần đã bật Trợ năng)") {
            save()
            val s = ChatAccessibilityService.instance
            if (s == null) toast("Chưa bật Trợ năng")
            else s.run("I goes to school yesterday and meet my friend.", emptyList())
        })

        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick)
    }

    private fun refreshStatus() {
        val on = ChatAccessibilityService.instance != null
        statusTv.text = "Trợ năng: " + (if (on) "ĐANG CHẠY" else "chưa bật") +
            "\nChữ ô nhập bắt được gần nhất: " + ChatAccessibilityService.dbgText +
            "\nSố lần phát hiện gửi: " + ChatAccessibilityService.dbgSent
    }

    private fun refreshLevels(keep: String) {
        levels = if (isGemma) gemmaLevels else geminiLevels
        thinkSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, levels.map { it.second })
        val i = levels.indexOfFirst { it.first == keep }
        thinkSp.setSelection(if (i >= 0) i else 0)
    }

    private fun curThink(): String = levels.getOrNull(thinkSp.selectedItemPosition)?.first ?: ""

    private fun save() {
        Prefs.save(
            this,
            keyEt.text.toString().trim(),
            modelEt.text.toString().trim(),
            curThink(),
            pkgEt.text.toString().trim(),
            ctxEt.text.toString().trim().toIntOrNull() ?: 0,
            promptsEt.text.toString()
        )
        ChatAccessibilityService.instance?.applyConfig()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun edit(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 14f
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}
