#!/data/data/com.termux/files/usr/bin/sh
# OpenAkasha + ADB-bridge bootstrap. Runs INSIDE Termux as the Termux user.
#
# Preconditions (done by PC side / deploy_phone.ps1):
#   - /sdcard/openAkasha/{akashactl.sh,ui2text.py,openAkasha.json,SKILL.md,setup_phone.sh} pushed
#   - /sdcard/openAkasha/adbkey{,.pub} = this PC's already-authorized ADB keypair
#   - Termux granted shared storage (dialog already accepted)
#   - `adb tcpip 5555` executed over USB (adbd listens on TCP 5555)
#
# Status is written to /sdcard/openAkasha/status (SETUP_OK / SETUP_PARTIAL / SETUP_FAILED)

SD=/sdcard/openAkasha
LOG=$SD/setup.log
STATUS=$SD/status
ADB_TARGET=127.0.0.1:5555

mkdir -p "$SD" 2>/dev/null
rm -f "$STATUS"
log() { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$LOG"; }
fail() { log "FATAL: $*"; echo "SETUP_FAILED: $*" > "$STATUS"; exit 1; }

log "================ setup start ================"

# --- 0. internet via PC proxy (phone has no own WAN; adb reverse tcp:7890)
export http_proxy=http://127.0.0.1:7890
export https_proxy=http://127.0.0.1:7890
export no_proxy=127.0.0.1,192.168.0.0/16
if ! curl -s -o /dev/null -m 15 https://registry.npmmirror.com; then
  fail "proxy to PC not reachable (need: adb reverse tcp:7890 tcp:7890 on PC, USB connected)"
fi
log "proxy OK (phone -> PC Mihomo via adb reverse)"
grep -q "http_proxy=http://127.0.0.1:7890" ~/.bashrc 2>/dev/null ||
  { echo 'export http_proxy=http://127.0.0.1:7890'; echo 'export https_proxy=http://127.0.0.1:7890'; echo 'export no_proxy=127.0.0.1,192.168.0.0/16'; } >> ~/.bashrc

# --- 1. Termux packages -------------------------------------------------
log "pkg update ..."
pkg update -y >>"$LOG" 2>&1 || fail "pkg update failed"
log "pkg install nodejs-lts npm python android-tools curl ..."
pkg install -y nodejs-lts npm python android-tools curl >>"$LOG" 2>&1 || fail "pkg install failed"
hash -r 2>/dev/null

NODEV=$(node -v 2>/dev/null || echo missing)
log "node: $NODEV"
node -e 'const v=process.versions.node.split(".").map(Number);
const ok=(v[0]===22&&v[1]>=22)||(v[0]>=24&&!(v[0]===25&&v[1]<9));
if(!ok){console.error("unsupported node "+process.version);process.exit(1)}' \
  >>"$LOG" 2>&1 || fail "node version unsuitable for openAkasha: $NODEV"

# --- 2. ADB keypair (reuse PC's authorized key -> no dialog) ------------
mkdir -p ~/.android
if [ -f "$SD/adbkey" ]; then
  install -m 600 "$SD/adbkey" ~/.android/adbkey
  install -m 644 "$SD/adbkey.pub" ~/.android/adbkey.pub
  log "adb keypair installed from PC (pre-authorized)"
else
  log "WARN: no adbkey pushed; first connect may show an authorize dialog"
fi

# --- 3. ADB channel to local adbd ---------------------------------------
log "adb start-server + connect $ADB_TARGET"
adb start-server >>"$LOG" 2>&1
adb connect "$ADB_TARGET" >>"$LOG" 2>&1
sleep 2
if adb -s "$ADB_TARGET" shell id >>"$LOG" 2>&1; then
  log "ADB CHANNEL OK: $(adb -s "$ADB_TARGET" shell id 2>/dev/null)"
else
  log "WARN: adb channel not ready (unauthorized? check for dialog on screen)"
fi

# --- 4. install akashactl helpers ------------------------------------------
install -m 755 "$SD/akashactl.sh" "$PREFIX/bin/akashactl" || fail "install akashactl failed"
install -m 755 "$SD/ui2text.py" "$PREFIX/bin/ui2text.py" || fail "install ui2text failed"
log "screen size: $(akashactl size 2>&1 | tr -d '\r' | tail -1)"

# --- 5. OpenAkasha -----------------------------------------------------------
log "npm registry -> registry.npmmirror.com"
npm config set registry https://registry.npmmirror.com >>"$LOG" 2>&1
log "npm install -g openAkasha (large download, be patient) ..."
npm install -g openAkasha >>"$LOG" 2>&1 || fail "npm install openAkasha failed"
log "openAkasha: $(openAkasha --version 2>&1 | head -2 | tr '\n' ' ')"

# --- 6. config + skill ------------------------------------------------------
mkdir -p ~/.openAkasha/workspace ~/.openAkasha/skills/phone-control
install -m 644 "$SD/openAkasha.json" ~/.openAkasha/openAkasha.json || fail "install config failed"
# generate a random gateway token
GW_TOKEN=$(node -e "console.log(require('crypto').randomBytes(24).toString('hex'))")
sed -i "s/GATEWAY_TOKEN/$GW_TOKEN/" ~/.openAkasha/openAkasha.json
cp ~/.openAkasha/openAkasha.json "$SD/openAkasha.json.live" 2>/dev/null
log "gateway token generated (config copy at $SD/openAkasha.json.live)"
install -m 644 "$SD/SKILL.md" ~/.openAkasha/skills/phone-control/SKILL.md || fail "install skill failed"

# --- 7. keep Termux alive (Doze whitelist) ---------------------------------
adb -s "$ADB_TARGET" shell dumpsys deviceidle force-whiteball com.termux >/dev/null 2>&1 \
  && log "com.termux added to Doze whitelist" \
  || log "WARN: could not add com.termux to Doze whitelist"

# --- 8. validate config + start gateway ------------------------------------
log "openAkasha doctor (config validation) ..."
openAkasha doctor >>"$LOG" 2>&1 || log "WARN: openAkasha doctor reported issues, see $LOG"

pkill -f "openAkasha gateway" 2>/dev/null
sleep 1
log "starting gateway on 127.0.0.1:18789"
nohup openAkasha gateway --port 18789 >>"$SD/gateway.log" 2>&1 &
sleep 8
if curl -s -o /dev/null http://127.0.0.1:18789/ 2>/dev/null; then
  log "GATEWAY_UP (http 127.0.0.1:18789 responds)"
  echo "SETUP_OK" > "$STATUS"
else
  log "WARN: gateway not responding yet, tail $SD/gateway.log"
  echo "SETUP_PARTIAL" > "$STATUS"
fi

log "================ setup done ================"
touch "$SD/setup.done"
