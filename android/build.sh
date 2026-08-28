#!/bin/bash
# BiliLite 构建脚本:直接调用 JDK17 运行 gradle wrapper,避免 PATH 解析问题
export JAVA_HOME="C:/Users/Lenovo/Desktop/bililite/build-tools/jdk17_extract/jdk17"
export ANDROID_HOME="C:/Users/Lenovo/Desktop/bililite/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
JAVA="$JAVA_HOME/bin/java"
cd "$(dirname "$0")"
"$JAVA" -classpath "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain clean assembleDebug --no-daemon --no-build-cache --console=plain
