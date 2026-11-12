package com.example.datebutton

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.DatePicker
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Date

class DateButtonService : AccessibilityService() {

    companion object {
        // クイック設定タイルや設定画面から操作するための参照
        var instance: DateButtonService? = null
    }

    private lateinit var wm: WindowManager
    private var button: TextView? = null
    private var panel: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    private fun prefs() = getSharedPreferences(DateFormats.PREFS, MODE_PRIVATE)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        DateFormats.migrate(prefs())
        if (button == null) showButton()
        setButtonVisible(prefs().getBoolean(DateFormats.KEY_VISIBLE, true))
    }

    fun setButtonVisible(visible: Boolean) {
        button?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) closePanel()
    }

    // ---------------------------------------------------------------
    // 浮遊ボタン（タップ / 長押し / ドラッグ）
    // ---------------------------------------------------------------
    @SuppressLint("ClickableViewAccessibility")
    private fun showButton() {
        val tv = TextView(this)
        tv.text = "日付"
        tv.textSize = 14f
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.setBackgroundColor(0xCC1A73E8.toInt())
        tv.setPadding(36, 24, 36, 24)
        tv.alpha = 0.85f

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 入力欄のフォーカスを奪わない
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 600

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        var longPressed = false
        val slop = dp(24)

        val longPressTask = Runnable {
            longPressed = true
            tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showMenu()
        }

        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    longPressed = false
                    handler.postDelayed(longPressTask, 500)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        moved = true
                        handler.removeCallbacks(longPressTask)
                    }
                    if (moved && !longPressed) {
                        params.x = startX + dx
                        params.y = startY + dy
                        wm.updateViewLayout(tv, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressTask)
                    if (!moved && !longPressed) tapInsert()
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressTask)
                }
            }
            true
        }

        wm.addView(tv, params)
        button = tv
    }

    // タップ: 今日の日付を入力
    private fun tapInsert() {
        val node = findInputNode()
        if (node == null) {
            toast("入力欄をタップしてから押してください")
            return
        }
        if (prefs().getBoolean(DateFormats.KEY_AUTO, false)) {
            toast(if (isTitleField(node)) "タイトル欄の形式で入力" else "本文の形式で入力")
        }
        insertFor(node, Date())
    }

    // 形式に従って入力する。タイトル欄でテンプレートを選んでいるときは、
    // タイトルには日付だけを入れ、残りは本文欄へ入れる
    private fun insertFor(node: AccessibilityNodeInfo, date: Date) {
        insertText(node, DateFormats.format(date, formatFor(node)))
    }

    // テンプレートを1回だけ挿入する（タップの動作は日付のまま変わらない）
    // 入力欄が2つ以上ある画面では、いちばん上をタイトルとみなし、
    // タイトルに日付、その下の欄に残りを入れる
    private fun insertTemplate(target: AccessibilityNodeInfo?, index: Int) {
        prefs().edit().putInt(DateFormats.KEY_LAST_TPL, index).apply()
        val parts = DateFormats.template(Date(), index)

        val list = ArrayList<AccessibilityNodeInfo>()
        val root = rootInActiveWindow
        if (root != null) collectEditables(root, list)

        if (list.size >= 2) {
            var title = list[0]
            var top = Int.MAX_VALUE
            for (n in list) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.top < top) {
                    top = r.top
                    title = n
                }
            }
            val titleText = if (title.isShowingHintText) "" else (title.text?.toString() ?: "")
            if (titleText.isEmpty()) {
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, parts.first
                )
                title.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                fillBody(title, parts.second)
            } else {
                // タイトルが入力済みなら、タイトルは触らず、本文に日付ごと入れる
                fillBody(title, parts.first + "\n" + parts.second)
            }
            return
        }

        val node = if (target != null && target.refresh()) target else findInputNode()
        if (node == null) {
            toast("入力欄をタップしてから、長押ししてください")
            return
        }
        insertText(node, parts.first + "\n" + parts.second)
    }

    // タイトル欄のすぐ下にある入力欄（本文）に、文字を入れる
    private fun fillBody(title: AccessibilityNodeInfo, rest: String) {
        val root = rootInActiveWindow
        if (root == null) {
            toast("本文欄が見つかりません")
            return
        }
        val list = ArrayList<AccessibilityNodeInfo>()
        collectEditables(root, list)
        val titleRect = Rect()
        title.getBoundsInScreen(titleRect)
        var body: AccessibilityNodeInfo? = null
        var bodyTop = Int.MAX_VALUE
        for (n in list) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.top > titleRect.top && r.top < bodyTop) {
                body = n
                bodyTop = r.top
            }
        }
        if (body == null) {
            toast("本文欄が見つかりません")
            return
        }
        val current = if (body.isShowingHintText) "" else (body.text?.toString() ?: "")
        val newText = if (current.isEmpty()) rest else rest + "\n" + current
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        body.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ---------------------------------------------------------------
    // 日付形式の決定（タイトル欄は別の形式にできる）
    // ---------------------------------------------------------------
    private fun formatFor(node: AccessibilityNodeInfo): Int {
        val p = prefs()
        val body = p.getInt(DateFormats.KEY_FORMAT, 0)
        if (!p.getBoolean(DateFormats.KEY_AUTO, false)) return body
        return if (isTitleField(node)) {
            p.getInt(DateFormats.KEY_TITLE_FORMAT, DateFormats.DEFAULT_TITLE)
        } else {
            body
        }
    }

    private fun isTitleField(node: AccessibilityNodeInfo): Boolean {
        val hint = node.hintText?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        if (hint.contains("タイトル") ||
            hint.contains("title", ignoreCase = true) ||
            id.contains("title", ignoreCase = true)
        ) {
            return true
        }
        // 予備: 同じ画面に入力欄が2つ以上あり、いちばん上の欄ならタイトルとみなす
        val root = rootInActiveWindow ?: return false
        val list = ArrayList<AccessibilityNodeInfo>()
        collectEditables(root, list)
        if (list.size < 2) return false
        val myRect = Rect()
        node.getBoundsInScreen(myRect)
        var minTop = Int.MAX_VALUE
        for (n in list) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.top < minTop) minTop = r.top
        }
        return myRect.top <= minTop
    }

    private fun collectEditables(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isEditable && node.isVisibleToUser) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEditables(child, out)
        }
    }

    private fun findInputNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        // 通常の入力欄 → 見つからなければ、フォーカス中の編集可能な部品を探す
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocusedEditable(root)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditable(child)
            if (found != null) return found
        }
        return null
    }

    // ---------------------------------------------------------------
    // 長押しメニュー（ダイアログではなく、画面に重ねる自前のパネル）
    // ---------------------------------------------------------------
    private fun titleView(message: String): TextView {
        val v = TextView(this)
        v.text = message
        v.textSize = 14f
        v.setTextColor(0xFF5F6368.toInt())
        v.setPadding(dp(8), dp(4), dp(8), dp(8))
        return v
    }

    private fun menuItem(label: String, onTap: () -> Unit): TextView {
        val v = TextView(this)
        v.text = label
        v.textSize = 16f
        v.setTextColor(0xFF202124.toInt())
        v.setPadding(dp(8), dp(14), dp(8), dp(14))
        v.setOnClickListener { onTap() }
        return v
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addPanel(content: View) {
        closePanel()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(12), dp(16), dp(12))
        val bg = GradientDrawable()
        bg.setColor(0xFFFFFFFF.toInt())
        bg.cornerRadius = dp(16).toFloat()
        box.background = bg
        box.elevation = dp(8).toFloat()

        val scroll = ScrollView(this)
        scroll.addView(content)
        box.addView(scroll)

        val p = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.CENTER

        // パネルの外側をタップしたら閉じる
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                closePanel()
                true
            } else {
                false
            }
        }

        wm.addView(box, p)
        panel = box
    }

    private fun closePanel() {
        val current = panel ?: return
        try {
            wm.removeView(current)
        } catch (e: Exception) {
        }
        panel = null
    }

    private fun showMenu() {
        // 長押しした時点の入力欄を覚えておく
        val target = findInputNode()

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(titleView("ワンタップデイト"))
        col.addView(menuItem("📅 カレンダーから日付を選ぶ") { showCalendar(target) })
        // テンプレート（1回だけ挿入）。最後に使ったものを先頭に出す
        val last = prefs().getInt(DateFormats.KEY_LAST_TPL, 0)
        val order = ArrayList<Int>()
        if (last >= 0 && last < DateFormats.templateCount) order.add(last)
        for (i in 0 until DateFormats.templateCount) {
            if (!order.contains(i)) order.add(i)
        }
        for (i in order) {
            col.addView(menuItem("📝 " + DateFormats.templateName(i) + " を挿入") {
                closePanel()
                insertTemplate(target, i)
            })
        }
        col.addView(menuItem("日付の形式を選ぶ（本文）") { showFormatPicker(false) })
        col.addView(menuItem("日付の形式を選ぶ（タイトル欄）") { showFormatPicker(true) })
        col.addView(menuItem("⚙ 設定画面を開く") {
            closePanel()
            openSettings()
        })
        col.addView(menuItem("ボタンを隠す（設定画面で再表示）") {
            closePanel()
            prefs().edit().putBoolean(DateFormats.KEY_VISIBLE, false).apply()
            setButtonVisible(false)
            toast("ボタンを隠しました。再表示は、設定画面かクイック設定から")
        })
        col.addView(menuItem("閉じる") { closePanel() })
        addPanel(col)
    }

    private fun openSettings() {
        try {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            toast("アプリ一覧から「ワンタップデイト」を開いてください")
        }
    }

    private fun showFormatPicker(forTitle: Boolean) {
        val key = if (forTitle) DateFormats.KEY_TITLE_FORMAT else DateFormats.KEY_FORMAT
        val current = prefs().getInt(key, if (forTitle) DateFormats.DEFAULT_TITLE else 0)
        val today = Date()

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(titleView(if (forTitle) "日付の形式（タイトル欄）" else "日付の形式（本文）"))
        for (i in 0 until DateFormats.count) {
            val mark = if (i == current) "✔ " else "　"
            col.addView(menuItem(mark + DateFormats.label(i, today)) {
                val editor = prefs().edit()
                editor.putInt(key, i)
                // タイトル欄の形式を選んだら、タイトル欄の見分けも自動でオンにする
                if (forTitle) editor.putBoolean(DateFormats.KEY_AUTO, true)
                editor.apply()
                toast("設定しました")
                closePanel()
            })
        }
        addPanel(col)
    }

    private fun showCalendar(target: AccessibilityNodeInfo?) {
        if (target == null) {
            toast("入力欄をタップしてから、長押ししてください")
            return
        }
        val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light)
        val picker = DatePicker(themed)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(titleView("入力する日付を選んでください"))
        col.addView(picker)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END
        row.addView(menuItem("キャンセル") { closePanel() })
        row.addView(menuItem("　入力する") {
            val cal = Calendar.getInstance()
            cal.set(picker.year, picker.month, picker.dayOfMonth)
            closePanel()
            val node = if (target.refresh()) target else findInputNode()
            if (node != null) {
                insertFor(node, cal.time)
            } else {
                toast("入力欄が見つかりません")
            }
        })
        col.addView(row)
        addPanel(col)
    }

    // ---------------------------------------------------------------
    // 入力欄への書き込み
    // ---------------------------------------------------------------
    private fun insertText(node: AccessibilityNodeInfo, date: String) {
        // ヒント文字（薄い案内文）は本文として扱わない
        val current = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        val start = node.textSelectionStart.let { if (it < 0) current.length else it }
        val end = node.textSelectionEnd.let { if (it < 0) current.length else it }
        val s = minOf(start, end).coerceIn(0, current.length)
        val e = maxOf(start, end).coerceIn(0, current.length)

        val newText = current.substring(0, s) + date + current.substring(e)

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val setText = Bundle()
        setText.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)
        node.refresh()
        val after = node.text?.toString() ?: ""
        if (!ok || !after.contains(date)) {
            // ChatGPT など、文字の直接入力が効かない入力欄は、貼り付けで入力する
            pasteViaClipboard(node, date)
            return
        }

        // カーソルを日付の後ろへ
        val sel = Bundle()
        val pos = s + date.length
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    private fun pasteViaClipboard(node: AccessibilityNodeInfo, date: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val old = cm.primaryClip
        cm.setPrimaryClip(ClipData.newPlainText("date", date))
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        // 貼り付け後に、元のクリップボードの内容へ戻す
        if (old != null) {
            handler.postDelayed({
                try {
                    cm.setPrimaryClip(old)
                } catch (e: Exception) {
                }
            }, 1000)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        closePanel()
        button?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        button = null
        super.onDestroy()
    }
}
