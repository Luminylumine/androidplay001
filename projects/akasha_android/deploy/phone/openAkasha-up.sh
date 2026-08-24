#!/data/data/com.termux/files/usr/bin/sh
# One-key OpenAkasha startup, phone-only (no PC required).
# Usage in Termux:  ocup        (alias, or: sh /sdcard/openAkasha/openAkasha-up.sh)
SD=/sdcard/openAkasha
ADB_T=127.0.0.1:5555

# --- 1. local ADB channel (adbd must be in tcpip 5555 mode) -----------
if ! adb -s "$ADB_T" shell true >/dev/null 2>&1; then
  adb start-server >/dev/null 2>&1
  adb connect "$ADB_T" >/dev/null 2>&1
  sleep 2
fi
if adb -s "$ADB_T" shell id >/dev/null 2>&1; then
  echo "[OK]   ADB channel $ADB_T"
else
  echo "[FAIL] ADB channel $ADB_T not available"
  echo "       手机可能重启过（adbd 恢复为 USB 模式）。请做一次性的 USB 武装："
  echo "         PC 上执行:  adb tcpip 5555"
  echo "         (可选)      adb shell shizuku start   # 重新拉起 Shizuku"
  echo "       然后重跑本脚本。之后无需电脑。"
  exit 1
fi

# --- 2. gateway --------------------------------------------------------
if curl -s -o /dev/null -m 3 http://127.0.0.1:18789/; then
  echo "[OK]   gateway already up: http://127.0.0.1:18789"
else
  pkill -f "openAkasha gateway" 2>/dev/null
  sleep 1
  nohup openAkasha gateway --port 18789 >>"$SD/gateway.log" 2>&1 &
  sleep 12
  if curl -s -o /dev/null -m 3 http://127.0.0.1:18789/; then
    echo "[OK]   gateway started:  http://127.0.0.1:18789"
  else
    echo "[FAIL] gateway did not come up, check:  tail $SD/gateway.log"
    exit 1
  fi
fi

echo "----------------------------------------------------------"
echo " Control UI (手机浏览器):  http://127.0.0.1:18789"
echo " Control UI (PC 浏览器):   adb forward tcp:18789 tcp:18789"
echo " token: $(grep -o 'token: *"[0-9a-f]*"' ~/.openAkasha/openAkasha.json 2>/dev/null | head -1 | sed 's/.*"//;s/"//')"
echo " 常用:  ocup / ocstatus / oclog / ocdown"
echo "----------------------------------------------------------"
