# Release APK automation — design

Date: 2026-11-15
Status: approved

## Goal

Every new GitHub release carries a signed release APK. The APK is built by CI,
never by a laptop, and it is attached to the release automatically.

## Trigger

The workflow runs on three events:

| Event | Behaviour |
| --- | --- |
| `push` of a tag matching `v*` | Build the signed APK and store it as a workflow artifact. Upload to the release when a release for that tag already exists. |
| `release: published` | Build the signed APK, or reuse the artifact of the tag build, and attach it to the release. |
| `workflow_dispatch` | Manual retry for a given tag. |

The release event only fires for workflows that exist on the default branch, so
the workflow file has to be merged into `main` before the first release.

## Versioning

`versionName` and `versionCode` come from the release tag:

- `versionName` is the tag without its leading `v` (`v0.2.0` becomes `0.2.0`).
- `versionCode` is `major * 10000 + minor * 100 + patch` (`v0.2.0` becomes `200`).
  Every component must be a number between 0 and 99, and the result must be at
  least 1, because `versionCode` 0 is invalid.
- A pre-release suffix is kept in `versionName` and ignored for `versionCode`
  (`v0.2.0-rc1` becomes `0.2.0-rc1` and `200`).
- Anything else fails the build with a message naming the offending tag.

The parser lives in a new `buildSrc` module so it can be unit tested. Without a
release tag the build keeps the current `0.1.0` / `1` values, so local builds
stay untouched.

## Signing

`app/build.gradle.kts` gains a `release` build type that uses a signing config
built from environment variables:

| Variable | Meaning |
| --- | --- |
| `ANDROID_KEYSTORE_PATH` | Path to the release keystore |
| `ANDROID_KEY_ALIAS` | Key alias inside the keystore |
| `ANDROID_KEY_PASSWORD` | Key password |
| `ANDROID_STORE_PASSWORD` | Keystore password |

The keystore itself travels through GitHub as the base64 encoded secret
`ANDROID_KEYSTORE_BASE64`. The workflow decodes it into the runner temp
directory, fails fast when a secret is missing, and runs `apksigner verify` on
the finished APK.

The release key is generated once with `keytool` and kept outside the
repository. The keystore and its passwords are the only way to publish an
update that users can install over an existing release, so they need a backup.

## Workflow

`.github/workflows/release.yml`, `ubuntu-24.04`, `contents: write` and
`actions: read`, 120 minute job timeout.

1. Resolve the tag from the event and fail when it is missing.
2. Check out the tagged commit.
3. Install Temurin JDK 21, then the Android SDK packages
   `platforms;android-37.0`, `build-tools;36.0.0`, `ndk;28.2.13676358` and
   `platform-tools`. Licenses are accepted first.
4. Restore the Gradle dependency cache and the native build tree. The native
   cache key is the hash of `scripts/build-native.sh` and `native/converter.c`,
   so a native change rebuilds and an unchanged native tree is reused.
5. Build with `./gradlew :buildSrc:test :app:assembleRelease`. The native build
   runs automatically through `preBuild`. A failed build is retried once with a
   clean native tree, which protects against a half-written cache entry.
6. Verify the APK with `apksigner` and upload it as the workflow artifact
   `app-release-apk`.
7. Attach the artifact to the release with `gh release upload --clobber` when a
   release for the tag exists.

On `release: published` the build step first looks for a successful earlier run
of the same commit and downloads its artifact, so the normal flow compiles the
native libraries once, not twice. When no earlier run exists the workflow builds
from scratch.

## Risks

- The native build is the long pole. FFmpeg, LAME and the Xiph libraries are
  compiled for `arm64-v8a` and `x86_64`, which takes roughly 30 to 50 minutes on
  a four core runner. The cache removes that cost on later runs.
- The runner needs about 6 GB of free disk for the native build tree.
- `platforms;android-37.0` is the package id of the platform this project
  compiles against. The install step falls back to `platforms;android-37` when
  the minor version package is not published yet.
- A release APK signed with the release key cannot be installed over a debug
  build, because the signatures differ.

## Tests

- `buildSrc/src/test/kotlin/.../ReleaseVersionTest.kt` covers the tag parser.
- The workflow itself is verified by an end-to-end run: push a tag, publish the
  release, and confirm the APK is attached.
