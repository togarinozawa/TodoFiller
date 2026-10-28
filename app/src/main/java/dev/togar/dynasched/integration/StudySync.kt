package dev.togar.dynasched.integration

import android.content.Context
import android.content.Intent
import dev.togar.dynasched.api.ScheduledEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * ドパチル（com.dopachiru）へ「いま学習中の予定」を渡す。
 * 仕様は dopachiru/INTEGRATION.md。要点だけ再掲する:
 *
 * - **全置換**。送った配列がそのまま向こうの全予定になる。解除専用の経路は無い
 *   （経路が2本あると「終了だけ届かなかった」状態が生まれるため）
 * - 各窓は自分の終了時刻を持つ。**こちらのプロセスが死んでも時刻が来れば勝手に解ける**
 * - 時刻は **UTCのエポック「秒」**（ミリ秒ではない）
 * - `setPackage` は必須。付けないとエラーも出ずに届かない
 * - 通信は一切しない。ドパチルには INTERNET 権限が無く、それ自体が抜け道封じになっている
 *
 * ドパチルが入っていなければ、送っても何も起きない（害は無い）。
 */
object StudySync {

    private const val TARGET_PACKAGE = "com.dopachiru"
    private const val ACTION = "com.dopachiru.action.SYNC_STUDY_WINDOWS"
    private const val EXTRA_WINDOWS = "windows"
    private const val PAYLOAD_VERSION = 1

    /**
     * 予定をドパチルへ全置換で送る。
     *
     * 送る対象は **未完了かつ未終了の予定**。教材のコマ（kind "study"）とタスク（kind "hobby"）の両方。
     * - 以前は教材だけだった。タスクの時間も作業に要る物以外を閉めたい、と決めたので入れた（2026-10）
     * - 完了済みを外すことで、早期完了がそのまま「制限解除」になる
     *   （INTEGRATION.md が言う「endAtを現在時刻にして送り直す」と同じ効果で、より単純）。
     *   タスクは一覧で片付けた時点で予定も済み扱いになる（[dev.togar.dynasched.data.LocalRepo.getSchedule]）
     * - 完了・中断はスキマスでやる。ドパチルから返してもらう経路は作らない
     */
    fun send(ctx: Context, events: List<ScheduledEvent>) {
        // Android 11 以下では下のブロードキャストが届かない。読みに来てもらう合図も一緒に出す
        DopaSignal.changed(ctx)
        try {
            val nowSec = System.currentTimeMillis() / 1000
            val arr = JSONArray()
            for (ev in events) {
                if (ev.isCompleted) continue
                if (ev.eventType != "study" && ev.eventType != "hobby") continue
                val startSec = (ev.startAsDate()?.time ?: continue) / 1000
                val endSec = (ev.endAsDate()?.time ?: continue) / 1000
                if (endSec <= startSec) continue   // 向こうで捨てられる窓は送らない
                if (endSec <= nowSec) continue     // 既に終わった窓は送っても意味が無い
                arr.put(
                    JSONObject()
                        .put("id", "sched_${ev.id}")
                        .put("startAt", startSec)
                        .put("endAt", endSec)
                        .put("title", ev.title)
                        .put("goalId", ev.materialId?.let { "material_$it" } ?: "")
                        .put("kind", ev.eventType)
                )
            }

            val payload = JSONObject()
                .put("version", PAYLOAD_VERSION)
                .put("windows", arr)
                .toString()

            val intent = Intent(ACTION).apply {
                setPackage(TARGET_PACKAGE)
                putExtra(EXTRA_WINDOWS, payload)
            }
            ctx.sendBroadcast(intent)
        } catch (e: Exception) {
            // 連携の失敗で本体を壊さない。ドパチル未導入もここに来る
        }
    }
}
