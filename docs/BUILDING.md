# Building Autogram

How to build Autogram from source with your own credentials and signing key.

## Requirements

- JDK 17 (set `JAVA_HOME`).
- Android SDK with platform 36 and build-tools 35 (set `ANDROID_HOME`, or
  `sdk.dir` in `local.properties`).
- TDLib native libraries are vendored under `app/src/main/jniLibs`, so no NDK
  is needed. To upgrade TDLib, run the `Build TDLib for Android` workflow
  (`.github/workflows/build-tdlib.yml`) and vendor its output.

## Telegram API credentials

Autogram talks to Telegram through TDLib, which needs an `api_id` and
`api_hash`.

**Forks and redistributed builds must register their own `api_id`.** Do not
reuse the values extracted from the official APKs: Telegram can rate-limit or
ban an `api_id` that is abused, which would break every build that shares it.

1. Sign in at <https://my.telegram.org> → *API development tools*.
2. Create an application and note the `api_id` (an integer) and `api_hash`.
3. Add them to `local.properties` at the repo root (gitignored):

```properties
sdk.dir=/path/to/android/sdk
telegram.apiId=1234567
telegram.apiHash=0123456789abcdef0123456789abcdef
# Optional: Sentry crash reporting. Leave empty or omit to disable it.
sentry.dsn=
```

Debug builds and unit tests work without these values (the app just cannot
log in). **Release builds fail** with a clear error if `telegram.apiId` is
missing or `0`, or `telegram.apiHash` is empty.

### Sentry (optional)

`sentry.dsn` enables crash reporting in the `github` flavor. Use a DSN from
your own Sentry project; an empty value disables Sentry at runtime and the
app logs locally instead. The DSN is compiled into the APK, so treat it as
public: enable rate limits and inbound filters on the Sentry project.

## Signing key

Release builds are signed with the key described in `keystore.properties` at
the repo root (gitignored). Create your own key:

```bash
keytool -genkeypair -v -keystore my-release.jks -alias autogram \
    -keyalg RSA -keysize 4096 -validity 10000
```

```properties
storeFile=/absolute/path/to/my-release.jks
storePassword=...
keyAlias=autogram
keyPassword=...
```

Never commit the keystore or `keystore.properties`. Without
`keystore.properties` the release APK is built unsigned.

APKs signed with your key cannot upgrade over the official builds (and vice
versa): Android requires the same signing certificate for in-place updates.

## Gradle commands

```bash
# Debug APK
./gradlew assembleGithubDebug

# Unit tests
./gradlew testGithubDebugUnitTest

# Signed release APK (needs Telegram credentials + keystore.properties)
./gradlew assembleGithubRelease
```

Output: `app/build/outputs/apk/github/release/app-github-release.apk`.

Check the signature (v2 + v3) and certificate with:

```bash
apksigner verify -v --print-certs app/build/outputs/apk/github/release/app-github-release.apk
```

## Verifying an official release

Official APKs are published at
<https://github.com/PepeImpulso/autogram-releases/releases>, each with an
`autogram-<version>.apk.sha256` file:

```bash
sha256sum -c autogram-<version>.apk.sha256
apksigner verify --print-certs autogram-<version>.apk
```

The signer certificate SHA-256 must be
`e97d2c4cfd035043aeecf05b7ae947d41bc2f47c4d6bbaa59957484655b42699`.

## Release flow (maintainers)

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Write `docs/release-notes/v<version>.md`.
3. Publish with `scripts/publish_autogram_release.sh <version>` (pushes a tag;
   the `Release APK` workflow builds, signs and publishes from CI secrets) or
   `scripts/publish_autogram_release.sh --manual <version>` (builds and signs
   locally, then uploads the APK and its `.sha256` file).
4. Releases go to `PepeImpulso/autogram-releases`, which the in-app updater
   checks. Tags with `-alpha`, `-beta` or `-rc` are published as pre-releases.
