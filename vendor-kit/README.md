# Vendor kit — Standard client in a vendor's own image

What a hardware vendor gets to put this agent into their Android image (Android 9–15) as a
**privileged system app** that becomes **Device Owner** on first boot and **enrolls itself**
from a config file in the image. No adb, no QR. Decided 17 Nov 2026: the Chinese vendors
build in the Standard client; the firmware client is not made generic (our platform key
can't sign their images, so every update would need their signature).

The vendor-facing guide is `GUIDE.en.md` / `GUIDE.zh-CN.md` (same content). This file is AIO's.

## Make a kit for a vendor

1. Make an **enrollment profile for that vendor** on the server the devices should join:
   no expiry, no device limit, placement "Not decided yet" (To place). Never revoke or delete
   it while the vendor's images are in use: new devices could not enroll, and a device that
   loses its key recovers only with the token it enrolled with.
2. Run (signing env from `keystore/signing.env`; the APK is built for that server):

   ```bash
   vendor-kit/make-kit.sh -s https://mdm.aioapp.com -t enr_XXXX -n <vendor>
   ```

   It refuses an APK that is not the AIO release key, not page-aligned, has compressed dex
   (Soong rejects a preprocessed priv-app with it), has native libs (the build files don't
   handle them), or is older than 0.16.0 (code 57, the first that reads `aio-mdm.conf`).
3. Send `~/aio-mdm/vendor-kits/aio-mdm-vendor-kit-<ver>-<vendor>.zip`. It holds the token:
   never commit it, and never put it in a public place.

## How it works

| Piece | Does |
|---|---|
| `Android.mk` | `BUILD_PREBUILT`, `PRESIGNED`, privileged, `LOCAL_REPLACE_PREBUILT_APK_INSTALLED` (copied untouched: the build would otherwise uncompress dex / zipalign and break the v2 signature). One module per partition (`AioMdm`, `AioMdmProduct`, `AioMdmSystemExt`). |
| `Android.bp.example` | `android_app_import`, `preprocessed: true` (required for presigned targetSdk ≥ 30). |
| `aio-mdm.mk` | `AIO_MDM_PARTITION` (system / product / system_ext) → package + every file in that partition. |
| `privapp-permissions-aio-mdm.xml` | READ_PRIVILEGED_PHONE_STATE, READ_LOGS, PACKAGE_USAGE_STATS, WRITE_SECURE_SETTINGS + reserved ones. **A permission the APK requests that is missing here stops the device booting** (enforce mode), so a new privileged permission in a client version is only granted on devices whose image lists it (an OTA'd update with an unlisted one just isn't granted). |
| `default-permissions-aio-mdm.xml` | phone state, location, BT, notifications at first boot. |
| `aio-mdm-sysconfig.xml` | Doze / Data Saver whitelist. |
| `aio-mdm.rc` + `aio-mdm-setup.sh` | after `sys.boot_completed`, a oneshot service as user shell in `u:r:shell:s0` (AOSP has `domain_trans(init, shell_exec, shell)`, as for `console`): `dpm set-device-owner`, appops GET_USAGE_STATS + PROJECT_MEDIA. Mark `/data/local/tmp/aio-mdm-setup.done`, log `/data/local/tmp/aio-mdm-setup.log`. |
| `aio-mdm.conf` | `server_url`, `enroll_token`; read by `FactoryConfig.kt` (client 0.16.0) only while the agent has no key, token or last token and was never unenrolled. |

The client reports `extra.preinstalled` (FLAG_SYSTEM) on every check-in.

## Not tested yet

Made and checked on hwpc02 only (17 Nov 2026): the kit builds, the patch applies outside
git and reproduces the APK byte for byte, the makefiles resolve for every partition (GNU make
with stub build files). **Not yet built into a real image or booted on a device.** The first
vendor's image is the real test: watch its `verify-device.sh` report.
