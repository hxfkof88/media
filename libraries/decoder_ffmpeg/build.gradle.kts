// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
import java.util.Properties

plugins { id("media3.android-library") }

val nativeDependencies =
  Properties().apply {
    file(
        "src/main/resources/META-INF/androidx.media3.decoder.ffmpeg/native-dependencies.properties"
      )
      .inputStream()
      .use(::load)
  }

android {
  namespace = "androidx.media3.decoder.ffmpeg"
  ndkVersion = nativeDependencies.getProperty("android.ndk.version")

  defaultConfig { ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") } }

  sourceSets { getByName("androidTest").assets.directories.add("../test_data/src/test/assets") }
}

// 让本模块产出的 AAR 不带 FFmpeg 七件套（libav* / libsw*），落实 FongMi 式
// 「APK 内仅一份 libav*」的单一 ffmpeg 机制：mytv 的 :mpvplayer 自带与
// libmpv.so 同源的 libav*，本模块预编译的 libav* 来自 bundled FFmpeg(18815aca)，
// 经 908592a 升级后两 abi 的 md5 已与 mpv 那份逐字节相同（arm64=4a21dc92、
// armeabi=181f53f3），故 excludes 不再是为解决 md5 冲突，而是杜绝重复拷贝与
// 符号歧义、避免 x86_64 转译运行时 libmpv.so dlopen 到不同源副本闪崩。
// libffmpegJNI.so 仍保留，它运行期动态链接外部 libav*（DT_NEEDED: libavcodec/
// libavutil/libswresample/libavformat）。
//
// 此改动只影响 mytv 消费方（去重），不影响 fork 自身构建 —— CMake 编译时
// 仍按 IMPORTED 引用 jniLibs 源里的 .so，packaging excludes 仅在 AAR 打包阶段生效。
android.packaging.jniLibs.excludes += setOf(
    "**/libavcodec.so",
    "**/libavdevice.so",
    "**/libavfilter.so",
    "**/libavformat.so",
    "**/libavutil.so",
    "**/libswresample.so",
    "**/libswscale.so",
)

// Configure the native build only if ffmpeg is present to avoid gradle sync
// failures if ffmpeg hasn't been built according to the README instructions.
if (project.file("src/main/ffmpeg/include/libavcodec/avcodec.h").exists()) {
  android.externalNativeBuild.cmake.path = file("src/main/jni/CMakeLists.txt")
  // LINT.IfChange
  // Should match cmake_minimum_required.
  android.externalNativeBuild.cmake.version = "3.21.0+"
  // LINT.ThenChange(src/main/jni/CMakeLists.txt)
}

dependencies {
  api(project(":lib-decoder"))
  // TODO(b/203752526): Remove this dependency.
  implementation(project(":lib-exoplayer"))
  implementation(libs.androidx.annotation)
  testImplementation(project(":test-utils"))
  testImplementation(libs.robolectric)
  androidTestImplementation(project(":test-utils"))
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.truth)
  androidTestImplementation(libs.androidx.core.ktx)
  androidTestImplementation(libs.kotlinx.coroutines.android)
  androidTestImplementation(libs.test.parameter.injector)
}
