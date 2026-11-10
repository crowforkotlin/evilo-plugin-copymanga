# evilo-plugin-copymanga

An independent provider repository using the evilo plugin template's structure.
Remote: `https://github.com/crowforkotlin/evilo-plugin-copymanga`.
CopyManga source is not part of evilo or the provider-neutral template.

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

This is a manual instruction, not a recorded packaging result. Export writes a
version-specific directory under `evilo-plugin-copymanga/build/release/` with the
signed manifest/artifacts, exact source ZIP/hash, PNG, and `plugins.json`.
Upload every exported artifact. The source ZIP includes actual code/build logic,
not only binaries. The operator reviews source versus the linked commit;
source hashes are not reproducible-build attestations.

Replace placeholder icons before publication. Do not publish ignored old preview
artifacts or use preview signing keys. See the template README for external key
paths, SDK/toolchain requirements, and publication rules.

## Loading and limitations

Add the published JSON URL in evilo's Browse page. This provider is conservatively
marked NSFW and hidden until that preference is enabled in More. The host checks
signed identity, provenance, compatibility, and signature, then reviews network
permissions with the user. Custom catalogs cannot add keys or self-certify.

Live provider availability, guest packaging, and signed loading are not proven
by source edits. Browser CORS/mixed-content restrictions and the CDN allowlist
apply. Login, library/history, saved progress, and downloads are not implemented.
A signature or official review is not a safety guarantee.

Sharing the template structure does not change this independently created
repository's GitHub fork relationship or publish signed plugin releases.

## Manual parser tests

These tests use local fixtures and an injected clock, not a live provider:

```sh
./gradlew :evilo-plugin-copymanga-core:jvmTest -Pevilo.sdkPath=../evilo
./gradlew :evilo-plugin-copymanga-core:wasmWasiNodeTest -Pevilo.sdkPath=../evilo
```

Tests and plugin builds need a separate explicit execution request.
