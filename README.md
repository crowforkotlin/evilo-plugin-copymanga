# evilo-plugin-copymanga

An evilo plugin repository for CopyManga, built on the structure of
`evilo-plugin-template`. It publishes a signed Kotlin/Wasm provider that serves
manga search, metadata, chapter groups, and ordered image pages.

## Install in evilo

Subscribe to this catalog URL, not the repository homepage:

```text
https://raw.githubusercontent.com/crowforkotlin/evilo-plugin-copymanga/main/catalog.json
```

1. Enable the NSFW display preference in More; this provider is classified NSFW
   and is hidden otherwise.
2. Select Manga, open Browse, use the App Bar's `+` action, and subscribe to the
   URL above.
3. Tap the CopyManga row, review the available version, and approve installation.

Users never enter a key ID, public key, or seed. The catalog URL is the only
address needed.

## Layout

| Path | Contents |
| --- | --- |
| `evilo-plugin-copymanga/` | `plugin-spec.json`, WASI entrypoint, icons |
| `evilo-plugin-copymanga-core/` | Requests, HMAC signing, models, parsers |
| `evilo-plugin-build-logic/base/` | JVM/WASI library convention |
| `evilo-plugin-build-logic/plugin/` | Signed package and source/catalog export |
| `gradle/` | Version catalog and wrapper |

The guest passes its generated descriptor to the portable core; the core does not
depend on generated guest fields. `CopyMangaParser` maps the provider onto the
generic media models; `CopyMangaSource` keeps manga API 2 compatibility for older
callers. See `evilo-plugin-copymanga/README.md` for pagination, mirror retries,
and image ordering.

## Provider facts

`CopyMangaConfig` validates both base URLs against a reviewed allowlist, so a
misconfigured value cannot point this provider at an unreviewed origin. Mirror
selection is internal: list requests use the list hosts, while details, chapters,
and images use the `mapi.*` mirror family. HTTP and empty-payload failures are
retried across the reviewed mirrors.

Requests are HMAC-signed with the provider's published header scheme and a fixed
`User-Agent`. Login, library/history, reading progress, and downloads are not
implemented.

## SDK

Modules consume `io.github.crowforkotlin.evilo:evilo-library`, pinned as
`evilo-plugin-sdk` in `gradle/libs.versions.toml`. `-Pevilo.useLocalSdk`
substitutes an unpublished local build during development.

## plugin-spec.json

`descriptor` holds the signed identity: version, version code, kinds, and
languages. `catalog` holds authors, the introduction, region, NSFW, and creation
date. There is no permission block; installing the plugin is the authorization
decision, and the host forwards the requests the provider constructs.

## Signing

This repository signs with the official evilo publisher seed held in the
`EVILO_PRIVATE_KEY_HEX` GitHub Secret. `EVILO_PUBLIC_KEY_HEX` must hold the
matching public key; the identity workflow compares the derived key against it
and stops before export when they differ. `EVILO_KEY_ID` overrides the default
key label.

Signature verification at install is mandatory, and the approved manifest digest
is pinned per release. A catalog-declared key is not trusted automatically, and a
different publisher cannot replace an installed plugin by reusing its ID.

## Releases

`.github/workflows/plugin-release.yml` runs on GitHub-hosted `ubuntu-24.04` for
`v*` tags and for manual dispatch naming an existing tag. `vX.Y.Z` must equal
`descriptor.version`.

The pipeline is two jobs:

1. `package` (read-only) resolves the module, version, plugin ID, and revision
   from the tagged `plugin-spec.json`, restores the Secret into `$RUNNER_TEMP`,
   runs `:exportPluginRelease`, copies only public files, and deletes the key.
2. `publish` (write) creates the immutable `package-vX.Y.Z` tag, copies the
   exported files to `releases/X.Y.Z/`, appends the release to `main`'s
   `catalog.json` while older entries keep their effective kinds and languages,
   and creates a GitHub Release with the package ZIP and the exact source ZIP.

Never overwrite package files, reuse a version for changed source, or replace a
published source hash.

## Manual export

Commit the released source first, then supply its full commit, the source
repository, and directly served HTTPS release URLs:

```sh
./gradlew :evilo-plugin-copymanga:exportPluginRelease \
  -Pevilo.signingKeyDirectory="$PUBLISHER_SIGNING_DIRECTORY" \
  -Pevilo.useLocalSdk \
  -Pplugin.sourceRevision="$SOURCE_COMMIT" \
  -Pplugin.releaseBaseUrl="$RELEASE_BASE_URL" \
  -Pplugin.sourceUrl="$SOURCE_REPOSITORY_URL" \
  -Pplugin.publishedAt="$PUBLISHED_AT" \
  -Pwasmline.autoDownload=true
```

`PUBLISHED_AT` accepts `YYYY-MM-DD` (midnight UTC) or a full ISO-8601 timestamp
and is never read from the clock. `plugin.sourceSnapshotUrl` is optional; omit it
to inherit `plugin.sourceUrl`. Export writes `manifest.wlm`, content-addressed
artifacts, the source ZIP and its hash, `icon.png`, and `catalog.json` under
`evilo-plugin-copymanga/build/release/`.

## Catalog files

| Path | Written by | Role |
| --- | --- | --- |
| `evilo-plugin-copymanga/plugin-spec.json` | the author | The only hand-edited file: `descriptor` and `catalog`. |
| `releases/<version>/catalog.json` | the release workflow | Immutable snapshot frozen into `package-v<version>`. |
| `catalog.json` at the repository root | the release workflow | The subscription catalog listing every published version. |

`exportPluginRelease` computes every SHA-256 and derives artifact URLs; do not
hand-edit either generated file, because `restore_release.py` re-checks the
recorded hashes and refuses different bytes. Keep the root catalog address stable
and retain older release entries.

## Tests

```sh
./gradlew :evilo-plugin-copymanga-core:jvmTest -Pevilo.useLocalSdk
```

The tests use local fixtures and an injected clock, not a live provider.
