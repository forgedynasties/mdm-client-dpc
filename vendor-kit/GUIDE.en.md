# AIO MDM — Vendor integration guide

**For:** hardware vendors who build Android images for AIO devices
**Covers:** Android 9 – 15 (API 28 – 35), any CPU, with or without Google Mobile Services
**Chinese version:** `GUIDE.zh-CN.md`

---

## 1. What this is

AIO manages its devices with **AIO MDM**. The device side is one app, the **AIO MDM agent**
(package `aio.app.mdmclient.dpc`). We ask you to build it into your system image, so that
every device leaves the factory already managed.

You add **one directory** (`vendor/aio/mdm`) and **one line** to your device makefile.
After that, a device built from your image does this on its own:

1. **Install.** The agent is installed from the image as a privileged system app.
2. **Device Owner.** As soon as the first boot completes, a one-shot init service makes the agent
   the device's **Device Owner**. This is the same thing `adb shell dpm set-device-owner`
   does.
3. **Enroll.** Once the device has a network, the agent enrolls itself with the AIO MDM server
   using the settings in `aio-mdm.conf`. The device then appears on AIO's dashboard.

Nobody needs to touch the device: no adb, no QR code, no typing.

**Agent updates are AIO's job.** AIO updates the agent over the network. You do **not**
rebuild your image when the agent changes. The copy in your image is only the first version a
device runs.

---

## 2. Rules (please read before you start)

| # | Rule | Why |
|---|------|-----|
| R1 | **Never re-sign the APK.** Do not sign it with your platform key or any other key. Keep it `PRESIGNED`, including in release signing scripts such as `sign_target_files_apks`. | Android only accepts an update signed with the same key as the installed copy. If you re-sign it, AIO can never update the agent on those devices. |
| R2 | **Install the APK byte for byte.** No zipalign, no dex uncompression, no stripping. The kit's build files already do this. | Any change breaks the APK's v2/v3 signature, and the device refuses the app. |
| R3 | **Keep the package name and the files' names.** | The Device Owner record stores the component `aio.app.mdmclient.dpc/.MdmDeviceAdminReceiver`. |
| R4 | **Put the APK and its XML files in the same partition** (system, product or system_ext). | Android reads the permission allowlist from the APK's own partition. If the allowlist is missing, **the device does not boot**. |
| R5 | **Do not preinstall another MDM or Device Owner app,** and do not add an account during factory setup. | Android allows one Device Owner, and only on a device with no accounts and no other users. |
| R6 | **Do not edit `aio-mdm.conf`.** | It holds the server and the enrollment token AIO made for you. |
| R7 | **Keep the source tree private.** | `aio-mdm.conf` holds an enrollment credential (see section 9). |
| R8 | **`ro.serialno` must be the unique serial printed on the device label.** | AIO identifies each device by it. |

---

## 3. What is in the kit

```
aio-mdm-vendor-kit-<version>-<vendor>/
├── 0001-vendor-aio-mdm-add-AIO-MDM-agent.patch   the whole vendor/aio/mdm directory as one patch
├── vendor/aio/mdm/                               the same directory, ready to copy
│   ├── Android.mk                  prebuilt module, Android 9–15 (default)
│   ├── Android.bp.example          Soong version, only if your tree refuses Android.mk
│   ├── aio-mdm.mk                  product makefile: you inherit this one
│   ├── AioMdm.apk                  the agent, signed by AIO
│   ├── etc/permissions/privapp-permissions-aio-mdm.xml       privileged permission allowlist
│   ├── etc/default-permissions/default-permissions-aio-mdm.xml  runtime permissions at first boot
│   ├── etc/sysconfig/aio-mdm-sysconfig.xml                   no Doze / Data Saver limits
│   ├── etc/aio-mdm/aio-mdm.conf                              server + enrollment token (from AIO)
│   ├── etc/aio-mdm/aio-mdm-setup.sh                          first-boot Device Owner script
│   ├── init/aio-mdm.{system,product,system_ext}.rc           starts the script after boot
│   └── sepolicy-optional/aio_mdm_thermal.te                  optional: CPU temperature
├── tools/verify-device.sh          checks a finished device over adb and writes a report
├── AioMdm.apk, AioMdm.apk.sha256   the APK again, with its checksum
├── KIT-INFO.txt                    version, signer and server of this kit
├── GUIDE.en.md, GUIDE.zh-CN.md     this guide
```

---

## 4. Integration steps

### 4.1 Add the directory

From the **root of your Android source tree**, use either the patch or a plain copy:

```bash
# Option A: the patch (it contains the APK as a binary diff, so use git apply, not patch)
git apply --stat  /path/to/0001-vendor-aio-mdm-add-AIO-MDM-agent.patch
git apply         /path/to/0001-vendor-aio-mdm-add-AIO-MDM-agent.patch

# Option B: copy
mkdir -p vendor/aio && cp -r /path/to/kit/vendor/aio/mdm vendor/aio/
```

`git apply` works even when the tree root is not a git repository, which is normal for a
`repo`-managed tree. Use `vendor/aio/mdm` or another directory under `vendor/` or
`device/`. Do **not** use `prebuilts/`, `frameworks/` or the other directories where
Android 13+ blocks `Android.mk` files.

### 4.2 Add one line to your device makefile

In the product makefile your `lunch` target uses (usually `device/<company>/<board>/device.mk`
or `<product>.mk`):

```make
$(call inherit-product, vendor/aio/mdm/aio-mdm.mk)
```

By default everything goes into **/system**. If your product may not add files to /system
(for example `PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS`, or a GSI-compliant system image),
choose another partition **before** the inherit line:

```make
AIO_MDM_PARTITION := system_ext     # Android 11+;  or: product
$(call inherit-product, vendor/aio/mdm/aio-mdm.mk)
```

The `.mk` then installs the APK, the XML files and the init script together in that
partition (rule R4).

### 4.3 Android.mk or Android.bp

Keep **Android.mk** (the default). It works on Android 9 – 15 and installs the APK untouched
through `LOCAL_REPLACE_PREBUILT_APK_INSTALLED`.

Only if your tree refuses Android.mk: rename `Android.bp.example` to `Android.bp`, **delete**
`Android.mk`, and in `Android.bp` uncomment `product_specific` or `system_ext_specific` if you
changed `AIO_MDM_PARTITION`. The Soong module uses `preprocessed: true`. Do not change that
(rule R2).

### 4.4 Release signing

If you sign builds with your own release keys (`sign_target_files_apks`), check that the agent
is still listed as presigned in `META/apkcerts.txt` of your target files:

```
name="AioMdm.apk" certificate="PRESIGNED" private_key=""
```

(The name is `AioMdmProduct.apk` or `AioMdmSystemExt.apk` for the other partitions.) Never
pass `-k` or a key mapping for it.

### 4.5 Optional: CPU temperature

The AIO dashboard shows CPU temperature when the agent can read `/sys/class/thermal`. On many
boards SELinux blocks this for apps. If you want it, add
`sepolicy-optional/aio_mdm_thermal.te` to your device sepolicy (instructions are in the file).
If a `neverallow` rule blocks it, skip this step: everything else works without it.

### 4.6 Build and check the output

```bash
m    # or your usual build command
sha256sum out/target/product/<device>/system/priv-app/AioMdm/AioMdm.apk
cat /path/to/kit/AioMdm.apk.sha256    # the two checksums must match
```

If the checksums differ, the build changed the APK. Stop and check rules R1 and R2.

### 4.7 Flash, reset, boot

1. Flash the image, then **factory reset** (wipe data).
2. Boot to the home screen or through the setup wizard. **Do not add any account.**
3. Connect the device to a network that can reach the internet (Wi-Fi or Ethernet).
4. Wait about **2 minutes**.

### 4.8 Verify and send us the report

On a computer with `adb` (Linux, macOS, or Git Bash/WSL on Windows), with the device
connected:

```bash
tools/verify-device.sh --apk AioMdm.apk
```

Every line should say **PASS**. A **WARN** is not a failure, but tell us about it. The script
writes `aio-mdm-verify-<serial>-<time>.txt`: send it to AIO along with what section 10 asks
for.

USB debugging is only needed for this check. Your production images can ship with it
switched off.

---

## 5. What happens on the device (useful for debugging)

| When | What | Where to see it |
|---|---|---|
| First boot, package scan | `AioMdm.apk` is installed as a privileged system app. Privileged permissions come from `privapp-permissions-aio-mdm.xml`, runtime permissions from `default-permissions-aio-mdm.xml`. | `adb shell dumpsys package aio.app.mdmclient.dpc` |
| `BOOT_COMPLETED` | The agent starts, reads `etc/aio-mdm/aio-mdm.conf`, and enrolls as soon as there is a network. | `adb logcat -s FactoryConfig MdmService` |
| `sys.boot_completed=1` | init starts `aio_mdm_setup` (user shell, domain `u:r:shell:s0`, the same as the AOSP `console` service). It runs `dpm set-device-owner`, then allows two app-ops. | `adb shell cat /data/local/tmp/aio-mdm-setup.log`, `adb logcat -s aio-mdm-setup` |
| After success | The script leaves `/data/local/tmp/aio-mdm-setup.done` and does not run again until the next factory reset. If it failed, it tries again at the next boot. | |
| From then on | The agent keeps one connection to the server, and AIO updates it over the air. | AIO dashboard |

The init service needs **no new SELinux rule**: AOSP already allows `init` to start a shell
process (`domain_trans(init, shell_exec, shell)`). The service runs for a few seconds and then
exits.

---

## 6. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| **Device does not boot**, or boot-loops after adding the kit. Logcat shows `Signature\|privileged permissions not in privapp-permissions allowlist: aio.app.mdmclient.dpc` | The allowlist XML is missing, or it is in a different partition from the APK | Keep `aio-mdm.mk` as given. Make sure `etc/permissions/privapp-permissions-aio-mdm.xml` lands in the same partition as the APK |
| Build error: `Prebuilt, presigned apks with targetSdkVersion >= 30 ... must set preprocessed: true` | You use Android.bp without `preprocessed: true` | Use the kit's Android.bp as it is, or use Android.mk |
| Build error: `Found blocked Android.mk file` | The directory is under `prebuilts/`, `frameworks/` and so on | Move it to `vendor/aio/mdm` |
| Build error: duplicate module `AioMdm` | Both Android.mk and Android.bp exist | Keep only one |
| Build error about artifact path requirements, naming `system/priv-app/AioMdm` | Your product may not add files to /system | `AIO_MDM_PARTITION := system_ext` (or `product`) before the inherit line |
| `verify-device.sh`: "differs from the kit's AioMdm.apk" | The build or a signing script changed the APK | Rules R1 and R2: check `apkcerts.txt` and that `LOCAL_REPLACE_PREBUILT_APK_INSTALLED` is still in Android.mk |
| The app is missing after boot. Logcat: `INSTALL_PARSE_FAILED_NO_CERTIFICATES` or `no signatures` | The APK was changed, which broke its signature | Same as above |
| Setup log: `already some accounts on the device` | An account was added during factory setup | Factory reset and do not add accounts. The script tries again at every boot |
| Setup log: `several users` / `already several users` | A guest or second user exists | Do not create extra users in the factory image |
| Setup log: `another Device Owner is set` | Another MDM or kiosk app is Device Owner | Remove that app from the image (rule R5), then factory reset |
| No setup log at all | init did not start the service | `adb shell getprop init.svc.aio_mdm_setup` and `adb logcat -s init`. Check that `aio-mdm.rc` is in `etc/init` of the partition you chose |
| PASS everywhere but "no Enrolled line" | No network, the clock is wrong (TLS fails with a 1970 date), or a firewall | Connect to the internet and turn on automatic time. The device must reach the server in `aio-mdm.conf` on port 443 |
| On the dashboard the serial shows as `android-…` or `unknown` | `ro.serialno` is empty, not unique, or unreadable | Rule R8. `READ_PRIVILEGED_PHONE_STATE` must be granted ([3] in the report) |
| The agent stops after the screen goes off, or after a while | The ROM's own background killer, "auto-start" manager or app freezer | Add `aio.app.mdmclient.dpc` to every vendor whitelist: auto-start, background protection, battery optimisation, clean-up / memory booster. `aio-mdm-sysconfig.xml` only covers AOSP Doze |

---

## 7. Serial numbers

AIO identifies each device by the serial number on its label. The agent reads it with
`Build.getSerial()`, which returns `ro.serialno` (`ro.boot.serialno`). Each device needs its
own serial, written at the factory, and it must not be `0123456789ABCDEF`, empty or random
per boot.

---

## 8. If you cannot use the init service

Some vendors' certification rules do not allow an init service in the `shell` domain. In that
case, keep everything else, remove the `.rc` line from `aio-mdm.mk`, and run these on the
factory test station after the final factory reset (USB debugging on):

```bash
adb shell dpm set-device-owner aio.app.mdmclient.dpc/.MdmDeviceAdminReceiver
adb shell cmd appops set aio.app.mdmclient.dpc GET_USAGE_STATS allow
adb shell cmd appops set aio.app.mdmclient.dpc PROJECT_MEDIA allow
```

The first command must print `Success`. Then turn USB debugging off.

---

## 9. Security notes

- `aio-mdm.conf` holds an **enrollment token** made only for you. With it a device can join
  AIO's fleet, so keep the source tree and the kit private.
- If the token leaks, AIO can replace it and send you a new kit. Devices that already enrolled
  are not affected.
- The agent connects only to the server named in `aio-mdm.conf`, over HTTPS / WSS.

---

## 10. What to send AIO

For each model:

1. The model name, board and SoC, Android version and `ro.build.fingerprint`
2. Which partition you used (`system`, `product` or `system_ext`), and Android.mk or Android.bp
3. The `aio-mdm-verify-*.txt` report from at least **two** devices
4. Anything you had to change in the kit, and why

When AIO confirms the devices are on its dashboard, the integration is done.
