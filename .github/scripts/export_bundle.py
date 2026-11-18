"""Copy only public release files and verify their content-addressed filenames."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys

source, output = map(Path, sys.argv[1:])
catalog = json.loads((source / 'catalog.json').read_text())
plugin, = catalog['plugins']
release, = plugin['availableVersions']
archive_name = release['sourceArchiveUrl'].rsplit('/', 1)[1]
if not re.fullmatch(r'[A-Za-z0-9_.-]+\.zip', archive_name):
    raise SystemExit('Unexpected source archive name.')
files = [Path('manifest.wlm'), Path('catalog.json'), Path('icon.png'), Path(archive_name)]
artifacts = list((source / 'artifacts').rglob('*'))
for artifact in artifacts:
    if artifact.is_symlink():
        raise SystemExit('Symlinked package artifacts are not permitted.')
    if artifact.is_dir():
        continue
    relative = artifact.relative_to(source)
    match = re.fullmatch(r'artifacts/sha256/([0-9a-f]{2})/([0-9a-f]{64})\.(wasm|pwasm|cwasm)', relative.as_posix())
    if not match or match[1] != match[2][:2] or artifact.is_symlink():
        raise SystemExit('Unexpected package artifact layout.')
    if hashlib.sha256(artifact.read_bytes()).hexdigest() != match[2]:
        raise SystemExit('Artifact content does not match its filename.')
    files.append(relative)
if len(files) == 4:
    raise SystemExit('The package contains no artifacts.')
if hashlib.sha256((source / 'manifest.wlm').read_bytes()).hexdigest() != release['manifestSha256']:
    raise SystemExit('Signed manifest does not match catalog digest.')
if hashlib.sha256((source / archive_name).read_bytes()).hexdigest() != release['sourceArchiveSha256']:
    raise SystemExit('Source archive does not match catalog digest.')
output.mkdir(parents=True, exist_ok=False)
for relative in files:
    file = source / relative
    if not file.is_file() or file.is_symlink():
        raise SystemExit('Missing or symlinked public release file.')
    target = output / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(file, target)
