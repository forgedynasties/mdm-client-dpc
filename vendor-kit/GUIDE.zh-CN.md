# AIO MDM — 厂商集成指南

**适用对象：** 为 AIO 设备制作 Android 固件的硬件厂商
**适用范围：** Android 9 – 15（API 28 – 35），任意 CPU 架构，带或不带 GMS 均可
**英文版：** `GUIDE.en.md`（如两版有出入，以英文版为准）

---

## 1. 这是什么

AIO 通过 **AIO MDM** 管理自己的设备。设备端只有一个应用：**AIO MDM 客户端**
（包名 `aio.app.mdmclient.dpc`）。我们希望您把它内置到系统镜像中，
让每台设备出厂时就已处于受管理状态。

您只需添加**一个目录**（`vendor/aio/mdm`），并在设备 makefile 中加**一行**。
之后，用您的镜像生产的每台设备都会自动完成以下步骤：

1. **安装。** 客户端随镜像作为特权系统应用（priv-app）安装。
2. **设备所有者。** 首次开机完成后，一个一次性的 init 服务立即把客户端设为设备的
   **设备所有者（Device Owner）**，效果与 `adb shell dpm set-device-owner` 相同。
3. **注册。** 设备联网后，客户端按照 `aio-mdm.conf` 中的配置自动注册到 AIO MDM 服务器，
   随后设备出现在 AIO 的管理后台中。

整个过程无需任何人操作设备：不需要 adb，不需要扫码，也不需要输入任何内容。

**客户端的更新由 AIO 负责。** AIO 通过网络更新客户端；客户端升级时，您**不需要**重新编译固件。
镜像里的版本只是设备运行的第一个版本。

---

## 2. 必须遵守的规则（开始前请先阅读）

| # | 规则 | 原因 |
|---|------|------|
| R1 | **绝对不要重新签名 APK。** 不要用平台密钥或任何其他密钥签名，保持 `PRESIGNED`，包括 `sign_target_files_apks` 等正式签名脚本中。 | Android 只接受与已安装版本签名相同的更新。一旦被重新签名，AIO 将永远无法在这些设备上更新客户端。 |
| R2 | **按原样逐字节安装 APK。** 不要 zipalign，不要解压 dex，不要裁剪。套件中的编译文件已经做到这一点。 | 任何修改都会破坏 APK 的 v2/v3 签名，设备会拒绝安装。 |
| R3 | **不要修改包名和文件名。** | 设备所有者记录保存的是组件名 `aio.app.mdmclient.dpc/.MdmDeviceAdminReceiver`。 |
| R4 | **APK 和它的 XML 文件必须放在同一个分区**（system、product 或 system_ext）。 | Android 从 APK 所在分区读取权限白名单。白名单缺失时**设备无法开机**。 |
| R5 | **不要预装其他 MDM 或设备所有者应用**，工厂设置时也不要添加任何账户。 | Android 只允许一个设备所有者，而且设备上不能有账户或其他用户。 |
| R6 | **不要修改 `aio-mdm.conf`。** | 其中保存的是 AIO 为您生成的服务器地址和注册令牌。 |
| R7 | **请妥善保管源码树，不要公开。** | `aio-mdm.conf` 中含有注册凭证（见第 9 节）。 |
| R8 | **`ro.serialno` 必须是设备标签上印刷的唯一序列号。** | AIO 用它识别每一台设备。 |

---

## 3. 套件内容

```
aio-mdm-vendor-kit-<版本>-<厂商>/
├── 0001-vendor-aio-mdm-add-AIO-MDM-agent.patch   整个 vendor/aio/mdm 目录打成的一个补丁
├── vendor/aio/mdm/                               同一目录，可直接复制
│   ├── Android.mk                  预编译模块，适用于 Android 9–15（默认）
│   ├── Android.bp.example          Soong 版本，仅在源码树不接受 Android.mk 时使用
│   ├── aio-mdm.mk                  产品 makefile：您只需继承这个文件
│   ├── AioMdm.apk                  客户端，由 AIO 签名
│   ├── etc/permissions/privapp-permissions-aio-mdm.xml       特权权限白名单
│   ├── etc/default-permissions/default-permissions-aio-mdm.xml  首次开机授予的运行时权限
│   ├── etc/sysconfig/aio-mdm-sysconfig.xml                   不受 Doze / 省流量模式限制
│   ├── etc/aio-mdm/aio-mdm.conf                              服务器地址 + 注册令牌（AIO 提供）
│   ├── etc/aio-mdm/aio-mdm-setup.sh                          首次开机设置设备所有者的脚本
│   ├── init/aio-mdm.{system,product,system_ext}.rc           开机完成后启动该脚本
│   └── sepolicy-optional/aio_mdm_thermal.te                  可选：读取 CPU 温度
├── tools/verify-device.sh          通过 adb 检查成品设备并生成报告
├── AioMdm.apk, AioMdm.apk.sha256   APK 副本及其校验值
├── KIT-INFO.txt                    本套件的版本、签名和服务器信息
├── GUIDE.en.md, GUIDE.zh-CN.md     本指南
```

---

## 4. 集成步骤

### 4.1 添加目录

在 **Android 源码树根目录**下，使用补丁或直接复制，二选一：

```bash
# 方式 A：补丁（补丁中以二进制方式包含 APK，必须用 git apply，不能用 patch 命令）
git apply --stat  /path/to/0001-vendor-aio-mdm-add-AIO-MDM-agent.patch
git apply         /path/to/0001-vendor-aio-mdm-add-AIO-MDM-agent.patch

# 方式 B：复制
mkdir -p vendor/aio && cp -r /path/to/kit/vendor/aio/mdm vendor/aio/
```

即使源码树根目录本身不是 git 仓库（用 `repo` 管理的源码树通常如此），`git apply` 也能正常使用。
请放在 `vendor/aio/mdm`，或 `vendor/`、`device/` 下的其他目录。**不要**放在
`prebuilts/`、`frameworks/` 等 Android 13 及以上版本禁止使用 `Android.mk` 的目录。

### 4.2 在设备 makefile 中加一行

在 `lunch` 目标使用的产品 makefile 中（通常是 `device/<公司>/<板型>/device.mk`
或 `<产品>.mk`）加入：

```make
$(call inherit-product, vendor/aio/mdm/aio-mdm.mk)
```

默认安装到 **/system**。如果您的产品不允许向 /system 添加文件（例如启用了
`PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS`，或系统镜像需符合 GSI），请在继承那一行**之前**
选择其他分区：

```make
AIO_MDM_PARTITION := system_ext     # Android 11 及以上；也可以是 product
$(call inherit-product, vendor/aio/mdm/aio-mdm.mk)
```

`.mk` 会把 APK、XML 文件和 init 脚本一起安装到该分区（规则 R4）。

### 4.3 使用 Android.mk 还是 Android.bp

保留 **Android.mk**（默认）即可。它适用于 Android 9 – 15，并通过
`LOCAL_REPLACE_PREBUILT_APK_INSTALLED` 原样安装 APK。

仅当源码树不接受 Android.mk 时：把 `Android.bp.example` 改名为 `Android.bp`，**删除**
`Android.mk`；如果修改过 `AIO_MDM_PARTITION`，请在 `Android.bp` 中取消注释
`product_specific` 或 `system_ext_specific`。Soong 模块使用 `preprocessed: true`，
请勿修改（规则 R2）。

### 4.4 正式签名

如果您用自己的 release 密钥签名（`sign_target_files_apks`），请确认 target files 的
`META/apkcerts.txt` 中客户端仍为预签名：

```
name="AioMdm.apk" certificate="PRESIGNED" private_key=""
```

（其他分区对应的名称为 `AioMdmProduct.apk` 或 `AioMdmSystemExt.apk`。）切勿为它指定 `-k`
或任何密钥映射。

### 4.5 可选：CPU 温度

客户端能读取 `/sys/class/thermal` 时，AIO 管理后台会显示 CPU 温度。很多主板的 SELinux
不允许应用读取。如需此功能，请把 `sepolicy-optional/aio_mdm_thermal.te` 加入设备
sepolicy（方法见该文件）。如果被 `neverallow` 规则拦截，跳过此步即可，其他功能不受影响。

### 4.6 编译并检查产物

```bash
m    # 或您平时使用的编译命令
sha256sum out/target/product/<device>/system/priv-app/AioMdm/AioMdm.apk
cat /path/to/kit/AioMdm.apk.sha256    # 两个校验值必须相同
```

如果校验值不同，说明编译过程修改了 APK。请停下来检查规则 R1 和 R2。

### 4.7 刷机、恢复出厂设置、开机

1. 刷入固件，然后**恢复出厂设置**（清除数据）。
2. 开机进入桌面或完成开机向导。**不要添加任何账户。**
3. 让设备连上可以访问互联网的网络（Wi-Fi 或以太网）。
4. 等待约 **2 分钟**。

### 4.8 检查并把报告发给我们

在装有 `adb` 的电脑上（Linux、macOS，或 Windows 下的 Git Bash / WSL），连接设备后运行：

```bash
tools/verify-device.sh --apk AioMdm.apk
```

每一行都应为 **PASS**。**WARN** 不算失败，但请告诉我们。脚本会生成
`aio-mdm-verify-<序列号>-<时间>.txt`，请将它连同第 10 节要求的信息一起发给 AIO。

只有这项检查需要 USB 调试；量产固件可以关闭 USB 调试。

---

## 5. 设备上发生了什么（调试时参考）

| 时间点 | 发生的事情 | 查看方法 |
|---|---|---|
| 首次开机扫描应用 | `AioMdm.apk` 作为特权系统应用安装。特权权限来自 `privapp-permissions-aio-mdm.xml`，运行时权限来自 `default-permissions-aio-mdm.xml`。 | `adb shell dumpsys package aio.app.mdmclient.dpc` |
| `BOOT_COMPLETED` | 客户端启动，读取 `etc/aio-mdm/aio-mdm.conf`，联网后立即注册。 | `adb logcat -s FactoryConfig MdmService` |
| `sys.boot_completed=1` | init 启动 `aio_mdm_setup`（用户 shell，域 `u:r:shell:s0`，与 AOSP 自带的 `console` 服务相同），执行 `dpm set-device-owner`，然后放开两项 app-op 权限。 | `adb shell cat /data/local/tmp/aio-mdm-setup.log`，`adb logcat -s aio-mdm-setup` |
| 成功之后 | 脚本留下 `/data/local/tmp/aio-mdm-setup.done`，下次恢复出厂设置前不再运行。如果失败，下次开机时会重试。 | |
| 之后 | 客户端与服务器保持一条连接，AIO 通过网络更新它。 | AIO 管理后台 |

该 init 服务**不需要新增任何 SELinux 规则**：AOSP 本身已允许 `init` 启动 shell 进程
（`domain_trans(init, shell_exec, shell)`）。服务运行几秒后就退出。

---

## 6. 常见问题排查

| 现象 | 可能原因 | 解决方法 |
|---|---|---|
| 加入套件后**设备无法开机**或反复重启。日志中有 `Signature\|privileged permissions not in privapp-permissions allowlist: aio.app.mdmclient.dpc` | 权限白名单 XML 缺失，或与 APK 不在同一分区 | 原样使用 `aio-mdm.mk`，确认 `etc/permissions/privapp-permissions-aio-mdm.xml` 与 APK 在同一分区 |
| 编译报错：`Prebuilt, presigned apks with targetSdkVersion >= 30 ... must set preprocessed: true` | 使用了 Android.bp，但没有 `preprocessed: true` | 原样使用套件中的 Android.bp，或改用 Android.mk |
| 编译报错：`Found blocked Android.mk file` | 目录放在了 `prebuilts/`、`frameworks/` 等位置 | 移到 `vendor/aio/mdm` |
| 编译报错：模块 `AioMdm` 重复定义 | Android.mk 和 Android.bp 同时存在 | 只保留一个 |
| 编译报错：artifact path requirements，提到 `system/priv-app/AioMdm` | 您的产品不允许向 /system 添加文件 | 在继承那一行之前设置 `AIO_MDM_PARTITION := system_ext`（或 `product`） |
| `verify-device.sh` 提示 "differs from the kit's AioMdm.apk" | 编译或签名脚本修改了 APK | 见规则 R1、R2：检查 `apkcerts.txt`，并确认 Android.mk 中仍有 `LOCAL_REPLACE_PREBUILT_APK_INSTALLED` |
| 开机后应用不存在。日志：`INSTALL_PARSE_FAILED_NO_CERTIFICATES` 或 `no signatures` | APK 被修改，签名失效 | 同上 |
| 设置日志：`already some accounts on the device` | 工厂设置时添加了账户 | 恢复出厂设置，不要添加账户。脚本每次开机都会重试 |
| 设置日志：`several users` / `already several users` | 存在访客或第二个用户 | 出厂镜像中不要创建额外用户 |
| 设置日志：`another Device Owner is set` | 其他 MDM 或 kiosk 应用已是设备所有者 | 从镜像中移除该应用（规则 R5），然后恢复出厂设置 |
| 完全没有设置日志 | init 没有启动该服务 | 运行 `adb shell getprop init.svc.aio_mdm_setup` 和 `adb logcat -s init`，确认 `aio-mdm.rc` 位于所选分区的 `etc/init` 中 |
| 全部 PASS，但提示 "no Enrolled line" | 没有网络、系统时间错误（时间为 1970 年会导致 TLS 失败），或被防火墙拦截 | 连接互联网，开启自动时间。设备必须能通过 443 端口访问 `aio-mdm.conf` 中的服务器 |
| 管理后台中序列号显示为 `android-…` 或 `unknown` | `ro.serialno` 为空、不唯一或无法读取 | 见规则 R8。`READ_PRIVILEGED_PHONE_STATE` 必须已授予（报告中的 [3]） |
| 息屏后或运行一段时间后客户端停止 | ROM 自带的后台清理、"自启动管理"或应用冻结功能 | 把 `aio.app.mdmclient.dpc` 加入厂商的所有白名单：自启动、后台保护、电池优化、一键清理 / 内存加速。`aio-mdm-sysconfig.xml` 只覆盖 AOSP 的 Doze |

---

## 7. 序列号

AIO 用设备标签上的序列号识别每台设备。客户端通过 `Build.getSerial()` 读取，它返回
`ro.serialno`（`ro.boot.serialno`）。每台设备都必须在工厂写入自己独有的序列号，不能是
`0123456789ABCDEF`、空值，也不能每次开机随机生成。

---

## 8. 无法使用 init 服务时

有些厂商的认证规则不允许 `shell` 域的 init 服务。这种情况下，其他部分保持不变，从
`aio-mdm.mk` 中删除 `.rc` 那一行，并在工厂测试工位上、最后一次恢复出厂设置之后
（需开启 USB 调试）执行：

```bash
adb shell dpm set-device-owner aio.app.mdmclient.dpc/.MdmDeviceAdminReceiver
adb shell cmd appops set aio.app.mdmclient.dpc GET_USAGE_STATS allow
adb shell cmd appops set aio.app.mdmclient.dpc PROJECT_MEDIA allow
```

第一条命令必须输出 `Success`。完成后关闭 USB 调试。

---

## 9. 安全说明

- `aio-mdm.conf` 中有一个**专为您生成的注册令牌**。持有它就能让设备加入 AIO 的设备群，
  因此请勿公开源码树和套件。
- 如果令牌泄露，AIO 可以更换令牌并发给您新的套件。已经注册的设备不受影响。
- 客户端只通过 HTTPS / WSS 连接 `aio-mdm.conf` 中指定的服务器。

---

## 10. 请发给 AIO 的信息

每个型号请提供：

1. 型号名称、主板和 SoC、Android 版本以及 `ro.build.fingerprint`
2. 使用的分区（`system`、`product` 或 `system_ext`），以及使用的是 Android.mk 还是 Android.bp
3. 至少**两台**设备的 `aio-mdm-verify-*.txt` 报告
4. 您对套件做过的任何修改及原因

AIO 确认设备已出现在管理后台后，集成即告完成。
