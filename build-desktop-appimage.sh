#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_DIR="$ROOT_DIR/V2rayNG/desktopApp"
BIN_DIR="$DESKTOP_DIR/bin"
ASSETS_DIR="$DESKTOP_DIR/assets"
BUILD_BINARIES_DIR="$DESKTOP_DIR/build/compose/binaries/main"
APP_DIR="$BUILD_BINARIES_DIR/app/v2whitelist"
APPIMAGE_BUILD_DIR="/tmp/v2w-appimage-build/AppDir"
RUNTIME_FILE="${HOME}/.local/lib/appimagetool/runtime-x86_64"

echo "=== 1. Проверка бинарников ядра и сканера ==="
mkdir -p "$BIN_DIR" "$ASSETS_DIR"

if [ -d "$ROOT_DIR/v2w-core" ]; then
    echo "Сборка v2w-scan..."
    pushd "$ROOT_DIR/v2w-core" >/dev/null
    CGO_ENABLED=0 go build -trimpath -tags "netgo osusergo" -ldflags="-s -w -extldflags '-static'" -o "$BIN_DIR/v2w-scan" ./cmd/v2w-scan
    popd >/dev/null
fi

if [ -f "$ROOT_DIR/V2rayNG/app/src/main/assets/geoip.dat" ]; then
    cp -u "$ROOT_DIR/V2rayNG/app/src/main/assets/geoip.dat" "$ASSETS_DIR/"
fi
if [ -f "$ROOT_DIR/V2rayNG/app/src/main/assets/geosite.dat" ]; then
    cp -u "$ROOT_DIR/V2rayNG/app/src/main/assets/geosite.dat" "$ASSETS_DIR/"
fi

chmod +x "$BIN_DIR"/* || true

echo "=== 2. Сборка Compose Desktop (packageAppImage) ==="
pushd "$ROOT_DIR/V2rayNG" >/dev/null
./gradlew :desktopApp:packageAppImage
popd >/dev/null

echo "=== 3. Подготовка дистрибутива в $APP_DIR ==="
mkdir -p "$APP_DIR/bin"
mkdir -p "$APP_DIR/lib/app/resources/bin"
mkdir -p "$APP_DIR/lib/app/resources/assets"

cp -a "$BIN_DIR"/* "$APP_DIR/bin/"
cp -a "$BIN_DIR"/* "$APP_DIR/lib/app/resources/bin/"
cp -a "$ASSETS_DIR"/*.dat "$APP_DIR/lib/app/resources/assets/"
cp -a "$ASSETS_DIR"/*.dat "$APP_DIR/lib/app/resources/"
cp -a "$DESKTOP_DIR/src/desktopMain/resources/icon.png" "$APP_DIR/lib/app/resources/"
chmod +x "$APP_DIR/bin"/* "$APP_DIR/lib/app/resources/bin"/* || true

echo "=== 4. Сборка архива tar.gz ==="
pushd "$BUILD_BINARIES_DIR" >/dev/null
tar -czf v2whitelist-linux-x86_64.tar.gz app/v2whitelist
popd >/dev/null

echo "=== 5. Сборка автономного AppImage ==="
rm -rf "$APPIMAGE_BUILD_DIR"
mkdir -p "$APPIMAGE_BUILD_DIR"
cp -a "$APP_DIR"/* "$APPIMAGE_BUILD_DIR"/

cat << 'EOF' > "$APPIMAGE_BUILD_DIR/AppRun"
#!/bin/sh
HERE="$(dirname "$(readlink -f "${0}")")"
export APPDIR="${APPDIR:-$HERE}"
export PATH="$HERE/bin:$PATH"
exec "$HERE/bin/v2whitelist" "$@"
EOF
chmod +x "$APPIMAGE_BUILD_DIR/AppRun"

cat << 'EOF' > "$APPIMAGE_BUILD_DIR/v2whitelist.desktop"
[Desktop Entry]
Type=Application
Name=v2whitelist
Exec=v2whitelist %u
Icon=v2whitelist
Categories=Network;
Comment=ПроБел — v2whitelist Desktop
Terminal=false
EOF

cp "$DESKTOP_DIR/src/desktopMain/resources/icon.png" "$APPIMAGE_BUILD_DIR/v2whitelist.png"
ln -sf v2whitelist.png "$APPIMAGE_BUILD_DIR/.DirIcon"

APPIMAGETOOL_BIN="$(command -v appimagetool || echo "${HOME}/.local/bin/appimagetool")"
if [ ! -x "$APPIMAGETOOL_BIN" ]; then
    echo "Ошибка: appimagetool не найден."
    exit 1
fi

RUNTIME_ARG=""
if [ -f "$RUNTIME_FILE" ]; then
    RUNTIME_ARG="--runtime-file $RUNTIME_FILE"
fi

ARCH=x86_64 "$APPIMAGETOOL_BIN" --no-appstream $RUNTIME_ARG "$APPIMAGE_BUILD_DIR" "$BUILD_BINARIES_DIR/v2whitelist-linux-x86_64.AppImage"

echo "=== Готово! Файлы сборки: ==="
ls -lh "$BUILD_BINARIES_DIR"/v2whitelist-linux-x86_64.*
