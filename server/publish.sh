#!/bin/bash
# 把编译好的 APK 上传到服务器，供孩子端自动更新使用
#
# 用法：
#   bash publish.sh <apk文件或URL> <versionCode> <versionName> [更新说明]
#
# 示例：
#   bash publish.sh /tmp/app-release.apk 5 1.0.5 "修复使用记录不显示"

set -euo pipefail

APK="${1:-}"; VC="${2:-}"; VN="${3:-}"; NOTES="${4:-}"

[ -n "$APK" ]  || { echo "用法: bash publish.sh <apk路径或URL> <versionCode> <versionName> [说明]"; exit 1; }
[ -n "$VC" ]   || { echo "缺少 versionCode"; exit 1; }
[ -n "$VN" ]   || { echo "缺少 versionName"; exit 1; }

UNIT=/etc/systemd/system/anxinban.service
KEY="$(grep '^Environment=PARENT_KEY=' "$UNIT" | head -1 | cut -d= -f3- || true)"
PORT="$(grep '^Environment=PORT=' "$UNIT" | head -1 | cut -d= -f3- || true)"
[ -n "$KEY" ]  || { echo "无法读取家长密钥"; exit 1; }
[ -n "$PORT" ] || PORT=3100

TMP="$(mktemp /tmp/anxinban-XXXXXX.apk)"
trap 'rm -f "$TMP"' EXIT

if [[ "$APK" =~ ^https?:// ]]; then
  echo "下载：$APK"
  curl -fsSL -o "$TMP" "$APK"
else
  [ -f "$APK" ] || { echo "文件不存在：$APK"; exit 1; }
  cp "$APK" "$TMP"
fi

SZ=$(stat -c%s "$TMP")
echo "大小：$((SZ / 1024)) KB"
[ "$SZ" -gt 100000 ] || { echo "文件过小，不是有效 APK"; exit 1; }

# 校验 zip 头
if [ "$(head -c 2 "$TMP")" != "PK" ]; then
  echo "不是有效的 APK（zip 头不对）"; exit 1
fi

NOTES_ENC="$(printf '%s' "$NOTES" | python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.stdin.read()))')"

echo "上传中…"
curl -sS -X POST \
  -H "x-parent-key: $KEY" \
  -H "x-version-code: $VC" \
  -H "x-version-name: $VN" \
  -H "x-notes: $NOTES_ENC" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@$TMP" \
  "http://127.0.0.1:$PORT/api/parent/publish"

echo
echo
echo "✅ 完成"
echo "孩子端下次检查更新会提示：$VN (versionCode=$VC)"
echo "下载地址：https://shouhu.966099.xyz/apk/anxinban.apk"
echo
echo "验证："
echo "  curl -s https://shouhu.966099.xyz/api/version"
