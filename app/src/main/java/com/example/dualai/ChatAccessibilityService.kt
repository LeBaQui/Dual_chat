package com.example.dualai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class MaxHeightScrollView(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }
}

class ChatAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: ChatAccessibilityService? = null
        @Volatile var dbgText: String = ""
        @Volatile var dbgSent: Int = 0
    }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())

    private var targetPkg = ""
    private var lastText = ""
    private var lastTriggered = ""
    private var lastTrigAt = 0L
    private val recent = ArrayList<String>()

    private var curText = ""
    private var curCtx: List<String> = emptyList()
    @Volatile private var jobId = 0
    private val resultBuf = StringBuilder()

    private lateinit var bubble: TextView
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private var bubbleOn = false

    private lateinit var panel: LinearLayout
    private lateinit var panelLp: WindowManager.LayoutParams
    private var panelOn = false
    private lateinit var modeTv: TextView
    private lateinit var origTv: TextView
    private lateinit var resultTv: TextView

    // ---------- vòng đời ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildViews()
        instance = this
        applyConfig()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        jobId++
        removeSafe(bubble, bubbleOn)
        removeSafe(panel, panelOn)
        bubbleOn = false
        panelOn = false
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    // Gọi lại sau khi lưu cài đặt: chỉ nhận sự kiện từ đúng app đã chọn
    fun applyConfig() {
        targetPkg = Prefs.pkg(this)
        val info = serviceInfo ?: return
        info.packageNames = arrayOf(targetPkg)
        serviceInfo = info
    }

    // ---------- bắt tin nhắn ----------

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        if (e.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (e.packageName?.toString() != targetPkg) return
        if (e.isPassword) {
            lastText = ""
            return
        }

        val src = e.source
        val hintShown = src?.isShowingHintText == true
        val hintStr = src?.hintText?.toString()
        var cur = if (hintShown) "" else (e.text?.joinToString("") ?: "")
        if (hintStr != null && cur == hintStr) cur = ""
        dbgText = cur

        if (cur.trim().isEmpty()) {
            // ô nhập đang có chữ mà rỗng cả cụm (không phải xoá từng ký tự) => coi là đã gửi
            val prev = lastText.trim()
            lastText = ""
            if (prev.length >= 2) onSent(prev)
        } else {
            lastText = cur
        }
    }

    private fun onSent(text: String) {
        val now = SystemClock.uptimeMillis()
        if (text == lastTriggered && now - lastTrigAt < 1500) return
        lastTriggered = text
        lastTrigAt = now
        dbgSent++

        val n = Prefs.ctxN(this)
        val ctx: List<String> = if (n > 0) recent.takeLast(n) else emptyList()
        recent.add(text)
        if (recent.size > 30) recent.removeAt(0)
        run(text, ctx)
    }

    // ---------- gọi Gemini ----------

    fun run(text: String, ctx: List<String>) {
        curText = text
        curCtx = ctx

        val prompts = Prefs.prompts(this)
        val p = prompts[Prefs.activeIdx(this).coerceIn(0, prompts.size - 1)]

        showPanel()
        modeTv.text = p.name + " ▾"
        origTv.text = text
        resultBuf.setLength(0)

        val key = Prefs.key(this)
        val model = Prefs.model(this)
        val think = Prefs.think(this)
        if (key.isBlank() || model.isBlank()) {
            resultTv.text = "Chưa nhập API key / model trong app Dual AI."
            return
        }
        resultTv.text = "Đang xử lý…"

        val myJob = ++jobId
        val content = buildContent(p, text, ctx)
        Thread {
            try {
                GeminiClient.stream(key, model, think, content, { myJob != jobId }) { chunk ->
                    ui.post {
                        if (myJob == jobId) {
                            resultBuf.append(chunk)
                            resultTv.text = resultBuf.toString().replace("**", "")
                        }
                    }
                }
                ui.post {
                    if (myJob == jobId && resultBuf.isEmpty()) resultTv.text = "(không có nội dung trả về)"
                }
            } catch (ex: Exception) {
                ui.post {
                    if (myJob == jobId) resultTv.text = "Lỗi: " + (ex.message ?: ex.toString())
                }
            }
        }.start()
    }

    private fun buildContent(p: PromptItem, text: String, ctx: List<String>): String {
        val sb = StringBuilder()
        sb.append(p.text).append("\n\n")
        if (ctx.isNotEmpty()) {
            sb.append("Ngữ cảnh (các tin nhắn trước của tôi, chỉ để tham khảo, KHÔNG xử lý):\n")
            ctx.forEach { sb.append("- ").append(it).append("\n") }
            sb.append("\n")
        }
        sb.append("Văn bản cần xử lý:\n").append(text)
        sb.append("\n\n(Trả lời bằng văn bản thuần, không dùng Markdown.)")
        return sb.toString()
    }

    private fun nextMode() {
        val ps = Prefs.prompts(this)
        if (ps.size < 2) return
        val cur = Prefs.activeIdx(this).coerceIn(0, ps.size - 1)
        Prefs.setActiveIdx(this, (cur + 1) % ps.size)
        if (curText.isNotEmpty()) run(curText, curCtx)
        else modeTv.text = ps[Prefs.activeIdx(this)].name + " ▾"
    }

    private fun copyResult() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("gemini", resultBuf.toString()))
        Toast.makeText(this, "Đã chép", Toast.LENGTH_SHORT).show()
    }

    // ---------- popup / bong bóng ----------

    private fun addSafe(v: View, lp: WindowManager.LayoutParams): Boolean {
        return try {
            wm.addView(v, lp)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun removeSafe(v: View, on: Boolean) {
        if (!on) return
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun showPanel() {
        if (bubbleOn) {
            removeSafe(bubble, true)
            bubbleOn = false
        }
        if (!panelOn) panelOn = addSafe(panel, panelLp)
    }

    private fun minimize() {
        if (panelOn) {
            removeSafe(panel, true)
            panelOn = false
        }
        if (!bubbleOn) bubbleOn = addSafe(bubble, bubbleLp)
    }

    private fun hideAll() {
        if (bubbleOn) {
            removeSafe(bubble, true)
            bubbleOn = false
        }
    }

    private fun chip(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply {
            setColor(0x33FFFFFF)
            cornerRadius = dp(10).toFloat()
        }
    }

    private fun buildViews() {
        val dm = resources.displayMetrics

        // --- bong bóng ---
        bubble = TextView(this).apply {
            text = "G"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE62563EB.toInt())
            }
        }
        bubbleLp = WindowManager.LayoutParams(
            dp(48), dp(48),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - dp(60)
            y = dm.heightPixels / 3
        }
        // chạm = mở popup, giữ lâu = ẩn bong bóng (đến lần gửi tin sau)
        makeDraggable(bubble, bubbleLp, bubble, false, { showPanel() }, { hideAll() })

        // --- popup ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modeTv = chip("Chế độ ▾")
        modeTv.setOnClickListener { nextMode() }
        val rerun = chip("↻")
        rerun.setOnClickListener { if (curText.isNotEmpty()) run(curText, curCtx) }
        val copy = chip("Chép")
        copy.setOnClickListener { copyResult() }
        val mini = chip("—")
        mini.setOnClickListener { minimize() }

        header.addView(modeTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        for (v in listOf(rerun, copy, mini)) {
            header.addView(v, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(6) })
        }

        origTv = TextView(this).apply {
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 12f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, dp(4))
        }

        resultTv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setLineSpacing(0f, 1.15f)
        }
        val scroll = MaxHeightScrollView(this, (dm.heightPixels * 0.42).toInt())
        scroll.addView(resultTv)

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xF01F2937.toInt())
                cornerRadius = dp(16).toFloat()
            }
        }
        panel.addView(header)
        panel.addView(origTv)
        panel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        panelLp = WindowManager.LayoutParams(
            dm.widthPixels - dp(16), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(56)
        }
        // kéo header để dời popup lên xuống
        makeDraggable(header, panelLp, panel, true, null, null)
    }

    private fun makeDraggable(
        handle: View,
        lp: WindowManager.LayoutParams,
        target: View,
        lockX: Boolean,
        onTap: (() -> Unit)?,
        onLong: (() -> Unit)?
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var downT = 0L
        handle.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    downT = ev.eventTime
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) moved = true
                    if (moved) {
                        if (!lockX) lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try {
                            wm.updateViewLayout(target, lp)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (ev.eventTime - downT > 600) onLong?.invoke() else onTap?.invoke()
                    }
                    true
                }
                else -> false
            }
        }
    }
}
