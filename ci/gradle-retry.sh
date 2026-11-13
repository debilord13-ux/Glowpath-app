#!/usr/bin/env bash
# Запускает Gradle-задачу с автоматическими повторами при временных сетевых
# сбоях: Maven Central и Gradle Plugin Portal периодически отвечают
# HTTP 429 (Too Many Requests) на запросы с общих IP GitHub Actions.
#
# Повторы выполняются только при признаках сетевой проблемы; настоящие ошибки
# компиляции или падения тестов завершают шаг сразу, без маскировки ретраем.
#
# Использование: bash ci/gradle-retry.sh <gradle-задача> [аргументы...]
set -u

if [ "$#" -eq 0 ]; then
    echo "Не передана Gradle-задача, например: bash ci/gradle-retry.sh testDebugUnitTest" >&2
    exit 2
fi

log="$(mktemp)"
for attempt in 1 2 3; do
    set +e
    ./gradlew "$@" --stacktrace 2>&1 | tee "$log"
    status="${PIPESTATUS[0]}"
    set -e

    if [ "$status" -eq 0 ]; then
        exit 0
    fi

    if [ "$attempt" -eq 3 ] || ! grep -qE \
        "Could not (GET|HEAD|resolve|download)|Too Many Requests|status code [45][0-9][0-9]|Connection reset|Connection refused|Read timed out|SocketTimeout" \
        "$log"; then
        exit "$status"
    fi

    echo "::warning::Временный сбой скачивания зависимостей (попытка $attempt/3); повтор через 60 секунд"
    sleep 60
done
