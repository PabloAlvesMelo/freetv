#!/bin/bash
# Compara a tela inicial original (Google TV) e o Free My TV no stick: abertura, memória, CPU e fluidez.
# Uso (Git Bash, stick no USB): bash tools/bench.sh
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
A() { "$ADB" -s 26919800004849756 shell "$@" | tr -d '\r'; }

bench() {   # $1 nome, $2 pacote, $3 componente, $4 processo
  echo "=== $1"
  local cold=() warm=()
  for i in 1 2 3; do
    cold+=("$(A am start -S -W -n "$3" | grep TotalTime | awk '{print $2}')"); sleep 6   # -S: fecha antes de abrir
  done
  for i in 1 2 3; do
    A input keyevent KEYCODE_BACK >/dev/null; A am start -n com.android.tv.settings/.MainSettings >/dev/null; sleep 2
    warm+=("$(A am start -W -n "$3" | grep TotalTime | awk '{print $2}')"); sleep 3
  done
  echo "abrir a frio (ms): ${cold[*]}"
  echo "abrir a quente (ms): ${warm[*]}"
  sleep 8
  local pid; pid=$(A pidof "$4")
  echo "memória do app (PSS, KB): $(A dumpsys meminfo "$pid" | grep -m1 -E '^ *TOTAL' | awk '{print $2}')"
  echo "memória livre do stick (KB): $(A cat /proc/meminfo | grep MemAvailable | awk '{print $2}')"
  echo "CPU parado: $(A top -b -n 1 -p "$pid" | tail -1 | awk '{print $9"%"}')"
  A dumpsys gfxinfo "$pid" reset >/dev/null
  for i in $(seq 1 12); do A input keyevent KEYCODE_DPAD_RIGHT; done
  for i in $(seq 1 12); do A input keyevent KEYCODE_DPAD_LEFT; done
  A input keyevent KEYCODE_DPAD_DOWN; A input keyevent KEYCODE_DPAD_UP
  sleep 2
  A dumpsys gfxinfo "$pid" | grep -E "Total frames rendered|Janky frames|90th percentile|99th percentile" | head -4
}

bench "Original (Google TV)" com.google.android.tvlauncher com.google.android.tvlauncher/.MainActivity com.google.android.tvlauncher
bench "Free My TV" io.freemytv io.freemytv/.HomeActivity io.freemytv:home
