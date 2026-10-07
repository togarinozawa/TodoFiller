package dev.togar.dynasched.calendar

/**
 * 端末のカレンダーから読み取った「配置の材料」。
 *
 * 日時は **タイムゾーン無しのローカル文字列** `yyyy-MM-dd'T'HH:mm:ss` で持つ。
 * サーバー版と同じ形にしてあるのは、スケジューラのロジック（文字列を切って
 * 日内の分に直す処理）をそのまま移せるようにするため。形を変えると移植の
 * 難易度が跳ね上がるので、ここは意図的に揃えている。
 */

/** 末尾に場所の印が付いた予定＝作業できる枠 */
data class AvailabilityWindow(
    val start: String,
    val end: String,
    val location: String,   // 場所のid（"home" | "out" | 設定で足した場所）
    val title: String,
    val eventId: Long
)

/** タグ無しの予定＝埋まっている時間 */
data class BusyBlock(
    val start: String,
    val end: String,
    val title: String,
    val eventId: Long
)

/** 「テスト期間」で始まる予定。この期間は定期テストONの教材だけを配置する */
data class ExamPeriod(
    val startDate: String,  // yyyy-MM-dd
    val endDate: String,    // yyyy-MM-dd（両端を含む）
    val title: String,
    val eventId: Long
)

/** 1回の読み取り結果 */
data class CalendarSnapshot(
    val windows: List<AvailabilityWindow>,
    val busy: List<BusyBlock>,
    val examPeriods: List<ExamPeriod>,
    val calendarName: String,
    val skippedGenerated: Int   // 末尾「%」で読み飛ばした自動生成予定の数
)

/** 端末上のカレンダー1つ */
data class DeviceCalendar(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val isPrimary: Boolean,
    /**
     * 予定を足したり消したりできるか（CALENDAR_ACCESS_LEVEL が寄稿者以上）。
     * 祝日・誕生日・購読カレンダーは読み取り専用で、書いても**黙って失敗する**。
     * ここを見ずに選ぶと「予定が消えない・増えない」という形でしか気付けない。
     */
    val canWrite: Boolean = true
)
