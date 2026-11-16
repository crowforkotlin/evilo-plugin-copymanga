"""Append a release while retaining the old releases' effective common fields."""
import copy
import json
from pathlib import Path
import sys

old_path, new_path, output_path = map(Path, sys.argv[1:])
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
        for field in ('permissions', 'protocol', 'kinds', 'languages', 'sourceUrl', 'signing'):
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
output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
