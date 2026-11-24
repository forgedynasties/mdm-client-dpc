#!/usr/bin/env bash
# Build the AIO MDM vendor kit: what a hardware vendor needs to put the Standard client
# into their own Android image as a privileged system app that becomes Device Owner and
# enrolls itself on first boot. (AIO side only; the vendor never runs this.)
#
#   vendor-kit/make-kit.sh -s https://mdm.aioapp.com -t enr_XXXX -n sunmi
#   vendor-kit/make-kit.sh -s ... -t ... -n sunmi --apk path/to/app-release.apk
#
#   -s  server the devices enroll with
#   -t  enrollment token: make one enrollment profile PER VENDOR, with no expiry (a factory
#       image lives for years) and no device limit; never revoke or delete it while that
#       vendor's images are in use, or new devices can't enroll and lost keys can't recover
#   -n  vendor short name, used in file names
#   --apk  a signed release APK; else built here (needs keystore/signing.env)
#   -o  output directory (default ~/aio-mdm/vendor-kits). The kit holds the token: it is
#       never written inside a repo.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
SERVER=""; TOKEN=""; NAME=""; APK=""; OUT="$HOME/aio-mdm/vendor-kits"
EXPECT_CERT="13e63dda66370e750851fbe6b31f70f7f49ce9cc27cd482ed1e517e614bc8507"
MIN_CODE=57   # 0.16.0: reads aio-mdm.conf (FactoryConfig) and READ_PRIVILEGED_PHONE_STATE
while [ $# -gt 0 ]; do
  case "$1" in
    -s) SERVER="${2%/}"; shift 2 ;;
    -t) TOKEN="$2"; shift 2 ;;
    -n) NAME="$2"; shift 2 ;;
    -o) OUT="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    -h|--help) sed -n 2,19p "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
[ -n "$SERVER" ] && [ -n "$TOKEN" ] && [ -n "$NAME" ] || { echo "need -s SERVER -t TOKEN -n VENDOR (see -h)" >&2; exit 2; }
case "$NAME" in *[!a-z0-9-]*) echo "-n: lowercase letters, digits and - only" >&2; exit 2 ;; esac
case "$OUT" in "$REPO"*|*/aio-mdm-web*) echo "-o must be outside the repos (the kit holds the token)" >&2; exit 2 ;; esac

BT="${ANDROID_HOME:?ANDROID_HOME not set}/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"

if [ -z "$APK" ]; then
  [ -f "$REPO/keystore/signing.env" ] || { echo "no --apk and no keystore/signing.env" >&2; exit 1; }
  # shellcheck disable=SC1091
  set -a; source "$REPO/keystore/signing.env"; set +a
  (cd "$REPO" && ./gradlew :app:assembleRelease -PmdmServerUrl="$SERVER" --console=plain -q)
  APK="$REPO/app/build/outputs/apk/release/app-release.apk"
fi
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }

# The APK must be installable untouched by the AOSP build and updatable by us afterwards.
badging=$("$BT/aapt2" dump badging "$APK")
pkg=$(echo "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
code=$(echo "$badging" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
ver=$(echo "$badging" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
[ "$pkg" = "aio.app.mdmclient.dpc" ] || { echo "wrong package: $pkg (a debug build?)" >&2; exit 1; }
[ "$code" -ge "$MIN_CODE" ] || { echo "versionCode $code < $MIN_CODE: this agent cannot enroll from aio-mdm.conf" >&2; exit 1; }
cert=$("$BT/apksigner" verify --print-certs "$APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ "$cert" = "$EXPECT_CERT" ] || { echo "signed with $cert, not the AIO release key" >&2; exit 1; }
"$BT/zipalign" -c -p 4 "$APK" || { echo "APK not page-aligned" >&2; exit 1; }
unzip -v "$APK" | awk '$NF ~ /\.dex$/ && $2 != "Stored" {bad=1} END {exit bad}' \
  || { echo "dex is compressed: Soong refuses a preprocessed priv-app like that" >&2; exit 1; }
if unzip -l "$APK" | grep -q ' lib/.*\.so$'; then echo "APK now has native libs: check Android.mk/Android.bp handling first" >&2; exit 1; fi

KIT="aio-mdm-vendor-kit-$ver-$NAME"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
D="$WORK/$KIT"
mkdir -p "$D/vendor/aio" "$D/tools"
cp -r "$HERE/aosp/vendor/aio/mdm" "$D/vendor/aio/"
cp "$APK" "$D/vendor/aio/mdm/AioMdm.apk"
cat > "$D/vendor/aio/mdm/etc/aio-mdm/aio-mdm.conf" <<EOF
# AIO MDM factory enrollment for $NAME. Made by AIO on $(date -u +%Y-%m-%d): do not edit.
# The agent reads this once, on first boot, and enrolls the device with the AIO MDM server.
server_url=$SERVER
enroll_token=$TOKEN
EOF
chmod 644 "$D/vendor/aio/mdm/etc/aio-mdm/aio-mdm.conf"

# 0001 patch: creates vendor/aio/mdm at the tree root (APK included, binary).
P="$WORK/patchrepo"; mkdir -p "$P/vendor/aio"; cp -r "$D/vendor/aio/mdm" "$P/vendor/aio/"
(
  cd "$P" && git init -q && git add -A
  GIT_AUTHOR_DATE="$(date -R)" git -c user.name="AIO MDM" -c user.email="" \
    commit -q -m "vendor/aio/mdm: add the AIO MDM agent $ver as a privileged app" \
    -m "Device Owner on first boot, enrolls itself with $SERVER. See GUIDE.en.md / GUIDE.zh-CN.md."
  git format-patch -q --binary --root -1 -o "$D"
)
mv "$D"/0001-*.patch "$D/0001-vendor-aio-mdm-add-AIO-MDM-agent.patch"

cp "$HERE/GUIDE.en.md" "$HERE/GUIDE.zh-CN.md" "$D/"
cp "$HERE/tools/verify-device.sh" "$D/tools/"
cp "$APK" "$D/AioMdm.apk"
(cd "$D" && sha256sum AioMdm.apk > AioMdm.apk.sha256)
cat > "$D/KIT-INFO.txt" <<EOF
AIO MDM vendor kit / AIO MDM 厂商集成套件
vendor / 厂商     $NAME
agent / 客户端    aio.app.mdmclient.dpc $ver (code $code)
signer SHA-256   $cert
apk SHA-256      $(cut -d' ' -f1 "$D/AioMdm.apk.sha256")
server / 服务器   $SERVER
made / 生成日期    $(date -u +%Y-%m-%dT%H:%MZ)
EOF

mkdir -p "$OUT"; chmod 700 "$OUT"
rm -rf "${OUT:?}/$KIT" "$OUT/$KIT.zip"
cp -r "$D" "$OUT/"
(cd "$OUT" && zip -qr "$KIT.zip" "$KIT")
echo "kit: $OUT/$KIT.zip"
echo "     $OUT/$KIT/"
