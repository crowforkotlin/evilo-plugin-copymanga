"""Append a release, then drop the oldest releases until the catalog fits its byte budget.

The catalog is a single file each subscriber downloads, so it has a size the app
accepts. Rather than refuse to publish when that size is reached, this drops the
oldest releases: a subscriber keeps the newest ones, and an installation older than
what remains is reported as withdrawn, which is the same outcome as a publisher
deleting an old release by hand.

A release listed at or above the plugin's `minimumUsableVersionCode` is never
dropped, because that floor exists precisely to keep such versions available. If
those alone exceed the budget, the script fails rather than publish a catalog the
app would reject.
"""

import copy
import json
import sys
from pathlib import Path

# The app's catalog byte limit. Keep these in step: evilo-core CatalogParser.MAX_CATALOG_BYTES.
DEFAULT_MAX_BYTES = 10 * 1024 * 1024
# Slack for the JSON this script writes: it uses indent=2, which the byte count must cover.
# Proportional, so a caller that sets a small limit still gets room to trim.
MARGIN_FRACTION = 0.01


def serialized(catalog):
    return json.dumps(catalog, ensure_ascii=False, indent=2).encode()


def merge(old_path, new_path):
    new = json.loads(new_path.read_text())
    result = json.loads(old_path.read_text()) if old_path.exists() else copy.deepcopy(new)
    incoming = copy.deepcopy(new['plugins'][0])
    release = incoming['availableVersions'][0]
    previous = next((p for p in result['plugins'] if p['id'] == incoming['id']), None)
    if old_path.exists() and previous is not None:
        older = []
        for entry in previous['availableVersions']:
            if entry['version'] == release['version'] or int(entry['versionCode']) >= int(release['versionCode']):
                raise SystemExit('Never reuse published versions; versionCode must increase.')
            entry = copy.deepcopy(entry)
            for field in ('protocol', 'kinds', 'languages', 'sourceUrl', 'signing'):
                effective = entry.get(field, previous.get(field))
                if effective != incoming.get(field):
                    if effective is None and field != 'signing':
                        raise SystemExit(f'Supply explicit legacy {field} before changing common defaults.')
                    entry[field] = copy.deepcopy(effective)
            older.append(entry)
        incoming['availableVersions'] += older
        result['plugins'][result['plugins'].index(previous)] = incoming
    elif old_path.exists():
        result['plugins'].append(incoming)
    return result


def fit(result, max_bytes):
    """Drop the oldest releases until the document fits, never dropping a floored one.

    Trimming runs from the globally oldest version upward, so a plugin with a long
    history loses old releases first. A release at or above its plugin's
    `minimumUsableVersionCode` is a promise to subscribers and is never dropped; if the
    promises alone exceed the budget, publishing fails instead of silently breaking one.
    """
    budget = int(max_bytes * (1 - MARGIN_FRACTION))
    if len(serialized(result)) <= budget:
        return result, 0

    # Everything at or above a floor is fixed, so it must fit on its own.
    probe = copy.deepcopy(result)
    for plugin in probe['plugins']:
        floor = int(plugin.get('minimumUsableVersionCode', 1))
        if floor <= 1:
            # The format default, not a publisher promise: nothing is fixed, so this
            # plugin contributes nothing to the fixed footprint.
            plugin['availableVersions'] = []
            continue
        plugin['availableVersions'] = [r for r in plugin['availableVersions']
                                       if int(r['versionCode']) >= floor]
    if len(serialized(probe)) > budget:
        raise SystemExit(
            f"The releases at or above minimumUsableVersionCode already exceed {max_bytes} bytes.")

    entries = []
    for index, plugin in enumerate(result['plugins']):
        floor = int(plugin.get('minimumUsableVersionCode', 1))
        for release in plugin['availableVersions']:
            if floor > 1 and int(release['versionCode']) >= floor:
                continue
            entries.append((int(release['versionCode']), index, release))
    entries.sort(key=lambda entry: entry[0])

    # Binary-search how many of the oldest releases must go. Dropping a prefix keeps the
    # remaining order, so the document size is monotonic in that count.
    def size_without(count):
        probe = copy.deepcopy(result)
        for _, index, release in entries[:count]:
            probe['plugins'][index]['availableVersions'].remove(release)
        return len(serialized(probe))

    low, high = 0, len(entries)
    while low < high:
        middle = (low + high) // 2
        if size_without(middle) <= budget:
            high = middle
        else:
            low = middle + 1

    if low == len(entries) and size_without(low) > budget:
        raise SystemExit(f"The catalog cannot fit {max_bytes} bytes without dropping a protected release.")
    for _, index, release in entries[:low]:
        result['plugins'][index]['availableVersions'].remove(release)
    return result, low


def main(argv):
    max_bytes = int(argv[4]) if len(argv) > 4 else DEFAULT_MAX_BYTES
    old_path, new_path, output_path = map(Path, argv[1:4])
    result = merge(old_path, new_path)
    result, dropped = fit(result, max_bytes)
    body = serialized(result)
    if len(body) > max_bytes:
        raise SystemExit(f'Catalog is {len(body)} bytes, above the {max_bytes} byte limit.')
    output_path.write_text(body.decode('utf-8'))
    if dropped:
        print(f'merge_catalog: dropped {dropped} oldest release(s) to fit {max_bytes} bytes')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
