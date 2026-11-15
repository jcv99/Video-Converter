# Releasing

A release is a git tag plus a GitHub release. The release workflow builds the
signed APK and attaches it to the release.

## What the workflow does

`.github/workflows/release.yml` runs on three events.

| Event | What happens |
| --- | --- |
| A tag matching `v*` is pushed | The signed APK is built and stored as a workflow artifact. It is attached to the release right away when a release for that tag already exists. |
| A GitHub release is published | The APK of the tag build is reused, or built from scratch when no earlier build exists, and attached to the release. |
| A manual run | The same, for the tag given as input. |

The release event only fires for workflows that live on the default branch, so
the workflow file has to be merged into `main` before the first release.

## Versioning

The tag carries the version.

- `versionName` is the tag without its leading `v`, so `v0.2.0` builds an app
  called `0.2.0`.
- `versionCode` is `major * 10000 + minor * 100 + patch`, so `v0.2.0` becomes
  `200` and `v1.2.3` becomes `10203`. A tag with a pre-release suffix keeps the
  suffix in `versionName` and uses the numbers for `versionCode`.
- A tag that does not hold one to three numeric parts fails the build.

Every release needs a higher `versionCode` than the one before it. A local
build without a tag keeps the versions `0.1.0` and `1`.

## Release procedure

1. Merge the release changes into `main`.
2. Tag the release and push the tag.

   ```bash
   git tag -a v0.2.0 -m "Video Converter 0.2.0"
   git push origin v0.2.0
   ```

   The tag build starts on its own and stores the APK as a workflow artifact.
   It takes roughly 30 to 50 minutes on a cold runner because the native
   libraries are compiled for `arm64-v8a` and `x86_64`. Later runs reuse the
   cached native build tree.

3. Publish the release. The workflow attaches the APK to it.

   ```bash
   gh release create v0.2.0 --generate-notes --title "Video Converter 0.2.0"
   ```

   The published release now carries `video-converter-0.2.0.apk`.

4. Check the run in the Actions tab. A failed build leaves the release without
   an APK, and a manual run with the same tag retries it:

   ```bash
   gh workflow run release.yml -f tag=v0.2.0
   ```

## Signing

The release APK is signed with a key that never enters the repository.

| Secret | Meaning |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The base64 encoded release keystore |
| `ANDROID_KEY_ALIAS` | The key alias inside the keystore |
| `ANDROID_STORE_PASSWORD` | The keystore password |
| `ANDROID_KEY_PASSWORD` | The key password |

The local copy of the key sits next to the properties file that holds its
passwords:

```
~/.config/video-converter/release.keystore
~/.config/video-converter/release-keystore.properties
```

Back both files up somewhere safe. The key is the only way to publish an
update that users can install over an existing release. Never commit the
keystore, the properties file or the secrets.

A release APK cannot be installed over a debug build because the two use
different signatures. Uninstall the debug build first.

## Native build cache

The workflow caches `app/build/native` under a key made of the hash of
`scripts/build-native.sh` and `native/converter.c`. A change to the native
bridge or to the build script produces a new key and a fresh native build. A
build that fails with a restored cache is retried once with a clean native
tree.
