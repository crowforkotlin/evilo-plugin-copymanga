"""Restore GitHub Release assets from immutable Git files without building or signing."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile


def run(*args):
    subprocess.run(args, check=True)


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


def read_blob(ref, path):
    return subprocess.check_output(['git', 'show', f'{ref}:{path}'])


def remote_tag(name):
    ref = 'refs/tags/' + name
    value = git('ls-remote', '--tags', 'origin', ref)
    if not value:
        return False
    if value.split()[0] != git('rev-parse', ref):
        raise SystemExit(f'Local and remote {name} differ; never replace a published tag.')
    return True


def release_record(repository, tag):
    # Listing includes drafts, which the published-release-by-tag endpoint can omit.
    page = 1
    while True:
        records = json.loads(subprocess.check_output(['gh', 'api',
            f'repos/{repository}/releases?per_page=100&page={page}'], text=True))
        record = next((entry for entry in records if entry['tag_name'] == tag), None)
        if record is not None:
            return record
        if len(records) < 100:
            return None
        page += 1


def verify_assets(record, expected, repository, tag, directory):
    assets = {}
    for asset in record['assets']:
        if asset['name'] in assets:
            raise SystemExit('GitHub Release contains duplicate asset names.')
        assets[asset['name']] = asset
    missing = []
    for path in expected:
        asset = assets.get(path.name)
        if asset is None:
            missing.append(str(path))
            continue
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        remote_digest = asset.get('digest')
        if not remote_digest:
            downloaded = directory / path.name
            if not downloaded.exists():
                run('gh', 'release', 'download', tag, '--repo', repository,
                    '--pattern', path.name, '--dir', str(directory))
            remote_digest = 'sha256:' + hashlib.sha256(downloaded.read_bytes()).hexdigest()
        if asset['size'] != path.stat().st_size or remote_digest != 'sha256:' + digest:
            raise SystemExit(f'Existing asset {path.name} differs; never overwrite it.')
    return missing


def main():
    tag, = sys.argv[1:]
    if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', tag):
        raise SystemExit('Supply an existing vX.Y.Z source tag.')
    repository = os.environ['GITHUB_REPOSITORY']
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise SystemExit('Invalid repository name.')
    if not remote_tag(tag):
        raise SystemExit('Restore the original source tag first; do not create a new source revision.')
    version, package_tag = tag[1:], 'package-' + tag
    # Legacy releases stored public files in the source tag instead of a package tag.
    file_tag = package_tag if remote_tag(package_tag) else tag
    ref = 'refs/tags/' + file_tag
    prefix = f'releases/{version}/'
    catalog_bytes = read_blob(ref, prefix + 'plugins.json')
    plugin, = json.loads(catalog_bytes)['plugins']
    release, = plugin['availableVersions']
    if release['version'] != version or not re.fullmatch(r'[A-Za-z0-9_.-]+', plugin['id']):
        raise SystemExit('Catalog identity does not match the requested release.')
    revision = release['sourceRevision']
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise SystemExit('Use a full immutable source revision.')
    if file_tag == package_tag and git('rev-parse', f'refs/tags/{tag}^{{commit}}') != revision:
        raise SystemExit('Package source revision does not match its source tag.')
    run('git', 'merge-base', '--is-ancestor', revision, f'refs/tags/{tag}^{{commit}}')
    base = f'https://raw.githubusercontent.com/{repository}/{file_tag}/{prefix.rstrip("/")}'
    archive_name = release['sourceArchiveUrl'].rsplit('/', 1)[1]
    if not re.fullmatch(r'[A-Za-z0-9_.-]+\.zip', archive_name):
        raise SystemExit('Unexpected source archive name.')
    if (release['manifestUrl'] != base + '/manifest.wlm'
            or release['sourceArchiveUrl'] != base + '/' + archive_name
            or plugin['iconUrl'] != base + '/icon.png'):
        raise SystemExit('Catalog URLs do not refer to the immutable package files.')
    required = {'manifest.wlm', 'plugins.json', 'icon.png', archive_name}
    files = {}
    for entry in subprocess.check_output(['git', 'ls-tree', '-r', '-z', ref, '--', prefix]).split(b'\0'):
        if not entry:
            continue
        metadata, path = entry.decode().split('\t', 1)
        relative = path.removeprefix(prefix)
        artifact = re.fullmatch(r'artifacts/sha256/([0-9a-f]{2})/([0-9a-f]{64})\.(wasm|pwasm|cwasm)', relative)
        if relative not in required and not relative.startswith('artifacts/'):
            continue
        if metadata.split()[0] not in ('100644', '100755'):
            raise SystemExit('Public package files must be regular Git blobs.')
        value = read_blob(ref, path)
        if relative.startswith('artifacts/'):
            if not artifact or artifact[1] != artifact[2][:2] or hashlib.sha256(value).hexdigest() != artifact[2]:
                raise SystemExit('Package artifact does not match its content-addressed filename.')
        files[relative] = value
    if not required.issubset(files) or len(files) == len(required):
        raise SystemExit('The immutable package is incomplete.')
    if hashlib.sha256(files[archive_name]).hexdigest() != release['sourceArchiveSha256']:
        raise SystemExit('Source archive does not match its catalog digest.')
    if release.get('manifestSha256') and hashlib.sha256(files['manifest.wlm']).hexdigest() != release['manifestSha256']:
        raise SystemExit('Signed manifest does not match its catalog digest.')
    with tempfile.TemporaryDirectory(prefix='evilo-public-assets-') as directory:
        directory = Path(directory)
        package = directory / f'{plugin["id"]}-{version}-package.zip'
        # Fixed ZIP metadata lets retries compare bytes without replacing existing assets.
        with zipfile.ZipFile(package, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, value in sorted(files.items()):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                archive.writestr(info, value)
        expected = [package]
        for name in (archive_name, 'manifest.wlm', 'plugins.json', 'icon.png'):
            path = directory / name
            path.write_bytes(files[name])
            expected.append(path)
        downloaded = directory / 'existing-assets'
        downloaded.mkdir()
        record = release_record(repository, tag)
        if record is None:
            run('gh', 'release', 'create', tag, '--repo', repository, '--verify-tag', '--draft',
                '--title', f'{plugin["name"]} {version}', '--notes',
                f'Signed plugin release. Subscribe to https://raw.githubusercontent.com/{repository}/{os.environ["DEFAULT_BRANCH"]}/plugins.json. '
                f'Immutable package files: {file_tag}. Catalog publication date: {release["publishedAt"]}. '
                'A signature does not establish code safety.')
            record = release_record(repository, tag)
        missing = verify_assets(record, expected, repository, tag, downloaded)
        if missing:
            run('gh', 'release', 'upload', tag, '--repo', repository, *missing)
        record = release_record(repository, tag)
        if verify_assets(record, expected, repository, tag, downloaded):
            raise SystemExit('GitHub Release is missing required public assets.')
        if record['draft']:
            run('gh', 'release', 'edit', tag, '--repo', repository, '--draft=false')
        print(f'https://github.com/{repository}/releases/tag/{tag}')


if __name__ == '__main__':
    main()
