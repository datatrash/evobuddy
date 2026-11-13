# F-Droid packaging

The recipe is `metadata/sh.datatra.evobuddy.yml`. Store descriptions, changelogs
and images are maintained under `fastlane/metadata/android`.

## Build and validate

Use OpenJDK 21, Android platform 36 and build-tools 36.0.0.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
python tools/check-release.py --require-tag
```

The APK is `app/build/outputs/apk/release/app-release-unsigned.apk`. The recipe
pins the full commit resolved from the published release tag. Update that hash
in a separate commit after publishing each release; do not move published tags.

To validate with fdroidserver, copy the recipe into a current `fdroiddata`
checkout, then run:

```sh
fdroid rewritemeta sh.datatra.evobuddy
fdroid lint sh.datatra.evobuddy
fdroid build --server sh.datatra.evobuddy:11
```

The local Gradle build does not replace the F-Droid server build. Before
submitting, check the [inclusion policy](https://f-droid.org/en/docs/Inclusion_Policy/),
including its development-age requirement, and follow the
[submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/).

## Signing

The current recipe uses F-Droid signing. GitHub production releases and local
previews use separate keys, so changing channels requires a reinstall. Export
schedules and note shortcut settings before switching.

Using the production GitHub signature on F-Droid requires a verified
[reproducible build](https://f-droid.org/en/docs/Reproducible_Builds/). That has
not been established, so reference binaries and signing-key pins are not enabled
in this recipe. Decide on signing before the first F-Droid publication.

## Service dependency

`NonFreeNet` and `TetheredNet` declare the fixed Resideo cloud dependency.
Apache-2.0 covers the app's code and original artwork; service access and use of
the TCC application registration remain subject to
[Resideo's European terms](https://international.mytotalconnectcomfort.com/Home/TermsAndConditions).
