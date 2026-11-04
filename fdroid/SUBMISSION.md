# Add EvoBuddy

Source: https://github.com/datatrash/evobuddy
Application ID: `sh.datatra.evobuddy`
Licence: Apache-2.0
Release: `v0.1.10`, version code `11`
Website and privacy policy: https://evobuddy.datatra.sh/

EvoBuddy controls European Evohome systems through Total Connect Comfort. It
supports room temperatures and overrides, weekly schedules, editable multi-room
shortcuts, launcher actions and temperature widgets. A demo mode is available
without an account; real use requires an Evohome system and a European TCC
account.

The recipe uses `subdir: app` and the unsigned Gradle release build. The project
uses OpenJDK 21, SDK 36 and build-tools 36.0.0. It has no proprietary SDK or
Google Play Services dependency. The small native library supplied through
AndroidX graphics-path is Apache-2.0 and obtained through Google Maven; there is
no project NDK build.

The `NonFreeNet` and `TetheredNet` flags disclose its dependence on Resideo's
fixed cloud service. Credentials go directly to that service over HTTPS. The
password is not persisted; session tokens are encrypted with Android Keystore.
No advertising, analytics, crash-reporting service or developer-operated backend
is used.

English and Dutch listing text, version-code changelog, icon, feature graphic
and phone screenshots are under `fastlane/metadata/android`. Original source
and artwork are Apache-2.0; dependency notices are included in the APK.

Before sending this proposal, confirm the public source and tag are reachable,
the service/application-registration rights review is complete, and the
`fdroid build --server sh.datatra.evobuddy:11` pipeline result is available.
