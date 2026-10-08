# Releasing Elyndra

How to publish a version so that the in-app updater (Settings → About →
Updates) finds it and can install it. The updater reads the public GitHub
Releases API of `jefer02/Elyndra`, so a release is "live" for everyone as soon
as it is published.

## The rules the updater relies on

| Thing | Rule | Example |
|---|---|---|
| Tag | `v` + semver, optional pre-release suffix | `v0.4.0-beta`, `v0.4.0-beta.2`, `v1.0.0` |
| `versionName` | The tag without the `v`. Must match exactly | `0.4.0-beta` |
| `versionCode` | Previous release + 1. **Never lower it**: Android refuses to install a lower code over a higher one | `4` |
| APK name | Ends in `.apk` and carries the ABI as its own token, or `universal` | `Elyndra-v0.4.0-beta-arm64-v8a.apk`, `Elyndra-v0.4.0-beta-universal.apk` |
| Signing key | Always the same release keystore | — |
| Draft | Drafts are ignored | — |
| Pre-release | Offered only to users with "Include beta versions" on (the default) | betas: yes |

The updater compares the tag with the installed `versionName` using semver
(`0.4.0-beta` < `0.4.0-beta.2` < `0.4.0` < `0.4.1`). It picks the APK by
matching the device's ABIs (`Build.SUPPORTED_ABIS`, preferred first) against
the asset names, then falls back to the `universal` APK, then to a single APK
with no ABI in its name. If none fits, it opens the release page instead.
The ABI tokens it recognises are `arm64-v8a` (also `arm64`, `aarch64`),
`armeabi-v7a` (also `armv7`), `x86_64` and `x86`.

## Steps

### 1. Bump the version

In `app/build.gradle.kts`:

```kotlin
versionCode = 4              // previous + 1
versionName = "0.4.0-beta"   // = tag without the "v"
```

### 2. Test

```bash
./gradlew testDebugUnitTest
```

### 3. Build the signed release APKs

The keystore and its passwords stay **outside the repository**. Never commit
them, never put them in `local.properties`, and avoid typing passwords where
they end up in shell history (read them into environment variables first).

arm64 build (what most handhelds and phones use):

```bash
./gradlew :app:assembleRelease \
  -Pandroid.injected.signing.store.file="<path outside the repo>/elyndra-release.jks" \
  -Pandroid.injected.signing.store.password="$ELYNDRA_STORE_PASSWORD" \
  -Pandroid.injected.signing.key.alias=elyndra \
  -Pandroid.injected.signing.key.password="$ELYNDRA_KEY_PASSWORD" \
  -Pandroid.injected.invoked.from.ide=true \
  -Pandroid.injected.build.abi=arm64-v8a
```

Output: `app/build/intermediates/apk/release/app-release.apk`. Copy and rename
it to `Elyndra-v0.4.0-beta-arm64-v8a.apk` before building the next one (the
next build overwrites it).

Universal build (every ABI, larger): run the same command **without** the last
two `-Pandroid.injected.invoked.from.ide` / `-Pandroid.injected.build.abi`
lines. Output: `app/build/outputs/apk/release/app-release.apk`. Rename it to
`Elyndra-v0.4.0-beta-universal.apk`.

Check that both are signed with the release key (the certificate digest must
be the same as in previous releases):

```bash
apksigner verify --print-certs Elyndra-v0.4.0-beta-arm64-v8a.apk
```

`apksigner` is in `<Android SDK>/build-tools/<version>/`.

### 4. Optional: checksum files

GitHub already publishes a SHA-256 `digest` for every uploaded asset, and the
updater verifies the download against it. A `.sha256` next to each APK is
only a fallback (and handy for people who download by hand). Name it after the
APK plus `.sha256`:

```bash
sha256sum Elyndra-v0.4.0-beta-arm64-v8a.apk > Elyndra-v0.4.0-beta-arm64-v8a.apk.sha256
```

On Windows PowerShell:

```powershell
(Get-FileHash Elyndra-v0.4.0-beta-arm64-v8a.apk -Algorithm SHA256).Hash.ToLower() + "  Elyndra-v0.4.0-beta-arm64-v8a.apk" |
  Out-File -Encoding ascii Elyndra-v0.4.0-beta-arm64-v8a.apk.sha256
```

### 5. Publish on GitHub

1. Commit the version bump and push it.
2. Go to **Releases → Draft a new release**.
3. **Choose a tag**: type `v0.4.0-beta` and create it on the commit you just
   pushed.
4. **Title**: for example `Elyndra v0.4.0-beta`. **Notes**: Markdown. The app
   shows roughly the first 12 lines / 600 characters, so put the highlights
   first.
5. Attach `Elyndra-v0.4.0-beta-arm64-v8a.apk`, `Elyndra-v0.4.0-beta-universal.apk`
   and, if you made them, the `.sha256` files.
6. Tick **Set as a pre-release** for betas. Leave it unticked for a stable
   version.
7. **Publish release** (not "Save draft": drafts are invisible to the app).

### 6. Check

- <https://api.github.com/repos/jefer02/Elyndra/releases> lists the new tag
  with `"draft": false` and both assets.
- On a device with the previous release installed: Settings → About →
  Updates → Check now. It should offer the new version, download it and open
  Android's install dialog.

## Things not to do

- Don't reuse or move a tag that is already published. Make a new version.
- Don't lower `versionCode`, and don't publish two releases with the same one.
- Don't sign with another key. Android won't install an update signed with a
  different key (this is also why a debug build can't update to a release, and
  the app says so).
- Don't publish an APK without the ABI token or `universal` when there is more
  than one APK in the release: the updater won't guess and will open the
  release page.

## Testing the updater without publishing

Debug builds have a **Simulate a newer release (debug build)** button in
Settings → About → Updates. It takes the newest real release, pretends it is
version `vN+1.0.0-debug` and runs the whole flow (dialog, download, checksum,
install). On a debug build, the install ends with the "different key" message,
which is expected. The button does not exist in release builds (the code lives
in `app/src/debug/`; `app/src/release/` has an empty stub).
