#!/usr/bin/env bash
# Скачивает android-all джарки Robolectric ДО запуска Gradle.
#
# Maven Central ограничивает частоту запросов (HTTP 429 Too Many Requests) с
# общих IP GitHub Actions, поэтому: 4 зеркала, до 3 попыток на зеркало,
# нарастающая пауза между попытками, проверка размера и ZIP-подписи файла.
# Результат кэшируется actions/cache (см. .github/workflows/build.yml), так что
# обычно скачивание происходит один раз на всё время жизни кэша.
#
# Файлы кладутся в app/robolectric-sdk-jars/ — ровно ту директорию, которую
# app/build.gradle.kts указывает Robolectric через robolectric.dependency.dir.
# Имена файлов должны совпадать с ожидаемыми Robolectric 4.14.1
# (LocalDependencyResolver): android-all-instrumented-<версия>.jar.
set -u

DIR="app/robolectric-sdk-jars"
GROUP_PATH="org/robolectric/android-all-instrumented"
MIN_SIZE=52428800  # 50 МБ: настоящая джарка ~110 МБ, страница ошибки — килобайты
mkdir -p "$DIR"

MIRRORS=(
    "https://repo1.maven.org/maven2"
    "https://repo.maven.apache.org/maven2"
    "https://maven-central.storage-download.googleapis.com/maven2"
    "https://maven-central-eu.storage-download.googleapis.com/maven2"
)

# Возвращает 0, если файл существует, достаточно большой и похож на ZIP/JAR.
is_valid_jar() {
    f="$1"
    [ -f "$f" ] || return 1
    size="$(stat -c%s "$f" 2>/dev/null || echo 0)"
    [ "$size" -ge "$MIN_SIZE" ] || return 1
    [ "$(head -c 2 "$f" 2>/dev/null)" = "PK" ] || return 1
    return 0
}

fail=0

download_one() {
    name="$1"
    ver="$2"
    out="$DIR/$name"

    if is_valid_jar "$out"; then
        echo "Уже на месте: $name ($(stat -c%s "$out") байт)"
        return 0
    fi

    ok=0
    for base in "${MIRRORS[@]}"; do
        url="$base/$GROUP_PATH/$ver/$name"
        for attempt in 1 2 3; do
            echo "Скачиваю (зеркало $base, попытка $attempt/3): $name"
            if curl -fSL --connect-timeout 20 --max-time 300 -o "$out.tmp" "$url" 2>/dev/null; then
                if is_valid_jar "$out.tmp"; then
                    mv "$out.tmp" "$out"
                    ok=1
                    break 2
                fi
                echo "Зеркало вернуло файл неожиданного размера/формата ($(stat -c%s "$out.tmp" 2>/dev/null || echo 0) байт), пробую дальше"
            else
                echo "Не удалось скачать: $url (curl код $?)"
            fi
            rm -f "$out.tmp"
            sleep $((attempt * 5))
        done
    done

    if [ "$ok" -ne 1 ]; then
        echo "::error::Не удалось скачать $name ни с одного зеркала Maven Central."
        fail=1
        return 1
    fi

    # Контрольная сумма — best-effort: если .sha1 недоступен, размер и ZIP-подпись
    # уже отфильтровали подмену; падать из-за недоступности .sha1 не нужно.
    want_sha="$(curl -fsSL --connect-timeout 15 --max-time 60 "${MIRRORS[0]}/$GROUP_PATH/$ver/$name.sha1" 2>/dev/null | awk '{print $1}' || true)"
    if [ -n "$want_sha" ]; then
        have_sha="$(sha1sum "$out" | awk '{print $1}')"
        if [ "$want_sha" != "$have_sha" ]; then
            echo "::warning::SHA1 не совпал для $name (ожидалось $want_sha, получено $have_sha)"
        else
            echo "SHA1 OK: $name"
        fi
    fi

    echo "Скачано: $name ($(stat -c%s "$out") байт)"
    return 0
}

download_one "android-all-instrumented-8.0.0_r4-robolectric-r1-i7.jar" "8.0.0_r4-robolectric-r1-i7" || true
download_one "android-all-instrumented-14-robolectric-10818077-i7.jar" "14-robolectric-10818077-i7" || true
download_one "android-all-instrumented-15-robolectric-12650502-i7.jar" "15-robolectric-12650502-i7" || true

if [ "$fail" -ne 0 ]; then
    exit 1
fi

echo "Все SDK-джарки Robolectric на месте: $DIR"
