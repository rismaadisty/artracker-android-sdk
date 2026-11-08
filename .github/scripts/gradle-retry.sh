#!/usr/bin/env bash
# Menjalankan ./gradlew dan mengulangnya HANYA bila kegagalan berasal dari unduhan jaringan (504, timeout, koneksi
# putus). Kegagalan test, kompilasi, atau lint tidak diulang, supaya test yang flaky tidak tersembunyi.
set -uo pipefail

max_attempts="${GRADLE_RETRY_ATTEMPTS:-4}"
base_delay="${GRADLE_RETRY_DELAY:-30}"
# Hanya pola error unduhan milik Gradle; frasa umum seperti "Read timed out" bisa muncul di log test IngestClient.
network_pattern='Could not (GET|HEAD|download)|Could not resolve all (files|dependencies)|Received status code 5[0-9][0-9]'

for attempt in $(seq 1 "$max_attempts"); do
  log="$(mktemp)"
  ./gradlew "$@" 2>&1 | tee "$log"
  status="${PIPESTATUS[0]}"
  if [ "$status" -eq 0 ]; then
    rm -f "$log"
    exit 0
  fi
  if [ "$attempt" -lt "$max_attempts" ] && grep -qE "$network_pattern" "$log"; then
    delay=$((attempt * base_delay))
    echo "::warning::Gradle gagal karena unduhan jaringan (percobaan ${attempt} dari ${max_attempts}); mengulang dalam ${delay} detik"
    rm -f "$log"
    sleep "$delay"
    continue
  fi
  rm -f "$log"
  exit "$status"
done
