package dev.togar.dynasched.sync

/**
 * NotionのURLからページIDを取り出す。
 *
 * 貼られる形が何通りもある（題名付き・ワークスペース名付き・`?v=` 付き・
 * 共有リンクの `?pvs=` 付き・アプリからの `notion://`）。**どれで貼られても
 * 同じIDになる**ようにして、「URLが読めません」で詰まらせない。
 */
object NotionLink {

    private val HEX32 = Regex("[0-9a-fA-F]{32}")
    private val DASHED = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )

    /**
     * 読めれば区切り付きのUUIDを返す。読めなければ null。
     *
     * **末尾から探す。**ワークスペース名や題名にも16進が並ぶことがあり、
     * 手前から取ると別のIDを掴む。
     */
    fun pageIdFrom(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null

        // 問い合わせ文字列は落とす。?v= のビューIDを本体と間違えないため
        val body = raw.substringBefore('?').substringBefore('#')

        DASHED.findAll(body).lastOrNull()?.let { return it.value.lowercase() }
        HEX32.findAll(body).lastOrNull()?.let { return dash(it.value.lowercase()) }
        return null
    }

    private fun dash(hex: String): String = buildString {
        append(hex, 0, 8); append('-')
        append(hex, 8, 12); append('-')
        append(hex, 12, 16); append('-')
        append(hex, 16, 20); append('-')
        append(hex, 20, 32)
    }
}
