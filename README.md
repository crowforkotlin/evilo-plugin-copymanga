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
3. Tap the uninstalled CopyManga row, review an available version, its source and
   network permissions, and approve installation. Tap the installed row to open it.

No individual manifest URL, key ID, or Ed25519 key is entered by the user.
Official CopyManga releases use the publisher's preserved Ed25519 seed from this
repository's `EVILO_PRIVATE_KEY_HEX` Secret, which is already configured. The app
receives the matching official public key through `EVILO_PUBLIC_KEY_HEX`.
This independently hosted subscription does not require a PR to evilo's bundled
source. Third-party publishers use their own keys; their catalog declarations
require approval of the specific version, public-key fingerprint, and permissions.
Wasmline signature verification remains mandatory, with the approved manifest
digest pinned. A different publisher cannot replace an installed plugin by
reusing its ID or access its publisher-scoped private key-value storage.

The next CopyManga release version is `1.0.1`, sourced from tag `v1.0.1`. Workflow
configuration does not establish that it has been built or published. New releases
use immutable `package-vX.Y.Z` tags for raw release files, while the root catalog
remains the stable subscription address.

Historical `1.0.0` download URLs remain pinned to tag `v1.0.0` under
`releases/1.0.0/`. Its source snapshot is commit
`b600122756e24b3bc7169f9c530c67b53c45771b`, which contains the actual implementation.
If the GitHub Release is deleted, restore its assets from the original tag
without rebuilding or signing. If that tag was also deleted remotely, push the
preserved local tag rather than creating one at the current source commit.

The root `plugins.json` now uses the compact catalog format and requires an app
with evilo's new `CatalogParser` normalization. Kinds, languages, protocol, and
permissions are declared once per plugin. Release `1.0.0` keeps its original
version/code, publication time, source snapshot/commit/hash, and download URLs.
The tag-pinned `releases/1.0.0/plugins.json` remains in the previous full format
for older clients. Existing signed packages, artifacts, and source ZIPs are not
rewritten by this catalog-format update.

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

## Maintain the plugin source

`evilo-plugin-copymanga/plugin.json` is the editable build specification, not the
subscription URL. Its `descriptor` supplies signed identity and permissions;
`catalog` supplies authors and introduction, plus optional region/NSFW, creation
date, and update policy. Authors may be strings or name/URL objects. Creation
and publication dates accept `YYYY-MM-DD` or full timestamps.

Release export writes a compact `plugins.json` with `name` and `plugins` at the
root, common descriptor fields once per plugin, and immutable release metadata
inside `availableVersions`. No review metadata or separate JSON Schema is emitted.
The root catalog's source snapshot override is retained for the existing release.
For a new export, `plugin.sourceSnapshotUrl` is optional; without it a release
inherits the source repository URL while its actual commit/archive/hash remain
required.

For a new release, append its entry to the stable root catalog. Preserve older
release metadata and use explicit overrides if permissions, protocol, languages,
or kinds change. Do not overwrite tag-pinned files or re-export `1.0.0` merely to
change the catalog layout. Catalog changes do not publish a new plugin binary.

## Signing and publication

Each publisher owns its signing key. Official CopyManga uses this repository's
already configured `EVILO_PRIVATE_KEY_HEX` Secret. The workflow reuses that
preserved Ed25519 seed and derives its public key; it never generates a fresh
release key. Its default signing key ID is the existing official `evilo-root-v1`;
the optional repository variable `EVILO_KEY_ID` overrides that label. The app's
`EVILO_PUBLIC_KEY_HEX` must contain the matching official public key.

The repository variable `EVILO_PUBLIC_KEY_HEX` holds that same application key.
The signing job compares it with the key derived from the Secret and stops before
exporting when they differ, so a rotated or replaced Secret cannot silently
publish packages that fail manifest verification at install time. `Check signing
identity` performs only that comparison.

Independent publishers generate their own private seed once locally, back it up
privately, and configure their own repository Secret. The template defaults to
`publisher-<fingerprint>` using a SHA-256 public-key fingerprint. Never request or
distribute the official private key, commit private material, include it in a
source/package ZIP, or print it in logs.

### GitHub Actions

`.github/workflows/plugin-release.yml` is configured for `v*` tags and manual
dispatch naming an existing tag on GitHub-hosted `ubuntu-24.04`. The version comes
from `vX.Y.Z`; the next release uses `v1.0.1`, not a rewritten `v1.0.0`.

`EVILO_PRIVATE_KEY_HEX` is the only required Secret; the repository variable
`EVILO_PUBLIC_KEY_HEX` is required too, because export is refused without it.
`PLUGIN_MODULE` is
autodiscovered from the guest `plugin.json`; use an explicit selection only if
needed. Optional repository variables `EVILO_SDK_REPOSITORY` and
`EVILO_SDK_REVISION` select an unpublished SDK at a full immutable commit. That
SDK checkout stays outside the plugin source archive; otherwise use the
published dependency.

The read-only signing job restores the Secret to an external temporary signing
directory, derives the public key/key ID, exports the release, and cleans up
restored private material. The separate write-permission publish job receives
only public files. It creates immutable `package-vX.Y.Z` raw release files,
appends historical effective metadata to `main/plugins.json`, and creates a
GitHub Release with a package ZIP and the exact source ZIP. Public package files
include the signed manifest, referenced artifacts, icon, and versioned catalog.
Never overwrite old package files, source hashes, or historical download URLs.

A workflow file is configuration, not evidence that signing, publication, or
tests have succeeded. See [.github/README.md](.github/README.md) for job boundaries
and repository configuration. Existing manual export remains supported.

### Restore a deleted GitHub Release

The manual `republish` option reads the committed public package files from
`package-vX.Y.Z`, or the original `vX.Y.Z` for legacy releases. It does not build,
sign, request the private-key Secret, change the catalog, or replace tags.

For historical `1.0.0`, restore the preserved tag only if it is missing remotely,
then dispatch the workflow from `main` so it uses the current recovery script:

```sh
git push origin refs/tags/v1.0.0
gh workflow run plugin-release.yml --repo crowforkotlin/evilo-plugin-copymanga \
  --ref main -f source_tag=v1.0.0 -f republish=true
```

The workflow creates a draft Release, uploads the package ZIP, exact source ZIP,
manifest, versioned catalog, and icon, then publishes it after checking all asset
digests. Retrying adds only missing assets and refuses different existing bytes.
Historical raw download URLs and catalog publication dates remain unchanged.

### Manual release export

Keep the preserved publisher key in an external signing directory for manual
export. Commit the actual released source before recording its full source revision.
The initial license-only commit does not contain this implementation and must
not be used as its release source. From this repository, with real directly
served HTTPS source/release URLs:

```sh
./gradlew :evilo-plugin-copymanga:exportPluginRelease \
  -Pevilo.signingKeyDirectory="$PUBLISHER_SIGNING_DIRECTORY" \
  -Pevilo.sdkPath=../evilo \
  -Pplugin.sourceRevision="$SOURCE_COMMIT" \
  -Pplugin.releaseBaseUrl="$RELEASE_BASE_URL" \
  -Pplugin.sourceUrl="https://github.com/crowforkotlin/evilo-plugin-copymanga" \
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

`PUBLISHED_AT` is explicit and may be an ordinary date or full timestamp. Add
`-Pplugin.sourceSnapshotUrl="$SOURCE_SNAPSHOT_URL"` when the new release needs
its own immutable source link. An omitted creation date is derived by the app;
an explicit one must not be after publication.

Preserve the existing `1.0.0` package and source metadata; do not re-export it
under a new signing identity or overwrite its tag-pinned files. Its signature
predates the current official key, so its catalog entry keeps `usable: false`
and users must upgrade to `1.0.1`. Restore a deleted
Release from those immutable public files rather than deleting old package URLs.
Use version `1.0.1` for the next release. Do not publish ignored preview artifacts
or use preview signing keys. See the template README for external signing paths,
SDK/toolchain requirements, and publication rules.

New-version builds and recovery of existing Release assets are separate
operations. Configured workflow source is not a successful-run record.

## Loading and limitations

Add the published JSON URL in evilo's Browse page. This provider is conservatively
marked NSFW and hidden until that preference is enabled in More. The host checks
signed identity, provenance, compatibility, and the mandatory Wasmline signature.
For third-party catalog keys, approval covers the version, public-key fingerprint,
and permissions, and pins the manifest digest. A catalog-declared key is not
automatically trusted. Different publishers cannot replace the same installed
plugin ID; private key-value storage is scoped by publisher identity and ID.

This documentation update does not establish loading in an installed app or
current live-provider availability. Browser CORS/mixed-content
restrictions and the CDN allowlist apply. Login, library/history, saved progress,
and downloads are not implemented. A signature is not a safety guarantee.

Sharing the template structure does not change this independently created
repository's GitHub fork relationship or publish signed plugin releases.

## Manual parser tests

These tests use local fixtures and an injected clock, not a live provider:

```sh
./gradlew :evilo-plugin-copymanga-core:jvmTest -Pevilo.sdkPath=../evilo
./gradlew :evilo-plugin-copymanga-core:wasmWasiNodeTest -Pevilo.sdkPath=../evilo
```

Tests and plugin builds need a separate explicit execution request.

For the compact catalog exporter, from this repository:

```sh
./gradlew -p evilo-plugin-build-logic :plugin:test
```

These build-logic tests use local metadata fixtures, not signing keys or live
provider requests. They have not been run for this adaptation.
