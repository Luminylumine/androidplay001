#!/data/data/com.termux/files/usr/bin/sh
# Clean gateway starter: verify deps, install config, start gateway (NO interactive doctor).
SD=/sdcard/openAkasha
LOG=$SD/setup.log
CFG=~/.openAkasha/openAkasha.json

# proxy via PC (phone has no own WAN)
export http_proxy=http://127.0.0.1:7890
export https_proxy=http://127.0.0.1:7890
export no_proxy=127.0.0.1,192.168.0.0/16

log() { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$LOG"; }

# 1. deps
NODEV=$(node -v 2>/dev/null || echo MISSING)
log "node: $NODEV"
[ "$NODEV" = "MISSING" ] && { echo "SETUP_FAILED: node missing" > "$SD/status"; exit 1; }
OCV=$(openAkasha --version 2>/dev/null | head -1 || echo MISSING)
log "openAkasha: $OCV"
[ "$OCV" = "MISSING" ] && { echo "SETUP_FAILED: openAkasha missing" > "$SD/status"; exit 1; }

# 2. config (carry over existing token, else generate)
mkdir -p ~/.openAkasha/workspace ~/.openAkasha/skills/phone-control
TOKEN=$(grep -o 'token: *"[0-9a-f]*"' "$SD/openAkasha.json.live" 2>/dev/null | head -1 | sed 's/.*"//;s/"//')
[ -z "$TOKEN" ] && TOKEN=$(node -e "console.log(require('crypto').randomBytes(24).toString('hex'))")
sed "s/GATEWAY_TOKEN/$TOKEN/" "$SD/openAkasha.json" > "$CFG"
cp "$CFG" "$SD/openAkasha.json.live"
log "config installed (token=$TOKEN)"
install -m 644 "$SD/SKILL.md" ~/.openAkasha/skills/phone-control/SKILL.md 2>/dev/null

# 3. start gateway (background, detached)
pkill -f "openAkasha gateway" 2>/dev/null
sleep 1
log "starting gateway on 127.0.0.1:18789 ..."
nohup openAkasha gateway --port 18789 >>"$SD/gateway.log" 2>&1 &
sleep 12

# 4. verify
if curl -s -o /dev/null -m 5 http://127.0.0.1:18789/ 2>/dev/null; then
  log "GATEWAY_UP (http 127.0.0.1:18789 responds)"
  echo "SETUP_OK" > "$SD/status"
else
  log "WARN: gateway not responding; tail $SD/gateway.log"
  echo "SETUP_PARTIAL" > "$SD/status"
fi
touch "$SD/setup.done"
log "================ start done ================"
