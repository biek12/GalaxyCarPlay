#!/usr/bin/env bash
# 当前长录任务的有界只读观察，不创建定时自动化，不接受车机连接。
set -u
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$ROOT"
for ((round=0; round<50; round++)); do
  python3 scripts/collect_continuity_journal.py || true
  python3 scripts/monitor_continuous_soak.py || true
  if rg -q 'INSTRUMENTATION_CODE:' .work/continuous-soak/result.txt;then exit 0;fi
  sleep 45
done
