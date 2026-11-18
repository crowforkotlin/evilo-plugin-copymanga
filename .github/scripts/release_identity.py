"""Resolve a clean source tag and a single guest module without shell evaluation."""
import json
import os
from pathlib import Path
import re
import subprocess


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


module = os.environ.get('PLUGIN_MODULE', '')
if not module:
    candidates = list(Path('.').glob('*/plugin-spec.json'))
    if len(candidates) != 1:
        raise SystemExit('Set PLUGIN_MODULE to the guest module name.')
    module = candidates[0].parent.name
if not re.fullmatch('[A-Za-z0-9_-]+', module):
    raise SystemExit('Invalid guest module name.')
descriptor = json.loads((Path(module) / 'plugin-spec.json').read_text())['descriptor']
version, plugin_id = descriptor['version'], descriptor['id']
repository, tag = os.environ['GITHUB_REPOSITORY'], os.environ['SOURCE_TAG']
if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', version):
    raise SystemExit('Use a filesystem-safe semantic version.')
if not re.fullmatch('[A-Za-z0-9_.-]+', plugin_id) or not re.fullmatch('[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
    raise SystemExit('Invalid plugin ID or repository name.')
if type(descriptor['versionCode']) is not int or descriptor['versionCode'] <= 0:
    raise SystemExit('versionCode must be a positive integer.')
revision = git('rev-parse', 'HEAD')
if tag != 'v' + version or git('rev-parse', f'refs/tags/{tag}^{{commit}}') != revision:
    raise SystemExit('Source tag must match descriptor.version and the checked out commit.')
if git('status', '--porcelain'):
    raise SystemExit('Commit all released source before packaging.')
info = {
    'module': module, 'version': version, 'plugin_id': plugin_id, 'source_tag': tag,
    'revision': revision, 'package_tag': 'package-v' + version,
    'base_url': f'https://raw.githubusercontent.com/{repository}/package-v{version}/releases/{version}',
    'source_url': f'https://github.com/{repository}/tree/{revision}',
}
with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
    for key, value in info.items():
        output.write(f'{key}={value}\n')
