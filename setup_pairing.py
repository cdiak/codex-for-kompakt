#!/usr/bin/env python3
"""Create local TLS credentials, install the APK, and pair over authorized USB."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import ipaddress
import shlex

p = argparse.ArgumentParser()
p.add_argument('--runtime', type=Path, required=True)
p.add_argument('--wifi-ip', default='127.0.0.1', help='Optional host LAN address; USB is the default transport')
p.add_argument('--adb', type=Path)
p.add_argument('--apk', type=Path)
p.add_argument('--prepare-only', action='store_true')
p.add_argument('--enable-wifi', action='store_true', help='Provision a Wi-Fi address only after explicitly configuring a scoped listener')
a = p.parse_args()
ipaddress.ip_address(a.wifi_ip)
os.umask(0o077)
a.runtime.mkdir(parents=True, exist_ok=True)
cert, key = a.runtime/'server.crt', a.runtime/'server.key'
if not cert.exists():
    conf = a.runtime/'certificate.cnf'
    conf.write_text('[req]\nprompt=no\ndistinguished_name=dn\nx509_extensions=v3\n'
        '[dn]\nCN=Kompakt Remote Personal Host\n[v3]\n'
        'basicConstraints=critical,CA:TRUE\nkeyUsage=critical,digitalSignature,keyEncipherment,keyCertSign\n'
        'extendedKeyUsage=serverAuth\nsubjectAltName=@names\n[names]\n'
        'DNS.1=localhost\nIP.1=127.0.0.1\nIP.2='+a.wifi_ip+'\n')
    subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','365',
        '-keyout',str(key),'-out',str(cert),'-config',str(conf)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
token = a.runtime/'token'
if not token.exists():
    token.write_text(secrets.token_urlsafe(32))
if a.prepare_only:
    print('Local TLS certificate and pairing credential prepared.')
    raise SystemExit()
if not a.adb or not a.apk:
    p.error('--adb and --apk are required for installation')
adb = str(a.adb.resolve())
subprocess.run([adb,'install','--no-incremental','-r',str(a.apk.resolve())],check=True)
subprocess.run([adb,'shell','am','force-stop','org.kompakt.remote'],check=True)
subprocess.run([adb,'shell','run-as','org.kompakt.remote','mkdir','-p','files'],check=True)
pairing = json.dumps({'token':token.read_text().strip(),'certificate':cert.read_text(),
    'usbUrl':'https://127.0.0.1:39443','wifiUrl':('https://'+a.wifi_ip+':39443') if a.enable_wifi else ''}).encode()
subprocess.run([adb,'shell','run-as org.kompakt.remote sh -c '+shlex.quote('cat > files/pairing.json')],input=pairing,check=True)
subprocess.run([adb,'reverse','tcp:39443','tcp:39443'],check=True)
subprocess.run([adb,'shell','am','start','-n','org.kompakt.remote/.MainActivity'],check=True)
print('Installed and paired. Open Codex on the phone.')
