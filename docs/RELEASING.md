# Releasing Elyndra

How to publish a version so that the in-app updater (Settings → About →
Updates) finds it and can install it. The updater reads the public GitHub
Releases API of `jefer02/Elyndra`, so a release is "live" for everyone as soon
as it is published.

## The rules the updater relies on

| Thing | Rule | Example |
|---|---|---|
| Tag | `v` + semver, optional pre-release suffix | `v1.0.0`, `v1.1.0-beta`, `v1.1.0-beta.2` |
| `versionName` | The tag without the `v`. Must match exactly | `1.0.0` |
| `versionCode` | Previous release + 1. **Never lower it**: Android refuses to install a lower code over a higher one | `4` for 1.0.0, then `5`, `6`… |
| APK name | Ends in `.apk` and carries the ABI as its own token, or `universal` | `Elyndra-v1.1.0-arm64-v8a.apk`, `Elyndra-v1.1.0-universal.apk` |
| Signing key | Always the same release keystore | — |
| Draft | Drafts are ignored | — |
| Pre-release | Offered only to users with "Include beta versions" on (off by default since 1.0.0) | stable: no, betas: yes |

The updater compares the tag with the installed `versionName` using semver
(`1.0.0` < `1.1.0-beta` < `1.1.0-beta.2` < `1.1.0` < `1.1.1`). It picks the APK by
matching the device's ABIs (`Build.SUPPORTED_ABIS`, preferred first) against
the asset names, then falls back to the `universal` APK, then to a single APK
with no ABI in its name. If none fits, it opens the release page instead.
The ABI tokens it recognises are `arm64-v8a` (also `arm64`, `aarch64`),
`armeabi-v7a` (also `armv7`), `x86_64` and `x86`.

## Steps

### 1. Bump the version

In `app/build.gradle.kts`:

```kotlin
versionCode = 6              // previous + 1
versionName = "1.1.0"        // = tag without the "v" (a beta would be "1.1.0-beta")
```

### 2. Test

```bash
./gradlew testDebugUnitTest
```

If the UI changed a lot since the last release, regenerate the Baseline
Profile first (see [Baseline Profile](#baseline-profile)) and commit it.

### 3. Build the signed release APKs

The keystore and its passwords stay **outside the repository**. Never commit
them, never put them in `local.properties`, and avoid typing passwords where
they end up in shell history (read them into environment variables first).

One build makes both APKs (arm64 + universal). The `elyndra.releaseApks`
property turns on the ABI split in `app/build.gradle.kts`:

```bash
./gradlew :app:assembleRelease -Pelyndra.releaseApks \
  -Pandroid.injected.signing.store.file="<path outside the repo>/elyndra-release-key.jks" \
  -Pandroid.injected.signing.store.password="$ELYNDRA_STORE_PASSWORD" \
  -Pandroid.injected.signing.key.alias=elyndra-key \
  -Pandroid.injected.signing.key.password="$ELYNDRA_KEY_PASSWORD"
```

Output in `app/build/outputs/apk/release/`: `app-arm64-v8a-release.apk` and
`app-universal-release.apk`.

Then check that both carry the Baseline Profile and nothing from the
benchmark builds (see [Verify the profile is in the release APK](#verify-the-profile-is-in-the-release-apk)):

```bash
./gradlew :app:verifyReleaseBaselineProfile
``` Rename them to `Elyndra-v1.1.0-arm64-v8a.apk` and
`Elyndra-v1.1.0-universal.apk`.

**Don't** publish an APK built with `-Pandroid.injected.invoked.from.ide=true`
(with or without `-Pandroid.injected.build.abi`). It is marked `testOnly`:
Android refuses to install it outside `adb install -t`, from a file manager or
from the in-app updater. To check, run
`aapt2 dump xmltree --file AndroidManifest.xml <apk> | grep testOnly`; it must
print nothing.

Check that both are signed with the release key (the certificate digest must
be the same as in previous releases):

```bash
apksigner verify --print-certs Elyndra-v1.1.0-arm64-v8a.apk
```

`apksigner` is in `<Android SDK>/build-tools/<version>/`.

### 4. Optional: checksum files

GitHub already publishes a SHA-256 `digest` for every uploaded asset, and the
updater verifies the download against it. A `.sha256` next to each APK is
only a fallback (and handy for people who download by hand). Name it after the
APK plus `.sha256`:

```bash
sha256sum Elyndra-v1.1.0-arm64-v8a.apk > Elyndra-v1.1.0-arm64-v8a.apk.sha256
```

On Windows PowerShell:

```powershell
(Get-FileHash Elyndra-v1.1.0-arm64-v8a.apk -Algorithm SHA256).Hash.ToLower() + "  Elyndra-v1.1.0-arm64-v8a.apk" |
  Out-File -Encoding ascii Elyndra-v1.1.0-arm64-v8a.apk.sha256
```

### 5. Publish on GitHub

1. Commit the version bump and push it.
2. Go to **Releases → Draft a new release**.
3. **Choose a tag**: type `v1.1.0` (or `v1.1.0-beta` for a beta) and create it on the commit you just
   pushed.
4. **Title**: for example `Elyndra v1.1.0`. **Notes**: Markdown. The app
   shows roughly the first 12 lines / 600 characters, so put the highlights
   first.
5. Attach `Elyndra-v1.1.0-arm64-v8a.apk`, `Elyndra-v1.1.0-universal.apk`
   and, if you made them, the `.sha256` files.
6. Leave **Set as a pre-release** unticked for a stable version. Tick it only
   for a beta (tag with `-beta`): only users who turned on "Include beta
   versions" will be offered it.
7. **Publish release** (not "Save draft": drafts are invisible to the app).

### 6. Check

- <https://api.github.com/repos/jefer02/Elyndra/releases> lists the new tag
  with `"draft": false` and both assets.
- On a device with the previous release installed: Settings → About →
  Updates → Check now. It should offer the new version, download it and open
  Android's install dialog.

## Baseline Profile

A Baseline Profile tells Android which code to compile ahead of time when
the APK is installed: cold start, the library (Meridian wheel and Classic
shelf), folders, search, details, Settings and the art picker. It matters most
on low-end phones, tablets and TV boxes. The app builds and runs normally
without one; it just starts slower and stutters more until ART has profiled
it on its own.

The profile is **generated by hand** on a real device (the low-end test phone,
the Tecno Spark 30C) and **committed**. Release builds pick it up from the
source tree; building does not regenerate it.

### How it stays away from the real app

The profile is generated and measured on **"Elyndra Bench"**, not on Elyndra:

| | Real app | Elyndra Bench |
|---|---|---|
| Build types | `debug`, `release` | `nonMinifiedRelease` (generates), `benchmarkRelease` (measures) |
| applicationId | `com.elyndra.launcher` | `com.elyndra.launcher.benchmark` |
| Launcher label | Elyndra | Elyndra Bench |
| Signing | release key (or debug) | debug key, never published |
| Library | yours | a fixed fixture library (see below) |

Both are installed side by side. Gradle installs and uninstalls only
`com.elyndra.launcher.benchmark`, and Macrobenchmark only kills, resets and
measures that package (`baselineprofile/.../Ui.kt` refuses to run if the app
under test has any other id). Your Elyndra install, its data and its
compilation state are never touched.

The package suffix does not affect the profile: profile rules name classes
and methods (`HSPLcom/elyndra/launcher/MainActivity;->onCreate(...)`), and
classes come from the Kotlin packages (`namespace = com.elyndra.launcher`),
which are the same in every variant. Only the manifest package changes. R8
rewrites the rules for the obfuscated release names when it builds the release.

**Fixture library.** A fresh "Elyndra Bench" starts with a deterministic
library seeded on first launch (`app/src/benchmark/.../bench/fixtures/`): 25
Android games and four ROM folders (PSP, Switch, PS2, GBA, 12–15 games each)
with names, descriptions, genres, play time and sessions, and local artwork
drawn on the phone itself (tall, square and wide covers, icons, transparent
logos, low-res and full-HD backgrounds, some missing on purpose). No network,
no folder picker, no API keys, no real ROMs. In these variants the intro, the
notification prompt, music, video background, auto-metadata, Lucy nudges and
ambient tips, the updater and the background workers are off, and the art
picker only shows the local fixture images. None of this code or data is in
debug or release builds (`src/noBenchmark` has an empty stub).

### Before you start (once)

1. On the phone: **Settings → About phone →** tap **Build number** seven
   times. Then **Settings → System → Developer options →** turn on **USB
   debugging**.
2. Connect it by USB, unlock it and accept the "Allow USB debugging?" prompt.
3. On the computer, `adb devices` must list the phone, and only the phone
   (unplug other phones, close emulators).
4. Keep the screen on and unlocked during the runs (Developer options →
   **Stay awake** helps), and don't touch the phone while a run is going.

Run the commands below from the project root. Never run plain `./gradlew build`,
`./gradlew assemble` or `./gradlew :baselineprofile:assemble` with the phone
connected: the profile plugin makes those run the generator on the device.
Use the exact tasks below (`assembleNonMinifiedRelease`,
`assembleBenchmarkRelease`, `assembleDebug` and `:app:assembleRelease` never
touch a device).

### Generate it

```bash
./gradlew :app:generateReleaseBaselineProfile
```

This builds and installs "Elyndra Bench" (`nonMinifiedRelease`) and runs
`baselineprofile/src/main/java/.../BaselineProfileGenerator.kt`. The first
launch seeds the fixture library (up to a few minutes on the Tecno; the test
waits). Then it runs the same walk three times in **Quality** and three
times in **Performance** mode (the profile builds up across the three):

1. cold start to the library (Meridian, landscape);
2. ~15 D-pad steps on the wheel (hero, artwork, glass);
3. LB/RB through the tabs (All / Android / Consoles);
4. search: open, type, close;
5. details (X) and options (Y) of a game;
6. a ROM folder in Meridian: D-pad and flings;
7. portrait (Classic): the library shelf and a folder shelf;
8. Settings → Display → Performance;
9. the art picker of an Android game (local fixture images only), a few
   thumbnails with the D-pad.

It never presses Play and never opens Lucy (her 3D scene and voice are large
and not part of everyday browsing). If any expected screen or test tag is
missing, it fails with a message naming the tag. Expect about 25 minutes in
total. At the end Gradle uninstalls "Elyndra Bench".

**On Tecno, Infinix and itel phones** (HiOS/XOS) the system's "Hiber" freezer
stops any app without a window a few seconds after it starts, including the
test runner, and the run then hangs forever. The test APK's id ends in `.xts`
(`com.elyndra.launcher.baselineprofile.xts`, see `baselineprofile/build.gradle.kts`)
because Hiber leaves test suites with that kind of name alone. Battery
settings, `cmd deviceidle whitelist` or `am unfreeze` do not help.

The result lands in:

```
app/src/release/generated/baselineProfiles/baseline-prof.txt
app/src/release/generated/baselineProfiles/startup-prof.txt
```

**Commit both files.** Rules for the fixture classes
(`com.elyndra.launcher.bench`) are filtered out of both. Regenerate after big
UI changes (new screens, reworked library or Meridian); an outdated profile
still works, it just covers less.

`baseline-prof.txt` is compiled into the APK as `assets/dexopt/baseline.prof`
(+ `baseline.profm`). `startup-prof.txt` is used by R8 for DEX layout
optimization: classes needed at startup go first, in `classes.dex`. This needs
AGP 8.2+ with R8 (minify) on for the release, as it is here (AGP 8.7.3,
`dexLayoutOptimization = true` in `app/build.gradle.kts`).

The UI is found through stable test tags (`ui/UiTags.kt`, exposed as
resource ids). If you rename one, update `baselineprofile/.../Ui.kt` too.

### Measure the gain

```bash
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
```

This installs "Elyndra Bench" (`benchmarkRelease`, minified like the release)
and runs:

- `StartupBenchmark`: 10 cold starts without any AOT compilation
  (`startupWithoutCompilation`) and 10 with the profile
  (`startupWithBaselineProfile`), in Auto mode.
- `ScrollBenchmark`: the Classic shelf (`classicShelf`) and the Meridian wheel
  (`meridianWheel`), 5 iterations each, for every combination of
  `None` / `BaselineProfile` and `Performance` / `Quality`.

The `BaselineProfile` cases fail on purpose if no profile has been generated.
To run only one class, add
`-Pandroid.testInstrumentationRunnerArguments.class=com.elyndra.launcher.baselineprofile.ScrollBenchmark`.

Close other apps first, keep the phone at the same temperature for every run
(let it cool down between runs), and don't charge it at full power meanwhile.

### Read the results

Results are printed in the Gradle output and saved under
`baselineprofile/build/outputs/connected_android_test_additional_output/`, in
a subfolder per variant and device (`benchmarkRelease/connected/<device>/`):

```
com.elyndra.launcher.baselineprofile.xts-benchmarkData.json   all numbers, every test
*.perfetto-trace                                          one trace per iteration
```

Open a `.perfetto-trace` in <https://ui.perfetto.dev> to see where a slow frame
or a slow start spent its time.

The numbers that matter:

| Benchmark | Metric | What to compare |
|---|---|---|
| StartupBenchmark | `timeToInitialDisplayMs` **median** | `startupWithBaselineProfile` vs `startupWithoutCompilation` (lower is better) |
| ScrollBenchmark | `frameDurationCpuMs` **P50 / P90 / P99** | `BaselineProfile-*` vs `None-*` for the same test and mode (lower is better) |
| ScrollBenchmark | `frameOverrunMs` **P50 / P90 / P99** | How late frames are versus their deadline: positive values are visible jank; P90/P99 are what users feel (needs Android 12+) |

P50 is the typical frame, P90/P99 the worst ones. On a 60 Hz screen a frame
has 16.7 ms; on a 90 Hz screen 11.1 ms.

On the Tecno, `ScrollBenchmark` fails with "Observed no renderthread slices"
(or "no expect/actual slices"): the traces often lack thread names, so
`FrameTimingMetric` cannot find the RenderThread. The traces themselves are
complete; the frame numbers can be computed from them in Perfetto
(`Choreographer#doFrame <id>` → `DrawFrames <id>` for CPU time, and the
`actual_frame_timeline_slice` / `expected_frame_timeline_slice` tables for
overrun). Performance mode caps the app at 60 fps on a 120 Hz screen, so most
of its frames show as "Buffer Stuffing" with a positive overrun that is not
visible jank; count the "App Deadline Missed" frames instead.

### Verify the profile is in the release APK

After `assembleRelease` (step 3 of [Steps](#steps)), in the same command or
right after it:

```bash
./gradlew :app:verifyReleaseBaselineProfile
```

It checks the APKs that are in `app/build/outputs/apk/release/`: both
committed profile files exist and contain Elyndra rules and no fixture rules;
the merged profile used for the build has Elyndra rules; R8 received the
startup profile; every APK has `assets/dexopt/baseline.prof` and
`baseline.profm`; R8 kept `ProfileInstallerInitializer` (it installs the
profile on first launch for sideloaded and GitHub-Release installs, without
Play Store) and it is in the merged manifest; no fixture class or file is in
the APK; and no `benchmark`/`nonMinified` APK sits in `release-apk/`.

By hand:

```bash
unzip -l Elyndra-v1.2.0-arm64-v8a.apk | grep dexopt
#   assets/dexopt/baseline.prof
#   assets/dexopt/baseline.profm
```

(A release without the app's own profile still has a small `baseline.prof`,
about 7 KB, from the libraries; with it, the file is much larger.)

### Afterwards

Gradle normally uninstalls "Elyndra Bench" at the end of each run. If it is
still on the phone (a run was interrupted):

```bash
adb uninstall com.elyndra.launcher.benchmark
```

This removes only "Elyndra Bench" and its fixture data. It has a different
package from Elyndra (`com.elyndra.launcher`), so your Elyndra, its library,
its settings and its compiled code stay as they were. To confirm,
`adb shell pm list packages com.elyndra` must still list
`package:com.elyndra.launcher`.

The `nonMinifiedRelease` and `benchmarkRelease` APKs in `app/build/outputs/`
are debug-signed test builds: never rename, upload or copy them to
`release-apk/`.

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
