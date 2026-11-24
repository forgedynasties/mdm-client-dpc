#!/usr/bin/env bash
# AIO MDM — check a device built with the AIO MDM vendor kit. 检查集成了 AIO MDM 的设备。
#
#   tools/verify-device.sh                  # the only device in `adb devices`
#   tools/verify-device.sh -s SERIAL        # one device among several
#   tools/verify-device.sh --apk AioMdm.apk # also prove the APK in the image is untouched
#
# Run it on a device flashed with your new image, after a factory reset and one full boot
# (wait 2 minutes after the home screen). Send AIO the report file it writes.
# 刷入新固件、恢复出厂设置并完整开机（进入桌面后等待 2 分钟）后运行。请把生成的报告文件发给 AIO。
set -uo pipefail

PKG=aio.app.mdmclient.dpc
SER=""; APK=""
while [ $# -gt 0 ]; do
  case "$1" in
    -s) SER="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    -h|--help) sed -n 2,11p "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
command -v adb >/dev/null || { echo "adb not found in PATH / 未找到 adb" >&2; exit 2; }
A=(adb); [ -n "$SER" ] && A=(adb -s "$SER")
"${A[@]}" get-state >/dev/null 2>&1 || { echo "no device / 没有连接设备 (adb devices)" >&2; exit 2; }
sh_() { "${A[@]}" shell "$@" 2>/dev/null | tr -d '\r'; }

serial=$(sh_ getprop ro.serialno)
REPORT="aio-mdm-verify-${serial:-unknown}-$(date +%Y%m%d-%H%M%S).txt"
pass=0; fail=0; warn=0
out() { echo "$*" | tee -a "$REPORT"; }
ok()  { pass=$((pass+1)); out "  PASS  $*"; }
bad() { fail=$((fail+1)); out "  FAIL  $*"; }
meh() { warn=$((warn+1)); out "  WARN  $*"; }

out "AIO MDM vendor kit check — $(date)"
out "  device      $(sh_ getprop ro.product.manufacturer) $(sh_ getprop ro.product.model) ($(sh_ getprop ro.product.name))"
out "  serial      $serial"
out "  android     $(sh_ getprop ro.build.version.release) (sdk $(sh_ getprop ro.build.version.sdk))"
out "  build       $(sh_ getprop ro.build.fingerprint)"
out "  build type  $(sh_ getprop ro.build.type)"
out ""

# 1. Installed as a privileged system app 以特权系统应用安装
out "[1] App in the image / 应用已内置"
path=$(sh_ pm path $PKG | sed -n 's/^package://p' | head -1)
if [ -z "$path" ]; then
  bad "$PKG is not installed / 未安装"
else
  case "$path" in
    /system/priv-app/*|/product/priv-app/*|/system_ext/priv-app/*) ok "installed at $path" ;;
    /data/*) bad "installed in /data ($path): the image copy is missing or was replaced by adb install / 镜像中的副本缺失" ;;
    *) bad "installed at $path, expected a priv-app directory / 应位于 priv-app 目录" ;;
  esac
fi
dump=$(sh_ dumpsys package $PKG)
ver=$(echo "$dump" | sed -n 's/.*versionName=//p' | head -1)
out "        version $ver"
echo "$dump" | grep -q "pkgFlags=\[.*SYSTEM" && ok "system app (FLAG_SYSTEM)" || bad "not a system app / 不是系统应用"
echo "$dump" | grep -qE "privateFlags=\[.*PRIVILEGED|PRIVILEGED" && ok "privileged" || bad "not privileged: put it in priv-app (LOCAL_PRIVILEGED_MODULE := true) / 不是特权应用"

# 2. APK untouched (not re-signed) APK 未被重新签名
out "[2] APK not re-signed / APK 未被重新签名"
if [ -n "$path" ]; then
  dev_sha=$(sh_ sha256sum "$path" | awk '{print $1}')
  out "        on device sha256 $dev_sha"
  if [ -n "$APK" ] && [ -f "$APK" ]; then
    kit_sha=$( (sha256sum "$APK" 2>/dev/null || shasum -a 256 "$APK") | awk '{print $1}')
    [ "$dev_sha" = "$kit_sha" ] && ok "identical to the kit's AioMdm.apk" \
      || bad "differs from the kit's AioMdm.apk: it was re-signed, re-aligned or replaced / 与套件中的 APK 不一致（被重新签名或修改）"
  else
    meh "run with --apk AioMdm.apk to compare / 使用 --apk 参数比对"
  fi
fi

# 3. Permissions 权限
out "[3] Permissions / 权限"
for p in READ_PRIVILEGED_PHONE_STATE READ_LOGS PACKAGE_USAGE_STATS WRITE_SECURE_SETTINGS; do
  echo "$dump" | grep -q "android.permission.$p: granted=true" && ok "$p" \
    || bad "$p not granted: privapp-permissions-aio-mdm.xml missing or in another partition / 白名单文件缺失或分区不一致"
done
echo "$dump" | grep -q "android.permission.READ_PHONE_STATE: granted=true" && ok "READ_PHONE_STATE" \
  || meh "READ_PHONE_STATE not granted (default-permissions-aio-mdm.xml); the Device Owner grants it later"
priv_mode=$(sh_ getprop ro.control_privapp_permissions)
out "        ro.control_privapp_permissions=${priv_mode:-<unset>}"

# 4. Files 配置文件
out "[4] Files / 文件"
conf=""
for d in /system /system_ext /product /odm /vendor; do
  if [ -n "$(sh_ ls $d/etc/aio-mdm/aio-mdm.conf)" ]; then conf=$d/etc/aio-mdm/aio-mdm.conf; break; fi
done
if [ -n "$conf" ]; then
  srv=$(sh_ cat "$conf" | sed -n 's/^server_url=//p')
  tok=$(sh_ cat "$conf" | sed -n 's/^enroll_token=//p')
  case "$srv$tok" in
    *SERVER*|*TOKEN*|"") bad "$conf still has placeholders / 仍是占位符，请使用 AIO 提供的套件" ;;
    *) ok "$conf → $srv" ;;
  esac
else
  bad "aio-mdm.conf not found in any etc/aio-mdm / 未找到 aio-mdm.conf"
fi
rc=""
for d in /system /system_ext /product; do
  [ -n "$(sh_ ls $d/etc/init/aio-mdm.rc)" ] && rc=$d/etc/init/aio-mdm.rc
done
[ -n "$rc" ] && ok "$rc" || bad "aio-mdm.rc not found in etc/init / 未找到 aio-mdm.rc"

# 5. Device Owner 设备所有者
out "[5] Device Owner / 设备所有者"
owners="$(sh_ dpm list-owners) $(sh_ dumpsys device_policy | grep -A2 'Device Owner')"
if echo "$owners" | grep -q "$PKG"; then
  ok "AIO MDM is Device Owner"
else
  bad "AIO MDM is not Device Owner / 未成为设备所有者 — setup log below"
fi
log=$(sh_ cat /data/local/tmp/aio-mdm-setup.log)
if [ -n "$log" ]; then out "        setup log /data/local/tmp/aio-mdm-setup.log:"; echo "$log" | sed 's/^/          /' | tee -a "$REPORT"
else meh "no setup log: init did not start aio_mdm_setup (check aio-mdm.rc, logcat -s init) / 没有日志：init 未启动脚本"; fi

# 6. App-ops 应用操作权限
out "[6] App-ops"
for op in GET_USAGE_STATS PROJECT_MEDIA; do
  sh_ cmd appops get $PKG $op | grep -q allow && ok "$op allow" || meh "$op not allowed (set by aio-mdm-setup.sh after Device Owner)"
done

# 7. Running and enrolled 正在运行并已注册
out "[7] Agent / 客户端"
[ -n "$(sh_ pidof $PKG)" ] && ok "agent process running" || bad "agent not running / 客户端未运行"
sh_ dumpsys deviceidle whitelist | grep -q "$PKG" && ok "in the battery-optimisation whitelist" \
  || meh "not in the power-save whitelist (aio-mdm-sysconfig.xml)"
if sh_ logcat -d -s MdmService:I FactoryConfig:I | grep -qE "Enrolled|Re-enrolled"; then
  ok "enrolled with the AIO MDM server"
else
  meh "no 'Enrolled' line in logcat yet: check network, then send AIO the serial $serial / 未在日志中看到注册成功，请检查网络并把序列号发给 AIO"
fi

out ""
out "Result / 结果: $pass pass, $fail fail, $warn warn"
out "Report / 报告: $REPORT"
[ $fail -eq 0 ]
