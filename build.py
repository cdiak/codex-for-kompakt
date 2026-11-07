#!/usr/bin/env python3
"""Build a tiny Android APK with the official JDK/Android tools, without Gradle."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
import hashlib

p = argparse.ArgumentParser()
p.add_argument('--toolchain', type=Path, required=True)
p.add_argument('--work', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--whisper-source', type=Path, required=True)
p.add_argument('--ndk', type=Path, required=True)
p.add_argument('--model', type=Path, required=True)
p.add_argument('--cmake', default=shutil.which('cmake') or 'cmake')
a = p.parse_args()
source = Path(__file__).resolve().parent / 'android'
tools = a.toolchain.resolve()
java = next(tools.glob('jdk/*/Contents/Home'))
buildtools = next((tools / 'sdk-build').iterdir())
android = next(tools.glob('sdk-platform/*/android.jar'))
work = a.work.resolve()
work.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, JAVA_HOME=str(java))
env['PATH'] = str(java / 'bin') + os.pathsep + env.get('PATH', '')
def run(args):
    subprocess.run([str(x) for x in args], check=True, env=env)
for name in ('classes', 'generated', 'dex', 'assets'):
    if (work / name).exists():
        shutil.rmtree(work / name)
    (work / name).mkdir(exist_ok=True)
model_hash = hashlib.sha256(a.model.read_bytes()).hexdigest()
if model_hash != 'c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b':
    raise SystemExit('Use the pinned ggml-tiny.en-q5_1.bin model; SHA-256 did not match.')
shutil.copytree(source/'assets', work/'assets', dirs_exist_ok=True)
shutil.copy2(a.model, work/'assets'/'ggml-tiny.en-q5_1.bin')
run([a.cmake, '-S', source/'native', '-B', work/'native',
     '-DCMAKE_TOOLCHAIN_FILE='+str(a.ndk.resolve()/'build/cmake/android.toolchain.cmake'),
     '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_static',
     '-DCMAKE_BUILD_TYPE=Release', '-DWHISPER_SOURCE='+str(a.whisper_source.resolve())])
run([a.cmake, '--build', work/'native', '--target', 'kompakt_speech', '-j', '8'])
native = work/'native'/'libkompakt_speech.so'
strip = next(a.ndk.resolve().glob('toolchains/llvm/prebuilt/*/bin/llvm-strip'))
run([strip, '--strip-unneeded', native])
libraries = list((source / 'libs').glob('*.jar'))
classpath = os.pathsep.join(str(p) for p in [android, *libraries])
run([buildtools/'aapt2', 'compile', '--dir', source/'res', '-o', work/'resources.zip'])
run([buildtools/'aapt2', 'link', '-o', work/'base.apk', '-I', android,
     '--manifest', source/'AndroidManifest.xml', '-A', work/'assets', '--java', work/'generated', work/'resources.zip'])
files = list((source/'src').rglob('*.java')) + list((work/'generated').rglob('*.java'))
run([java/'bin/javac', '-source', '8', '-target', '8', '-classpath', classpath,
     '-d', work/'classes', *files])
run([buildtools/'d8', '--lib', android, '--min-api', '26', '--output', work/'dex',
     *list((work/'classes').rglob('*.class')), *libraries])
shutil.copy2(work/'base.apk', work/'unsigned.apk')
with zipfile.ZipFile(work/'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as z:
    for dex in (work/'dex').glob('*.dex'):
        z.write(dex, dex.name)
    z.write(native, 'lib/arm64-v8a/libkompakt_speech.so')
run([buildtools/'zipalign', '-f', '4', work/'unsigned.apk', work/'aligned.apk'])
key = work/'development.keystore'
if not key.exists():
    run([java/'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'android',
         '-keypass', 'android', '-alias', 'kompakt-development', '-keyalg', 'RSA',
         '-keysize', '2048', '-validity', '3650', '-dname', 'CN=Kompakt Remote Personal Development'])
    key.chmod(0o600)
a.output.parent.mkdir(parents=True, exist_ok=True)
run([buildtools/'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android',
     '--key-pass', 'pass:android', '--out', a.output.resolve(), work/'aligned.apk'])
run([buildtools/'apksigner', 'verify', '--verbose', a.output.resolve()])
print('APK:', a.output.resolve(), 'bytes:', a.output.stat().st_size)
