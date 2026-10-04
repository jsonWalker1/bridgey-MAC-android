#!/bin/sh
set -eu

SDK_PATH="${SDKROOT:-$(xcrun --sdk macosx --show-sdk-path)}"
CACHE_ROOT="${TMPDIR:-/private/tmp}/bridgey-swift-build"
CONFIGURATION="${CONFIGURATION:-debug}"
APP_PATH=".build/$CONFIGURATION/Bridgey.app"
# The SwiftPM package lives at the repository root (domain source tree); this script runs in macos/.
PACKAGE_ROOT=".."

env \
  SDKROOT="$SDK_PATH" \
  CLANG_MODULE_CACHE_PATH="$CACHE_ROOT/clang" \
  SWIFTPM_MODULECACHE_OVERRIDE="$CACHE_ROOT/swift" \
  swift build -c "$CONFIGURATION" --package-path "$PACKAGE_ROOT"

mkdir -p "$APP_PATH/Contents/MacOS"
mkdir -p "$APP_PATH/Contents/Resources"
cp "$PACKAGE_ROOT/.build/$CONFIGURATION/BridgeyMac" "$APP_PATH/Contents/MacOS/BridgeyMac"
cp "Resources/Info.plist" "$APP_PATH/Contents/Info.plist"
for localization in Resources/*.lproj; do
  [ -d "$localization" ] || continue
  cp -R "$localization" "$APP_PATH/Contents/Resources/"
done
python3 ./create-icns.py \
  "Resources/AppIcon.iconset" \
  "$APP_PATH/Contents/Resources/Bridgey.icns"

# macOS 26 prefers an app icon compiled into Assets.car, while older releases
# still use Bridgey.icns. Build both from the same reviewed source images.
if xcrun --find actool >/dev/null 2>&1; then
  ASSET_CATALOG="$CACHE_ROOT/BridgeyAssets.xcassets"
  APP_ICON_SET="$ASSET_CATALOG/AppIcon.appiconset"
  rm -rf "$ASSET_CATALOG"
  mkdir -p "$APP_ICON_SET"
  cp Resources/AppIcon.iconset/*.png "$APP_ICON_SET/"
  cp Resources/AppIconContents.json "$APP_ICON_SET/Contents.json"
  xcrun actool "$ASSET_CATALOG" \
    --compile "$APP_PATH/Contents/Resources" \
    --platform macosx \
    --minimum-deployment-target 13.0 \
    --app-icon AppIcon \
    --output-partial-info-plist "$CACHE_ROOT/BridgeyAppIcon.plist"
  test -f "$APP_PATH/Contents/Resources/Assets.car"
else
  echo "warning: actool is unavailable; keeping the compatible Bridgey.icns icon only" >&2
fi
# Local development builds prefer a free "Apple Development" certificate when one exists in the
# keychain (create it once in Xcode → Settings → Accounts → Manage Certificates). Its Team ID gives
# every build the same code identity, so macOS stops asking for Keychain access after each update;
# ad-hoc signatures change identity on every build. Set BRIDGEY_ADHOC_SIGNING=1 to opt out.
DEVELOPMENT_IDENTITY=""
if [ -z "${MACOS_SIGNING_IDENTITY:-}" ] && [ -z "${BRIDGEY_ADHOC_SIGNING:-}" ]; then
  DEVELOPMENT_IDENTITY="$(security find-identity -v -p codesigning 2>/dev/null \
    | sed -n 's/.*"\(Apple Development: [^"]*\)".*/\1/p' | head -n 1)"
fi
if [ -n "${MACOS_SIGNING_IDENTITY:-}" ]; then
  codesign --force --deep --options runtime --timestamp --entitlements Resources/Bridgey.entitlements --sign "$MACOS_SIGNING_IDENTITY" "$APP_PATH"
elif [ -n "$DEVELOPMENT_IDENTITY" ]; then
  echo "Signing with $DEVELOPMENT_IDENTITY" >&2
  codesign --force --deep --options runtime --timestamp=none \
    --entitlements Resources/Bridgey.entitlements --sign "$DEVELOPMENT_IDENTITY" "$APP_PATH"
else
  # Keep an explicit, stable designated requirement for ad-hoc builds. System
  # services such as Notification Center and Local Network privacy otherwise
  # see only a changing code hash after each update and can lose bundle metadata.
  codesign --force --deep --sign - \
    --entitlements Resources/Bridgey.entitlements \
    --requirements '=designated => identifier "dev.bridgey.mac"' \
    "$APP_PATH"
fi

echo "$APP_PATH"
