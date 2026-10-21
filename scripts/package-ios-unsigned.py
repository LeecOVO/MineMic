#!/usr/bin/env python3
"""Create an unsigned release IPA from an existing device IPA on macOS."""
import argparse
import hashlib
import shutil
from pathlib import Path
import subprocess
import tempfile
import zipfile

MACH_O = {bytes.fromhex(magic) for magic in (
    'feedface', 'cefaedfe', 'feedfacf', 'cffaedfe',
    'cafebabe', 'bebafeca', 'cafebabf', 'bfbafeca',
)}

def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True)


def package(source: Path, destination: Path):
    if not source.is_file():
        raise ValueError(f'Input IPA does not exist: {source}')
    if destination.exists():
        raise ValueError(f'Refusing to overwrite: {destination}')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='minemic-unsigned-') as temporary:
        work = Path(temporary)
        run('/usr/bin/ditto', '-x', '-k', '--norsrc', '--noextattr', str(source), str(work))
        payload = work / 'Payload'
        if not payload.is_dir() or not list(payload.glob('*.app')):
            raise ValueError('IPA has no Payload/*.app')
        binaries = []
        for path in payload.rglob('*'):
            if not path.is_file() or path.is_symlink():
                continue
            with path.open('rb') as stream:
                if stream.read(4) in MACH_O:
                    binaries.append(path)
        if not binaries:
            raise ValueError('IPA has no Mach-O executable')
        for binary in binaries:
            load_commands = run('/usr/bin/otool', '-l', str(binary)).stdout
            if 'LC_CODE_SIGNATURE' in load_commands:
                run('/usr/bin/codesign', '--remove-signature', str(binary))
            if 'LC_CODE_SIGNATURE' in run('/usr/bin/otool', '-l', str(binary)).stdout:
                raise RuntimeError(f'Code signature remains: {binary}')
            signature = subprocess.run(['/usr/bin/codesign', '-d', str(binary)], capture_output=True, text=True)
            if signature.returncode == 0 or 'not signed at all' not in signature.stderr:
                raise RuntimeError(f'Unsigned verification failed: {binary}: {signature.stderr}')
        # Bundle seals and provisioning profiles are separate from the Mach-O signature.
        for path in sorted(payload.rglob('*'), key=lambda p: len(p.parts), reverse=True):
            if path.name in {'_CodeSignature', 'embedded.mobileprovision', 'embedded.provisionprofile'} or path.name.startswith('._'):
                if path.is_dir() and not path.is_symlink():
                    shutil.rmtree(path)
                else:
                    path.unlink()
        candidate = work / 'unsigned.ipa'
        run('/usr/bin/ditto', '-c', '-k', '--norsrc', '--noextattr', '--keepParent', str(payload), str(candidate))
        with zipfile.ZipFile(candidate) as archive:
            if archive.testzip() is not None:
                raise RuntimeError('IPA archive integrity check failed')
            forbidden = [name for name in archive.namelist()
                         if '_CodeSignature' in name or name.endswith(('embedded.mobileprovision', 'embedded.provisionprofile'))]
            if forbidden:
                raise RuntimeError(f'Signing metadata remains: {forbidden}')
        shutil.copyfile(candidate, destination)
        digest = hashlib.sha256(destination.read_bytes()).hexdigest()
        destination.with_name(destination.name + '.sha256').write_text(f'{digest}  {destination.name}\n')
        print(f'Unsigned IPA: {destination}')
        print(f'Verified {len(binaries)} unsigned Mach-O executable(s); no provisioning profile or bundle signature.')
        print(f'SHA-256: {digest}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    package(args.source.resolve(), args.destination.resolve())
