LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := usbiface
LOCAL_SRC_FILES := usbiface.c
LOCAL_CFLAGS := -std=c99 -Wall -Wextra -Werror -Os -fvisibility=hidden
LOCAL_LDFLAGS := -Wl,--build-id=none -Wl,-z,relro -Wl,-z,now
include $(BUILD_SHARED_LIBRARY)
