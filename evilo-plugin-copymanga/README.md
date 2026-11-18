# CopyManga plugin

`evilo-plugin-copymanga` is the guest module in this independent provider repository.
Its WASI entrypoint binds `CopyMangaPlugin` directly to `EviloPluginService`.
`CopyMangaSource` remains an API 2 compatibility wrapper around the same parser;
the generic entrypoint does not use `MangaPluginAdapter`.
The app installs the generic protocol package from a JSON catalog and does not
statically link this implementation.

The current contract follows the supplied `interface.md` observations dated
2026-11-17 and kira's `lib/api/api_transport.dart`, `lib/api/manga/manga_api.dart`,
and `lib/models/comic.dart`. The API host split, accepted app version, pagination
caps, and image CDN behavior come from the interface notes. The earlier
`cutmangax-source` integration supplies HMAC and parallel image-order behavior.
Provider HTTP execution stays in evilo's shared transport; no Dio/Ktor client,
Android UI, JNI/OpenCC, account storage, or proxy is added to the guest.

## Implemented request and parser behavior

| Operation | API path and behavior |
| --- | --- |
| Search | `/api/v3/search/comic`, UTF-8 encoded `q`, all-field `q_type=`, up to 30 results per public page |
| Empty search | `/api/v3/comics?free_type=1&ordering=-datetime_updated`, up to 50 recently updated comics per public page |
| Details | `/api/v3/comic2/{pathword}?platform=3` on a `mapi.*` mirror, retaining the source-qualified ID |
| Chapters | Reuse groups from a fresh detail item or discover them, then paginate `/api/v3/comic/{pathword}/group/{group}/chapters?limit=500&offset=…&platform=3` |
| Image pages | `/api/v3/comic/{pathword}/chapter/{uuid}?platform=3` on the mirror, order by the parallel `words` list and assign zero-based indexes |

`evilo-plugin-copymanga/plugin-spec.json` is the release descriptor/metadata source of truth. The current
plugin ID is `crow.evilo.source.copymanga`, version `1.0.1`, version code `2`,
language `zh`, and media kind `manga`. The guest passes the generated descriptor to
`CopyMangaPlugin`; the core has no generated-field dependency. Media URLs are actual API resource URLs,
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
is rejected instead of failing at request time. The generic service retries the
same resource on each remaining approved origin of that role after HTTP
403/404/408/421/425/5xx, an invalid envelope, or a successful null/missing payload.
A chapter group advertised as non-empty also retries when a mirror returns an
empty initial list. Each attempt has a fresh signature, and a successful content
origin is carried in item/chapter extensions for subsequent operations. Business
code 210/401, locked content, identity mismatches, and malformed pagination/order
are reported directly. Transport exceptions that occur before a `SourceResponse`
(DNS, connection errors, host timeouts) remain host failures; the SDK has no
transport-failure continuation. No automatic domain discovery or proxy is used.

All API requests use `platform: 3`, `version: 3.1.1`, `webp: 1`,
`Accept: application/json`, and fresh `X-Auth-Timestamp`, `tapString`, and
HMAC-SHA256 `X-Auth-Signature`. Catalog/search additionally use
`source: copyApp`, `User-Agent: COPY/3.1.1`,
`Referer: com.copymanga.app-3.1.1`, and the fixed key's MD5 `umstring`.
Mirrors use kira's browser UA form and `X-Requested-With: com.manga2020.app`,
without the list-only client headers. The version window in the supplied notes is
`3.0.0`–`3.1.1`; `platform` values other than `3` and older client versions are
rejected with `code: 210`. The mirrors additionally require `platform`,
`version`, and `webp`; dropping any of them returns a non-JSON error page.
The injectable Kotlin clock and KotlinCrypto HMAC work in portable plugin code.
The provider's client protocol key is not the application's Ed25519 signing key.
The plugin does not send analytics device IDs, blank authorization, or cookies,
and does not implement account/login storage.

Parsing accepts numeric/string `code=200`, unknown fields, optional nulls, and
results/arrays encoded as a JSON string. Pagination counts, chapter numbers,
image positions, group counts, and lock flags accept their documented numeric or
string forms. Objects, invalid numbers, non-finite chapter numbers, and null image
positions fail explicitly. Missing required IDs/lists, HTTP/API
failures including `code=210`, locked chapters, mismatched offsets/IDs, and
malformed image ordering throw detailed errors instead of returning fake empty
content. HTTPS and protocol-relative image URLs are supported. Missing/empty
`words` preserves server ordering. If a response contains fewer items than the
remaining public page capacity and indicates more results, the generic service
advances by the actual count and requests the remainder until it reaches 30/50
items or the reported end. The API 2 wrapper retains 20-item pages and rejects
incomplete non-final pages.

## Media mapping

| Provider field | Generic field |
| --- | --- |
| `path_word` | `MediaItem.id.value`, qualified by plugin ID and manga kind |
| `name`, `cover`, `brief` | `title`, approved HTTPS `coverUrl`, `description` |
| `author[].name`, then `author_alias` | Trimmed, distinct `authors`; omitted author fields retain known authors |
| `theme[].name` | Trimmed, distinct `tags`; a search response may omit themes |
| `uuid` | `MediaChapter.id`, preserving its requested item ID |
| Positive `ordered`, then `index` | `MediaChapter.number`, matching kira's chapter ordering; both values remain in extensions |
| Chapter group/name, size, count, type, prev/next, creation time | `MediaChapter.extensions` under `copymanga.*` |
| Alias, authors/themes, popularity, update time, free type, status, region, reclass, latest chapter, audience/access fields | `MediaItem.extensions` under `copymanga.*` |

Structured extension values contain JSON; primitive values contain their text.
Detail responses preserve known optional values when those fields are omitted or
null. A chapter-group snapshot is attached as `copymanga.chapter_groups.v1` with
its manga ID, origin, and timestamp; it is reused for five minutes and otherwise
rediscovered. A detail refresh without groups removes any old snapshot. Empty
groups remain distinct from missing groups. The current SDK exposes search,
details, chapters, and content, so blank search opens the catalog; no removed
`discoverIndex/freeComic` or `search/key` route is called.

## Multi-request chapters

Group discovery does not assume a `default` group exists or skip groups with
unknown/zero counts. The default group, if present, is visited first; remaining
groups are ordered by their path words. Server chapter order is retained within
each group. Multiple groups prefix chapter titles with their group names.
Pagination advances by the actual returned list size, including server-capped
pages. A reported total determines completion; otherwise the returned/requested
limit determines whether another page is needed.

`CopyMangaPlugin` returns generic `PluginStep` continuations and chapter batches
directly. Context carries operation identity, the current approved origin,
attempted origins, manga ID, groups, group index, offset, and the previous page's
last chapter ID. Search continuations carry only their partially filled current
page. Context is never sent over HTTP or stored as a mutable last-request field.
Each response URL must match its request; manga/group/chapter IDs and duplicate
IDs are checked. The current host
bounds exchanges at 512 and chapter results at 20,000. Failure/cancellation does
not publish a partial chapter list. HTTP requests share evilo's connection pool;
the guest reuses immutable header maps and the JSON parser. Image order is O(n)
for already ordered lists and zero/one-based permutations; sparse ranks sort,
with a 2000-image limit before allocating pages. A package exposing only the old manga service
cannot be installed by the new generic app.

## Host approvals and limits

`plugin-spec.json` declares all eleven HTTP API origins — the five list hosts and the
six `mapi.*` mirrors — GET under `/api/v3/`, and exactly the header names the
source sends: `user-agent`, `accept`, `source`, `platform`, `version`, `webp`,
`referer`, `umstring`, `x-requested-with`, `x-auth-timestamp`, `tapstring`, `x-auth-signature`. The `region`
header and the signature-adjacent device/`pseudoid`/`dt` headers were dropped;
none of them changes a response today.

Media permission names 28 approved image/static hosts: `s3` and
`sa`–`sz` plus `s0`. Cover and page URLs come back on whichever single-letter
host matches the URL's own first path segment (`sm` ↔ `/m/`), so every letter
must be approved; wildcards are not supported. The dead
`hi77-overseas.mangafuna.xyz` domain was removed. Media requests carry only
`User-Agent`; the CDN answers `403` without one, and API headers are not
inherited by images. `MediaItem` has no cover header field, so the accompanying
evilo `PluginNetworkPolicy.mediaRequest` change adds a generic `User-Agent: evilo`
when the plugin approves that header and a request does not supply one. Explicit
page headers are preserved. Older hosts without this change may fail to load
covers. The parser retains each returned host/path and rejects an unknown CDN
before returning images. An undeclared CDN is rejected by the host, so a publisher
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
`catalog.json` is independently hosted; adding that JSON URL requires no PR to
evilo's bundled source. The compact catalog requires the updated app parser.

## Manual release and loading

Follow the repository root README for the application's preserved Ed25519 authority,
real immutable source/publication URLs, source ZIP, catalog export, and prerequisites.
Only the authority operator should export signed releases. From this repository root:

```sh
./gradlew :evilo-plugin-copymanga:exportPluginRelease \
  -Pevilo.useLocalSdk \
  -Pplugin.sourceRevision="$SOURCE_COMMIT" \
  -Pplugin.releaseBaseUrl="$RELEASE_BASE_URL" \
  -Pplugin.sourceUrl="$SOURCE_REPOSITORY_URL" \
  -Pplugin.publishedAt="$PUBLISHED_AT" \
  -Pwasmline.autoDownload=true
```

`PUBLISHED_AT` accepts an ordinary date or full timestamp. The optional
`-Pplugin.sourceSnapshotUrl="$SOURCE_SNAPSHOT_URL"` overrides the inherited source
repository link; actual commit/archive/hash are always required. Edit authors,
introduction, and optional creation date in `plugin-spec.json`'s `catalog` object.
The exporter writes common kinds/languages/protocol/permissions only once,
without review metadata or a schema association. Retain explicit overrides for
older releases when merging new releases into the stable root `catalog.json`.

Existing `releases/1.0.0` files and source URLs remain unchanged. Adapting the
root catalog does not rebuild or re-sign that release.

Version `1.0.0` was exported under
`evilo-plugin-copymanga/build/release/crow.evilo.source.copymanga-1.0.0/` with the
signed manifest/artifacts, actual source ZIP and its signed hash, PNG icon, and
`catalog.json`. Assembly output is under
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

The current changes have not been compiled, tested, or exercised against the
provider or an installed app. Common fixtures cover generic protocol mapping,
short-page continuations, group reuse/expiry, mirror retry, chapter pagination,
string values, image ordering, source identity, and declared CDN hosts. JVM HMAC
tests use the same fixed protocol key as the implementation and compare against
`javax.crypto.Mac`. These tests use local JSON and an injected clock; they do not
contact the provider.

Use the configured JDK and a compatible Node.js for WASI.

```sh
./gradlew :evilo-plugin-copymanga-core:jvmTest -Pevilo.useLocalSdk
./gradlew :evilo-plugin-copymanga-core:wasmWasiNodeTest -Pevilo.useLocalSdk
./gradlew -p evilo-plugin-build-logic :plugin:test
```

Host catalog, protocol, HTTP, storage, cancellation, and image-decoder commands
are documented separately in evilo's README. A passing parser test does not
establish live API availability or successful signed plugin loading.
