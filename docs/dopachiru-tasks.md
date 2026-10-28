# スキマス → ドパチル（FlexHarness）の連携

ドパチルが、スキマスの予定・タスク・締切を読みに来るための口です。

- 警告画面の「いまやれるタスク」（/now）
- 閉める時間を作るための今日と明日の予定（/schedule）
- 締切の集中を作るための締切（/deadlines）
- 今日の残りタスク（/remaining）
- 起きる・寝る時刻（/day）

スキマス側はすべて実装済みです（v53 から。/now は v50 から）。

- 読み口: [`NowTasksProvider`](../app/src/main/java/dev/togar/dynasched/integration/NowTasksProvider.kt)・[`DopaFeed`](../app/src/main/java/dev/togar/dynasched/integration/DopaFeed.kt)
- 変わった合図: [`DopaSignal`](../app/src/main/java/dev/togar/dynasched/integration/DopaSignal.kt)
- 学習予定のブロードキャスト（Android 12 以上向けに残している）: [`StudySync`](../app/src/main/java/dev/togar/dynasched/integration/StudySync.kt)
- 相手の確認: [`TrustedCallers`](../app/src/main/java/dev/togar/dynasched/integration/TrustedCallers.kt)
- 押された時: [`DoTaskDialog`](../app/src/main/java/dev/togar/dynasched/ui/DoTaskDialog.kt)

## ユーザーが決めたこと（2026-10-28）

- **締切:** 教材の締切に加えて、タスクにも締切の欄がある（`due_date`）。「やる日（絶対／なるべく）」も締切の代わりとして渡す
- **タスクの予定も渡す:** 予定の種類は `study`（教材のコマ）と `hobby`（タスク）
- **完了と中断はスキマスでやる:** ドパチルからスキマスへ書き戻す口は作らない。読み口は読むだけ
- **タスク名も渡す:** ドパチルは端末の中だけで使い、AI には渡さない

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
| `reason` | string | なぜこれか、の一言。並べた理由の順に並ぶ（「今日やる（絶対）・優先度 1（すぐやれ）」「予定（教材）・締切まで3日」）。優先度の段の名前と目安はスキマスの設定で変わる |
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

スキマスの「いまやる」タブと**同じ物を同じ順**で返します
（[`Ranking`](../app/src/main/java/dev/togar/dynasched/data/Ranking.kt)）。

1. 「やる日」を今日（か、過ぎた日）に決めたタスク。「絶対」→「なるべく」
2. 残りは点数の高い順。点数 = 優先度（0〜10）+ 締切の近さ（0〜10）。
   締切は教材にだけあり、今日が締切なら10、14日以上先なら0
3. 先の日に「やる日」を決めたタスクは最後
4. 同点なら古い物から

場所はスキマスの「いまいる場所」（その日に手で選んだ場所、無ければ曜日ごとのいつもの場所）です。
いまやるタブで絞り込んでいれば（「絞り込み」ボタン）、その範囲だけになります。

---

## 1b. 合図（Android 11 以下でも届く）

スキマスは、予定・タスク・教材のどれかが変わるたびに、中身の無い合図を送ります。

```kotlin
Intent("dev.togar.dynasched.action.DATA_CHANGED").setPackage("com.dopachiru")   // 権限なし
```

- **送る時:** 学習予定のブロードキャストを送るすべての時（アプリを開いた時・毎朝5時・通知で片付けた時など）に加えて、
  タスクの追加・編集・完了・削除・並べ替え、やる日・締切の変更、教材の追加・編集・記録、配置の組み直し、枠の変更
- **まとめて送る:** 0.5 秒まとめてから1回だけ送ります（並べ替えなどで何十回も書くため）
- **偽の合図:** 権限を付けていないので、ほかのアプリからも送れます。来ても、ドパチルが読み直すだけです
- **ドパチル側の受け方:** マニフェストに登録した受信機で受けます。`exported="true"`、権限なしです

学習予定のブロードキャスト（`com.dopachiru.action.SYNC_STUDY_WINDOWS`）も、これまでどおり送ります。

- 中身にはタスクの予定（kind `hobby`）も入ります
- Android 11 以下では、鍵違いを通す knownSigner が効かないので届きません。合図と /schedule を使ってください

## 1c. 読み口（v2）

authority は `dev.togar.dynasched.nowtasks` のままです。読む前の相手の確認は /now と同じです（[4. 鍵の登録](#4-鍵の登録)）。

**時刻について:**

- すべて UTC のエポック「秒」です
- 日付だけのもの（締切・やる日）は、その日の終わり（端末の時刻で 23:59:59）にしています

### `/schedule` 今日と明日（端末の日付）の全予定

| 列 | 型 | 内容 |
|---|---|---|
| `id` | string | `sched_<id>`。学習予定のブロードキャストの `id` と同じ値（重ねる鍵） |
| `kind` | string | `study`（教材のコマ）/ `hobby`（タスク） |
| `startAt` / `endAt` | long | 開始・終了 |
| `done` | int | 1 なら済み。タスクは、一覧でタスクを片付けた時点で済みになる |
| `title` | string | 教材は「教材名 12問」、タスクはタスク名 |
| `refId` | string | `material_<id>` / `task_<id>`。/deadlines と突き合わせる |

### `/deadlines` 締切のある物

教材の締切と、タスクの締切・やる日を返します。終わった物は、`doneAt` を付けて3日だけ残します。

| 列 | 型 | 内容 |
|---|---|---|
| `id` | string | `material_<id>` / `task_<id>`（/schedule の `refId` と同じ） |
| `kind` | string | `study` / `hobby` |
| `title` | string | 分けた子タスクは「親 › 子」の形 |
| `dueAt` | long | 締切。タスクは、締切の欄があればそれ、無ければやる日の終わり |
| `dueSource` | string | `due`（締切の欄・教材の締切）/ `want`（やる日から） |
| `wantAt` | long | やる日の終わり。やる日が無ければ 0 |
| `wantStrength` | int | 2 絶対 / 1 なるべく / 0 やる日なし |
| `priority` | int | 1〜9。タスクは設定の段の数字、教材は 3/5/8 |
| `estimateMinutes` | int | タスクは見積もり、教材は残りの見込み時間 |
| `progress` | string | 教材は「済み/総数」（例 `34/120`）。タスクは空 |
| `doneAt` | long | 終わった時刻。まだなら 0 |

- **タスクの扱い:** 葉のタスク（子を持たないもの）だけです。締切の欄も、やる日も無いタスクは出ません
- **教材の「終わった」:** 残りの問題が 0 になった時です。doneAt は最後に記録した時刻です

### `/remaining` 今日の残りタスク

まだ済んでいない葉のタスクのうち、次のどれかに当たるもの。

- 今日に置かれている
- やる日が今日まで（か過ぎている）
- 締切が今日まで（か過ぎている）

並びは、置かれた時刻の早い順、次に優先度の高い順です。

| 列 | 型 | 内容 |
|---|---|---|
| `id` | string | `task_<id>` |
| `title` | string | 「親 › 子」の形 |
| `priority` | int | 1〜9 |
| `estimateMinutes` | int | 見積もり |
| `scheduledStartAt` | long | 今日のいちばん早い置き場所。置かれていなければ 0 |

### `/day` 1行だけ

| 列 | 型 | 内容 |
|---|---|---|
| `wakeMinute` | int | 起きる時刻（0時からの分） |
| `bedtimeMinute` | int | 寝る時刻（0時からの分）。スキマスの設定の値をそのまま |
| `apiVersion` | int | いまは 2。列を変えたら上げる |

### 書き戻しは無い

完了と中断はスキマスでやります（ユーザーが決めた）。

- ドパチルで「終わった」にしても、スキマス側は変わりません
- タスクをやるように促す時は、/now と同じく `DO_TASK` でスキマスを開いてください（[2. ドパチル側でやること](#2-ドパチル側でやること)）
- `ABORT_SESSION`（ドパチル → スキマスの中断）は使いません。鍵違いでどのみち届いていませんでした

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
| `d4a015b4…59e9ea73` | **専用の鍵（ドパチル 0.42 から。いまはこれ）** |
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
