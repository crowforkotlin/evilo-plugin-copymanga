"""Derive public signing metadata from the existing Secret, never generate keys."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys

folder = Path(sys.argv[1]).resolve()
if folder.is_relative_to(Path.cwd().resolve()):
    raise SystemExit('Keep signing files outside the checkout.')
value = os.environ.get('EVILO_PRIVATE_KEY_HEX', '').strip()
if not re.fullmatch('[0-9a-fA-F]{64}', value):
    raise SystemExit('Configure EVILO_PRIVATE_KEY_HEX with the preserved 32-byte seed.')
result = subprocess.run(['openssl', 'pkey', '-inform', 'DER', '-pubout', '-outform', 'DER'],
    input=bytes.fromhex('302e020100300506032b657004220420' + value),
    capture_output=True)
if result.returncode or len(result.stdout) != 44 or result.stdout[:12].hex() != '302a300506032b6570032100':
    raise SystemExit('Public key derivation failed.')
public = result.stdout[12:]
key_id = os.environ.get('EVILO_KEY_ID', '') or 'publisher-' + hashlib.sha256(public).hexdigest()
if not re.fullmatch('[A-Za-z0-9_.-]{1,128}', key_id):
    raise SystemExit('Invalid publisher key ID.')
os.umask(0o077)
folder.mkdir(mode=0o700, exist_ok=False)
(folder / 'private.key').write_text(value.lower() + '\n')
(folder / 'public.key').write_text(public.hex() + '\n')
(folder / 'authority.properties').write_text(f'keyId={key_id}\npublicKeyHex={public.hex()}\n')
