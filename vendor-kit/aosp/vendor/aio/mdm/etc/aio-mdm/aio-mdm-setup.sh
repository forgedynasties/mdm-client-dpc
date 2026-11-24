#!/system/bin/sh
# AIO MDM first-boot setup. Started by init (aio-mdm.rc) as user shell, domain shell, after
# sys.boot_completed=1. It does what `adb shell` would do at a factory station:
#
#   1. make aio.app.mdmclient.dpc the Device Owner (dpm set-device-owner)
#   2. allow the two app-ops a Device Owner cannot give itself (usage stats, screen capture)
#
# Runs once per factory reset: after success it leaves /data/local/tmp/aio-mdm-setup.done.
# If Device Owner cannot be set yet (for example an account was added), it logs why and
# tries again on the next boot. Log: logcat -s aio-mdm-setup, and
# /data/local/tmp/aio-mdm-setup.log

PKG=aio.app.mdmclient.dpc
ADMIN=$PKG/.MdmDeviceAdminReceiver
MARK=/data/local/tmp/aio-mdm-setup.done
LOGF=/data/local/tmp/aio-mdm-setup.log

say() {
    /system/bin/log -t aio-mdm-setup "$*"
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> $LOGF 2>/dev/null
}

[ -f $MARK ] && exit 0
say "start (sdk $(getprop ro.build.version.sdk), build $(getprop ro.build.fingerprint))"

# The package manager may still be scanning right after boot_completed.
n=0
while ! /system/bin/pm path $PKG >/dev/null 2>&1; do
    n=$((n + 1))
    if [ $n -ge 60 ]; then
        say "FAIL: $PKG is not installed in this image"
        exit 1
    fi
    sleep 2
done

owner_info() {
    /system/bin/dpm list-owners 2>/dev/null
    /system/bin/dumpsys device_policy 2>/dev/null | grep -A2 "Device Owner"
}

case "$(owner_info)" in
    *"$PKG"*)
        say "already Device Owner"
        ;;
    *)
        out=$(/system/bin/dpm set-device-owner $ADMIN 2>&1)
        case "$out" in
            *Success*)
                say "Device Owner set"
                ;;
            *"already set"*|*"already has a device owner"*)
                # Android allows one Device Owner; only a factory reset removes it.
                say "FAIL: another Device Owner is set, leaving it alone: $(echo $out | tr '\n' ' ')"
                touch $MARK
                exit 0
                ;;
            *)
                say "FAIL: set-device-owner: $(echo $out | tr '\n' ' ') (will try again next boot)"
                exit 1
                ;;
        esac
        ;;
esac

# Grants the agent cannot give itself. Privileged permissions already come from
# privapp-permissions-aio-mdm.xml; these lines only matter on an image that skipped it.
for p in android.permission.READ_LOGS android.permission.WRITE_SECURE_SETTINGS; do
    /system/bin/pm grant $PKG $p >/dev/null 2>&1
done
/system/bin/cmd appops set $PKG GET_USAGE_STATS allow >/dev/null 2>&1 || say "appops GET_USAGE_STATS failed"
/system/bin/cmd appops set $PKG PROJECT_MEDIA allow >/dev/null 2>&1 || say "appops PROJECT_MEDIA failed"

touch $MARK
say "done"
exit 0
