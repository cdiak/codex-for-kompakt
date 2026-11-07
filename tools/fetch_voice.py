#!/usr/bin/env python3
"""Fetch checksum-pinned speech sources and weights; no account or API key required."""
import argparse
import hashlib
from pathlib import Path
import tarfile
import urllib.request

p = argparse.ArgumentParser()
p.add_argument('--directory', type=Path, default=Path('work/voice'))
a = p.parse_args()
a.directory.mkdir(parents=True, exist_ok=True)
artifacts = [
    ('whisper-v1.9.5.tar.gz', 'https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz',
     'ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720'),
    ('ggml-tiny.en-q5_1.bin', 'https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin',
     'c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b'),
]
for name, url, expected in artifacts:
    target = a.directory/name
    if not target.exists():
        temp = target.with_suffix('.part')
        print('Downloading', name, flush=True)
        with urllib.request.urlopen(url, timeout=60) as response, temp.open('wb') as out:
            while True:
                block = response.read(1024*1024)
                if not block: break
                out.write(block)
        temp.replace(target)
    if hashlib.sha256(target.read_bytes()).hexdigest() != expected:
        raise SystemExit('Checksum mismatch: '+str(target))
    print('Verified', name)
source = a.directory/'whisper.cpp-1.9.5'
if not source.exists():
    with tarfile.open(a.directory/'whisper-v1.9.5.tar.gz') as archive:
        for entry in archive.getmembers():
            path = a.directory/entry.name
            if not path.resolve().is_relative_to(a.directory.resolve()):
                raise SystemExit('Unsafe source archive path')
            if not entry.isdir() and not entry.isfile():
                raise SystemExit('Unexpected source archive entry')
        archive.extractall(a.directory)
print('Source:', source.resolve())
print('Model:', (a.directory/'ggml-tiny.en-q5_1.bin').resolve())
