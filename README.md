# 阿拉蕾TV

Android TV 视频客户端（Compose + Media3 ExoPlayer）。

## 功能

- 分类浏览、详情选集、断点续播
- 播放控制：暂停、下一话、重播、倍速、选集
- 手机扫码登录（设置页），登录后自动同步观看进度
- 局域网投词：搜索页扫码，手机发关键词电视实时搜索
- 退后台自动暂停，回前台恢复

## 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:assembleDebug \
  -Dorg.gradle.java.home=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 说明

- 数据来自公开接口，仅供个人学习使用
- 包名：`com.cycitv`