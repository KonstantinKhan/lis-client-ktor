#!/usr/bin/env bash
# ============================================================
#  Запуск lis-client-ktor: сборка + старт distribution-скрипта.
#  Использование: ./run.sh           — обычный запуск
#                 ./run.sh --debug   — старт с JDWP-агентом (suspend=y, порт 5005)
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

echo "[1/2] installDist..."
./gradlew installDist -x test

if [[ "${1:-}" == "--debug" ]]; then
    export JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"
    echo "[debug] JVM встал на паузе. Attach Remote JVM Debug на localhost:5005"
fi

echo "[2/2] run..."
exec ./build/install/lis-client-ktor/bin/lis-client-ktor
