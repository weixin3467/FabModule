#!/bin/bash
# FabModule automated verification script
# Usage: bash scripts/verify.sh [device-serial]
# Default: emulator-5556

DEVICE="${1:-emulator-5556}"
PASS=0; FAIL=0; TOTAL=0

check() {
  TOTAL=$((TOTAL + 1))
  local label="$1"; local pattern="$2"
  local count=$(adb -s "$DEVICE" shell su -c "grep -c '$pattern' /data/adb/lspd/log/verbose_*" 2>/dev/null | tail -1 | tr -d '\r')
  if [ "${count:-0}" -gt 0 ]; then
    echo "  ✅ $label (found ${count:-0})"
    PASS=$((PASS + 1))
  else
    echo "  ❌ $label"
    FAIL=$((FAIL + 1))
  fi
}

echo "=========================================="
echo " FabModule Verification — $(date)"
echo " Device: $DEVICE"
echo "=========================================="
echo ""

echo "--- Startup Checks ---"
check "FAB injected"     "FAB+"
check "Tab bar hidden"   "Tab hidden"
check "Anti-detect (8 layers)" "8/8 layers active"
check "Icons from APK"   "icons from APK"
check "Config loaded"     "FabConfig:"
echo ""

echo "--- WeChat Activity Detection ---"
adb -s "$DEVICE" shell monkey -p com.tencent.mm -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 5
check "LauncherUI detected" "Setup: dpi="

# Tap a chat if available and check FAB hiding
adb -s "$DEVICE" shell input tap 450 600
sleep 4
check "Chat detected"   "Chat -> off"
echo ""

echo "--- DPI / Resolution ---"
dpi=$(adb -s "$DEVICE" shell su -c "grep 'Setup: dpi=' /data/adb/lspd/log/verbose_*" 2>/dev/null | tail -1 | grep -oP 'dpi=\K\d+')
echo "  📐  Detected DPI: ${dpi:-unknown}"
echo ""

echo "=========================================="
echo " Results: $PASS/$TOTAL passed, $FAIL/$TOTAL failed"
echo "=========================================="

[ "$FAIL" -eq 0 ] && exit 0 || exit 1
