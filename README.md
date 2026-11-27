# evilo-plugin-copymanga

evilo plugin for CopyManga (NSFW).

## Commands

Build and test with JDK 21:

```sh
./gradlew :evilo-plugin-copymanga-core:jvmTest
./gradlew :evilo-plugin-copymanga:assemble
```

## Configure

- `evilo-plugin-copymanga/plugin-spec.json`: metadata, version, filters.
- GitHub secret `EVILO_PRIVATE_KEY_HEX`: publisher Ed25519 seed (64 hex characters).
- GitHub variable `EVILO_PUBLIC_KEY_HEX`: the app's public key derived from that seed.
- Publish: raise `descriptor.version` and `versionCode`, push tag `v<version>`. See [docs/publishing.md](docs/publishing.md).

Catalog: https://raw.githubusercontent.com/crowforkotlin/evilo-plugin-copymanga/main/catalog.json
