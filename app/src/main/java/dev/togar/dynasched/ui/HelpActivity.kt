package dev.togar.dynasched.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.R

/**
 * 使い方の説明。横スワイプで1枚ずつ読む。
 *
 * ページ送りは RecyclerView + PagerSnapHelper で作ってある。
 * ViewPager2 を足せば数行短くなるが、そのためだけに依存を1つ増やしたくない
 * （このアプリは実行時の外部ライブラリを持たない方針）。
 */
class HelpActivity : AppCompatActivity() {

    private var pages: List<HelpPage> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        pages = Help.pages(Prefs.ownerMode(this))

        val pager = findViewById<RecyclerView>(R.id.helpPager)
        val dots = findViewById<TextView>(R.id.helpDots)
        val closeBtn = findViewById<Button>(R.id.helpClose)

        val lm = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        pager.layoutManager = lm
        pager.adapter = PageAdapter()
        PagerSnapHelper().attachToRecyclerView(pager)

        fun refreshDots() {
            val i = lm.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0)
            dots.text = pages.indices.joinToString(" ") { if (it == i) "●" else "○" }
            closeBtn.text = if (i == pages.lastIndex) "はじめる" else "閉じる"
        }
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = refreshDots()
        })
        refreshDots()

        closeBtn.setOnClickListener { finish() }
        // 一度見たら初回案内は出さない。設定からはいつでも開ける
        Prefs.setHelpShown(this, true)
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val v = layoutInflater.inflate(R.layout.item_help_page, parent, false)
            // 1ページ＝画面幅ちょうど。これを外すと2枚が中途半端に見える
            v.layoutParams = RecyclerView.LayoutParams(
                parent.width, ViewGroup.LayoutParams.MATCH_PARENT
            )
            return PageVH(v)
        }

        override fun getItemCount(): Int = pages.size

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            val page = pages[position]
            holder.title.text = page.title
            holder.body.text = Emphasis.render(page.body)
            holder.step.text = "${position + 1} / ${pages.size}"
        }
    }

    private class PageVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.helpTitle)
        val body: TextView = v.findViewById(R.id.helpBody)
        val step: TextView = v.findViewById(R.id.helpStep)
    }
}
