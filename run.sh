#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

echo "================================================================"
echo "  CS-2 JAVA 4.0 : Dust II • Mirage • Nuke 3D Arena"
echo "================================================================"

if ! command -v javac >/dev/null 2>&1; then
    if command -v java >/dev/null 2>&1 && [ -f "CS-2-JAVA.jar" ]; then
        echo "[INFO] Найден JRE и CS-2-JAVA.jar — запуск игры..."
        exec java -Xms256m -Xmx1024m -Dsun.java2d.uiScale=1.0 -Dfile.encoding=UTF-8 -jar "CS-2-JAVA.jar" "$@"
    fi
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

echo "[2/2] Запуск CS-2 JAVA 4.0..."
exec java -Xms256m -Xmx1024m -Dsun.java2d.uiScale=1.0 -Dfile.encoding=UTF-8 -cp "bin:lib/*" Main "$@"
