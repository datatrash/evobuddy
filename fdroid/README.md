# Release preparation

The app listing is under `fastlane/metadata/android`. The build recipe in
`metadata/sh.datatra.evobuddy.yml` is intended for the F-Droid `fdroiddata`
repository. It builds the unsigned `release` variant, not the locally signed
`preview` variant.

## Build and check

Use OpenJDK 21, Android platform 36 and build-tools 36.0.0. Gradle 8.14.3,
Android Gradle Plugin 8.13.2 and Kotlin 2.2.20 are pinned in the source.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
python tools/check-release.py
```

The release output is `app/build/outputs/apk/release/app-release-unsigned.apk`.
Store artwork and screenshots are checked in; generating them is not part of
the Android build. `tools/render-assets.py` rebuilds the icon, banner and site
images using Pillow.

With fdroidserver installed on Linux, copy the recipe into an F-Droid data
checkout so its official category and anti-feature definitions are available:

```sh
cp fdroid/metadata/sh.datatra.evobuddy.yml /path/to/fdroiddata/metadata/
cd /path/to/fdroiddata
fdroid rewritemeta sh.datatra.evobuddy
fdroid lint sh.datatra.evobuddy
```

For the production build check, copy the recipe into a `fdroiddata` checkout and
run `fdroid build --server sh.datatra.evobuddy:11`. That check needs the public
source repository and the release tag; a local Gradle build is not a substitute.

## Before submitting

1. Make the source repository publicly accessible, including the Apache-2.0
   licence, metadata and release tag. The private repository cannot be built
   by F-Droid.
2. Review permission to distribute this independent TCC client and use its
   application registration, as described below.
3. Check that version `0.1.10`, version code `11` and tag `v0.1.10` agree. Tag the
   completed release commit: `git tag -a v0.1.10 -m 'Release 0.1.10'`, then push
   the branch and tag. Do not move a tag already used by F-Droid.
4. Connect the repository to Netlify and publish the minisite. Set the primary
   custom domain to `evobuddy.datatra.sh` and complete its DNS/TLS setup. Netlify
   reads the root `netlify.toml`; the publish directory is `site`, with no build
   command or package installation. Verify the home and privacy pages.
5. Fork `https://gitlab.com/fdroid/fdroiddata`, add the recipe under `metadata/`,
   run the F-Droid checks and open a merge request using `SUBMISSION.md`. The
   upstream screenshots stay in this repository, not in `fdroiddata`.
6. When the app is actually indexed, replace the pending-install section in
   `site/index.html` with a link to `https://f-droid.org/packages/sh.datatra.evobuddy/`.

F-Droid maintainers decide inclusion and may adjust the recipe or require
additional information. No listing or approval is implied by these files.

## Signing

F-Droid builds from source and signs its own APKs. Its builds do not require a
project signing key. GitHub APK releases use the Actions secrets described in
[`../.github/RELEASING.md`](../.github/RELEASING.md). Local previews use a development certificate and cannot
be updated in place by an APK signed by F-Droid. Switching channels requires
uninstalling the preview first; export any schedules you want to keep and note
your shortcut settings before doing so. Normal F-Droid updates use its
consistent signing identity.

## Service dependency and distribution

The app depends on Resideo's proprietary, fixed European TCC service. The recipe
declares both `NonFreeNet` and `TetheredNet`. It contains no proprietary SDK,
advertising, analytics or Google Play Services dependency. AndroidX, Compose,
Kotlin and coroutines come from the configured public Maven repositories.

Apache-2.0 licences EvoBuddy's code and original artwork, including commercial
redistribution rights. It does not grant access to Resideo's service or transfer
its trademarks. Resideo's European terms restrict commercial/resale uses of its
system and discuss third-party applications. Free distribution on F-Droid does
not itself establish permission to publish a client or reuse an application
registration. The shared TCC OAuth registration in `TccClient.kt` needs a rights
review or replacement with a registration authorised for this app before a
public release. Do not treat changing stores as a legal clearance.

References:

- [F-Droid inclusion policy](https://f-droid.org/en/docs/Inclusion_Policy/)
- [Submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [Build metadata](https://f-droid.org/en/docs/Build_Metadata_Reference/)
- [Upstream listing assets](https://f-droid.org/en/docs/All_About_Descriptions_Graphics_and_Screenshots/)
- [European TCC terms](https://international.mytotalconnectcomfort.com/Home/TermsAndConditions)
- [Netlify configuration](https://docs.netlify.com/build/configure-builds/file-based-configuration/)
- [Netlify HTTPS](https://docs.netlify.com/manage/domains/secure-domains-with-https/https-ssl/)
