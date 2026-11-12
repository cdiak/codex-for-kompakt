import tempfile
import unittest
from pathlib import Path
from setup_pairing import certificate_covers, ensure_certificate, tailscale_ip


class PairingTests(unittest.TestCase):
    def test_remote_address_must_be_private_tailnet_ipv4(self):
        self.assertEqual(tailscale_ip('100.100.100.100'), '100.100.100.100')
        for address in ('0.0.0.0', '192.168.1.2', '8.8.8.8', '127.0.0.1', '::1', 'example.com', '100.128.0.1'):
            with self.assertRaises(ValueError):
                tailscale_ip(address)

    def test_certificate_rotation_adds_remote_without_losing_usb(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory)
            certificate = ensure_certificate(runtime, ['127.0.0.1'])
            original = certificate.read_bytes()
            ensure_certificate(runtime, ['127.0.0.1'])
            self.assertEqual(certificate.read_bytes(), original)
            with self.assertRaises(ValueError):
                ensure_certificate(runtime, ['127.0.0.1', '100.100.100.100'])
            self.assertEqual(certificate.read_bytes(), original)
            ensure_certificate(runtime, ['127.0.0.1', '100.100.100.100'], rotate=True)
            key = runtime / 'server.key'
            for hostname in ('localhost', '127.0.0.1', '100.100.100.100'):
                self.assertTrue(certificate_covers(certificate, key, hostname))
            self.assertFalse(certificate_covers(certificate, key, '100.100.100.101'))
            self.assertEqual((runtime / 'server.key').stat().st_mode & 0o777, 0o600)


if __name__ == '__main__':
    unittest.main()
