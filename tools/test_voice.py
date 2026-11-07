#!/usr/bin/env python3
"""Build and run a separate Android instrumentation APK; never opens the microphone."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

p = argparse.ArgumentParser()
p.add_argument('--toolchain', type=Path, required=True)
p.add_argument('--app-build', type=Path, required=True)
p.add_argument('--work', type=Path, required=True)
p.add_argument('--sample', type=Path, required=True, help='16 kHz mono PCM16 WAV saying the documented Python sentence')
p.add_argument('--adb', type=Path, required=True)
p.add_argument('--serial')
a = p.parse_args()
root = Path(__file__).resolve().parent.parent
t, w = a.toolchain.resolve(), a.work.resolve()
java = next(t.glob('jdk/*/Contents/Home'))
bt = t/'sdk-build/android-15'
android = t/'sdk-platform/android-35/android.jar'
env = dict(os.environ, JAVA_HOME=str(java))
env['PATH'] = str(java/'bin')+os.pathsep+env.get('PATH','')
def run(args, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, env=env, **kwargs)
for name in ('classes','dex','assets'):
    path = w/name
    if path.exists(): shutil.rmtree(path)
    path.mkdir(parents=True)
shutil.copy2(a.sample, w/'assets/coding-task.wav')
run([bt/'aapt2','link','-o',w/'base.apk','-I',android,'--manifest',root/'tests/android/AndroidManifest.xml','-A',w/'assets'])
run([java/'bin/javac','-source','8','-target','8','-classpath',str(android)+os.pathsep+str(a.app_build.resolve()/'classes'),'-d',w/'classes',root/'tests/android/VoiceSmokeTest.java'])
run([bt/'d8','--lib',android,'--min-api','26','--output',w/'dex',*list((w/'classes').rglob('*.class'))])
shutil.copy2(w/'base.apk',w/'unsigned.apk')
with zipfile.ZipFile(w/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as z:
    z.write(w/'dex/classes.dex','classes.dex')
run([bt/'zipalign','-f','4',w/'unsigned.apk',w/'aligned.apk'])
run([bt/'apksigner','sign','--ks',a.app_build.resolve()/'development.keystore','--ks-pass','pass:android','--out',w/'tests.apk',w/'aligned.apk'])
adb = [str(a.adb.resolve())]+(['-s',a.serial] if a.serial else [])
run([*adb,'install','--no-incremental','-r',w/'tests.apk'])
try:
    result = run([*adb,'shell','am','instrument','-w','org.kompakt.remote.tests/.VoiceSmokeTest'], capture_output=True, text=True, timeout=120)
    print(result.stdout)
    if 'INSTRUMENTATION_CODE: -1' not in result.stdout or 'INSTRUMENTATION_RESULT: error=' in result.stdout:
        raise SystemExit('Voice checks failed')
finally:
    run([*adb,'uninstall','org.kompakt.remote.tests'])
