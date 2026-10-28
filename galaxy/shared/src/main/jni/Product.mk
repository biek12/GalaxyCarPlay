# Native产品装配留在Galaxy，官方Android.mk保持原样。
GALAXY_PROJECT_ROOT := $(abspath $(call my-dir)/../../../../..)
include $(GALAXY_PROJECT_ROOT)/shared/src/main/jni/Android.mk
include $(GALAXY_PROJECT_ROOT)/galaxy/shared/src/main/jni/Android.mk
