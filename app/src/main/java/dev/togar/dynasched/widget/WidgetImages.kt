package dev.togar.dynasched.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import dev.togar.dynasched.R
import dev.togar.dynasched.ui.Stats
import java.time.LocalDate

/**
 * ウィジェットに描く絵。RemoteViews は自前の View を置けないので、画像にして渡す。
 */
object RingImage {
    private const val SIZE_DP = 74f
    private const val STROKE_DP = 8f

    /** 目標までの円。真ん中に大きな数 */
    fun render(ctx: Context, done: Int, goal: Int, big: Int, reached: Boolean): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val size = (SIZE_DP * d).toInt().coerceAtLeast(1)
        val stroke = STROKE_DP * d
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.density = ctx.resources.displayMetrics.densityDpi
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        val inset = stroke / 2 + 1
        val r = RectF(inset, inset, size - inset, size - inset)
        p.color = ContextCompat.getColor(ctx, R.color.surface_variant)
        c.drawArc(r, 0f, 360f, false, p)
        val frac = if (goal <= 0) 0f else (done.toFloat() / goal).coerceIn(0f, 1f)
        if (frac > 0f) {
            p.color = ContextCompat.getColor(ctx, if (reached) R.color.accent_goal else R.color.primary)
            c.drawArc(r, -90f, frac * 360f, false, p)
        }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(ctx, if (reached) R.color.accent_goal else R.color.on_bg)
            textAlign = Paint.Align.CENTER
            textSize = 26 * d
            typeface = Typeface.DEFAULT_BOLD
        }
        val mid = size / 2f
        c.drawText(big.toString(), mid, mid - (t.descent() + t.ascent()) / 2, t)
        return bmp
    }
}

object ChainImage {
    private const val CELL_DP = 13f
    private const val GAP_DP = 3f

    /** 4週間の升目。片付けた日は色、3件以上は濃い色、今日まだなら薄い灰 */
    fun render(ctx: Context, days: List<Stats.DayCount>, today: LocalDate): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val cell = CELL_DP * d
        val gap = GAP_DP * d
        val rows = (days.size + 6) / 7
        val bmp = Bitmap.createBitmap(
            (7 * cell + 6 * gap).toInt().coerceAtLeast(1),
            (rows * cell + (rows - 1) * gap).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        bmp.density = ctx.resources.displayMetrics.densityDpi
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for ((i, day) in days.withIndex()) {
            val x = (i % 7) * (cell + gap)
            val y = (i / 7) * (cell + gap)
            val color = when {
                day.done >= 3 -> R.color.accent_goal
                day.done > 0 -> R.color.primary
                day.date == today -> R.color.on_bg_dim
                else -> R.color.surface_variant
            }
            p.color = ContextCompat.getColor(ctx, color)
            p.alpha = if (day.done != 0 || day.date == today) 255 else 128
            c.drawRoundRect(RectF(x, y, x + cell, y + cell), gap, gap, p)
        }
        return bmp
    }
}
