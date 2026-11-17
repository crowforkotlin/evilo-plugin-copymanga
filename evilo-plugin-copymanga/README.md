# CopyManga plugin

`evilo-plugin-copymanga` is the guest module in this independent provider repository, not a module of evilo or the template. Its WASI entrypoint binds `EviloPluginService` through the shared SDK's
`MangaPluginAdapter`; the internal parser uses legacy `MangaSourceService` API 2.
The app installs the generic protocol package from a JSON catalog and does not
statically link this implementation.

The source follows the provided `cutmangax-source` reference's API paths, headers,
HMAC signing, response models, chapter groups, and image ordering, principally
`MangaXStrings.kt`, `CopyMangaSigner.kt`, `NetworkModule.kt`, `HomeService.kt`,
`ComicService.kt`, and `ComicChapterLoader.kt`. Android UI, JNI/OpenCC, remote
configuration, login, account storage, and proxy endpoints are not copied.
The reference and upstream Wasmline repositories remain unchanged.

## Implemented request and parser behavior

| Operation | API path and behavior |
| --- | --- |
| Search | `/api/v3/search/comic`, UTF-8 encoded `q`, all-field `q_type=`, 20 results per requested page |
| Empty search | `/api/v3/comics?free_type=1`, catalog pagination |
| Details | `/api/v3/comic2/{pathword}?platform=3` on a `mapi.*` mirror, retaining the source-qualified ID |
| Chapters | Discover groups through details, then paginate `/api/v3/comic/{pathword}/group/{group}/chapters?limit=500&offset=…&platform=3` on the same mirror |
| Image pages | `/api/v3/comic/{pathword}/chapter/{uuid}?platform=3` on the mirror, order by the parallel `words` list and assign zero-based indexes |

`evilo-plugin-copymanga/plugin.json` is the release descriptor/metadata source of truth. The current
plugin ID is `crow.evilo.source.copymanga`, version `1.0.1`, version code `2`,
language `zh`, and media kind `manga`. The guest passes the generated descriptor ID and version to `CopyMangaSource`; the core has no generated-field dependency. Media URLs are actual API resource URLs,
not invented website addresses; requests are reconstructed from IDs.

### Host split

CopyManga serves its API from two host families, and the split is enforced
server-side rather than by request parameters:

| Role | Default | Behavior |
| --- | --- | --- |
| Catalog and search | `https://api.copy202601.com` | `/api/v3/comics` and `/api/v3/search/comic` return data; `/api/v3/comic2/{pathword}` answers `code: 200` with `results: null` |
| Details, chapters, images | `https://mapi.hotmangasd.com` | `/api/v3/comic2/…`, chapter lists, and `/chapter/` return data; `/chapter2/` is `404` |

`CopyMangaConfig` therefore carries two origins, both validated against the
reviewed allowlists in `CopyMangaConfig.LIST_BASE_URLS` and
`CopyMangaConfig.CONTENT_BASE_URLS`. Defaults are the first entries; the
alternates are `api.copy202602.com`, `api.copy-manga.com`,
`api.copy2000.online`, `api.mangacopy.com` and `mapi.hotmangasg.com`,
`mapi.hotmangasf.com`, `mapi.elfgjfghkk.club`, `mapi.fgjfghkkcenter.club`,
`mapi.fgjfghkk.club`. Passing a list host as the content origin, or the reverse,
is rejected instead of failing at request time. There is no runtime settings
service, automatic failover, or proxy.

API requests use `platform: 3`, `version: 3.1.1`, `webp: 1`,
`source: copyApp`, `User-Agent: COPY/3.1.1`, `Accept: application/json`, and
`Referer: com.copymanga.app-3.1.1`, plus fresh `X-Auth-Timestamp`, `tapString`,
and HMAC-SHA256 `X-Auth-Signature`. The version window the server accepts is
`3.0.0`–`3.1.1`; `platform` values other than `3` and older client versions are
rejected with `code: 210`. The mirrors additionally require `platform`,
`version`, and `webp`; dropping any of them returns a non-JSON error page.
The injectable Kotlin clock and KotlinCrypto HMAC work in portable plugin code.
The provider's client protocol key is not the application's Ed25519 signing key.
The plugin does not send analytics device IDs, blank authorization, or cookies,
and does not implement account/login storage.

Parsing accepts numeric/string `code=200`, unknown fields, optional nulls, and
object results encoded as a JSON string. Missing required IDs/lists, HTTP/API
failures including `code=210`, locked chapters, mismatched offsets/IDs, and
malformed image ordering throw detailed errors instead of returning fake empty
content. HTTPS and protocol-relative image URLs are supported. Missing/empty
`words` preserves server ordering. A short non-final search page is rejected
rather than skipping results with the next fixed 20-item offset.

## Multi-request chapters

Group discovery does not assume a `default` group exists or skip groups with
unknown/zero counts. The default group, if present, is visited first; remaining
groups are ordered by their path words. Server chapter order is retained within
each group. Multiple groups prefix chapter titles with their group names.
Pagination advances by the actual returned list size, including server-capped
pages. A reported total determines completion; otherwise the returned/requested
limit determines whether another page is needed.

The shared `MangaPluginAdapter` converts API 2's `SourceChapterRequest` context
and chapter batches into generic `PluginStep` continuations. Context carries the
manga ID, groups, group index, and offset, is passed back to the parser, and is
never sent over HTTP or stored as a mutable last-request field. The current host
bounds exchanges at 512 and chapter results at 20,000. Failure/cancellation does
not publish a partial chapter list. A package exposing only the old manga service
cannot be installed by the new generic app.

## Host approvals and limits

`plugin.json` declares all eleven HTTP API origins — the five list hosts and the
six `mapi.*` mirrors — GET under `/api/v3/`, and exactly the header names the
source sends: `user-agent`, `accept`, `source`, `platform`, `version`, `webp`,
`referer`, `x-auth-timestamp`, `tapstring`, `x-auth-signature`. The `region`
header and the signature-adjacent device/`pseudoid`/`dt` headers were dropped;
none of them changes a response today.

Media permission names the 27 image hosts the CDN actually uses: `s3` and
`sa`–`sz` plus `s0`. Cover and page URLs come back on whichever single-letter
host matches the URL's own first path segment (`sm` ↔ `/m/`), so every letter
must be approved; wildcards are not supported. The dead
`hi77-overseas.mangafuna.xyz` domain was removed. Media requests carry only
`User-Agent`; the CDN answers `403` without one, and API headers are not
inherited by images. An undeclared CDN is rejected by the host, so a publisher
must update the signed permission specification, release a new version, and
ask the user to approve it. There is no manual host/key form or automatic CDN
approval in the new app.

The host disables redirects, cookies, and ambient credentials, bounds requests
at 30 seconds/four concurrent exchanges per transport, limits API responses to
2 MiB and images to 16 MiB, and checks PNG/JPEG/WebP dimensions before decoding.
Packages use signed remote manifests with same-origin, content-addressed artifact
paths. Browser package/API/image servers still need CORS permission, and no proxy
is provided. Network availability and provider restrictions have not been tested
for this redesign. Signing does not prove code safety; the current
upstream runtime exposes no guest fuel or linear-memory budget to the host.

The metadata declares overseas availability and NSFW content conservatively.
Enable the NSFW preference in evilo's More tab to show this entry. Its root
`plugins.json` is independently hosted; adding that JSON URL requires no PR to
evilo's bundled source. The compact catalog requires the updated app parser.

## Manual release and loading

Follow the repository root README for the application's preserved Ed25519 authority,
real immutable source/publication URLs, source ZIP, catalog export, and prerequisites.
Only the authority operator should export signed releases. From this repository root:

```sh
./gradlew :evilo-plugin-copymanga:exportPluginRelease \
  -Pevilo.sdkPath=../evilo-library \
  -Pplugin.sourceRevision="$SOURCE_COMMIT" \
  -Pplugin.releaseBaseUrl="$RELEASE_BASE_URL" \
  -Pplugin.sourceUrl="$SOURCE_REPOSITORY_URL" \
  -Pplugin.publishedAt="$PUBLISHED_AT" \
  -Pwasmline.autoDownload=true
```

`PUBLISHED_AT` accepts an ordinary date or full timestamp. The optional
`-Pplugin.sourceSnapshotUrl="$SOURCE_SNAPSHOT_URL"` overrides the inherited source
repository link; actual commit/archive/hash are always required. Edit authors,
introduction, and optional creation date in `plugin.json`'s `catalog` object.
The exporter writes common kinds/languages/protocol/permissions only once,
without review metadata or a schema association. Retain explicit overrides for
older releases when merging new releases into the stable root `plugins.json`.

Existing `releases/1.0.0` files and source URLs remain unchanged. Adapting the
root catalog does not rebuild or re-sign that release.

Version `1.0.0` was exported under
`evilo-plugin-copymanga/build/release/crow.evilo.source.copymanga-1.0.0/` with the
signed manifest/artifacts, actual source ZIP and its signed hash, PNG icon, and
`plugins.json`. Assembly output is under
`evilo-plugin-copymanga/build/wasmline/output/` and its distribution ZIP under
`evilo-plugin-copymanga/build/wasmline/dist/`. The published installation files are
under the repository's `releases/1.0.0/`, without local debug build records. Do not
publish old moved preview artifacts or use old
`copymanga.preview`/`copymanga.signingKey` properties. The build uses only the
authority metadata/private-key directory generated by evilo.

Follow the root README's Install in evilo section for the actual JSON subscription
address. In a matching app, add the catalog in Browse, select CopyManga, approve
its permissions, and install. The user never enters an Ed25519 key or individual
manifest path. The app code offers search, details, chapters, vertical image
reading, and previous/next chapter navigation. Loading and live requests have not
been exercised on a phone. Library, saved progress, downloads, and provider login
are not implemented. An old installed APK is not changed by edits to these sources.

## Manual tests

`jvmTest` and `wasmWasiNodeTest` pass for this source on the configured JDK and
Node.js. Both use local JSON fixtures and an injectable clock, so they do not
contact the provider. A separate uncommitted smoke run exercised the live flow
once on 2026-11-17 — search, details, chapter pagination, images, and one image
download — against the default hosts; that run is not part of the test suite and
does not establish continued availability.

Use the configured JDK and a compatible Node.js for WASI.

```sh
./gradlew :evilo-plugin-copymanga-core:jvmTest -Pevilo.sdkPath=../evilo-library
./gradlew :evilo-plugin-copymanga-core:wasmWasiNodeTest -Pevilo.sdkPath=../evilo-library
./gradlew -p evilo-plugin-build-logic :plugin:test
```

Host catalog, protocol, HTTP, storage, cancellation, and image-decoder commands
are documented separately in evilo's README. A passing parser test does not
establish live API availability or successful signed plugin loading.
