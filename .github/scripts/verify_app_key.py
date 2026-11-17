"""Refuse to sign when the publisher Secret does not derive the app's verification key."""
import hashlib
import os
import re
import sys
from pathlib import Path

public_key, expected = sys.argv[1], sys.argv[2]
value = Path(public_key).read_text().strip().lower()
if not re.fullmatch('[0-9a-f]{64}', value):
    raise SystemExit('Restored publisher public key is not a raw 32-byte hexadecimal key.')
if not os.environ.get('EVILO_PRIVATE_KEY_HEX', '').strip():
    raise SystemExit('Configure repository Secret EVILO_PRIVATE_KEY_HEX.')
if not re.fullmatch('[0-9a-f]{64}', expected):
    raise SystemExit(
        'Configure repository variable EVILO_PUBLIC_KEY_HEX with the application verification '
        'key (64 lowercase hex characters); signed packages are verified against it at install time.')
if value != expected:
    raise SystemExit(
        'The Secret derives a different public key than the application trusts.\n'
        f'  derived:  {value}\n'
        f'  embedded: {expected}\n'
        'Signed packages would fail verification at install time. Set EVILO_PUBLIC_KEY_HEX to the '
        'application key, or rebuild the application with the key this Secret derives.')
key_id = os.environ.get('EVILO_KEY_ID', '').strip() or 'publisher-' + hashlib.sha256(bytes.fromhex(value)).hexdigest()
if key_id != 'evilo-root-v1':
    raise SystemExit(f'Official packages must use key ID evilo-root-v1, not {key_id}.')
print(f'Signing identity matches the application verification key: {value} ({key_id})')
