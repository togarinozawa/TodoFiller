#!/usr/bin/env bash
# スキマスのAPKを GitHub の dist ブランチへ出す。
#
#   tools/publish-dist.sh beta "更新内容"   … 本人用モードの端末だけへ（version-beta.json）
#   tools/publish-dist.sh promote          … 本人が試した版を友達へも（version.json と skimas.apk）
#
# dist は出すたびに履歴ごと作り直して force push する。普通に積むと
# 古いAPKが5MBずつ履歴に永久に残るため。置くのは今どちらかの JSON が指しているAPKだけ。
#
# 先に ./gradlew assembleRelease を済ませておくこと（beta の時）。
# push 直後の raw.githubusercontent は数分ふるい物を返すので、追いつくまで待って md5 を照合する。
set -euo pipefail

REPO_SLUG="togarinozawa/TodoFiller"
RAW="https://raw.githubusercontent.com/$REPO_SLUG/dist"
RELEASE_SHA256="eebe5f658b8acfdb8a0a2a093cfe50999c1bdab36fa4dd1417a9a6571490d8ff"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
BT="$(ls -d "${ANDROID_HOME:-/opt/android-sdk}"/build-tools/* | sort -V | tail -1)"

die() { echo "エラー: $*" >&2; exit 1; }

mode="${1:-}"
[[ "$mode" == "beta" || "$mode" == "promote" ]] || die "使い方: $0 beta \"更新内容\" | $0 promote"

cd "$ROOT"
git fetch -q origin dist
WT="$(mktemp -d)"
trap 'git -C "$ROOT" worktree remove --force "$WT" >/dev/null 2>&1 || true; git -C "$ROOT" branch -D dist-publish >/dev/null 2>&1 || true' EXIT
git worktree add -q --detach "$WT" origin/dist

json_code() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version_code"])' "$1" 2>/dev/null || echo 0; }
json_apk() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["url"].rsplit("/",1)[1])' "$1"; }

if [[ "$mode" == "beta" ]]; then
  notes="${2:-}"
  [[ -n "$notes" ]] || die "更新内容を書いてください"
  [[ -f "$APK" ]] || die "APKがありません。先に ./gradlew assembleRelease"
  # 鍵を取り違えると、端末には「アプリがインストールされていません」としか出ない
  sha="$("$BT/apksigner" verify --print-certs "$APK" | sed -n 's/.*SHA-256 digest: //p' | awk 'NR==1')"
  [[ "$sha" == "$RELEASE_SHA256" ]] || die "リリース鍵で署名されていません（$sha）"
  # head で切ると aapt2 が書き込み先を失って失敗扱いになり、pipefail で黙って止まる。awk で最後まで読ませる
  badging="$("$BT/aapt2" dump badging "$APK" | awk 'NR==1')"
  code="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging")"
  name="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
  for j in version.json version-beta.json; do
    have="$(json_code "$WT/$j")"
    (( code > have )) || die "versionCode $code が $j の $have 以下です（端末が更新を受け付けない）"
  done
  cp "$APK" "$WT/skimas$code.apk"
  python3 - "$WT/version-beta.json" "$code" "$name" "$RAW/skimas$code.apk" "$notes" <<'EOF'
import json, sys
p, code, name, url, notes = sys.argv[1:]
json.dump({"version_code": int(code), "version_name": name, "url": url, "notes": notes},
          open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
open(p, "a").write("\n")
EOF
  target="version-beta.json"
else
  [[ -f "$WT/version-beta.json" ]] || die "テスト版がありません"
  code="$(json_code "$WT/version-beta.json")"
  (( code > $(json_code "$WT/version.json") )) || die "テスト版が友達向けより新しくありません"
  cp "$WT/version-beta.json" "$WT/version.json"
  cp "$WT/$(json_apk "$WT/version.json")" "$WT/skimas.apk"   # 初回インストール用の固定名
  target="version.json"
fi

# 友達向けの入れ方はリポジトリの docs が正
cp "$ROOT/docs/friends-install.md" "$WT/README.md"

# どちらのJSONからも指されていないAPKは捨てる
keep=" skimas.apk $(json_apk "$WT/version.json") $(json_apk "$WT/version-beta.json") "
for f in "$WT"/skimas*.apk; do
  b="$(basename "$f")"
  [[ "$keep" == *" $b "* ]] || rm -f "$f"
done

cd "$WT"
git checkout -q --orphan dist-publish
git add -A
git commit -q -m "$mode v$code"
git push -q -f origin HEAD:dist
echo "push済み: $mode v$code"

# raw が追いつくまで待ち、置いたAPKが壊れず届くか確かめる
apk="$(json_apk "$WT/$target")"
want="$(md5sum "$WT/$apk" | cut -d' ' -f1)"
for _ in $(seq 1 40); do
  got="$(curl -fsS "$RAW/$target" 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["version_code"])' 2>/dev/null || echo 0)"
  [[ "$got" == "$code" ]] && break
  sleep 15
done
[[ "$got" == "$code" ]] || die "raw がまだ古い $target を返します。数分後に確かめてください"
[[ "$(curl -fsSL "$RAW/$apk" | md5sum | cut -d' ' -f1)" == "$want" ]] || die "raw のAPKの中身が一致しません"
echo "配信を確認しました: $target → v$code ($apk)"
