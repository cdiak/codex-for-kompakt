#!/usr/bin/env python3
"""Provision pinned TLS and a companion credential through authorized USB."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import secrets
import shlex
import ssl
import subprocess
import tempfile


def tailscale_ip(value):
    address = ipaddress.ip_address(value)
    if address not in ipaddress.ip_network('100.64.0.0/10'):
        raise ValueError("Use the Mac's private Tailscale IPv4 address (100.64.0.0/10).")
    return str(address)


def certificate_covers(cert, key, address):
    """Run the phone's check in memory: pinned trust, hostname match, and expiry."""
    server = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    server.load_cert_chain(cert, key)
    client = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    client.load_verify_locations(cafile=str(cert))
    to_server, to_client = ssl.MemoryBIO(), ssl.MemoryBIO()
    ends = [client.wrap_bio(to_client, to_server, server_hostname=address),
            server.wrap_bio(to_server, to_client, server_side=True)]
    done = [False, False]
    for _ in range(20):
        for i, end in enumerate(ends):
            if not done[i]:
                try:
                    end.do_handshake()
                    done[i] = True
                except ssl.SSLWantReadError:
                    pass
                except ssl.SSLError:
                    return False
        if all(done):
            return True
    return False


def ensure_certificate(runtime, addresses, rotate=False):
    cert, key = runtime / 'server.crt', runtime / 'server.key'
    if cert.exists() and key.exists() and not rotate:
        for address in addresses:
            if not certificate_covers(cert, key, address):
                raise ValueError('Certificate has expired or does not cover the connection address. Stop the companion, then use --rotate-certificate and pair again.')
        return cert
    if (cert.exists() or key.exists()) and not rotate:
        raise ValueError('Incomplete TLS credentials. Use --rotate-certificate after stopping the companion.')
    names = 'DNS.1=localhost\n' + ''.join('IP.%d=%s\n' % (i + 1, address) for i, address in enumerate(sorted(set(addresses))))
    # Generate both files before replacing either live file. Stop the companion for rotation.
    with tempfile.TemporaryDirectory(dir=runtime) as directory:
        temp = Path(directory)
        conf = temp / 'certificate.cnf'
        conf.write_text('[req]\nprompt=no\ndistinguished_name=dn\nx509_extensions=v3\n'
            '[dn]\nCN=Codex for Kompakt Personal Host\n[v3]\n'
            'basicConstraints=critical,CA:TRUE\nkeyUsage=critical,digitalSignature,keyEncipherment,keyCertSign\n'
            'extendedKeyUsage=serverAuth\nsubjectAltName=@names\n[names]\n' + names)
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '365',
            '-keyout', str(temp / 'server.key'), '-out', str(temp / 'server.crt'), '-config', str(conf)],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for filename in ('server.key', 'server.crt'):
            os.chmod(temp / filename, 0o600)
            os.replace(temp / filename, runtime / filename)
    return cert


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--runtime', type=Path, required=True)
    p.add_argument('--remote-ip', help='Mac Tailscale IPv4; requires tailnet-only TCP Serve on port 39443')
    p.add_argument('--rotate-certificate', action='store_true', help='Stop the companion first; all paired phones must be paired again')
    p.add_argument('--adb', type=Path)
    p.add_argument('--serial', help='Explicit USB device serial if multiple devices are connected')
    p.add_argument('--apk', type=Path)
    p.add_argument('--prepare-only', action='store_true')
    p.add_argument('--no-launch', action='store_true', help='Install and pair without interrupting the foreground app')
    a = p.parse_args()
    try:
        remote = tailscale_ip(a.remote_ip) if a.remote_ip else ''
    except ValueError as error:
        p.error(str(error))
    if not a.prepare_only and (not a.adb or not a.apk):
        p.error('--adb and --apk are required for installation')
    os.umask(0o077)
    a.runtime.mkdir(parents=True, exist_ok=True)
    try:
        cert = ensure_certificate(a.runtime, ['127.0.0.1'] + ([remote] if remote else []), a.rotate_certificate)
    except ValueError as error:
        p.error(str(error))
    token = a.runtime / 'token'
    if not token.exists():
        token.write_text(secrets.token_urlsafe(32))
    if a.prepare_only:
        print('Local TLS certificate and pairing credential prepared.')
        return
    adb = [str(a.adb.resolve())] + (['-s', a.serial] if a.serial else [])
    subprocess.run(adb + ['install', '--no-incremental', '-r', str(a.apk.resolve())], check=True)
    subprocess.run(adb + ['shell', 'am', 'force-stop', 'org.kompakt.remote'], check=True)
    subprocess.run(adb + ['shell', 'run-as', 'org.kompakt.remote', 'mkdir', '-p', 'files'], check=True)
    pairing = json.dumps({'token': token.read_text().strip(), 'certificate': cert.read_text(),
        'usbUrl': 'https://127.0.0.1:39443', 'remoteUrl': ('https://' + remote + ':39443') if remote else '',
        'connectionRevision': remote or 'usb'}).encode()
    subprocess.run(adb + ['shell', 'run-as org.kompakt.remote sh -c ' + shlex.quote('cat > files/pairing.json')],
        input=pairing, check=True)
    # A remote pairing never creates or relies on an ADB tunnel.
    if not remote:
        subprocess.run(adb + ['reverse', 'tcp:39443', 'tcp:39443'], check=True)
    if not a.no_launch:
        subprocess.run(adb + ['shell', 'am', 'start', '-n', 'org.kompakt.remote/.MainActivity'], check=True)
    print('Installed and paired for ' + ('private remote access.' if remote else 'USB access.'))


if __name__ == '__main__':
    main()
