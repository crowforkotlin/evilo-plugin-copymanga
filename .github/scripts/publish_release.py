"""Publish immutable package files and a stable catalog; never force-push."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


def run(*args):
    subprocess.run(args, check=True)


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


bundle = Path(sys.argv[1]).resolve()
plugin, = json.loads((bundle / 'catalog.json').read_text())['plugins']
release, = plugin['availableVersions']
version = release['version']
if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', version):
    raise SystemExit('Invalid version.')
if not re.fullmatch('[A-Za-z0-9_.-]+', plugin['id']):
    raise SystemExit('Invalid plugin ID.')
tag, package_tag = 'v' + version, 'package-v' + version
repository = os.environ['GITHUB_REPOSITORY']
revision = release['sourceRevision']
if not re.fullmatch('[0-9a-f]{40}', revision):
    raise SystemExit('Use a full source revision.')
if git('rev-parse', f'refs/tags/{tag}^{{commit}}') != revision:
    raise SystemExit('Package does not match its source tag.')
base = f'https://raw.githubusercontent.com/{repository}/{package_tag}/releases/{version}'
if release['manifestUrl'] != base + '/manifest.wlm':
    raise SystemExit('Unexpected publication URL.')
if git('ls-remote', '--tags', 'origin', f'refs/tags/{package_tag}'):
    raise SystemExit('Package tag already exists; never overwrite it. Retry only GitHub Release asset upload if needed.')
# Capture trusted helpers before switching commits; never execute downloaded package code.
helper = Path(__file__).with_name('merge_catalog.py').read_bytes()
exporter = Path(__file__).with_name('export_bundle.py').read_bytes()
restorer = Path(__file__).with_name('restore_release.py').read_bytes()
run('git', 'config', 'user.name', 'github-actions[bot]')
run('git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
run('git', 'fetch', 'origin', os.environ['DEFAULT_BRANCH'])
run('git', 'switch', '--detach', 'FETCH_HEAD')
run('git', 'merge-base', '--is-ancestor', revision, 'HEAD')
target = Path('releases') / version
if target.exists():
    raise SystemExit('Release directory already exists.')
with tempfile.TemporaryDirectory(prefix='evilo-publish-helper-') as directory:
    directory = Path(directory)
    script, merged = directory / 'merge_catalog.py', directory / 'catalog.json'
    script.write_bytes(helper)
    check = directory / 'export_bundle.py'
    check.write_bytes(exporter)
    clean = directory / 'public-package'
    run(sys.executable, '-I', str(check), str(bundle), str(clean))
    run(sys.executable, '-I', str(script), str(Path('catalog.json').resolve()),
        str(clean / 'catalog.json'), str(merged))
    shutil.copytree(clean, target)
    shutil.copyfile(merged, 'catalog.json')
run('git', 'add', '--', str(target), 'catalog.json')
run('git', 'commit', '-m', f'release(plugins): publish signed version {version}')
run('git', 'tag', package_tag)
run('git', 'push', '--atomic', 'origin', f'HEAD:refs/heads/{os.environ["DEFAULT_BRANCH"]}', f'refs/tags/{package_tag}')
with tempfile.TemporaryDirectory(prefix='evilo-public-assets-') as directory:
    script = Path(directory) / 'restore_release.py'
    script.write_bytes(restorer)
    # Upload failure leaves immutable publication intact; republish these same files.
    run(sys.executable, '-I', str(script), tag)
