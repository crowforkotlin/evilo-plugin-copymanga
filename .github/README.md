# GitHub Actions releases

`.github/workflows/plugin-release.yml` configures signing and publication on
GitHub-hosted `ubuntu-24.04`. It runs for `v*` tags or manual dispatch naming an
existing source tag. `vX.Y.Z` supplies the version; `package-vX.Y.Z` identifies the
immutable exported release files. A configured workflow is not evidence that a
run, build, signature check, or publication has succeeded.

## Repository configuration

The only required repository Secret is `EVILO_PRIVATE_KEY_HEX`: official
CopyManga's preserved Ed25519 private seed in hexadecimal. It is already
configured in this repository. The workflow reuses that seed and derives its
public key; it never generates a fresh release key. Back up the preserved key
privately, never commit it, include it in archives, or print it in logs.

Optional repository variables:

- `EVILO_KEY_ID`: override this workflow's existing official key ID default,
  `evilo-root-v1`. A key ID is a label, not proof of trust.
- `PLUGIN_MODULE`: select the guest module explicitly if necessary; by default
  it is autodiscovered from the guest `plugin.json`.
- `EVILO_SDK_REPOSITORY` and `EVILO_SDK_REVISION`: set both to use an unpublished
  SDK checkout pinned to a full immutable commit. This checkout is outside the
  plugin source archive. Without the override, use the published SDK dependency.

The app receives the matching official public key through `EVILO_PUBLIC_KEY_HEX`,
not the private seed. Independent publishers generate their own seed **once
locally**, back it up privately, and configure their own repository Secret.
The template defaults to `publisher-<fingerprint>`, derived from SHA-256 of the
public key bytes, with an optional `EVILO_KEY_ID` override. Independent authors
do not request or receive the official private key or reuse its signing identity.

## Signing and public publication jobs

The signing job has read-only repository permissions. It restores the preserved
Secret into an external temporary signing directory, derives the public key and
key ID from that seed, exports the release, and cleans up restored private
material. No fresh key is generated. The files handed to the separate publish
job are public release files only, never the Secret or private signing directory.

The publish job has repository write permissions and:

1. Creates immutable `package-vX.Y.Z` containing raw release files: the signed
   `manifest.wlm`, every referenced artifact, exact source ZIP/hash, icon, and
   versioned catalog.
2. Appends the release to `main`'s stable root `plugins.json`, preserving older
   releases' effective metadata. If common permissions, protocol, kinds,
   languages, or source URL change, older entries get explicit overrides.
3. Creates a GitHub Release with a package ZIP and the exact source ZIP. The
   package includes public manifest, icon, and catalog files, not private keys
   or the optional external SDK checkout.

Never overwrite old package files, reuse a version for different source, or
replace an old source hash. Keep `main/plugins.json` as the subscription address;
GitHub Release pages and ZIP URLs are not subscription catalogs.

Removing a GitHub Release does not require deleting its source tag or immutable
package files. Retain tag `v1.0.0`, existing package/download URLs, and historical
catalog metadata. The next new CopyManga version is `1.0.1`, sourced from `v1.0.1`;
restoring the historical `1.0.0` Release does not publish that new source.

## Recover a deleted or incomplete GitHub Release

Dispatch the current workflow from `main` with `source_tag=v1.0.0` and
`republish=true` to restore the historical Release. If the source tag is missing
remotely, first push the preserved local `v1.0.0`; never recreate it at a new
commit. The `republish` job skips the signing and package jobs and does not read
the private-key Secret. It reads public files from `package-vX.Y.Z`, falling back
to `vX.Y.Z` for legacy packages, without changing tags or `main/plugins.json`.

```sh
gh workflow run plugin-release.yml --repo crowforkotlin/evilo-plugin-copymanga \
  --ref main -f source_tag=v1.0.0 -f republish=true
```

Recovery creates a draft Release, uploads the package ZIP, exact source ZIP,
manifest, versioned catalog, and icon, checks uploaded sizes and SHA-256 digests,
then publishes the draft. A retry uploads only missing assets and refuses to
overwrite different bytes. Run this mode after an asset-upload failure or
Release deletion; do not rebuild an already published version. Legacy catalogs
without a manifest digest retain their original bytes and signing metadata.

## Helper scripts

- `scripts/release_identity.py` reads the sole guest `plugin.json` (or
  `PLUGIN_MODULE`), requires a clean checkout at `SOURCE_TAG=v<version>`, and
  writes metadata to `GITHUB_OUTPUT`. URLs derive from `GITHUB_REPOSITORY`.
- `scripts/publisher_key.py DIRECTORY` restores the preserved Secret and derives public metadata outside the checkout. It does not generate keys.
- `scripts/export_bundle.py SOURCE OUTPUT` copies only public files and checks manifest, source-archive, and artifact hashes.
- `scripts/publish_release.py BUNDLE` publishes immutable package files and appends the stable catalog, then delegates Release assets to `restore_release.py`. After the atomic Git push, retry only with `republish=true`.
- `scripts/restore_release.py TAG` restores Release assets from immutable Git blobs without building, signing, executing archived scripts, or changing the catalog. Existing different asset bytes are rejected.
- `scripts/merge_catalog.py OLD NEW OUTPUT` appends an exported release to an
  existing catalog. It refuses version reuse and non-increasing version codes.
  Older effective metadata is preserved when common catalog fields change.

Catalog merging does not build or sign a plugin. Manual `exportPluginRelease`
remains supported; see the root README for external signing paths and explicit
source revision, release URL, and publication-date inputs.

## Installation trust

The app can read a third-party publisher's public key declared by a catalog,
but subscribing does not trust it automatically. Approval applies to the exact
version, public-key fingerprint, and permissions. Wasmline signature verification
is mandatory and the approved manifest digest is pinned. Different publishers
cannot replace an installed plugin by reusing its ID, and private key-value
storage is scoped by publisher identity and plugin ID. A valid signature does
not prove code safety. Users never enter private seeds or raw public-key fields.

The workflow and helper descriptions are configuration documentation, not
execution records. New-version builds and public-asset recovery are separate
operations.
