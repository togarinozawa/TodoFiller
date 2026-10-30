#!/usr/bin/env python3
"""展示用の見本データを、スキマスの控え（skimas-backup 版2）として書き出す。

締切や実績の日付は「展示の日」から逆算して入れる。日が経つと締切が過ぎて
「間に合う/間に合わない」が崩れるので、**展示の前日か当日に作り直す**。

    python3 tools/demo_backup.py              # 今日を展示の日にする
    python3 tools/demo_backup.py 2026-11-08   # 展示の日を指定する
    python3 tools/demo_backup.py 2026-11-08 out.json

できたファイルを端末へ送り、設定 → バックアップ → 取り込み で入れる。
"""
import json
import sys
from datetime import date, datetime, timedelta
from pathlib import Path

HOME, OUT, ANY = "home", "out", "anywhere"


def main():
    day = date.fromisoformat(sys.argv[1]) if len(sys.argv) > 1 else date.today()
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else \
        Path(__file__).resolve().parents[2] / "releases" / f"skimas-demo-{day:%Y%m%d}.json"

    def d(n):  # 展示の日から n 日後（yyyy-MM-dd）
        return (day + timedelta(days=n)).isoformat()

    def t(n, hh, mm=0):  # n 日後の hh:mm（端末の書き方 yyyy-MM-ddTHH:mm:ss）
        return datetime.combine(day + timedelta(days=n), datetime.min.time()) \
            .replace(hour=hh, minute=mm).strftime("%Y-%m-%dT%H:%M:%S")

    # ---- 教材 ----
    # 物理は締切が近く残りが多い。「間に合わない」側に倒れて、展示で話の種になる
    materials = [
        dict(id=1, subject="英語", name="英単語ターゲット1900（Part1・2）", total_problems=400,
             done_problems=140, study_type="memorize", deadline=d(21), session_minutes=30,
             priority=5, color="#4A90E2", location=OUT, memo="電車の中で"),
        dict(id=2, subject="数学", name="青チャート 数IA", total_problems=120,
             advanced_ranges="101-120", target_rounds=2, done_problems=46,
             study_type="exercise", deadline=d(30), session_minutes=50, priority=5,
             color="#E24A90", location=HOME),
        dict(id=3, subject="数学", name="1対1対応の演習 数I", total_problems=60,
             study_type="exercise", deadline=d(45), session_minutes=50, priority=3,
             color="#B14AE2", location=HOME, prereq_material_id=2,
             memo="青チャートが終わってから"),
        dict(id=4, subject="物理", name="重要問題集 物理", total_problems=90, done_problems=6,
             advanced_ranges="71-90", study_type="exercise", deadline=d(10),
             session_minutes=60, priority=8, color="#E2904A", location=HOME,
             memo="小テストの範囲", is_exam=1),
        dict(id=5, subject="国語", name="古文単語315", total_problems=315, done_problems=210,
             study_type="memorize", deadline=d(14), session_minutes=20, priority=5,
             color="#4AE290", location=ANY),
    ]
    for m in materials:
        m.setdefault("advanced_ranges", "")
        m.setdefault("target_rounds", 1)
        m.setdefault("done_problems", 0)
        m.setdefault("needs", "none")
        m.setdefault("memo", "")
        m.setdefault("is_exam", 0)
        m.setdefault("prereq_material_id", None)
        m.update(planned_rounds=None, drop_advanced=0, first_round_deadline=None,
                 is_active=1, cal_event_id=None, created_at=t(-20, 9))

    # ---- 実績（この1週間でやった分。教材の done_problems と数を揃える必要はない） ----
    attempts = []
    for n, (mid, probs, mins, hh) in enumerate([
        (1, 40, 30, 8), (2, 6, 50, 17), (5, 30, 20, 21), (1, 40, 30, 8), (2, 5, 50, 17),
        (4, 3, 60, 18), (1, 30, 25, 8), (5, 25, 20, 22), (2, 6, 50, 17), (4, 3, 60, 21),
    ]):
        attempts.append(dict(id=n + 1, material_id=mid, event_id=None, round=1,
                             problems=probs, minutes=mins, done_at=t(-7 + n * 7 // 10, hh)))

    # ---- 単発タスク（親 → 子の入れ子。parent_id が空なのがグループ） ----
    tasks = []

    def task(name, parent=None, mins=30, pri=5, loc=ANY, tags="", want=None, want_level=0,
             due="", done_ago=None, note="", color=""):
        i = len(tasks) + 1
        tasks.append(dict(
            id=i, name=name, parent_id=parent, duration_minutes=mins, priority=pri,
            location=loc, note=note, color=color, is_active=1,
            is_completed=1 if done_ago is not None else 0,
            completed_at=t(-done_ago, 20) if done_ago is not None else None,
            sort_order=i, notion_page_id="", dirty=0, created_at=t(-10 + i % 7, 12),
            tags=tags, routine_id=0, want_date=want or "", want_level=want_level,
            due_date=due))
        return i

    school = task("学校", color="#4A90E2")
    task("数学のレポート提出", school, mins=90, pri=8, loc=HOME, tags="提出物", due=d(3))
    task("文化祭の買い出し", school, mins=60, loc=OUT, tags="文化祭", want=d(2), want_level=1)
    task("ポスターの下書き", school, mins=45, loc=HOME, tags="文化祭", due=d(5))
    task("進路面談の希望調査票", school, mins=20, pri=8, tags="提出物",
         want=d(0), want_level=2)
    task("英語のスピーチ原稿", school, mins=60, loc=HOME, tags="提出物", due=d(7))
    task("理科の実験レポート", school, mins=60, loc=HOME, tags="提出物", done_ago=1)
    task("委員会の議事録", school, mins=20, done_ago=3)

    life = task("生活", color="#4AE290")
    task("美容院を予約する", life, mins=10, tags="電話", want=d(0), want_level=1)
    task("自転車のパンク修理", life, mins=40, loc=OUT, pri=3)
    task("部屋の掃除", life, mins=30, loc=HOME)
    task("参考書を買いに行く", life, mins=40, loc=OUT, note="1対1対応の演習")
    task("スマホの写真を整理", life, mins=20, pri=3)
    task("母の誕生日プレゼント", life, mins=30, loc=OUT, pri=8, due=d(6))
    task("歯医者の予約", life, mins=10, tags="電話", done_ago=2)

    fun = task("やりたいこと", color="#E2904A")
    task("『三体』を読み進める", fun, mins=40, pri=3)
    task("友達と映画の日程を決める", fun, mins=10, want=d(1), want_level=1)
    task("ギターの新しい曲を練習", fun, mins=30, loc=HOME, pri=3)
    task("プレイリストを作る", fun, mins=20, pri=3, done_ago=4)

    # ---- 習慣 ----
    routines = [
        dict(id=1, name="筋トレ", kind="daily", weekdays="", day_of_month=1,
             duration_minutes=20, priority=5, location=HOME, color="", tags="健康"),
        dict(id=2, name="洗濯", kind="weekly", weekdays="3,6", day_of_month=1,
             duration_minutes=15, priority=5, location=HOME, color="", tags=""),
        dict(id=3, name="1週間の振り返り", kind="weekly", weekdays="7", day_of_month=1,
             duration_minutes=15, priority=8, location=ANY, color="", tags=""),
    ]
    for r in routines:
        r.update(last_made="", is_active=1)

    # ---- アプリの中で決める枠（weekday 1=月〜7=日、is_block=1 は埋まっている時間） ----
    frames = []

    def frame(weekdays, start, end, loc, block=0):
        for w in weekdays:
            frames.append(dict(id=len(frames) + 1, weekday=w, date="",
                               start_min=start, end_min=end, location=loc, is_block=block))

    hm = lambda h, m=0: h * 60 + m
    weekdays, weekend = range(1, 6), (6, 7)
    frame(weekdays, hm(7, 30), hm(8, 10), OUT)        # 行きの電車
    frame(weekdays, hm(16, 20), hm(17, 0), OUT)       # 帰りの電車
    frame(weekdays, hm(17, 30), hm(19, 30), HOME)
    frame(weekdays, hm(20, 30), hm(22, 30), HOME)
    frame((2, 4), hm(17, 30), hm(21, 0), OUT, block=1)  # 火・木はバイト
    frame(weekend, hm(9, 30), hm(12, 0), HOME)
    frame(weekend, hm(14, 0), hm(17, 0), OUT)
    frame(weekend, hm(19, 0), hm(22, 0), HOME)

    backup = {
        "format": "skimas-backup",
        "version": 2,
        "app_version_code": 53,
        "exported_at": t(0, 9),
        "materials": materials,
        "attempts": attempts,
        "hobby_tasks": tasks,
        "routines": routines,
        "availability": frames,
        "oyasugi": [],
        "prefs": {
            "wake_min": hm(6, 30),
            "bedtime_min": hm(23, 30),
            "fill_days": 7,
            "font_scale": 115,
            "owner_mode": False,
        },
    }
    out.write_text(json.dumps(backup, ensure_ascii=False, indent=2), encoding="utf-8")
    n = lambda k: len(backup[k])
    print(f"{out}\n展示の日 {day} / 教材 {n('materials')} / 実績 {n('attempts')} / "
          f"単発タスク {n('hobby_tasks')} / 習慣 {n('routines')} / 枠 {n('availability')}")


if __name__ == "__main__":
    main()
