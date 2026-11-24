# AIO MDM agent (aio.app.mdmclient.dpc) as a privileged system app.
# Works on Android 9 – 15. Keep this directory at <tree>/vendor/aio/mdm.
#
# The APK is signed by AIO. It must be installed BYTE FOR BYTE: never re-sign it, never
# zipalign or uncompress it. LOCAL_REPLACE_PREBUILT_APK_INSTALLED makes the build copy the
# file untouched. If the APK is changed, its signature breaks and AIO can no longer update it.
#
# Three module names, one per partition; aio-mdm.mk adds only the one for AIO_MDM_PARTITION.

LOCAL_PATH := $(call my-dir)

define aio-mdm-prebuilt
include $$(CLEAR_VARS)
LOCAL_MODULE := $(1)
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_SUFFIX := $$(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_SRC_FILES := AioMdm.apk
LOCAL_REPLACE_PREBUILT_APK_INSTALLED := $$(LOCAL_PATH)/AioMdm.apk
LOCAL_CERTIFICATE := PRESIGNED
LOCAL_PRIVILEGED_MODULE := true
LOCAL_DEX_PREOPT := false
LOCAL_ENFORCE_USES_LIBRARIES := false
$(2)
include $$(BUILD_PREBUILT)
endef

# /system/priv-app/AioMdm (default)
$(eval $(call aio-mdm-prebuilt,AioMdm,))

# /product/priv-app/AioMdmProduct
ifneq ($(TARGET_COPY_OUT_PRODUCT),)
$(eval $(call aio-mdm-prebuilt,AioMdmProduct,LOCAL_PRODUCT_MODULE := true))
endif

# /system_ext/priv-app/AioMdmSystemExt (Android 11+)
ifneq ($(TARGET_COPY_OUT_SYSTEM_EXT),)
$(eval $(call aio-mdm-prebuilt,AioMdmSystemExt,LOCAL_SYSTEM_EXT_MODULE := true))
endif
