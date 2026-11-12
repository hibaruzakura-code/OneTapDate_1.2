package com.example.datebutton

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Date

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var preview: TextView

    private fun appPrefs() = getSharedPreferences(DateFormats.PREFS, MODE_PRIVATE)

    private fun makeSpinner(
        labels: List<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ): Spinner {
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        spinner.setSelection(selected.coerceIn(0, labels.size - 1))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                onPick(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return spinner
    }

    private fun heading(message: String): TextView {
        val view = TextView(this)
        view.text = message
        view.textSize = 16f
        view.setPadding(0, 40, 0, 8)
        return view
    }

    private fun refreshStatus() {
        val on = DateButtonService.instance != null
        status.text = if (on) {
            "ユーザー補助：オン（ボタンが使えます）"
        } else {
            "ユーザー補助：オフ（下のボタンから設定を開き、「ワンタップデイト」をオンにしてください）"
        }
        val p = appPrefs()
        val today = Date()
        val body = DateFormats.format(today, p.getInt(DateFormats.KEY_FORMAT, 0))
        val title = DateFormats.format(
            today, p.getInt(DateFormats.KEY_TITLE_FORMAT, DateFormats.DEFAULT_TITLE)
        )
        val auto = p.getBoolean(DateFormats.KEY_AUTO, false)
        preview.text = "いま入力される内容\n本文：" + body + "\nタイトル欄：" +
            (if (auto) title else "（切り替えオフ。本文と同じ形式）")
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = appPrefs()
        DateFormats.migrate(prefs)
        val today = Date()
        val labels = (0 until DateFormats.count).map { DateFormats.label(it, today) }

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(48, 48, 48, 48)

        val version = TextView(this)
        version.textSize = 18f
        version.text = "ワンタップデイト  v1.2  by Hibaruzakura"
        layout.addView(version)

        status = TextView(this)
        status.textSize = 14f
        status.setPadding(0, 16, 0, 16)
        layout.addView(status)

        val usage = TextView(this)
        usage.textSize = 15f
        usage.text = "【使い方】\n入力欄をタップしてから、青い「日付」ボタンを押します。\n・タップ: 今日の日付を入力\n・長押し: メニュー（カレンダー、日記テンプレートの挿入、日付の形式、設定画面）\n・ドラッグ: ボタンの位置を移動"
        layout.addView(usage)

        preview = TextView(this)
        preview.textSize = 14f
        preview.setPadding(0, 24, 0, 0)
        layout.addView(preview)

        // タイトル欄を見分けるチェック（先に作って、下の選択から参照する）
        val auto = CheckBox(this)
        auto.text = "タイトル欄を見分けて、形式を自動で切り替える"
        auto.isChecked = prefs.getBoolean(DateFormats.KEY_AUTO, false)
        auto.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(DateFormats.KEY_AUTO, checked).apply()
            refreshStatus()
        }

        // 本文用の形式
        layout.addView(heading("日付の形式（本文）"))
        layout.addView(
            makeSpinner(labels, prefs.getInt(DateFormats.KEY_FORMAT, 0)) { pos ->
                prefs.edit().putInt(DateFormats.KEY_FORMAT, pos).apply()
                refreshStatus()
            }
        )

        // タイトル欄用の形式
        layout.addView(heading("日付の形式（タイトル欄）"))
        layout.addView(
            makeSpinner(
                labels,
                prefs.getInt(DateFormats.KEY_TITLE_FORMAT, DateFormats.DEFAULT_TITLE)
            ) { pos ->
                val before = prefs.getInt(
                    DateFormats.KEY_TITLE_FORMAT, DateFormats.DEFAULT_TITLE
                )
                prefs.edit().putInt(DateFormats.KEY_TITLE_FORMAT, pos).apply()
                // 利用者がタイトル欄の形式を変えたときは、見分けも自動でオンにする
                if (pos != before && !auto.isChecked) auto.isChecked = true
                refreshStatus()
            }
        )
        layout.addView(auto)

        val note = TextView(this)
        note.textSize = 13f
        note.text = "オンのとき、入力欄の案内文や名前が「タイトル」の欄、または同じ画面でいちばん上の入力欄を、タイトル欄として扱います。アプリによっては見分けられないことがあります。"
        layout.addView(note)

        // ボタンの表示・非表示
        val visible = CheckBox(this)
        visible.text = "画面にボタンを表示する"
        visible.isChecked = prefs.getBoolean(DateFormats.KEY_VISIBLE, true)
        visible.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(DateFormats.KEY_VISIBLE, checked).apply()
            DateButtonService.instance?.setButtonVisible(checked)
        }
        layout.addView(visible)

        val openButton = Button(this)
        openButton.text = "ユーザー補助の設定を開く"
        openButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        layout.addView(openButton)

        val scroll = ScrollView(this)
        scroll.addView(layout)
        setContentView(scroll)
        refreshStatus()
    }
}
