package com.example.datebutton

import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateFormats {
    const val PREFS = "datebutton"
    const val KEY_FORMAT = "format_index"            // 本文用の形式
    const val KEY_TITLE_FORMAT = "title_format_index" // タイトル欄用の形式
    const val KEY_AUTO = "auto_switch"               // タイトル欄を見分けて切り替えるか
    const val KEY_VISIBLE = "visible"
    const val KEY_LAST_TPL = "last_template"         // 最後に使ったテンプレート
    const val DEFAULT_TITLE = 0                      // 本文と同じ 2026/11/08

    private class Fmt(val group: String, val pattern: String, val era: Boolean = false)

    private class Tpl(val name: String, val build: (Date) -> Pair<String, String>)

    private fun sdf(pattern: String, date: Date, locale: Locale = Locale.JAPAN): String =
        SimpleDateFormat(pattern, locale).format(date)

    // ---------------------------------------------------------------
    // 日付の形式（タップで入力されるもの）
    // 並び順を変えると、保存済みの選択がずれるので、追加は末尾に行うこと
    // ---------------------------------------------------------------
    private val formats: List<Fmt> = listOf(
        Fmt("日付", "yyyy/MM/dd"),            // 0
        Fmt("日付", "yyyy-MM-dd"),            // 1
        Fmt("日付", "yyyyMMdd"),              // 2
        Fmt("日付", "yyyy年M月d日"),           // 3
        Fmt("曜日", "M月d日(E)"),             // 4
        Fmt("和暦", "GGGGy年M月d日", true),    // 5
        Fmt("日付", "yyMMdd"),                // 6  261108
        Fmt("曜日", "yyyy年M月d日（E）"),       // 7  全角かっこ
        Fmt("曜日", "M月d日（E）"),            // 8
        Fmt("曜日", "yyMMdd E"),              // 9  261108 土
        Fmt("区切り", "yyMMdd "),             // 10 261108 ＋空白
        Fmt("区切り", "yyMMdd_")              // 11 261108 ＋_
    )

    val count: Int get() = formats.size

    fun format(date: Date, index: Int): String {
        val f = formats[index.coerceIn(0, formats.size - 1)]
        return sdf(f.pattern, date, if (f.era) Locale("ja", "JP", "JP") else Locale.JAPAN)
    }

    // 設定画面の一覧に出す名前
    fun label(index: Int, today: Date): String {
        val f = formats[index.coerceIn(0, formats.size - 1)]
        val text = format(today, index)
        val body = if (text.endsWith(" ")) text.trimEnd() + "（＋空白）" else text
        return f.group + "｜" + body
    }

    // 以前の版でテンプレートを「形式」として選んでいた場合は、日付の形式に戻す
    fun migrate(prefs: SharedPreferences) {
        val editor = prefs.edit()
        val body = prefs.getInt(KEY_FORMAT, 0)
        if (body < 0 || body >= formats.size) editor.putInt(KEY_FORMAT, 0)
        val title = prefs.getInt(KEY_TITLE_FORMAT, DEFAULT_TITLE)
        if (title < 0 || title >= formats.size) editor.putInt(KEY_TITLE_FORMAT, DEFAULT_TITLE)
        editor.apply()
    }

    // ---------------------------------------------------------------
    // テンプレート（長押しメニューから、1回だけ挿入するもの）
    // first = タイトルに入れる日付の行、second = 本文に入れる内容
    // ---------------------------------------------------------------
    private val templates: List<Tpl> = listOf(
        Tpl("日記1（シンプル）") { d ->
            Pair(
                sdf("yyMMdd E", d),
                listOf("■ できごと", "", "■ 気づき", "", "■ 明日").joinToString("\n")
            )
        },
        Tpl("日記2（絵文字）") { d ->
            Pair(
                sdf("yyyy年M月d日（E）", d),
                listOf(
                    "☀️ 天気：",
                    "🟠 できごと：",
                    "💭 ひとこと：",
                    "━━━━━━"
                ).joinToString("\n")
            )
        },
        Tpl("日記3（項目別）") { d ->
            val eng = sdf("EEE", d, Locale.ENGLISH).uppercase()
            Pair(
                sdf("yyyy-MM-dd", d) + " " + eng,
                listOf(
                    "【天気】",
                    "【予定】",
                    "【記録】",
                    "【振り返り】",
                    "────────",
                    "#日記"
                ).joinToString("\n")
            )
        }
    )

    val templateCount: Int get() = templates.size

    fun templateName(index: Int): String =
        templates[index.coerceIn(0, templates.size - 1)].name

    fun template(date: Date, index: Int): Pair<String, String> =
        templates[index.coerceIn(0, templates.size - 1)].build(date)
}
