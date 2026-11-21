#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

echo "================================================================"
echo "  CS-2 JAVA : Dust II 3D Tactical Arena (Build & Launch)"
echo "================================================================"

if ! command -v javac >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
    echo "[ОШИБКА] Не найден JDK (javac и java)."
    echo "Установите OpenJDK 17 или 21 (например: https://adoptium.net/temurin/releases/)"
    exit 1
fi

mkdir -p lib bin
rm -f bin/*.class

SRC_DIR="src"
if [ ! -f "src/Main.java" ] && [ -f "Main.java" ]; then
    SRC_DIR="."
fi

echo "[1/2] Компиляция ${SRC_DIR}/*.java -> bin/ ..."
javac -encoding UTF-8 -cp "${SRC_DIR}:lib/*" -d bin "${SRC_DIR}"/*.java

echo "[2/2] Запуск CS-2 JAVA..."
exec java -Xms256m -Xmx1024m -Dfile.encoding=UTF-8 -cp "bin:lib/*" Main "$@"
