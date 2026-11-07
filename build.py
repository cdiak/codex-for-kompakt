#!/usr/bin/env python3
"""Build a tiny Android APK with the official JDK/Android tools, without Gradle."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

p = argparse.ArgumentParser()
p.add_argument('--toolchain', type=Path, required=True)
p.add_argument('--work', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
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
for name in ('classes', 'generated', 'dex'):
    if (work / name).exists():
        shutil.rmtree(work / name)
    (work / name).mkdir(exist_ok=True)
libraries = list((source / 'libs').glob('*.jar'))
classpath = os.pathsep.join(str(p) for p in [android, *libraries])
run([buildtools/'aapt2', 'compile', '--dir', source/'res', '-o', work/'resources.zip'])
run([buildtools/'aapt2', 'link', '-o', work/'base.apk', '-I', android,
     '--manifest', source/'AndroidManifest.xml', '-A', source/'assets', '--java', work/'generated', work/'resources.zip'])
files = list((source/'src').rglob('*.java')) + list((work/'generated').rglob('*.java'))
run([java/'bin/javac', '-source', '8', '-target', '8', '-classpath', classpath,
     '-d', work/'classes', *files])
run([buildtools/'d8', '--lib', android, '--min-api', '26', '--output', work/'dex',
     *list((work/'classes').rglob('*.class')), *libraries])
shutil.copy2(work/'base.apk', work/'unsigned.apk')
with zipfile.ZipFile(work/'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as z:
    for dex in (work/'dex').glob('*.dex'):
        z.write(dex, dex.name)
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
