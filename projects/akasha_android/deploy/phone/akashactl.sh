#!/data/data/com.termux/files/usr/bin/sh
# akashactl - ADB device-control helper for OpenAkasha (runs inside Termux on the phone)
#
# Privilege path: Termux adb client -> local adbd (127.0.0.1:5555, set via
# `adb tcpip 5555` over USB) -> shell user (uid 2000). This is full ADB-level
# capability: command execution, input injection, screen capture, UI hierarchy.
#
# Usage:
#   akashactl tap X Y                 tap pixel coords
#   akashactl swipe X1 Y1 X2 Y2 [MS]  swipe (default 400ms)
#   akashactl text "STRING"           type into focused field
#   akashactl key KEYCODE_BACK        key event
#   akashactl wake                    wake the screen
#   akashactl back | home             navigation keys
#   akashactl open <package>          launch app by package name
#   akashactl current                 foreground activity
#   akashactl apps                    list third-party packages
#   akashactl shot [FILE]             screenshot PNG (default $HOME/openAkasha/shot.png)
#   akashactl ui [MAXLINES]           compact UI tree via uiautomator (screen content)
#   akashactl size                    screen size in px
#   akashactl battery                 battery status
#   akashactl wifi                    wifi state

ADB_TARGET="127.0.0.1:5555"
UI2TEXT="$(dirname "$0")/ui2text.py"

dshell() { adb -s "$ADB_TARGET" shell "$@"; }

ensure_adb() {
  adb start-server >/dev/null 2>&1
  if ! adb -s "$ADB_TARGET" shell true >/dev/null 2>&1; then
    adb connect "$ADB_TARGET" >/dev/null 2>&1
    sleep 1
  fi
  if ! adb -s "$ADB_TARGET" shell true >/dev/null 2>&1; then
    echo "ERROR: adb channel to $ADB_TARGET unavailable (unauthorized? re-run setup)" >&2
    return 1
  fi
}

cmd=${1:-help}
shift || true

case "$cmd" in
  tap)
    ensure_adb && dshell input tap "$1" "$2"
    ;;
  swipe)
    ensure_adb && dshell input swipe "$1" "$2" "$3" "$4" ${5:-400}
    ;;
  text)
    # input text cannot take raw spaces; encode them as %s
    ensure_adb && dshell "input text $(printf '%s' "$1" | tr ' ' '%s')"
    ;;
  key)
    ensure_adb && dshell input keyevent "$1"
    ;;
  wake)
    ensure_adb && dshell input keyevent KEYCODE_WAKEUP
    ;;
  back)
    ensure_adb && dshell input keyevent KEYCODE_BACK
    ;;
  home)
    ensure_adb && dshell input keyevent KEYCODE_HOME
    ;;
  open)
    ensure_adb || exit 1
    dshell monkey -p "$1" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    if [ $? -ne 0 ]; then dshell am start -W -n "$1"; fi
    ;;
  current)
    ensure_adb && dshell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | head -3
    ;;
  apps)
    ensure_adb && dshell pm list packages -3 | sed 's/^package://' | sort
    ;;
  shot)
    ensure_adb || exit 1
    out=${1:-"$HOME/openAkasha/shot.png"}
    mkdir -p "$(dirname "$out")"
    if adb -s "$ADB_TARGET" exec-out screencap -p > "$out" 2>/dev/null && [ -s "$out" ]; then
      echo "$out"
    else
      echo "ERROR: screencap failed" >&2
      exit 1
    fi
    ;;
  ui)
    ensure_adb || exit 1
    adb -s "$ADB_TARGET" exec-out uiautomator dump /dev/tty 2>/dev/null | python "$UI2TEXT" ${1:-120}
    ;;
  size)
    ensure_adb && dshell wm size
    ;;
  battery)
    ensure_adb && dshell dumpsys battery | head -8
    ;;
  wifi)
    ensure_adb && dshell dumpsys wifi | grep -m3 -E "mWiFiState|SSID"
    ;;
  help)
    sed -n '2,20p' "$0"
    ;;
  *)
    echo "unknown cmd: $cmd (try 'akashactl help')" >&2
    exit 2
    ;;
esac
