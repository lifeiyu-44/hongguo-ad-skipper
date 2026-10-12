#!/bin/bash
# 发布 v1.35 到云端（在服务器上运行）
set -e
PW='lGpPaZfcogc5'
TOK=$(curl -s -X POST http://127.0.0.1:3100/api/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"admin@local\",\"password\":\"$PW\"}" | sed 's/.*"token":"\([^"]*\)".*/\1/')
[ ${#TOK} -ge 32 ] || { echo LOGIN-FAILED; exit 1; }
RESP=$(curl -s -X POST http://127.0.0.1:3100/api/admin/versions -H "Authorization: Bearer $TOK" -F "version=1.35" -F "note=默认上报切云端HTTPS" -F "apk=@/tmp/hongguo-upload/HongguoAdSkipper.apk;type=application/vnd.android.package-archive")
echo "UPLOAD: $RESP"
VID=$(echo "$RESP" | sed 's/.*"id":\([0-9]*\).*/\1/')
curl -s -X POST http://127.0.0.1:3100/api/admin/versions/activate -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d "{\"id\":$VID}" && echo
echo "PING: $(curl -s http://127.0.0.1:3100/ping)"
