# Building & Testing LibrePods (Android)

## Prerequisites
- **JDK 21** (macOS Homebrew: `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`).
- **Android SDK** (`ANDROID_HOME=~/Library/Android/sdk`).
- **Android NDK** (`30.0.14904198` or matching `android/app/build.gradle.kts`).

## Build Commands
Run from `android/`. Larger Gradle and Kotlin daemon heaps are required to avoid GC thrashing:

```bash
cd android
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=~/Library/Android/sdk \
./gradlew assembleFossDebug \
  -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g" \
  -Pkotlin.daemon.jvmargs="-Xmx3g"
```

Output APK: `android/app/build/outputs/apk/foss/debug/app-foss-debug.apk`.

Install over USB:
```bash
adb install -r app/build/outputs/apk/foss/debug/app-foss-debug.apk
```

## Unit Tests
```bash
cd android
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=~/Library/Android/sdk \
./gradlew :core:testDebugUnitTest
```

## ADB & LSPosed Debugging Tips
- **Raise logcat buffer** (resets to 64 KiB on reboot):
  ```bash
  adb logcat -G 16M
  ```
- **Filter LibrePods & Bluetooth hook logs:**
  ```bash
  adb logcat -s LibrePodsService:V AACPManager:V BLEManager:V MediaController:V LSPosed-Bridge:V
  ```
- **Check LSPosed hook loading:** After updating hooks in `KotlinModule.kt`, install the APK and toggle Bluetooth off/on (or restart `com.android.bluetooth`) so LSPosed reloads the updated module DEX.
