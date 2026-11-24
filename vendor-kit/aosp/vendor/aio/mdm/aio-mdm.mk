# AIO MDM — product makefile. Add ONE line to your device makefile (device.mk or the
# product .mk your lunch target uses):
#
#     $(call inherit-product, vendor/aio/mdm/aio-mdm.mk)
#
# Where the agent goes. Default "system". Use "product" or "system_ext" (Android 11+) when
# your product must not add files to /system (PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS,
# GSI-compliant system image). Set it BEFORE the inherit-product line, e.g.:
#
#     AIO_MDM_PARTITION := product
#     $(call inherit-product, vendor/aio/mdm/aio-mdm.mk)

AIO_MDM_DIR := vendor/aio/mdm
AIO_MDM_PARTITION ?= system

ifeq ($(AIO_MDM_PARTITION),system)
  AIO_MDM_MODULE := AioMdm
  AIO_MDM_OUT := $(TARGET_COPY_OUT_SYSTEM)
else ifeq ($(AIO_MDM_PARTITION),product)
  AIO_MDM_MODULE := AioMdmProduct
  AIO_MDM_OUT := $(TARGET_COPY_OUT_PRODUCT)
else ifeq ($(AIO_MDM_PARTITION),system_ext)
  AIO_MDM_MODULE := AioMdmSystemExt
  AIO_MDM_OUT := $(TARGET_COPY_OUT_SYSTEM_EXT)
else
  $(error AIO_MDM_PARTITION must be system, product or system_ext (got "$(AIO_MDM_PARTITION)"))
endif

PRODUCT_PACKAGES += $(AIO_MDM_MODULE)

PRODUCT_COPY_FILES += \
    $(AIO_MDM_DIR)/etc/permissions/privapp-permissions-aio-mdm.xml:$(AIO_MDM_OUT)/etc/permissions/privapp-permissions-aio-mdm.xml \
    $(AIO_MDM_DIR)/etc/default-permissions/default-permissions-aio-mdm.xml:$(AIO_MDM_OUT)/etc/default-permissions/default-permissions-aio-mdm.xml \
    $(AIO_MDM_DIR)/etc/sysconfig/aio-mdm-sysconfig.xml:$(AIO_MDM_OUT)/etc/sysconfig/aio-mdm-sysconfig.xml \
    $(AIO_MDM_DIR)/etc/aio-mdm/aio-mdm.conf:$(AIO_MDM_OUT)/etc/aio-mdm/aio-mdm.conf \
    $(AIO_MDM_DIR)/etc/aio-mdm/aio-mdm-setup.sh:$(AIO_MDM_OUT)/etc/aio-mdm/aio-mdm-setup.sh \
    $(AIO_MDM_DIR)/init/aio-mdm.$(AIO_MDM_PARTITION).rc:$(AIO_MDM_OUT)/etc/init/aio-mdm.rc

# Nothing else is needed. Do NOT sign the agent with the platform key or any other key
# (no PRODUCT_*_CERTIFICATE override, no re-signing in a release script such as
# sign_target_files_apks: keep it listed as PRESIGNED). See the guide, section 2.
