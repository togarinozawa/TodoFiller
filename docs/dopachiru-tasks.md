# ドパチルの警告画面に「いまやれるタスク」を出す

スキマス → ドパチル の連携です。ドパチルが警告画面（ブロック・使い過ぎなど）を出す瞬間に、
スキマスへ「いまやれるタスク」を読みに来て、画面に1〜3件出します。
「スマホを開いた瞬間に、代わりにやることを言われる」のが目的です。

- スキマス側: [`NowTasksProvider`](../app/src/main/java/dev/togar/dynasched/integration/NowTasksProvider.kt)（実装済み）
- 相手の確認: [`TrustedCallers`](../app/src/main/java/dev/togar/dynasched/integration/TrustedCallers.kt)
- 押された時: [`DoTaskDialog`](../app/src/main/java/dev/togar/dynasched/ui/DoTaskDialog.kt)
- **ドパチル側はこれから**（この文書の「ドパチル側でやること」）

---

## 決めたこと

**ドパチルが読みに来る。スキマスからは送らない。** 送る形だと、片付けたタスクや
前の場所の候補が次に送るまで残ります。開いた瞬間に読めば、いまの場所・空き時間・完了が
そのまま反映されます。

**読むだけ。** 完了はスキマスの中でだけ付けます。両方で付けられると食い違うので、
ドパチルで押されたらスキマスを開いて、そこで「やる」「もう片付けた」を選んでもらいます。

**制限の判定には一切響かない。** スキマスが入っていない・落ちている・答えない時は、
タスクを出さないだけです。ドパチルの「通信しない・他に頼らない」方針はそのまま守れます。

**署名権限は使わない。** スキマスはリリース鍵（`CN=skimas`）、ドパチルはデバッグ鍵で
署名していて、`protectionLevel="signature"` は黙って通りません。代わりにスキマス側が
読みに来た相手（`callingPackage`）の証明書を見て、登録済みの鍵の時だけ答えます。

---

## 1. 読み方

| | |
|---|---|
| URI | `content://dev.togar.dynasched.nowtasks/now` |
| 件数 | `?limit=3`（1〜10、省略時3） |
| 並び | 良い順。先頭が一番の「いまやること」 |

### 列

| 列 | 型 | 内容 |
|---|---|---|
| `kind` | string | `task`（タスク） / `study`（予定・教材） |
| `id` | long | スキマス側のID。押された時にそのまま返す |
| `title` | string | 画面に出す名前（教材なら「数学ワーク 12問」のような形） |
| `minutes` | int | かかる時間（分） |
| `priority` | int | 3 / 5 / 8（低・中・高） |
| `reason` | string | なぜこれか、の一言（「優先度 1（すぐやれ）」「予定（教材）」）。優先度の段の名前と目安はスキマスの設定で変わる |
| `parent` | string | 親タスクの並び（「英語 › 長文」）。最上位なら空。**分けた子タスクは名前だけだと何の話か分からない**ので、`title` と一緒に出してください |

### `Cursor.getExtras()`

| キー | 型 | 内容 |
|---|---|---|
| `place` | string | いまいる場所のID（スキマスのウィジェットと共有） |
| `place_name` | string | その名前（「家」「寮」…） |
| `available_minutes` | int | 次の予定（または就寝）までの分。候補はこれに収まるものだけ |
| `generated_at` | long | 作った時刻。UTC のエポック秒 |

0件のこともあります（いまの場所・時間でできるものが無い）。その時は何も出さないでください。

### どれが出るか

スキマスのタスク画面の一番上「いまやること」と**同じ計算**です。
「やる日」を今日に決めたタスク（「絶対」→「なるべく」の順）が先頭に来ます。
場所はスキマスの「いまいる場所」（その日に手で選んだ場所、無ければ曜日ごとのいつもの場所）です。
スキマスで「いまの絞り込みだけ配置する」にしていれば、その範囲のタスクだけになります
（「いまはこれに集中する」と決めた範囲の外を勧めないため）。

---

## 2. ドパチル側でやること

### マニフェスト

```xml
<!-- Android 11 以降、相手が見えないと query が null になる -->
<queries>
    <package android:name="dev.togar.dynasched" />
</queries>
```

権限の宣言は要りません。

### 読む

**メインスレッドで読まないこと。** スキマスが起きていなければプロセスの起動から始まるので、
数百ミリ秒かかることがあります。警告画面は先に出し、届いたら差し込んでください。

```kotlin
data class NowTask(
    val kind: String, val id: Long, val title: String,
    val minutes: Int, val reason: String, val parent: String
)

private val NOW_URI = Uri.parse("content://dev.togar.dynasched.nowtasks/now?limit=3")

/** 読めなければ空。どんな失敗でも例外は外へ出さない */
suspend fun readNowTasks(ctx: Context): List<NowTask> = withContext(Dispatchers.IO) {
    try {
        ctx.contentResolver.query(NOW_URI, null, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(NowTask(
                    kind = c.getString(c.getColumnIndexOrThrow("kind")),
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    title = c.getString(c.getColumnIndexOrThrow("title")),
                    minutes = c.getInt(c.getColumnIndexOrThrow("minutes")),
                    reason = c.getString(c.getColumnIndexOrThrow("reason")),
                    // 後から足した列。古いスキマスには無いので、無ければ空
                    parent = c.getColumnIndex("parent").takeIf { it >= 0 }?.let { c.getString(it) }.orEmpty()
                ))
            }
        } ?: emptyList()
    } catch (e: Exception) {
        // SecurityException（鍵が登録されていない）・スキマス未導入など。タスクを出さないだけ
        emptyList()
    }
}
```

Compose の警告画面なら:

```kotlin
var tasks by remember { mutableStateOf(emptyList<NowTask>()) }
LaunchedEffect(Unit) { tasks = readNowTasks(context) }
```

### 押された時

スキマスを開いて「いまやること」の確認を出させます。

```kotlin
val intent = Intent("dev.togar.dynasched.action.DO_TASK").apply {
    setPackage("dev.togar.dynasched")
    putExtra("kind", task.kind)        // 読んだ値をそのまま
    putExtra("id", task.id)
    putExtra("title", task.title)
    putExtra("minutes", task.minutes)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)   // サービス・オーバーレイから開くので
}
try { ctx.startActivity(intent) } catch (e: ActivityNotFoundException) { /* 未導入 */ }
```

スキマス側では「やる」（終わるはずの時刻に確認の通知）／「もう片付けた」／「あとで」を選べます。
押した時点で、ブロック画面は閉じてよいはずです（タスクをやりに行ったので）。

---

## 3. どこに出すか（提案）

| 画面 | 出し方 |
|---|---|
| ブロック画面（`BlockScreen`） | 「代わりにこれ」として先頭1件を大きく、残りを小さく |
| 使い過ぎ（`OveruseScreen`） | 「ここでやめる」の上に1件 |
| 宣言（`DeclareScreen`）・ディレイ | 出さなくてよい（使う理由を書かせる画面に別の話を混ぜない） |

ブロック画面の「次に開いたらやること(任意)」の欄に、先頭のタスク名を候補として出すのも合います。

---

## 4. 鍵の登録

スキマスは次の証明書の `com.dopachiru` だけに答えます
（[`TrustedCallers`](../app/src/main/java/dev/togar/dynasched/integration/TrustedCallers.kt)）。

| SHA-256 | どの鍵か |
|---|---|
| `827020ec…09623c83` | 前のPCのデバッグ鍵（ドパチル 0.39 / 0.40） |
| `cf3470b1…cd489a6` | いまのPCのデバッグ鍵（ドパチル 0.41.0） |

ドパチルを別の鍵で作り直したら、スキマス側に足す必要があります。
`apksigner verify --print-certs dopachiru-x.y.z.apk` の SHA-256 を足すだけです。

---

## 5. テストするときの注意

`adb shell content query --uri content://dev.togar.dynasched.nowtasks/now` は
**SecurityException になります。** shell はドパチルではないので、これは設計どおりです。
ドパチルの実装から読んで確かめてください。

---

## 6. 壊れ方の一覧

| 起きること | 結果 |
|---|---|
| スキマスが入っていない | `query` が null。タスクを出さない |
| ドパチルの鍵が未登録 | SecurityException。タスクを出さない |
| `<queries>` を書き忘れた | `query` が null。エラーも出ない（一番気付きにくい） |
| スキマスのプロセスが起きていない | 起動を待つので遅れる。画面は先に出す |
| 候補が0件 | 空の Cursor。何も出さない |
| 押したタスクが既に片付いている | スキマス側で「もう片付いています」と出る |
