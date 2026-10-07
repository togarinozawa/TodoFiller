package dev.togar.dynasched.feedback

/**
 * 「作者に送る」の文面づくり。**画面に依存しない**ので、ここだけテストで固定する。
 *
 * 送り先は共有シート（LINEなど）。サーバーは使わない。
 * 何を・どの画面で・どの版で、を自動で添えるのは、友達に「どの版？」と
 * 聞き返さずに済ませるため。
 */
object Feedback {

    enum class Kind(val label: String, val hint: String) {
        BUG("不具合", "何をしたら、どうなりましたか？\n（本当はどうなってほしかったかも）"),
        WANT("欲しい機能", "どんな時に、何ができたら助かりますか？"),
        CHANGE("変えてほしい所", "どこを、どう変えたら使いやすくなりますか？");

        companion object {
            fun from(name: String): Kind = entries.firstOrNull { it.name == name } ?: BUG
        }
    }

    /** 自動で添える情報 */
    data class Env(
        val screen: String,
        val versionName: String,
        val versionCode: Int,
        val device: String,
        val android: String,
        val sentAt: String
    )

    data class Crash(val at: Long, val atText: String, val trace: String)

    /** 強制終了の記録を添えるのは不具合の時だけ。要望に長いスタックトレースは要らない */
    fun attachesCrash(kind: Kind): Boolean = kind == Kind.BUG

    fun canSend(text: String): Boolean = text.isNotBlank()

    fun compose(kind: Kind, text: String, env: Env, crash: Crash?): String {
        val sb = StringBuilder()
        sb.append("【スキマス】").append(kind.label).append('\n')
        sb.append(text.trim()).append('\n')
        sb.append("\n―― 自動で付けた情報 ――\n")
        for (line in envLines(env)) sb.append(line).append('\n')
        if (crash != null && attachesCrash(kind)) {
            sb.append("前回の強制終了: ").append(crash.atText).append('\n')
            sb.append(trimTrace(crash.trace)).append('\n')
        }
        return sb.toString().trimEnd() + "\n"
    }

    fun envLines(env: Env): List<String> = listOf(
        "画面: ${env.screen}",
        "版: v${env.versionName} (${env.versionCode})",
        "端末: ${env.device} / ${env.android}",
        "送信: ${env.sentAt}"
    )

    /**
     * トレースを縮める。LINEに貼るので全部は要らない。
     * 先頭の数行と、原因（Caused by）の行だけ残す。
     */
    fun trimTrace(trace: String, head: Int = 8): String {
        val lines = trace.trim().lines()
        if (lines.size <= head) return lines.joinToString("\n")
        val out = ArrayList(lines.take(head))
        val causes = lines.drop(head).filter { it.trimStart().startsWith("Caused by") }
        if (causes.isNotEmpty()) {
            out.add("…")
            out.addAll(causes)
        }
        return out.joinToString("\n") + "\n…（以下略）"
    }
}
