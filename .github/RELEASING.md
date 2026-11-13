# GitHub APK releases

Pushing a `vX.Y.Z` tag runs the APK release workflow. It checks that the tag
matches the app version, runs unit tests and lint, builds the release APK,
signs it and publishes the APK and SHA-256 checksum as a GitHub Release.
The workflow can also be run manually for an existing tag. Published assets
are not overwritten by a rerun.

## One-time signing setup

Use a dedicated, stable production key. Do not use an Android debug key or
generate a new key for each release. Keep a secure backup of the key and its
password; future updates require the same signing identity.

For a new key on Windows, run this in a terminal and answer the password prompts:

```powershell
$keyStore = "$env:USERPROFILE\.android\evobuddy-release.p12"
keytool -genkeypair -storetype PKCS12 -keystore $keyStore -alias evobuddy -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 -dname "CN=EvoBuddy"
```

With GitHub CLI authenticated to the repository, upload the key directly to
Actions secrets without writing a Base64 copy to disk:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes($keyStore)) | gh secret set ANDROID_KEYSTORE_BASE64 --repo datatrash/evobuddy
gh secret set ANDROID_KEYSTORE_PASSWORD --repo datatrash/evobuddy
```

The second command prompts for the password. For an existing key with a
different key password, also set `ANDROID_KEY_PASSWORD`. The alias defaults to
`evobuddy`; set the repository Actions variable `ANDROID_KEY_ALIAS` if needed.
Never commit signing keys or passwords. The workflow checks for the required
secrets before building and does not publish an unsigned APK as an installer.

## Release

1. Increase `versionName` and `versionCode` in `app/build.gradle.kts`.
2. Update the version displayed in Settings, the F-Droid recipe and the
   corresponding version-code changelog under each store locale.
3. Run `python tools/check-release.py` and the Android checks.
4. Commit, create an annotated tag, and push:

   ```sh
   git tag -a v0.1.10 -m 'Release 0.1.10'
   git push origin main
   git push origin v0.1.10
   ```

5. Check the Actions run and the APK/checksum on the Releases page.
6. Resolve the published tag with `git rev-parse 'v0.1.10^{commit}'` and put that
   full hash in the F-Droid recipe's `commit` field. Commit this metadata update
   separately, then run `python tools/check-release.py --require-tag` before
   preparing an F-Droid submission. Do not move the published release tag.

Local previews, production GitHub releases and F-Droid builds can have different
signing keys. Switching channels may require uninstalling the old app first,
which clears its private data. Export schedules and note shortcut settings
before switching. F-Droid uses its own signing identity unless a separately
verified reproducible-build arrangement is configured with its maintainers.
