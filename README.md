# evilo-plugin-copymanga

An independent provider repository using the evilo plugin template's structure.
Remote: `https://github.com/crowforkotlin/evilo-plugin-copymanga`.
CopyManga source is not part of evilo or the provider-neutral template.

## Install in evilo

Subscribe to this direct JSON catalog URL, not the GitHub repository homepage:

```text
https://raw.githubusercontent.com/crowforkotlin/evilo-plugin-copymanga/main/plugins.json
```

1. In More, enable the NSFW display preference; this provider is conservatively
   classified as NSFW and is otherwise hidden.
2. Select Manga, open Browse, and use the App Bar's `+` action to open Plugin
   Sources. Enter the JSON URL and tap Subscribe.
3. Open CopyManga, select version `1.0.0`, review its source and network
   permissions, and approve installation. Use Open after installation.

No individual manifest URL, key ID, or Ed25519 key is entered by the user.
The package uses the app's preserved authority; an APK built with a different
authority cannot install it. This is a custom subscription, not an officially
certified entry in evilo's bundled catalog.

The versioned catalog and all download files are pinned to tag `v1.0.0` under
`releases/1.0.0/`. The root catalog is the stable subscription address for future
updates. The source snapshot is commit
`b600122756e24b3bc7169f9c530c67b53c45771b`, which contains the actual implementation.

## Modules

```text
evilo-plugin-copymanga-core/        Requests, HMAC, models and response parsers
evilo-plugin-copymanga/             WASI entrypoint, descriptor and icons
evilo-plugin-build-logic/
  base/                            JVM/WASI library convention
  plugin/                          Signed package and source/catalog export
gradle/                            Pinned dependencies and wrapper
```

Root: `evilo-plugin-copymanga-root`. The guest passes its generated release ID and
version to the portable core; the core does not depend on generated guest fields.
Both consume `io.github.crowforkotlin.evilo:evilo-library:1.0.0`. Before SDK
publication, use `-Pevilo.sdkPath=../evilo`. Protocol source is never copied.

The generic guest wraps the SDK's `MangaPluginAdapter`; its internal parser uses
manga API 2. See `evilo-plugin-copymanga/README.md` for API paths, headers,
pagination, image ordering, and limitations.

## Signing and publication

Only the operator of the app's preserved Ed25519 authority signs packages.
Private keys stay outside repositories and are not given to independent authors.
This build reuses the app authority and cannot generate another one. Users never
fill in Ed25519 fields. The task from evilo is
`:evilo-app:generatePluginSigningKeys`; preserve that authority across updates.

Commit the actual released source before recording its full source revision.
The initial license-only commit does not contain this implementation and must
not be used as its release source. From this repository, with real directly
served HTTPS source/release URLs:

```sh
./gradlew :evilo-plugin-copymanga:exportPluginRelease \
  -Pevilo.sdkPath=../evilo \
  -Pplugin.sourceRevision="$SOURCE_COMMIT" \
  -Pplugin.releaseBaseUrl="$RELEASE_BASE_URL" \
  -Pplugin.sourceUrl="https://github.com/crowforkotlin/evilo-plugin-copymanga" \
  -Pplugin.sourceSnapshotUrl="$SOURCE_SNAPSHOT_URL" \
  -Pplugin.publishedAt="$PUBLISHED_AT" \
  -Pwasmline.autoDownload=true
```

Export writes a version-specific directory under
`evilo-plugin-copymanga/build/release/` with the signed manifest/artifacts, exact
source ZIP/hash, PNG, and `plugins.json`. Publish the manifest, every referenced
artifact, source ZIP, icon, and catalog. Debug build records are not download
requirements and are excluded from publication because they contain local paths.
The source ZIP includes actual code/build logic, not only binaries. The operator
reviews source versus the linked commit; source hashes are not reproducible-build
attestations.

Version `1.0.0` was exported with the preserved application authority. Its
Ed25519 manifest signature, all three signed artifact hashes, and source ZIP were
checked before publication. All 30 archived source files match the linked source
commit. The provider PNG is exported from this repository's editable SVG, not the
template's Android placeholder. Do not publish ignored old preview artifacts or
use preview signing keys. See the template README for external key paths,
SDK/toolchain requirements, and publication rules.

The packaging task reported `WLAOT002`: the upstream online AOT catalog query was
unavailable because remote catalog validation failed. The local catalog and
selected AOT generation passed the task's checks; the package includes Pulley
32/64 artifacts for Wasmtime `50.0.1` and raw Wasm. This does not establish
successful loading on a phone or current live-provider availability.

## Loading and limitations

Add the published JSON URL in evilo's Browse page. This provider is conservatively
marked NSFW and hidden until that preference is enabled in More. The host checks
signed identity, provenance, compatibility, and signature, then reviews network
permissions with the user. Custom catalogs cannot add keys or self-certify.

The guest package was built and its signature checked. Loading in an installed
app and live provider availability have not been tested. Browser CORS/mixed-content
restrictions and the CDN allowlist apply. Login, library/history, saved progress,
and downloads are not implemented. A signature or official review is not a safety
guarantee.

Sharing the template structure does not change this independently created
repository's GitHub fork relationship or publish signed plugin releases.

## Manual parser tests

These tests use local fixtures and an injected clock, not a live provider:

```sh
./gradlew :evilo-plugin-copymanga-core:jvmTest -Pevilo.sdkPath=../evilo
./gradlew :evilo-plugin-copymanga-core:wasmWasiNodeTest -Pevilo.sdkPath=../evilo
```

Tests and plugin builds need a separate explicit execution request.
