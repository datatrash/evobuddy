# EvoBuddy

A lightweight Android app for European Evohome systems using Total Connect Comfort. Control room temperatures, edit schedules, and run saved shortcuts from the app or home-screen widgets.

## Build locally

Install JDK 21 and the Android SDK with platform 36 and build-tools 36.0.0. Set `ANDROID_HOME`, or put `sdk.dir=/path/to/android-sdk` in `local.properties`.

```sh
./gradlew :app:assemblePreview
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

The locally signed APK is in `app/build/outputs/apk/preview/app-preview.apk`. For an unsigned release build, run `:app:assembleRelease`. Run checks with `:app:testDebugUnitTest :app:lintDebug`.
