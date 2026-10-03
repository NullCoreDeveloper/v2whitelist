#!/bin/bash
# Сборка libv2ray.aar с пропатченным Xray-core (мусорные пакеты в WG-хендшейке, см. patches/xray-wg-junk.patch).
#
# Что делает:
#   1. берёт версию xray-core, которую использует AndroidLibXrayLite (go.mod), из Go mod cache;
#   2. копирует её в build-core/xray-core и накладывает patches/xray-wg-junk.patch;
#   3. копирует AndroidLibXrayLite в build-core/libv2ray, подменяет xray-core через `replace`;
#   4. собирает aar через gomobile и кладёт в V2rayNG/app/libs/libv2ray.aar.
#
# Сабмодуль AndroidLibXrayLite не изменяется. build-core/ в .gitignore.
#
# Требуется: go, gomobile (~/go/bin), Android SDK + NDK (ANDROID_HOME / ANDROID_NDK_HOME).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB_SRC="$ROOT/AndroidLibXrayLite"
BUILD="$ROOT/build-core"
PATCH="$ROOT/patches/xray-wg-junk.patch"
OUT="$ROOT/V2rayNG/app/libs/libv2ray.aar"

export PATH="$PATH:$HOME/go/bin"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -n1)"
    export ANDROID_NDK_HOME
fi

XRAY_VER="$(grep -E 'github.com/xtls/xray-core ' "$LIB_SRC/go.mod" | awk '{print $2}')"
XRAY_CACHE="$(go env GOMODCACHE)/github.com/xtls/xray-core@${XRAY_VER}"
[[ -d "$XRAY_CACHE" ]] || { echo "xray-core $XRAY_VER не найден в mod cache: $XRAY_CACHE (выполни go mod download в AndroidLibXrayLite)"; exit 1; }

echo "[*] xray-core $XRAY_VER, NDK $ANDROID_NDK_HOME"
rm -rf "$BUILD/xray-core" "$BUILD/libv2ray"
mkdir -p "$BUILD"

echo "[*] Копирую и патчу xray-core"
cp -r "$XRAY_CACHE" "$BUILD/xray-core"
chmod -R u+w "$BUILD/xray-core"
(cd "$BUILD/xray-core" && patch -p1 < "$PATCH")

echo "[*] Копирую AndroidLibXrayLite"
mkdir -p "$BUILD/libv2ray"
(cd "$LIB_SRC" && tar --exclude=.git --exclude='*.aar' --exclude='*.jar' -cf - .) | (cd "$BUILD/libv2ray" && tar -xf -)
cd "$BUILD/libv2ray"
echo "replace github.com/xtls/xray-core => ../xray-core" >> go.mod

# Патчим anet для совместимости с Go 1.26
ANET_DIR="$(go env GOMODCACHE)/github.com/wlynxg/anet@v0.0.5"
if [[ -d "$ANET_DIR" ]]; then
    chmod -R u+w "$ANET_DIR" 2>/dev/null || true
    sed -i '/\/\/go:linkname zoneCache/d'   "$ANET_DIR/interface_android.go" || true
    sed -i '/var zoneCache ipv6ZoneCache/d'  "$ANET_DIR/interface_android.go" || true
    sed -i '/\/\/go:linkname zoneCacheX/d'  "$ANET_DIR/interface_android.go" || true
    sed -i '/var zoneCacheX ipv6ZoneCache/d' "$ANET_DIR/interface_android.go" || true
    sed -i '/zoneCache\.update/d'            "$ANET_DIR/interface_android.go" || true
    sed -i '/zoneCacheX\.update/d'           "$ANET_DIR/interface_android.go" || true
fi

# Добавляем привязки v2wscanner для V2WScannerEngine в Android-приложении
V2W_ABS="$(readlink -f "$ROOT/v2w-core")"
sed -i '/mobasset "golang.org\/x\/mobile\/asset"/a \ \t"github.com/kiktor/v2w-core/v2wscanner"' libv2ray_main.go
printf '\ntype V2WScanCallback interface {\n\tOnServerSuccess(configUrl string, delay int64)\n\tOnScanComplete(totalSuccess int64, totalFailed int64)\n}\n\nfunc RunV2WScanner(configs string, maxConcurrency int64, callback V2WScanCallback) {\n\tv2wscanner.RunV2WScanner(configs, maxConcurrency, callback)\n}\n\nfunc StopV2WScanner() {\n\tv2wscanner.StopV2WScanner()\n}\n' >> libv2ray_main.go

go mod edit -require github.com/kiktor/v2w-core@v0.0.0
go mod edit -replace "github.com/kiktor/v2w-core=$V2W_ABS"
GOWORK=off go mod tidy

echo "[*] gomobile bind"
gomobile init
GOWORK=off gomobile bind -v -target=android -androidapi 24 -trimpath -ldflags='-s -w -buildid= -checklinkname=0' ./

if [[ -f "$OUT" && ! -f "$OUT.orig" ]]; then
    cp "$OUT" "$OUT.orig"
fi
cp -v libv2ray.aar "$OUT"
[[ -f libv2ray-sources.jar ]] && cp -v libv2ray-sources.jar "$(dirname "$OUT")/libv2ray-sources.jar"
echo "[+] Готово: $OUT"

