"""Real JDK keystore inspection using disposable keys; no Android SDK or release key."""
import hashlib
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_obtainium_release as release

ROOT = Path(__file__).resolve().parents[2]
JAVA = Path(os.environ['JAVA_HOME'])/'bin/java' if os.environ.get('JAVA_HOME') else Path(shutil.which('java') or '/missing-java')


def jdk17():
    if not JAVA.is_file(): return False
    result = subprocess.run([str(JAVA), '-version'], capture_output=True, text=True)
    return 'version "17.' in result.stdout + result.stderr


@unittest.skipUnless(jdk17(), 'JDK 17 required for real signing-key inspection')
class SigningProbeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='comicviewer signing keys ')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.directory = Path(cls.temporary.name)
        cls.env = {k:v for k,v in os.environ.items() if k not in release.SIGNING_ENV}
        cls.env['JAVA_HOME'] = str(JAVA.resolve().parent.parent)
        cls.keytool = Path(cls.env['JAVA_HOME'])/'bin/keytool'
        cls.fixtures = {}
        for kind in ('PKCS12', 'JKS'):
            store_secret = secrets.token_urlsafe(16) + ('ä漢字' if kind == 'JKS' else '')
            key_secret = secrets.token_urlsafe(16) + 'ö' if kind == 'JKS' else store_secret
            path = cls.directory/(kind + ' test.keystore')
            generation = dict(cls.env, PROBE_STORE_SECRET=store_secret, PROBE_KEY_SECRET=key_secret)
            cls.keytool_run(generation, '-genkeypair', '-keystore', str(path), '-storetype', kind,
                            '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048',
                            '-validity', '1', '-dname', 'CN=Disposable test key',
                            '-storepass:env', 'PROBE_STORE_SECRET', '-keypass:env', 'PROBE_KEY_SECRET')
            certificate = cls.keytool_run(generation, '-exportcert', '-keystore', str(path),
                                          '-alias', 'fixture', '-storepass:env', 'PROBE_STORE_SECRET').stdout
            cls.fixtures[kind] = (path, store_secret, key_secret, hashlib.sha256(certificate).hexdigest())

        # A certificate-only trust entry is never eligible for signing.
        certificate_path = cls.directory/'public-certificate.der'
        certificate_path.write_bytes(certificate)
        cls.trust_only = cls.directory/'trust-only.jks'
        cls.trust_secret = secrets.token_urlsafe(16)
        cls.keytool_run(dict(cls.env, PROBE_STORE_SECRET=cls.trust_secret), '-importcert', '-noprompt',
                        '-keystore', str(cls.trust_only), '-storetype', 'JKS', '-alias', 'trusted',
                        '-file', str(certificate_path), '-storepass:env', 'PROBE_STORE_SECRET')

    @classmethod
    def keytool_run(cls, env, *arguments):
        return subprocess.run([str(cls.keytool), *arguments], env=env, capture_output=True, check=True)

    def probe(self, kind, alias='fixture', key_secret=None):
        path, store_secret, actual_key, expected = self.fixtures[kind]
        before = path.read_bytes()
        entries = release.signing_probe(ROOT, self.env, path, alias, store_secret,
                                        actual_key if key_secret is None else key_secret)
        self.assertEqual(path.read_bytes(), before)
        self.assertNotIn(store_secret, str(entries)); self.assertNotIn(actual_key, str(entries))
        return entries, expected

    def test_pkcs12_reuses_store_password(self):
        entries, expected = self.probe('PKCS12')
        self.assertEqual(entries, [{'alias':'fixture', 'certificate_sha256':expected, 'key_unlocked':True}])

    def test_jks_separate_unicode_passwords(self):
        entries, expected = self.probe('JKS')
        self.assertEqual(entries, [{'alias':'fixture', 'certificate_sha256':expected, 'key_unlocked':True}])

    def test_wrong_key_password_reports_locked_without_changing_store(self):
        entries, _ = self.probe('JKS', key_secret=self.fixtures['JKS'][1])
        self.assertFalse(entries[0]['key_unlocked'])

    def test_pkcs12_wrong_key_password_is_reported_as_locked(self):
        entries, _ = self.probe('PKCS12', key_secret='wrong key secret')
        self.assertFalse(entries[0]['key_unlocked'])

    def test_discovery_and_case_insensitive_alias(self):
        entries, expected = self.probe('JKS', alias='')
        self.assertEqual(entries[0]['alias'], 'fixture')
        entries, _ = self.probe('JKS', alias='FIXTURE')
        self.assertEqual(entries, [{'alias':'FIXTURE', 'certificate_sha256':expected, 'key_unlocked':True}])

    def test_absent_alias_is_not_a_signing_key(self):
        entries, _ = self.probe('PKCS12', alias='missing')
        self.assertEqual(entries, [])

    def test_certificate_only_entry_is_not_a_signing_key(self):
        entries = release.signing_probe(ROOT, self.env, self.trust_only, '', self.trust_secret, self.trust_secret)
        self.assertEqual(entries, [])

    def test_wrong_store_password_fails_without_changing_store(self):
        path, _, key_secret, _ = self.fixtures['JKS']
        before = path.read_bytes()
        with self.assertRaisesRegex(release.ReleaseError, 'Cannot unlock or inspect'):
            release.signing_probe(ROOT, self.env, path, 'fixture', 'wrong store secret', key_secret)
        self.assertEqual(path.read_bytes(), before)


if __name__ == '__main__': unittest.main()
