package dev.togar.dynasched.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import dev.togar.dynasched.R
import dev.togar.dynasched.calendar.Slot
import dev.togar.dynasched.calendar.Slots
import java.time.LocalDate

/**
 * カレンダー画面の時間割。**縦が時間、横が日付**（[horizontal]）。縦に日を積む見せ方もできる。
 *
 * 触り方は全部「長押ししてから引く」に揃えてある。すぐ動き出すと、
 * 画面をスクロールしたいだけの指で枠や予定が動いてしまうため。
 * - 空いている所を長押しして引く → 枠（作業できる時間）を作る（[onCreateSlot]）
 * - 枠や予定を長押しして引く → 動かす。下端をつかめば長さを変える（[onChangeBlock]）
 * - 下のタスク置き場から長押しで運んで落とす → その時刻に置く（[onDropTask]）
 * - 軽く押す → [onTapBlock] / [onTapEmpty]
 *
 * 時刻は0時からの分で受け渡しし、[Slots.STEP] 分刻みに丸める。
 */
class TimeGridView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    /** 描く物の種類。重なった時は後ろの種類ほど上（タスク > 予定 > 枠） */
    enum class Kind { SLOT, BUSY, TASK }

    data class Block(
        val id: Long,
        val kind: Kind,
        val dayIndex: Int,
        val startMin: Int,
        val endMin: Int,
        val label: String,
        val location: String = "",
        val isStudy: Boolean = false,
        val isManual: Boolean = false
    )

    /** タスク置き場から運ぶ時の中身（DragEvent の localState） */
    data class DragPayload(val taskId: Long, val durationMinutes: Int, val label: String)

    private enum class Mode { NONE, CREATE, MOVE, RESIZE }

    var days: List<LocalDate> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }

    var horizontal = true
        set(v) { field = v; requestLayout(); invalidate() }

    var blocks: List<Block> = emptyList()
        set(v) { field = v; invalidate() }

    var startHour = 6
        set(v) { field = v.coerceIn(0, 23); requestLayout(); invalidate() }

    var endHour = 24
        set(v) { field = v.coerceIn(1, 24); requestLayout(); invalidate() }

    /** (日, 開始分, 終了分) */
    var onCreateSlot: ((Int, Int, Int) -> Unit)? = null
    /** (動かした物, 日, 開始分, 終了分) */
    var onChangeBlock: ((Block, Int, Int, Int) -> Unit)? = null
    var onTapBlock: ((Block) -> Unit)? = null
    /** (日, 分) */
    var onTapEmpty: ((Int, Int) -> Unit)? = null
    /** (タスクID, 日, 開始分) */
    var onDropTask: ((Long, Int, Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val gutter = 44 * d
    private val headerH = 26 * d
    private val perMin = 1.1f * d
    /** 下端のこの幅をつかむと長さを変える */
    private val edge = 18 * d
    private val radius = 6 * d
    private val slop = 12 * d
    private val bandGap = 12 * d

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * d }

    private val colPrimary = ContextCompat.getColor(ctx, R.color.primary)
    private val colAccent = ContextCompat.getColor(ctx, R.color.accent_goal)
    private val colSurface = ContextCompat.getColor(ctx, R.color.surface)
    private val colVariant = ContextCompat.getColor(ctx, R.color.surface_variant)
    private val colOn = ContextCompat.getColor(ctx, R.color.on_bg)
    private val colDim = ContextCompat.getColor(ctx, R.color.on_bg_dim)

    // 指の状態
    private var mode = Mode.NONE
    /** 長押しが成立したか。成立するまでは動かさない */
    private var armed = false
    private val holder = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var grabbed: Block? = null
    private var dragDay = 0
    private var dragStart = 0
    private var dragEnd = 0
    /** つかんだ点が予定の頭から何分下か。動かしても指と予定の位置関係を保つ */
    private var grabOffset = 0

    // 運んでいる途中のタスクの落とし先
    private var dropDay = -1
    private var dropStart = 0
    private var dropDuration = 30
    private var dropLabel = ""

    init {
        setOnDragListener { _, e -> onDrag(e) }
    }

    private fun onDrag(e: DragEvent): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return e.localState is DragPayload
            DragEvent.ACTION_DRAG_LOCATION -> {
                val p = e.localState as? DragPayload ?: return true
                val pos = at(e.x, e.y) ?: return true
                dropDay = pos.first
                dropStart = Slots.snap(pos.second)
                dropDuration = p.durationMinutes
                dropLabel = p.label
                invalidate()
            }
            DragEvent.ACTION_DROP -> {
                val p = e.localState as? DragPayload
                val pos = at(e.x, e.y)
                dropDay = -1
                if (p != null && pos != null) onDropTask?.invoke(p.taskId, pos.first, Slots.snap(pos.second))
                invalidate()
            }
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                dropDay = -1
                invalidate()
            }
        }
        return true
    }

    // ---- 寸法 ----

    private val minutes: Int get() = (endHour - startHour) * 60

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val h = if (horizontal) headerH + minutes * perMin
            else days.size.coerceAtLeast(1) * (headerH + minutes * perMin + bandGap)
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h.toInt())
    }

    private fun columnWidth(): Float =
        if (horizontal) (width - gutter) / days.size.coerceAtLeast(1) else width - gutter

    /** 縦に積む時の、その日の帯の上端 */
    private fun bandTop(dayIndex: Int): Float =
        if (horizontal) 0f else dayIndex * (headerH + minutes * perMin + bandGap)

    private fun yOf(dayIndex: Int, min: Int): Float =
        bandTop(dayIndex) + headerH + (min - startHour * 60) * perMin

    private fun xOf(dayIndex: Int): Float =
        if (horizontal) gutter + dayIndex * columnWidth() else gutter

    private fun rectFor(dayIndex: Int, startMin: Int, endMin: Int): RectF {
        val x = xOf(dayIndex)
        return RectF(
            x + 2 * d, yOf(dayIndex, startMin),
            x + columnWidth() - 2 * d, yOf(dayIndex, maxOf(endMin, startMin + 15))
        )
    }

    /** 画面上の点 → (日, 分)。時間割の外なら null */
    private fun at(x: Float, y: Float): Pair<Int, Int>? {
        if (days.isEmpty() || x < gutter) return null
        val day: Int
        val inBand: Float
        if (horizontal) {
            day = ((x - gutter) / columnWidth()).toInt()
            inBand = y - headerH
        } else {
            day = (y / (headerH + minutes * perMin + bandGap)).toInt()
            inBand = y - bandTop(day) - headerH
        }
        if (day !in days.indices) return null
        val min = (startHour * 60 + (inBand / perMin).toInt()).coerceIn(startHour * 60, endHour * 60)
        return day to min
    }

    /** その点にある物。重なっていれば上に描いている方（タスク > 予定 > 枠） */
    private fun blockAt(x: Float, y: Float): Block? =
        blocks.filter { rectFor(it.dayIndex, it.startMin, it.endMin).contains(x, y) }
            .maxByOrNull { it.kind.ordinal }

    // ---- 描く ----

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        drawGrid(canvas)
        for (b in blocks.sortedBy { it.kind.ordinal }) drawBlock(canvas, b)
        drawGhost(canvas)
        drawDropPreview(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        val today = LocalDate.now()
        for (i in days.indices) {
            val isToday = days[i] == today
            text.color = if (isToday) colAccent else colDim
            text.typeface = if (isToday) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(dayLabel(days[i]), xOf(i) + 4 * d, bandTop(i) + headerH - 8 * d, text)
            if (!horizontal) drawHours(canvas, i)
        }
        if (horizontal) drawHours(canvas, 0)
    }

    private fun drawHours(canvas: Canvas, dayIndex: Int) {
        paint.style = Paint.Style.FILL
        text.color = colDim
        text.typeface = Typeface.DEFAULT
        for (h in startHour..endHour) {
            val y = yOf(dayIndex, h * 60)
            paint.color = colVariant
            canvas.drawRect(gutter, y, width.toFloat(), y + 1f, paint)
            if (h < endHour) canvas.drawText(String.format("%02d:00", h), 6 * d, y + 12 * d, text)
        }
    }

    private fun drawBlock(canvas: Canvas, b: Block) {
        val r = rectFor(b.dayIndex, b.startMin, b.endMin)
        when (b.kind) {
            // 枠は薄い面と縁だけ。中に予定が乗るので塗りつぶさない
            Kind.SLOT -> {
                paint.color = colPrimary
                paint.alpha = 38
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(r, radius, radius, paint)
                paint.alpha = 140
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f * d
                canvas.drawRoundRect(r, radius, radius, paint)
                paint.alpha = 255
                text.color = colPrimary
                text.typeface = Typeface.DEFAULT
            }
            Kind.BUSY -> {
                paint.style = Paint.Style.FILL
                paint.color = colVariant
                canvas.drawRoundRect(r, radius, radius, paint)
                text.color = colDim
                text.typeface = Typeface.DEFAULT
            }
            Kind.TASK -> {
                paint.style = Paint.Style.FILL
                paint.color = if (b.isStudy) colAccent else colPrimary
                canvas.drawRoundRect(r, radius, radius, paint)
                // 手で置いた物は左に細い印。組み直しで動かないことが見て分かるように
                if (b.isManual) {
                    paint.color = colSurface
                    canvas.drawRect(r.left, r.top + 2 * d, r.left + 3 * d, r.bottom - 2 * d, paint)
                }
                text.color = colSurface
                text.typeface = Typeface.DEFAULT_BOLD
            }
        }
        clipText(canvas, b.label, r)
    }

    /** 長押しして引いている間の形 */
    private fun drawGhost(canvas: Canvas) {
        if (mode == Mode.NONE || !armed) return
        val r = rectFor(dragDay, dragStart, dragEnd)
        paint.style = Paint.Style.FILL
        paint.color = colAccent
        paint.alpha = 90
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.alpha = 255
        text.color = colOn
        text.typeface = Typeface.DEFAULT_BOLD
        clipText(canvas, "${Slot.hhmm(dragStart)}-${Slot.hhmm(dragEnd)}", r)
    }

    /** タスクを運んでいる間の落とし先 */
    private fun drawDropPreview(canvas: Canvas) {
        if (dropDay !in days.indices) return
        val r = rectFor(dropDay, dropStart, dropStart + maxOf(15, dropDuration))
        paint.style = Paint.Style.FILL
        paint.color = colAccent
        paint.alpha = 110
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.alpha = 255
        text.color = colOn
        text.typeface = Typeface.DEFAULT_BOLD
        clipText(canvas, dropLabel, r)
    }

    private fun clipText(canvas: Canvas, label: String, r: RectF) {
        if (r.height() < 12 * d) return
        canvas.save()
        canvas.clipRect(r)
        canvas.drawText(label, r.left + 6 * d, r.top + 13 * d, text)
        canvas.restore()
    }

    private fun dayLabel(date: LocalDate): String =
        "${date.monthValue}/${date.dayOfMonth}(${WEEKDAYS[date.dayOfWeek.value - 1]})"

    // ---- 触る ----

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                armed = false
                prepare(e.x, e.y)
                holder.postDelayed({ arm() }, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (armed) {
                    update(e.x, e.y)
                    invalidate()
                } else if (Math.abs(e.x - downX) > slop || Math.abs(e.y - downY) > slop) {
                    // 長押しが成立する前に動いた指はスクロール。ここでは何もしない
                    holder.removeCallbacksAndMessages(null)
                    mode = Mode.NONE
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                holder.removeCallbacksAndMessages(null)
                parent?.requestDisallowInterceptTouchEvent(false)
                finish(e.x, e.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                holder.removeCallbacksAndMessages(null)
                parent?.requestDisallowInterceptTouchEvent(false)
                mode = Mode.NONE
                armed = false
                grabbed = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** 長押しが成立した。ここからは指をスクロールに取られないようにする */
    private fun arm() {
        if (mode == Mode.NONE) return
        armed = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    /** 指を置いた所で、何をするかを決めておく */
    private fun prepare(x: Float, y: Float) {
        val b = blockAt(x, y)
        val pos = at(x, y)
        grabbed = b
        when {
            // 予定（カレンダーの物）は動かせない。動かすならカレンダーアプリで
            b != null && b.kind != Kind.BUSY -> {
                val r = rectFor(b.dayIndex, b.startMin, b.endMin)
                dragDay = b.dayIndex
                dragStart = b.startMin
                dragEnd = b.endMin
                mode = if (y > r.bottom - edge) Mode.RESIZE else Mode.MOVE
                grabOffset = (pos?.second ?: b.startMin) - b.startMin
            }
            b == null && pos != null -> {
                dragDay = pos.first
                dragStart = Slots.snap(pos.second)
                dragEnd = dragStart + 30
                mode = Mode.CREATE
            }
            else -> mode = Mode.NONE
        }
    }

    private fun update(x: Float, y: Float) {
        val pos = at(x, y) ?: return
        when (mode) {
            Mode.CREATE, Mode.RESIZE -> dragEnd = maxOf(Slots.snap(pos.second), dragStart + Slots.STEP)
            Mode.MOVE -> {
                val len = dragEnd - dragStart
                dragDay = pos.first
                dragStart = Slots.snap(pos.second - grabOffset).coerceIn(startHour * 60, endHour * 60 - len)
                dragEnd = dragStart + len
            }
            Mode.NONE -> Unit
        }
    }

    private fun finish(x: Float, y: Float) {
        val b = grabbed
        val wasArmed = armed
        val m = mode
        mode = Mode.NONE
        armed = false
        grabbed = null
        if (!wasArmed) {
            performClick()
            val hit = blockAt(x, y)
            if (hit != null) onTapBlock?.invoke(hit)
            else at(x, y)?.let { (day, min) -> onTapEmpty?.invoke(day, Slots.snap(min)) }
            return
        }
        when (m) {
            Mode.CREATE -> onCreateSlot?.invoke(dragDay, dragStart, dragEnd)
            Mode.MOVE, Mode.RESIZE -> if (b != null) onChangeBlock?.invoke(b, dragDay, dragStart, dragEnd)
            Mode.NONE -> Unit
        }
    }

    companion object {
        private const val LONG_PRESS_MS = 280L
        private val WEEKDAYS = arrayOf("月", "火", "水", "木", "金", "土", "日")
    }
}
