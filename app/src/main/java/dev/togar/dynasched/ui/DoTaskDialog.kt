package dev.togar.dynasched.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.SuggestItem
import dev.togar.dynasched.data.NowTasks
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.widget.SuggestWidgetProvider

/**
 * 外（ドパチルの警告画面）でタスクを押された時に出す「これをやる」。
 *
 * 完了はスキマスの中でだけ付ける。ドパチルは読むだけにしてあるので、
 * 押されたらこちらへ来てもらい、ここで「やる」か「片付けた」を選ぶ。
 */
object DoTaskDialog {

    const val ACTION = "dev.togar.dynasched.action.DO_TASK"
    const val EXTRA_KIND = "kind"
    const val EXTRA_ID = "id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_MINUTES = "minutes"

    /** この画面を開く Intent か。違えば何もしない */
    fun handle(activity: Activity, intent: Intent?): Boolean {
        if (intent?.action != ACTION) return false
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id <= 0) return false
        val kind = NowTasks.internalKind(intent.getStringExtra(EXTRA_KIND))
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 30).coerceAtLeast(5)
        // 一度出したら忘れる。画面を回したり戻ったりした時に、また出てこないように
        intent.action = null
        show(activity, SuggestItem(kind, id, title, minutes, 5, 0))
        return true
    }

    private fun show(activity: Activity, given: SuggestItem) {
        val ctx = activity.applicationContext
        if (given.kind != "hobby") {
            ask(activity, given, canComplete = false)
            return
        }
        // 警告画面で見てから押すまでに片付いている・消えていることがある
        Api.async({
            val all = Repo.current(ctx).getHobby(ctx)
            all.firstOrNull { it.id == given.id } to NowTasks.parentPath(all, given.id)
        }, { (item, parent) ->
            if (activity.isFinishing) return@async
            when {
                item == null -> Toast.makeText(activity, "そのタスクはもうありません", Toast.LENGTH_SHORT).show()
                item.isCompleted -> Toast.makeText(activity, "「${item.name}」はもう片付いています", Toast.LENGTH_SHORT).show()
                else -> ask(activity, given.copy(title = item.name, minutes = item.durationMinutes,
                    priority = item.priority), canComplete = true, parent = parent, note = item.note)
            }
        }, { })
    }

    private fun ask(
        activity: Activity, item: SuggestItem, canComplete: Boolean, parent: String = "", note: String = ""
    ) {
        val msg = buildString {
            if (parent.isNotEmpty()) append(parent).append(" ›\n")
            append(item.title).append("（").append(item.minutes).append("分）")
            if (note.isNotBlank()) append("\n\n📝 ").append(note.trim())
        }
        val b = AlertDialog.Builder(activity)
            .setTitle("いまやること")
            .setMessage(msg)
            .setPositiveButton("やる") { _, _ ->
                FreeTimeDialog.decide(activity, item, NowTasks.availableMinutes(activity).coerceAtLeast(item.minutes))
            }
            .setNegativeButton("あとで", null)
        if (canComplete) b.setNeutralButton("もう片付けた") { _, _ -> complete(activity, item) }
        b.show()
    }

    private fun complete(activity: Activity, item: SuggestItem) {
        val ctx = activity.applicationContext
        Api.async({ Repo.current(ctx).setHobbyCompleted(ctx, item.id, true) }, {
            SuggestWidgetProvider.updateAll(ctx)
            Toast.makeText(ctx, "「${item.title}」を片付けました", Toast.LENGTH_SHORT).show()
        }, { e -> Toast.makeText(ctx, Api.friendlyMessage(e), Toast.LENGTH_LONG).show() })
    }
}
