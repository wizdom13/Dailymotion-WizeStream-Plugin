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

After the publisher configures `SIGNING_KEY`, the [release workflow](.github/workflows/release.yml)
publishes signed packages and an index in the rolling **`repo`** GitHub release. Its stable
installation URL is available after the first successful publication:

```text
https://github.com/wizdom13/Dailymotion-WizeStream-Plugin/releases/download/repo/index.json
```

The ordinary CI artifacts remain **test-only** and use disposable keys. Install from the
published repository above. Adding Dailymotion to the shared WizeStream Plugins catalog is
a separate catalog update.

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

### Automatic GitHub releases

[`.github/workflows/release.yml`](.github/workflows/release.yml) publishes after **Dailymotion
plugin CI** succeeds for a push to `main`, including both minified Android compatibility jobs.
It checks out that exact commit and uses the same pinned host SDK, JDK and Android compiler
profile as CI. Pull requests only run workflow validation; they cannot publish or access the
signing secret.

The workflow builds and tests the plugin, signs its package through `repositoryIndex`, and
creates or updates the rolling **`repo`** release. It retains older versioned `.ppp` assets,
uploads the current package first, and updates `index.json` last. An identical package can be
reused on a rerun. A changed package requires a higher `versionCode`; publisher-key changes
and version rollbacks are rejected. A new release remains a draft until its assets are uploaded.

Only one repository secret is required: **`SIGNING_KEY`**, containing the **Base64 encoding of
an Ed25519 PKCS#8 DER private key**. The workflow derives the matching X.509 DER public key,
uses the existing signature verification task, and removes temporary key files afterward.
Its GitHub token is supplied automatically; no personal access token is needed.

### 1. Create the persistent publisher key

Use **JDK 21** and sibling checkouts named `WizeStream` and `Dailymotion-WizeStream-Plugin`.
Key generation uses the host Gradle wrapper but does **not** require an exported plugin SDK
or Android SDK. First-time Gradle/plugin downloads still need network access.

Run the commands below from the **Dailymotion-WizeStream-Plugin checkout**. They store the
keys outside the repository. If you already have a publisher key pair for this plugin, use
those existing files and adjust the paths instead of creating another identity.
`generateSigningKey` reuses a complete existing pair and refuses to replace an incomplete pair.

#### Windows PowerShell

```powershell
$dailymotionKeyDir = Join-Path $env:USERPROFILE "WizeStream-signing\Dailymotion"
$dailymotionPrivateKey = Join-Path $dailymotionKeyDir "signing-key.pk8"
$dailymotionPublicKey = Join-Path $dailymotionKeyDir "public-key.der"

..\WizeStream\gradlew.bat -p . `
  "-PpluginSigningKey=$dailymotionPrivateKey" `
  "-PpluginPublicKey=$dailymotionPublicKey" `
  generateSigningKey

if ($LASTEXITCODE -ne 0) {
    throw "Signing key creation failed."
}

[Convert]::ToBase64String(
    [IO.File]::ReadAllBytes($dailymotionPrivateKey)
) | Set-Clipboard
```

The final command copies the exact secret value to your clipboard without printing it.

#### Linux or macOS

```sh
dailymotion_key_dir="$HOME/WizeStream-signing/Dailymotion"

../WizeStream/gradlew -p . \
  "-PpluginSigningKey=$dailymotion_key_dir/signing-key.pk8" \
  "-PpluginPublicKey=$dailymotion_key_dir/public-key.der" \
  generateSigningKey
```

After key generation succeeds, encode the private file:

```sh
base64 < "$dailymotion_key_dir/signing-key.pk8" | tr -d '\r\n'
```

Copy the resulting Base64 value. Encode the **private** file's binary bytes directly; do not
use the public key, a file path, or PEM header/footer text as the secret value.

### 2. Add the GitHub repository secret

Open this repository's **Settings → Secrets and variables → Actions → Secrets → New repository
secret**. Set the name to **`SIGNING_KEY`**, paste the Base64 value, and click **Add secret**.
The workflow reads it as `${{ secrets.SIGNING_KEY }}`; the secret's name itself is `SIGNING_KEY`.

If your GitHub CLI is already authenticated, this Linux/macOS alternative stores the value
directly without printing it:

```sh
base64 < "$dailymotion_key_dir/signing-key.pk8" |
  tr -d '\r\n' |
  gh secret set SIGNING_KEY --app actions --repo wizdom13/Dailymotion-WizeStream-Plugin
```

Keep a secure backup of **both** key files and reuse this pair for every release. Keep the
private key and its Base64 value out of commits, issues and chat messages. CI's disposable
test keys must not become the publisher identity.

### 3. Publish and update

Merge the release workflow into `main` and configure the secret. A successful CI run for a
push to `main` then starts publication automatically. To publish after adding the secret or
recover from a failed publication, open **Actions → Release Dailymotion plugin → Run workflow**
and select **main**. Manual publication also requires successful CI for the current main commit.

For a changed plugin, increase `versionCode` and update `versionName` in
`src/main/resources/plugin.json` before merging. Use the same `SIGNING_KEY`. The workflow
skips superseded commits and serializes publishers so concurrent runs cannot replace the
catalog out of order. Missing or malformed signing material stops publication.

If replacing the index fails, the workflow attempts to restore the previous index and saves
that previous public index as a workflow artifact for recovery. Published package assets are
never overwritten or deleted. A rerun can remove a verified empty `starter` placeholder left
by a failed upload; an incomplete index can be recovered this way only while its release is
still a draft.

### Local signing and publishing

You can also build a signed repository locally. From the WizeStream checkout, after exporting
the SDK, use the paths of the same permanent key pair:

```sh
./gradlew -p ../Dailymotion-WizeStream-Plugin \
  "-PpluginSdkJar=$plugin_sdk_jar" \
  "-PpluginSigningKey=/private/dailymotion/signing-key.pk8" \
  "-PpluginPublicKey=/private/dailymotion/public-key.der" \
  repositoryIndex
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
