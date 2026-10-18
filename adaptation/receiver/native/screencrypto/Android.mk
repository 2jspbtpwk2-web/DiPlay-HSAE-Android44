LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := screencrypto
LOCAL_SRC_FILES := screencrypto.c vendor/monocypher-4.0.2/monocypher.c
LOCAL_CFLAGS := -std=c99 -Wall -Wextra -Werror -O3 -fvisibility=hidden -ffunction-sections -fdata-sections
LOCAL_LDFLAGS := -Wl,--gc-sections -Wl,--build-id=none -Wl,-z,relro -Wl,-z,now
include $(BUILD_SHARED_LIBRARY)
