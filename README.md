# Dailymotion for WizeStream

A Kotlin service plugin implementing **native WizeStream plugin API 2.0** for
[Dailymotion](https://www.dailymotion.com/). It adds Dailymotion to WizeStream's service
selector and uses the host's networking, result screens, subscriptions, playlists and player.

- **Plugin:** `org.wizestream.plugin.dailymotion`, version **1.0.0** (`versionCode: 1`).
- **Host:** WizeStream **1.52.0 or newer**, on Android **7/API 24 or newer**.
- **Contract:** `host: "wizestream"`, `apiVersion: "2.0"`. This is a native WizeStream
  plugin; a PipePipe API 1.x host cannot load it.
- **Access:** public Dailymotion content, without an API key, Google account or Dailymotion login.

[WizeStream](https://github.com/wizdom13/WizeStream) ·
[Plugin API and installation guide](https://github.com/wizdom13/WizeStream/blob/pipe/docs/plugins.md) ·
[Report a plugin issue](https://github.com/wizdom13/Dailymotion-WizeStream-Plugin/issues)

## Supported features

| Feature | Behavior |
| --- | --- |
| Video links | Open Dailymotion watch/embed links and `dai.ly` short links; normalize them to stable video IDs. |
| Search | Search public videos and channels, with pagination. Video results support relevance, newest and most-viewed ordering. |
| Channel pages | Show public channel details, videos and playlists. Channel URLs can be saved through WizeStream's subscription interface. |
| Playlists | Show public playlist details and paginated videos. |
| Discovery | Browse the public Trending video feed. |
| Playback | Use public HLS, DASH and progressive MP4 sources advertised by Dailymotion's player metadata. Public live streams use the host's live playback type when available. |
| Captions | Expose subtitle tracks supplied in the player metadata. |
| Downloads | Progressive MP4 sources are exposed when Dailymotion supplies them. Many videos expose HLS only; the plugin does not manufacture MP4 download URLs. |

Global playlist search, comments, account/private libraries, authenticated content and importing
Dailymotion account subscriptions are not implemented. Public playlists remain accessible by URL
and through a channel's Playlists tab. The plugin does not bypass password, age/account, geographic,
payment or DRM restrictions. Deleted, restricted and unavailable videos produce an extraction
error rather than an invented playback source. Dailymotion can also reject a stream request for
a particular network or region even when its metadata is readable.

Native WizeStream API **2.0** is the host plugin contract. It is unrelated to Dailymotion's
OAuth-based API v2 for managing Studio accounts. Public discovery uses Dailymotion's public
catalog endpoints; playback uses the normal public player metadata response.

## Install

A source checkout or this GitHub repository URL is not an installable plugin repository.
Installation requires a published **signed `.ppp` package** and its matching **`index.json`**.

1. Obtain the publisher's direct HTTPS `index.json` URL from a published release or the
   [WizeStream Plugins catalog](https://github.com/wizdom13/WizeStream-Plugins).
2. In WizeStream, open **Settings → Plugins**, enable plugins, and add that repository URL
   if the plugin is not already present in a configured catalog.
3. Check the publisher and install **Dailymotion**.
4. Restart WizeStream when prompted, then select **Dailymotion** as the active service.

This implementation does not automatically publish a release or add an entry to the shared
catalog. CI artifacts are explicitly **test-only** and signed with disposable keys. They are
not a stable update channel and must not be redistributed as a publisher release.

The host pins the signing key on first installation. A published update must keep both the
plugin ID and publisher key and increment `versionCode`. Uninstalling does not reset that pin.

## Build and test

### Toolchain

- JDK **21**.
- Gradle **9.5.1**, using the WizeStream checkout's wrapper.
- Android SDK platform **37.0** and Build Tools **36.1.0**.
- A `wizestream-plugin-api.jar` exported from WizeStream.

The CI host baseline is commit
[`b33435d34268d7bb2564930e80d4c50bfae8e7d2`](https://github.com/wizdom13/WizeStream/commit/b33435d34268d7bb2564930e80d4c50bfae8e7d2)
(WizeStream 1.52.2). Keeping the SDK and Android compiler configuration aligned with the host
prevents binary and Java date/time compatibility failures.

Set `ANDROID_HOME` to the SDK installation. Alternatively, add an untracked `local.properties`
containing `sdk.dir=/absolute/path/to/android-sdk` to both projects. On Windows, use forward
slashes in that properties-file path, for example `sdk.dir=C:/Android/Sdk`.

Keep sibling checkouts named `WizeStream` and `Dailymotion-WizeStream-Plugin`. This repository
uses the host wrapper and does not include another Gradle wrapper.

### Linux or macOS

From the directory containing both repositories:

```sh
git clone --branch pipe https://github.com/wizdom13/WizeStream.git
git clone https://github.com/wizdom13/Dailymotion-WizeStream-Plugin.git
git -C WizeStream checkout b33435d34268d7bb2564930e80d4c50bfae8e7d2
cd WizeStream
./gradlew :app:pluginSdkJar
plugin_sdk_jar="$PWD/app/build/outputs/plugin-sdk/wizestream-plugin-api.jar"
./gradlew -p ../Dailymotion-WizeStream-Plugin "-PpluginSdkJar=$plugin_sdk_jar" test pluginPackage
```

If the repositories already exist, use the existing checkouts rather than cloning again.

### Windows PowerShell

From the directory containing both repositories:

```powershell
Set-Location .\WizeStream
.\gradlew.bat :app:pluginSdkJar
$pluginSdkJar = (Resolve-Path .\app\build\outputs\plugin-sdk\wizestream-plugin-api.jar).Path
.\gradlew.bat -p ..\Dailymotion-WizeStream-Plugin "-PpluginSdkJar=$pluginSdkJar" test pluginPackage
```

The unsigned package is written to
`Dailymotion-WizeStream-Plugin/build/repo/dailymotion-wizestream-plugin-1.ppp`.
The filename uses the plugin's `versionCode`, not its display version.

`test` runs deterministic fixtures without depending on a live Dailymotion response. It checks
URL identity, catalog parsing and pagination, stream metadata and error handling. Production
code uses the host downloader; test fixture responses never enter a release package.

### Packaged Android compatibility

[CI](.github/workflows/ci.yml) exports the SDK from the pinned host, runs the JVM suite, compiles
Android DEX and verifies a test-only package signature. Separate API **24** and **35** emulator
jobs use the host's minified `pluginCompatibility` build. They load the actual `.ppp` through
`DexClassLoader`, verify the native API entry point, and exercise search, playback metadata,
subtitles, channels, playlists and discovery through the host's public result types.

The repository-owned [Android fixture](integration/android/DailymotionPackageCompatibilityTest.kt)
is copied only into CI's isolated host checkout; plugin source classes are not compiled into
the host or its test APK. All Dailymotion requests in these tests are answered offline.

These checks verify extraction and binary compatibility. Successful end-to-end playback on a
real device remains unverified and should be checked before publishing. During development,
public metadata and catalog requests succeeded, but a request to an advertised HLS manifest
returned HTTP 403. That response does not establish the cause of the rejection.

## Sign and publish a release

Choose a permanent Ed25519 key pair before the first public release and keep a secure backup.
`generateSigningKey` creates a pair only if neither file exists and refuses to replace an
incomplete pair. The default key files, `signing-key.pk8` and `public-key.der`, are ignored by Git.
Do not use the CI disposable keys for a public release.

From the WizeStream checkout, after exporting the SDK:

```sh
./gradlew -p ../Dailymotion-WizeStream-Plugin \
  "-PpluginSdkJar=$plugin_sdk_jar" \
  "-PpluginSigningKey=/private/dailymotion/signing-key.pk8" \
  "-PpluginPublicKey=/private/dailymotion/public-key.der" \
  generateSigningKey repositoryIndex
```

The private key is PKCS#8 DER; the public key file is X.509 DER. On subsequent releases, retain
the same keys, increase the manifest's `versionCode`, update `versionName`, and run
`repositoryIndex` without generating another identity.

The build signs the **raw 32-byte SHA-256 digest** of the exact package with Ed25519 and verifies
the signature before producing `build/repo/index.json`. Publish that index and its matching
`.ppp` together in the same HTTPS directory, such as assets of one GitHub release. The index
uses a relative package filename. To copy its entry into another catalog, replace only the
package location with the absolute HTTPS download URL while retaining the generated digest,
signature, public key and compatibility fields. Do not modify a signed archive.

## Implementation notes

- Kotlin sources live under `org.wizestream.plugin.dailymotion` and use host-provided extractor
  interfaces. The entry class is `DailymotionPlugin` and the service name is **Dailymotion**.
- Preserve `org.wizestream.plugin.dailymotion` as the plugin ID so saved subscriptions, history
  and playlists continue to refer to the same service. The host assigns the numeric service ID.
- Keep `pluginMinSdk=23` and the pinned NIO desugaring configuration. This matches the host's
  compiled API; the independent installation minimum remains Android API 24.
- The SDK, Kotlin standard library, nanojson, jsoup and commons-lang3 remain `compileOnly`.
  JVM tests add them to the test runtime; the distributed `.ppp` contains no replacement host classes.
- The plugin does not store credentials or add its own networking client. Shared preferences,
  host version and profile identity are available through native API 2.0 if future features need them.

## License

**GPL-3.0-or-later.** See [LICENSE](LICENSE). This repository started from
[WizeStream-Plugin-Template](https://github.com/wizdom13/WizeStream-Plugin-Template).
The package format and original skeleton derive from the
[PipePipe Plugin Template](https://github.com/InfinityLoop1308/PipePipePluginTemplate/tree/91950887dfd173263ecacfc466437a33777d242d);
native SDK integration and signing are WizeStream additions. Dailymotion is a separate service
and this community plugin is not an official Dailymotion product.
