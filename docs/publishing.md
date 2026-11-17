# Publishing a signed release

Operator runbook for this repository. `.github/workflows/plugin-release.yml`
signs and publishes on GitHub-hosted `ubuntu-24.04`; this file is the supported
path through it. See [.github/README.md](../.github/README.md) for the job
contract and helper scripts.

Unlike the template, this repository also ships `identity-check.yml` and
`verify_app_key.py`, and the release job refuses to export when the Secret does
not derive the public key the app trusts. `EVILO_PUBLIC_KEY_HEX` is therefore
required here.

## 1. Keys

Both settings are per-repository. A key configured on another repository is not
visible here.

| Setting | Kind | Required | Purpose |
| --- | --- | --- | --- |
| `EVILO_PRIVATE_KEY_HEX` | Secret | yes | Preserved official Ed25519 seed, 64 lowercase hex characters. Signs the manifest. |
| `EVILO_PUBLIC_KEY_HEX` | Variable | yes | Public key the app embeds. Read through `vars.`, only compared, never used to sign. |
| `EVILO_KEY_ID` | Variable | no | Defaults to the official `evilo-root-v1`. |
| `PLUGIN_MODULE` | Variable | no | Only when more than one `*/plugin.json` exists. |

Current values:

```sh
gh variable list --repo crowforkotlin/evilo-plugin-copymanga
```

`EVILO_PUBLIC_KEY_HEX` is read as `vars.EVILO_PUBLIC_KEY_HEX`; storing it as a
Secret instead hides it from the workflow entirely. Either move it to a Secret
and change the two workflow references, or leave it as a Variable.

Hiding it is cosmetic anyway: the published catalog carries
`availableVersions[].signing.publicKeyHex` in clear text because the app must
read it to verify a signature.

```sh
curl -sfL https://raw.githubusercontent.com/crowforkotlin/evilo-plugin-copymanga/main/plugins.json \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['plugins'][0]['availableVersions'][0]['signing'])"
```

### Creating the key pair

This repository reuses the official pair; the app and the catalog already carry
its public half. Generate a new pair only for a real rotation:

```sh
mkdir -p ~/.evilo/signing && chmod 700 ~/.evilo/signing
openssl genpkey -algorithm ED25519 -out ~/.evilo/signing/private.pem
openssl pkey -in ~/.evilo/signing/private.pem -outform DER | tail -c 32 \
  | od -An -tx1 -v | tr -d ' \n' > ~/.evilo/signing/private.key
openssl pkey -in ~/.evilo/signing/private.pem -pubout -outform DER | tail -c 32 \
  | od -An -tx1 -v | tr -d ' \n' > ~/.evilo/signing/public.key
printf 'keyId=evilo-root-v1\npublicKeyHex=%s\n' "$(cat ~/.evilo/signing/public.key)" \
  > ~/.evilo/signing/authority.properties
rm ~/.evilo/signing/private.pem
```

`checkPluginAuthority` requires all three files in one directory outside the
checkout, with the metadata matching the key.

Upload and confirm:

```sh
gh secret set EVILO_PRIVATE_KEY_HEX --repo crowforkotlin/evilo-plugin-copymanga \
  < ~/.evilo/signing/private.key
gh variable set EVILO_PUBLIC_KEY_HEX --repo crowforkotlin/evilo-plugin-copymanga \
  --body "$(cat ~/.evilo/signing/public.key)"

gh workflow run identity-check.yml --repo crowforkotlin/evilo-plugin-copymanga --ref main
gh run list --repo crowforkotlin/evilo-plugin-copymanga -w identity-check.yml -L 1
```

`identity-check.yml` only compares the Secret with the app key; it builds and
signs nothing.

## 2. Choosing the next version

Edit `evilo-plugin-copymanga/plugin.json` only. `descriptor.version` must be a new
semver, `descriptor.versionCode` a strictly larger integer than every published
entry, and both must match the source you are shipping. Changed code,
permissions, hosts, or headers always need a new version: never re-tag, never
re-export, never reuse a version to change its bytes.

```sh
python3 - <<'PY'
import json
plugin, = json.load(open('plugins.json'))['plugins']
print('published:', [(v['version'], v['versionCode']) for v in plugin['availableVersions']])
spec = json.load(open('evilo-plugin-copymanga/plugin.json'))['descriptor']
print('building :', spec['version'], spec['versionCode'])
assert all(int(v['versionCode']) < spec['versionCode'] for v in plugin['availableVersions'])
PY
```

## 3. Verify locally

```sh
./gradlew :evilo-plugin-copymanga-core:jvmTest -Pevilo.sdkPath=../evilo
./gradlew :evilo-plugin-copymanga-core:wasmWasiNodeTest -Pevilo.sdkPath=../evilo
./gradlew -p evilo-plugin-build-logic :plugin:test

VERSION=1.0.1            # must equal descriptor.version
./gradlew :evilo-plugin-copymanga:exportPluginRelease \
  -Pevilo.signingKeyDirectory="$HOME/.evilo/signing" \
  -Pevilo.sdkPath=../evilo \
  -Pplugin.sourceRevision="$(git rev-parse HEAD)" \
  -Pplugin.releaseBaseUrl="https://raw.githubusercontent.com/crowforkotlin/evilo-plugin-copymanga/package-v$VERSION/releases/$VERSION" \
  -Pplugin.sourceUrl="https://github.com/crowforkotlin/evilo-plugin-copymanga" \
  -Pplugin.publishedAt="$(date -u +%Y-%m-%d)" \
  -Pwasmline.autoDownload=true
```

A successful export proves the descriptor, signing identity, source ZIP, and
catalog export. Delete `evilo-plugin-copymanga/build/release/` afterwards so a
local export cannot be mistaken for published bytes.

## 4. Commit, tag, push

The workflow checks out the tag with `fetch-depth: 0`, requires a clean tree, and
stops unless the tag resolves to exactly that commit.

```sh
git switch main
git add -A
git commit -m "feat(copymanga): describe the change"
git push origin main
git tag v1.0.1          # must equal descriptor.version
git push origin v1.0.1  # triggers plugin-release.yml
```

## 5. What the workflow does

1. `package` (read-only): resolves module, version, plugin ID, and revision from
   the tagged `plugin.json`, restores the Secret into `$RUNNER_TEMP`, compares it
   with `EVILO_PUBLIC_KEY_HEX`, runs `:exportPluginRelease`, copies only public
   files, deletes the key material.
2. `publish` (write): refuses if `package-vX.Y.Z` exists, switches to the default
   branch, copies the exported files to `releases/X.Y.Z/`, appends the release to
   `plugins.json` while older entries keep their effective
   permissions/protocol/kinds/languages, commits
   `release(plugins): publish signed version X.Y.Z`, tags `package-vX.Y.Z`, and
   pushes branch and tag atomically.
3. Release assets: `restore_release.py` creates a draft Release, uploads the
   package ZIP, exact source ZIP, `manifest.wlm`, `plugins.json`, and `icon.png`,
   verifies sizes and SHA-256 digests, then publishes it.

The catalog push happens before asset upload, so an upload failure leaves the
catalog valid and is repaired with `republish=true`, never with a new version.

## 6. Verify the publication

```sh
gh run list --repo crowforkotlin/evilo-plugin-copymanga -w plugin-release.yml -L 3
gh release view v1.0.1 --repo crowforkotlin/evilo-plugin-copymanga

curl -fsSL https://raw.githubusercontent.com/crowforkotlin/evilo-plugin-copymanga/main/plugins.json \
  | python3 -c "import json,sys; p=json.load(sys.stdin)['plugins'][0]; \
print([(v['version'], v['versionCode'], v.get('manifestSha256')) for v in p['availableVersions']])"

curl -fsSL https://raw.githubusercontent.com/crowforkotlin/evilo-plugin-copymanga/package-v1.0.1/releases/1.0.1/manifest.wlm \
  | sha256sum   # must equal that release's manifestSha256
```

Confirm older versions are still listed and that `git rev-parse v1.0.1` is the
commit you intended. Never delete or move a tag.

## 7. Recover assets without rebuilding

```sh
gh workflow run plugin-release.yml --repo crowforkotlin/evilo-plugin-copymanga \
  --ref main -f source_tag=v1.0.1 -f republish=true
```

`republish` skips signing and packaging, reads no Secret, restores assets from
immutable `package-vX.Y.Z` files (falling back to `vX.Y.Z` for legacy packages),
uploads only missing assets, rejects different existing bytes, and leaves the
catalog untouched.

For the historical `1.0.0`, restore the preserved tag only if it is missing
remotely, then dispatch from `main`:

```sh
git push origin refs/tags/v1.0.0
gh workflow run plugin-release.yml --repo crowforkotlin/evilo-plugin-copymanga \
  --ref main -f source_tag=v1.0.0 -f republish=true
```

## Rotating the key

While the app is in development, rotation is cheap: rebuild the APK and publish
a new plugin release. Once users have builds, it is a breaking app release.

1. Generate a new pair as above into a fresh directory; keep the old one.
2. Put the new public key in the app build (evilo's `docs/plugin-key-setup.md`)
   and rebuild the APK. The app embeds only the public key.
3. Set the same value in `EVILO_PUBLIC_KEY_HEX` here and replace
   `EVILO_PRIVATE_KEY_HEX` with the new seed.
4. Run `identity-check.yml` until it passes.
5. Publish under a **new version and a larger `versionCode`** — the app pins the
   approved manifest digest per version, so reusing a version for a differently
   signed manifest is rejected.
6. Retain the old seed until no supported app build trusts it. Old-key releases
   stop verifying in the new app; new-key releases fail in the old app.

## Failure modes

| Symptom | Cause |
| --- | --- |
| `Set PLUGIN_MODULE to the guest module name.` | More than one `*/plugin.json`; set the variable. |
| `Source tag must match descriptor.version` | Tag and `descriptor.version` disagree. |
| `Commit all released source before packaging.` | Dirty checkout at the tagged commit. |
| `Never reuse published versions; versionCode must increase.` | Version or `versionCode` not advanced. |
| `Package tag already exists; never overwrite it.` | That version is already published; bump instead. |
| `Package does not match its source tag.` | Tag points at a different commit than the export. |
| `Configure repository variable EVILO_PUBLIC_KEY_HEX …` | Variable absent, or stored as a Secret that the workflow does not read. |
| `The Secret derives a different public key than the application trusts.` | Secret and app key disagree; do not tag. |
| `Official packages must use key ID evilo-root-v1` | `EVILO_KEY_ID` overridden away from the official label. |
| `Missing publisher key` / `Invalid authority key format` | Secret absent, malformed, or restored inside the checkout. |
| `Could not resolve io.github.crowforkotlin.evilo:evilo-library` | The pinned SDK version is not on Maven Central yet; see evilo's `docs/library-publishing.md`. |
| `Existing asset X differs; never overwrite it.` | Committed bytes changed after publication. |

A workflow file is configuration, not evidence that a run signed or published
anything.
