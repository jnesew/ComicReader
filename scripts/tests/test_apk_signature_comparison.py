"""Real SDK signature comparison with disposable RSA keys and tiny APK fixtures."""
import hashlib
import importlib.util
import os
from pathlib import Path
import secrets
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SDK = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '/missing-sdk')
TOOLS = SDK / 'build-tools/35.0.1'
JAVA = Path(os.environ['JAVA_HOME'])/'bin/java' if os.environ.get('JAVA_HOME') else Path(shutil.which('java') or '/missing-java')
PAYLOAD = b'comicviewer signature comparison fixture'


def prerequisites():
    for path in [TOOLS/'apksigner', TOOLS/'aapt2', TOOLS/'zipalign', SDK/'platforms/android-36/android.jar', JAVA]:
        if not path.is_file():
            return False
    if importlib.util.find_spec('apksigcopier') is None:
        return False
    java = subprocess.run([str(JAVA), '-version'], capture_output=True, text=True)
    return 'version "17.' in java.stdout + java.stderr


AVAILABLE = prerequisites()
if os.environ.get('COMICVIEWER_REQUIRE_SIGNING_TESTS') == '1' and not AVAILABLE:
    raise RuntimeError('Real signing tests require JDK 17, SDK Platform 36, Build Tools 35.0.1, and the apksigcopier Python module.')


@unittest.skipUnless(AVAILABLE, 'JDK 17, pinned Android SDK, and apksigcopier required')
class SignatureComparisonTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='comicviewer signature comparison ')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.directory = Path(cls.temp.name)
        cls.env = dict(os.environ, JAVA_HOME=str(JAVA.resolve().parent.parent), ANDROID_HOME=str(SDK))
        cls.env['PATH'] = str(JAVA.resolve().parent) + os.pathsep + cls.env['PATH']
        manifest = cls.directory/'AndroidManifest.xml'
        manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="io.github.jnesew.signaturefixture" android:versionCode="1" android:versionName="1.0">
            <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
            <application android:hasCode="false"/>
            </manifest>''')
        base = cls.directory/'base.apk'
        cls.command([TOOLS/'aapt2', 'link', '-I', SDK/'platforms/android-36/android.jar',
                     '--manifest', manifest, '-o', base])
        cls.unsigned = cls.make_unsigned(base, 'original', PAYLOAD)
        cls.changed = cls.make_unsigned(base, 'changed', PAYLOAD.upper())
        cls.signed = {}
        for bits in (2048, 4096):
            secret = secrets.token_urlsafe(24)
            keystore = cls.directory/f'disposable-{bits}.p12'
            keytool = JAVA.resolve().parent/'keytool'
            cls.command([keytool, '-genkeypair', '-storetype', 'PKCS12', '-keystore', keystore,
                         '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', str(bits), '-validity', '1',
                         '-dname', 'CN=Disposable signature comparison test',
                         '-storepass:env', 'FIXTURE_SECRET', '-keypass:env', 'FIXTURE_SECRET'],
                        env=dict(cls.env, FIXTURE_SECRET=secret))
            cert = cls.command([keytool, '-exportcert', '-keystore', keystore, '-alias', 'fixture',
                                '-storepass:env', 'FIXTURE_SECRET'], env=dict(cls.env, FIXTURE_SECRET=secret)).stdout
            signed = cls.directory/f'RSA-{bits}.apk'
            cls.command([TOOLS/'apksigner', 'sign', '--alignment-preserved', 'true',
                         '--v4-signing-enabled', 'false', '--ks', keystore, '--ks-key-alias', 'fixture',
                         '--ks-pass', 'stdin', '--key-pass', 'stdin', '--out', signed, cls.unsigned],
                        input=(secret+'\n'+secret+'\n').encode())
            cls.command([TOOLS/'apksigner', 'verify', signed])
            cls.signed[bits] = signed, hashlib.sha256(cert).hexdigest()

    @classmethod
    def command(cls, args, **kwargs):
        return subprocess.run([str(arg) for arg in args], env=kwargs.pop('env', cls.env),
                              capture_output=True, check=True, **kwargs)

    @classmethod
    def make_unsigned(cls, base, name, payload):
        unaligned = cls.directory/(name+'-unaligned.apk')
        shutil.copyfile(base, unaligned)
        with zipfile.ZipFile(unaligned, 'a') as archive:
            archive.writestr('assets/payload.txt', payload)
            archive.writestr('assets/ää漢字.txt', b'Unicode filename')
        aligned = cls.directory/(name+'-unsigned.apk')
        cls.command([TOOLS/'zipalign', '-P', '16', '4', unaligned, aligned])
        return aligned

    def verify(self, bits=2048, *, signed=None, unsigned=None, cert=None, ok=True):
        original, expected = self.signed[bits]
        signed, unsigned = signed or original, unsigned or self.unsigned
        before = [path.read_bytes() for path in (signed, unsigned)]
        result = subprocess.run([str(ROOT/'scripts/verify-release-apk.sh'), str(signed),
                                 str(unsigned), cert or expected], env=self.env,
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode == 0, ok, result.stdout + result.stderr)
        self.assertEqual(before, [path.read_bytes() for path in (signed, unsigned)])
        return result

    def test_sha256_signature_roundtrip(self):
        self.verify(2048)

    def test_sha512_signature_roundtrip(self):
        self.verify(4096)

    def test_copied_headers_and_signature_match_original_bytes(self):
        sys.path.insert(0, str(ROOT/'scripts'))
        self.addCleanup(sys.path.remove, str(ROOT/'scripts'))
        from compare_apk_signatures import load_copier
        copier = load_copier()
        for bits, (signed, _) in self.signed.items():
            with self.subTest(bits=bits):
                copied = self.directory/f'copied-{bits}.apk'
                copier.do_copy(str(signed), str(self.unsigned), str(copied), copier.AUTO,
                               exclude=copier.exclude_default)
                self.assertEqual(signed.read_bytes(), copied.read_bytes())
                with zipfile.ZipFile(copied) as archive:
                    infos = [info for info in archive.infolist() if info.filename.startswith('META-INF/')]
                    self.assertEqual(len(infos), 3)
                    for info in infos:
                        self.assertTrue(info.flag_bits & 0x800)
                        # Check the local header too, not just zipfile's central directory.
                        flags = struct.unpack_from('<H', copied.read_bytes(), info.header_offset + 6)[0]
                        self.assertTrue(flags & 0x800)

    def test_changed_rebuild_is_rejected_for_both_digests(self):
        for bits in self.signed:
            with self.subTest(bits=bits):
                result = self.verify(bits, unsigned=self.changed, ok=False)
                self.assertIn('digest mismatch', result.stderr)

    def test_corrupt_original_is_rejected(self):
        original, _ = self.signed[4096]
        data = original.read_bytes()
        self.assertEqual(data.count(PAYLOAD), 1)
        corrupt = self.directory/'corrupted.apk'
        corrupt.write_bytes(data.replace(PAYLOAD, PAYLOAD.upper()))
        self.assertIn('digest mismatch', self.verify(4096, signed=corrupt, ok=False).stderr)

    def test_wrong_certificate_is_rejected(self):
        self.assertIn('Signing certificate mismatch', self.verify(cert='0'*64, ok=False).stderr)


if __name__ == '__main__':
    unittest.main()
