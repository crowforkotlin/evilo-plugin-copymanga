# evilo-plugin-copymanga

A CopyManga provider for evilo: a signed Kotlin/Wasmline plugin serving manga
search, metadata, chapter groups, and ordered image pages. It is classified NSFW.

## Install in evilo

Subscribe to this catalog URL, not the repository page:

```text
https://raw.githubusercontent.com/crowforkotlin/evilo-plugin-copymanga/main/catalog.json
```

1. Enable the NSFW display preference in More.
2. Select Manga, open Browse, use the app bar's `+` action, and paste the URL.
3. Open CopyManga, review the version, and approve installation.

Users never enter a key ID, public key, or seed.

## Commands

| Command | Purpose |
| --- | --- |
| `./gradlew :evilo-plugin-copymanga-core:jvmTest` | Test requests, HMAC signing, models, and parsers. |
| `./gradlew :evilo-plugin-copymanga:assemble` | Compile the WASI guest. |
| `./gradlew -p evilo-plugin-build-logic :plugin:test` | Test the release build logic. |
| `git tag v1.0.0 && git push origin v1.0.0` | Publish through the release workflow. |

Sign and export a release locally; the version must equal `plugin-spec.json`:

```sh
./gradlew :evilo-plugin-copymanga:exportPluginRelease \
  -Pevilo.signingKeyDirectory="$KEY_DIR" \
  -Pplugin.sourceRevision="$COMMIT" \
  -Pplugin.sourceUrl="$REPO_URL" \
  -Pplugin.releaseBaseUrl="$RELEASE_BASE_URL" \
  -Pplugin.publishedAt="$YYYY-MM-DD" \
  -Pwasmline.autoDownload=true
```

## Requirements

- Repository secret `EVILO_PRIVATE_KEY_HEX`: the preserved Ed25519 seed as 64 hex
  characters. Never commit or print it.
- `evilo-plugin-copymanga/plugin-spec.json` is the only hand-edited release file.
- The SDK is `io.github.crowforkotlin.evilo:evilo-library`, pinned in
  `gradle/libs.versions.toml`. `-Pevilo.useLocalSdk` uses an unpublished local build.

Provider behavior and request details: `evilo-plugin-copymanga/README.md`.
Step-by-step publishing: `docs/publishing.md`.
