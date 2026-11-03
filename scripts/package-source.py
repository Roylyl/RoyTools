#!/usr/bin/env python3
"""Export the local RoyTools source tree using Git's ignore rules."""
from pathlib import Path
import argparse
import re
import subprocess
import zipfile

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--version', default='1.0.0', help='major.minor.patch')
arguments = parser.parse_args()
if not re.fullmatch(r'\d+\.\d+\.\d+', arguments.version):
    parser.error('--version must be major.minor.patch')

def git(*args, **kwargs):
    return subprocess.run(['git', '-C', str(root), *args], check=True,
                          stdout=subprocess.PIPE, **kwargs).stdout

candidates = sorted(set(git('ls-files', '--cached', '--others', '--exclude-standard', '-z').split(b'\0')) - {b''})
ignored_result = subprocess.run(
    ['git', '-C', str(root), 'check-ignore', '--no-index', '-z', '--stdin'],
    input=b'\0'.join(candidates) + b'\0', stdout=subprocess.PIPE, check=False)
if ignored_result.returncode not in (0, 1):
    raise SystemExit('Cannot validate Git ignore rules; export cancelled.')
ignored = set(ignored_result.stdout.split(b'\0'))
files = []
for entry in candidates:
    if entry in ignored:
        continue
    path = root / entry.decode('utf-8')
    if path.name in {'local.properties', 'signing.properties'} or path.suffix.lower() in {'.jks', '.keystore', '.p12', '.pfx'}:
        raise SystemExit(f'Private signing/local file must be ignored before export: {path.relative_to(root)}')
    if path.is_symlink():
        raise SystemExit(f'Review symlink before exporting: {path.relative_to(root)}')
    if path.is_file():
        if not path.resolve().is_relative_to(root):
            raise SystemExit('Source file escaped project root.')
        files.append(path)
required = ['README.md', 'LICENSE', 'NOTICE', 'THIRD_PARTY.md', 'CHANGELOG.md',
            'core/src/foss/golang/clash/LICENSE', 'gradle/wrapper/gradle-wrapper.jar']
for name in required:
    if root / name not in files:
        raise SystemExit(f'Required source/notice missing or ignored: {name}')
version = arguments.version
dist = root / 'dist'
dist.mkdir(exist_ok=True)
archive = dist / f'RoyTools-{version}-source.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as output:
    for path in files:
        output.write(path, 'RoyTools/' + path.relative_to(root).as_posix())
print(f'Exported {len(files)} source files to {archive}')
print('External dependency source and Geo licensing require separate distribution review.')
