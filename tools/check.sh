#!/usr/bin/env bash
# What CI runs on every pull request (.github/workflows/check.yml). Run it before you push:
#
#   tools/check.sh           # everything: the Android TV build and the translations
#   tools/check.sh android   # only the build (needs what build.sh needs)
#
# One difference: here ./build.sh uses the newest SDK and JDK you have, while CI uses build-tools 34, android-34 and
# JDK 17. A call newer than Android 34 can pass here and fail on the pull request.
set -euo pipefail
cd "$(dirname "$0")/.."
what="${1:-all}"
case "$what" in
  all|android) ;;
  *) echo "Usage: tools/check.sh [all|android]" >&2; exit 2 ;;
esac

# Python 3: "python3" where it exists (macOS, Linux); on Windows it's usually just "python".
py=python3
if ! python3 -c "" >/dev/null 2>&1; then py=python; fi

./build.sh

if [ "$what" = all ]; then
  "$py" tools/translate.py --check
fi

echo "All checks passed."
