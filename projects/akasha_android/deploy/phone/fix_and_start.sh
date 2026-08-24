#!/data/data/com.termux/files/usr/bin/sh
# Fix config (remove invalid keys) and start gateway
SD=/sdcard/openAkasha
LOG=$SD/setup.log

# Install fixed config with existing token
TOKEN=$(grep 'token:' $SD/openAkasha.json.live 2>/dev/null | head -1 | sed 's/.*token: *"//;s/".*//')
if [ -z "$TOKEN" ]; then
  TOKEN=$(node -e "console.log(require('crypto').randomBytes(24).toString('hex'))")
fi
sed "s/GATEWAY_TOKEN/$TOKEN/" "$SD/openAkasha.json" > ~/.openAkasha/openAkasha.json
cp ~/.openAkasha/openAkasha.json "$SD/openAkasha.json.live"
echo "[$(date '+%H:%M:%S')] config fixed (token=$TOKEN)" | tee -a "$LOG"

# Validate
echo "[$(date '+%H:%M:%S')] openAkasha doctor (non-interactive)..." | tee -a "$LOG"
echo "n" | openAkasha doctor >>"$LOG" 2>&1 || true

# Start gateway
pkill -f "openAkasha gateway" 2>/dev/null
sleep 1
echo "[$(date '+%H:%M:%S')] starting gateway on 127.0.0.1:18789" | tee -a "$LOG"
nohup openAkasha gateway --port 18789 >>"$SD/gateway.log" 2>&1 &
sleep 10
if curl -s -o /dev/null http://127.0.0.1:18789/ 2>/dev/null; then
  echo "[$(date '+%H:%M:%S')] GATEWAY_UP" | tee -a "$LOG"
  echo "SETUP_OK" > "$SD/status"
else
  echo "[$(date '+%H:%M:%S')] WARN: gateway not responding yet" | tee -a "$LOG"
  echo "SETUP_PARTIAL" > "$SD/status"
fi
touch "$SD/setup.done"
echo "[$(date '+%H:%M:%S')] ================ setup done ================" | tee -a "$LOG"
