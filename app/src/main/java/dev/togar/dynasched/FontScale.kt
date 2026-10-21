package dev.togar.dynasched

import android.content.Context
import android.content.res.Configuration

/**
 * アプリの中だけの文字の大きさ。
 *
 * 端末の文字サイズを大きくしている人だと、1行に収まらず縦に伸びて読みにくくなる。
 * 端末の設定はほかのアプリにも効くので、ここだけ小さくできるようにする。
 *
 * 各画面の attachBaseContext で [wrap] を通す。sp で書いた文字はすべて付いてくる。
 * ホーム画面のウィジェットは端末側が描くので、これは効かない。
 */
object FontScale {

    class Choice(val percent: Int, val label: String)

    val CHOICES = listOf(
        Choice(85, "小さめ"),
        Choice(92, "少し小さめ"),
        Choice(100, "標準"),
        Choice(110, "大きめ")
    )

    fun label(ctx: Context): String {
        val p = Prefs.fontPercent(ctx)
        return CHOICES.firstOrNull { it.percent == p }?.label ?: "標準"
    }

    fun wrap(base: Context): Context {
        val percent = Prefs.fontPercent(base)
        if (percent == 100) return base
        val conf = Configuration(base.resources.configuration)
        conf.fontScale *= percent / 100f
        return base.createConfigurationContext(conf)
    }
}
