# CopyManga plugin

The WASI entrypoint binds `CopyMangaPlugin` to `EviloPluginService`. Portable
core code builds HTTP requests and parses responses; evilo owns asynchronous
network execution and cancellation. `CopyMangaSource` remains the legacy API 2
wrapper. New source metadata is version `1.0.1`, version code `2`, canonical ID
`crow.evilo.source.copymanga`, manga kind, Chinese language. Existing published
`1.0.0` catalog/package files remain unchanged; the source change is not a signed
or published release.

## Chapter failure diagnosis

The current paths are already the ones implemented by the read-only references:

- Kira `lib/api/manga/manga_api.dart`: `getChapterList` requests
  `/api/v3/comic/{path_word}/group/{group}/chapters`; `getChapterDetail` requests
  `/api/v3/comic/{path_word}/chapter/{uuid}`.
- cutmangax-source `lib_mangax/.../MangaXStrings.kt`: `URL.ComicInfo`,
  `URL.ComicChapter`, and `URL.ComicPage` use `/comic2/`, grouped `/chapters`,
  and `/chapter/` respectively. `module_book/.../network/ComicService.kt` uses
  `mCopyDetailHeader`; `app/.../NetworkModule.kt` sends those requests to the
  separate `mapi.*` content family. `BookRepository` bounds chapter limit at 500.
- Kira's `ChapterDetail.fromJson` reads all `chapter.contents` in server order;
  cutmangax-source's `ComicChapterLoader.createChapterPages` handles absent
  `words`. This plugin already preserves server order when `words` is absent.

A parser mismatch was also present: Kira's `Comic.fromDetailJson` falls back
to `comic.groups` when top-level `results.groups` is absent or empty, while this
plugin previously required only top-level groups. Details and chapter discovery
now use that same explicit fallback, retaining a genuinely empty group object
as empty rather than assuming a `default` group.

The request mismatch was in client identity, not an obsolete chapter path.
`CopyMangaSigner` hardcoded `platform=3` and list requests sent `source=copyApp`.
cutmangax-source's actual `MangaXPreferencesConfig.defaultCopyHeader` uses
`platform=1`, `version=3.1.1`, `accept=*/*`, `webp=1`, and no `source`/`dt`.
Its `api-contract.md` records `source=copyApp` causing `code:error`, and platform
3's content cropping/version-dependent `code:210` rejection. Kira's HotManga
transport is a different client identity (`platform=3`, dated version
`2024.04.28`); those values are not the CopyManga app contract.

The correction aligns CopyManga API headers and matching query parameters to
platform 1, sets Accept to `*/*`, and removes `source`. HMAC timestamps remain
fresh per request. Empty/missing `chapter.contents` now counts as a retryable
mirror payload failure, unless the provider marks content locked. No live
response, stack trace or device trace was supplied or captured for the reported
chapter failure; these changes fix evidenced source discrepancies, not a
runtime-verified incident.

## Requests and parsing

| Operation | Request |
| --- | --- |
| Search | `/api/v3/search/comic`, encoded `q`, `q_type=`, limit 30 |
| Empty search | `/api/v3/comics?free_type=1&ordering=-datetime_updated`, limit 50 |
| Details | `/api/v3/comic2/{path_word}?platform=1` on a content mirror |
| Chapters | Fresh detail groups or discovery, then grouped `/chapters?limit=500&offset=…&platform=1` |
| Images | `/api/v3/comic/{path_word}/chapter/{uuid}?platform=1` on a content mirror |

List hosts are `api.copy202601.com` (default), `api.copy202602.com`,
`api.copy-manga.com`, `api.copy2000.online` and `api.mangacopy.com`.
Content hosts are `mapi.hotmangasd.com` (default), `mapi.hotmangasg.com`,
`mapi.hotmangasf.com`, `mapi.elfgjfghkk.club`, `mapi.fgjfghkkcenter.club` and
`mapi.fgjfghkk.club`. Both configured origins must belong to their respective
reviewed set; passing a list host for content is rejected.

API requests include `platform: 1`, `version: 3.1.1`, `Accept: */*`,
`webp: 1` and per-request `X-Auth-Timestamp`, `tapString`, HMAC-SHA256
`X-Auth-Signature`. List requests retain `COPY/3.1.1`, referer and umstring;
content mirrors retain the Android browser UA and `X-Requested-With` used by
the existing implementation. No `source`, `dt`, cookies, blank Authorization,
Host, Origin, forwarding headers or proxy credentials are sent. The published
client HMAC protocol constant is unrelated to the Ed25519 package-signing key.

Numeric/string code, numeric fields, optional nulls and JSON-encoded result
objects/lists are supported. Invalid numbers, mismatched manga/group/chapter
IDs, duplicate chapter IDs, malformed image orders, locked content and provider
rejection fail with their diagnostic reason. Unknown optional fields are ignored.
Missing/empty `words` preserves all returned images; valid optional permutations
are applied without dropping or duplicating pages. Returned CDN subdomain and
path are retained, limited to reviewed HTTPS `s0`, `s3`, and `sa` through `sz`
under `mangafunb.fun`. Images use only a User-Agent, not API headers.

The generic plugin fills short search pages by actual counts. Chapter groups
are carried in a five-minute per-item snapshot; absent/malformed/expired snapshots
rediscover. Default group comes first, remaining groups sort by path word, and
server order is retained within each group. Chapter batches and continuation
state are explicit, so interleaved operations share no mutable last-request state.

HTTP 403/404/408/421/425/5xx, invalid envelope, missing payload, advertised
non-empty group with no chapters, and empty/missing chapter images retry each
remaining reviewed mirror once for that request. Business code 210/401, locks
and malformed identities/orders are not retried. The successful origin is carried
on details/chapters for subsequent operations.

## Host boundaries

Each response must match its reconstructed request URL, including query. The
SDK contains no portable WASI URL parser dependency, so byte-exact binding is
retained rather than accepting a different origin/path/query. Ktor-normalized
URL equivalence is not established as the reported failure and remains a
compatibility consideration for noncanonical IDs.

DNS/connect/host-timeout failures occur before `SourceResponse`; SDK 2.0.0 has
no transport-failure continuation, so the guest cannot retry them. They remain
host errors. Coroutine cancellation is not swallowed and no partial chapter
list is published on a failed host operation.

The host bounds requests/responses and disables redirects and ambient cookies.
Plugin code retains reviewed origins and CDN limits. SDK 2.0.0 has no serialized
per-origin permission field; these plugin restrictions are not a host-enforced
permission declaration. Installation/signature verification does not prove code
safety. No trust key, automatic permission approval, dynamic domain discovery,
proxy, login, history, downloads or restricted-content access is added. CORS
still applies in browsers.

## Licensing

MIT; see the retained copyright/permission notice in the root `LICENSE`.
Kira (copyright 2026 孤独的Lonely, MIT) supplies the nested-group fallback and
chapter response contract. cutmangax-source (Apache-2.0) remains a read-only
reference for current headers/endpoints; its Android/OkHttp/JNI logic is not
copied by this correction. Software licensing does not grant rights to
redistribute provider content.

## Release and regression sources

Follow the root README and `docs/publishing.md`. Keep original `1.0.0` release
bytes/hashes/source references immutable. A new signed release requires actual
source revision, external publisher-owned authority and export-generated hashes.
No signing key or sample trust identity is copied for this correction.

Regression source covers chapter discovery/list/content requests and required
header pair, missing `words` with complete server order, empty image payload
mirror retries, limits, source identity and existing parsing behavior.
Builds, tests, formatters, linters, live API probing and device runs were not run.
For separate authorization, from this repository with JDK 21 and compatible Node:

```sh
./gradlew :evilo-plugin-copymanga-core:jvmTest -Pevilo.useLocalSdk
./gradlew :evilo-plugin-copymanga-core:wasmWasiNodeTest -Pevilo.useLocalSdk
```
