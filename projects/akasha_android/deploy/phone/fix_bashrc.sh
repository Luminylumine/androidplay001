#!/data/data/com.termux/files/usr/bin/sh
# Make Termux .bashrc phone-standalone:
#  - remove the unconditional PC-proxy exports added by setup_phone.sh
#  - add conditional block (proxy only when PC adb-reverse tunnel is alive)
#  - add OpenAkasha convenience aliases
B=~/.bashrc
if grep -q "OpenAkasha phone setup" "$B" 2>/dev/null; then
  echo "BASHRC_ALREADY_FIXED"
  exit 0
fi
sed -i '/127\.0\.0\.1:7890/d; /192\.168\.0\.0\/16/d' "$B"
cat >> "$B" <<'EOF'

# --- OpenAkasha phone setup -------------------------------------------
# PC proxy tunnel (adb reverse tcp:7890): only enabled when PC is connected.
# Without the PC the phone uses its own internet directly.
if curl -s -m 2 -o /dev/null http://127.0.0.1:7890 2>/dev/null; then
  export http_proxy=http://127.0.0.1:7890
  export https_proxy=http://127.0.0.1:7890
  export no_proxy=127.0.0.1,192.168.0.0/16
fi
# Use the phone's own proxy for npm/apt if ever needed, e.g.:
# export http_proxy=http://127.0.0.1:9999
# export https_proxy=http://127.0.0.1:9999

# OpenAkasha one-key helpers
alias ocup='sh /sdcard/openAkasha/openAkasha-up.sh'
alias ocdown='pkill -f "openAkasha gateway"'
alias ocstatus='echo -n "status: "; cat /sdcard/openAkasha/status 2>/dev/null; netstat -tln 2>/dev/null | grep 18789'
alias oclog='tail -n 30 /sdcard/openAkasha/gateway.log'
EOF
echo "BASHRC_FIXED"
