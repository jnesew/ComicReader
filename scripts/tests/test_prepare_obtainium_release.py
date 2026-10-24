"""Release workflow tests with real temporary Git remotes and fake external tools."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_obtainium_release as release

SOURCE = Path(__file__).resolve().parents[2]
CERT = hashlib.sha256(b'fixture certificate').hexdigest()

# Fakes stand in for SDK/Gradle/GitHub, not production release-policy functions.
# Every Git operation still runs against a real, isolated local repository.
TOOL = r'''#!/usr/bin/env python3
import hashlib,json,os,re,subprocess,sys
from pathlib import Path
name=Path(sys.argv[0]).name
args=sys.argv[1:]
fixture=Path(os.environ['RELEASE_TEST_FIXTURE'])
with (fixture/'events.jsonl').open('a') as f:
 f.write(json.dumps({'tool':name,'args':args,'signing_env':bool(os.environ.get('COMICVIEWER_STORE_PASSWORD'))})+'\n')
if os.environ.get('FAKE_FAIL_GATE')==name:sys.exit(9)
if os.environ.get('FAKE_INTERRUPT_GATE')==name:sys.exit(130)
if name=='java':print('openjdk version "17.0.16"');sys.exit(0)
if name=='javac':print('javac 17.0.16');sys.exit(0)
if name=='keytool':
 sys.stdout.buffer.write(b'wrong alias' if os.environ.get('FAKE_WRONG_ALIAS') else b'fixture certificate');sys.exit(0)
if name=='gh':
 if os.environ.get('FAKE_NETWORK_FAILURE'):sys.exit(1)
 assert args[0]=='api' and '--hostname' in args and 'github.com' in args
 endpoint=next(x for x in args if x.startswith('repos/'))
 if '/releases/assets/' in endpoint:
  sys.stdout.buffer.write((fixture/('asset-'+endpoint.rsplit('/',1)[1]+'.json')).read_bytes())
 else:print(json.dumps([json.loads((fixture/'releases.json').read_text())]))
 sys.exit(0)
if name=='gradlew':
 if ':app:dependencies' in args:
  print('+--- unexpected:library:1.0' if os.environ.get('FAKE_RUNTIME_DEPENDENCY') else 'No dependencies')
  sys.exit(0)
 props=dict(re.findall(r'^(comicViewerVersion\w+)=(.+)$',Path('gradle.properties').read_text(),re.M))
 data={'package':'io.github.jnesew.comicviewer','version_name':props['comicViewerVersionName'],
       'version_code':int(props['comicViewerVersionCode']),'debuggable':False,'content':'fixture comic viewer'}
 if os.environ.get('FAKE_APK_VERSION'):data['version_code']=999
 if os.environ.get('FAKE_DEBUGGABLE'):data['debuggable']=True
 if os.environ.get('FAKE_PERMISSION'):data['permission']='android.permission.INTERNET'
 if os.environ.get('FAKE_REPRO_MISMATCH'):data['random']=os.urandom(8).hex()
 path=Path('app/build/outputs/apk/release/app-release-unsigned.apk');path.parent.mkdir(parents=True,exist_ok=True)
 path.write_text(json.dumps(data,sort_keys=True)+'\n')
 pending=fixture/'publish-during-build.json'
 if pending.exists():
  (fixture/'releases.json').write_bytes(pending.read_bytes());pending.unlink()
 if os.environ.get('FAKE_ADVANCE_HEAD'):subprocess.run(['git','commit','--allow-empty','-m','Concurrent change'],check=True,stdout=subprocess.DEVNULL)
 sys.exit(0)
if name=='aapt2':
 data=json.loads(Path(args[-1]).read_text())
 if args[1]=='permissions':
  if data.get('permission'):print('uses-permission-sdk-23: name=\''+data['permission']+'\'')
 else:
  print("package: name='%s' versionCode='%s' versionName='%s'" % (data['package'],data['version_code'],data['version_name']))
  if data.get('debuggable'):print('application-debuggable')
 sys.exit(0)
if name=='apksigner':
 if args[0]=='sign':
  assert 'pass:' not in ' '.join(args)
  data=json.loads(Path(args[-1]).read_text());data['certificate']='b'*64 if os.environ.get('FAKE_BAD_CERT') else hashlib.sha256(b'fixture certificate').hexdigest()
  Path(args[args.index('--out')+1]).write_text(json.dumps(data,sort_keys=True)+'\n')
  sys.exit(0)
 data=json.loads(Path(args[-1]).read_text())
 if not data.get('certificate'):
  print('Missing META-INF/MANIFEST.MF',file=sys.stderr);sys.exit(1)
 if '--print-certs' in args:print('Signer #1 certificate SHA-256 digest: '+data['certificate'])
 sys.exit(0)
if name=='apksigcopier':
 a=json.loads(Path(args[-2]).read_text());a.pop('certificate',None)
 b=json.loads(Path(args[-1]).read_text());assert a==b
 sys.exit(0)
if name=='zipalign':sys.exit(1 if os.environ.get('FAKE_ALIGNMENT_FAILURE') else 0)
assert name in ['verify-source-security.sh','verify-database-roundtrip.sh'],name
'''


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='comicviewer release test ')
        self.addCleanup(self.temp.cleanup)
        self.fixture = Path(self.temp.name)
        self.root = self.fixture / 'checkout with spaces'
        self.root.mkdir()
        self.bin = self.fixture / 'jdk/bin'
        self.bin.mkdir(parents=True)
        self.sdk = self.fixture / 'sdk with spaces'
        self.tools = self.sdk / 'build-tools/35.0.1'
        self.tools.mkdir(parents=True)
        platform = self.sdk / 'platforms/android-36'
        platform.mkdir(parents=True)
        (platform/'android.jar').write_bytes(b'fixture')
        for path in [platform, self.tools]:(path/'source.properties').write_text('Pkg.Revision=2\n')
        for name in ['java','javac','keytool','gh','apksigcopier']:self.executable(self.bin/name, TOOL)
        for name in ['aapt2','apksigner','zipalign']:self.executable(self.tools/name, TOOL)
        self.env = {k:v for k,v in os.environ.items() if k not in release.SIGNING_ENV}
        self.env.update(PATH=str(self.bin)+os.pathsep+os.environ['PATH'], JAVA_HOME=str(self.bin.parent),
                        ANDROID_HOME=str(self.sdk), ANDROID_SDK_ROOT=str(self.sdk),
                        RELEASE_TEST_FIXTURE=str(self.fixture), COMICVIEWER_EXPECTED_CERT_SHA256=CERT,
                        COMICVIEWER_KEYSTORE_PATH=str(self.root/'signing/test.jks'),
                        COMICVIEWER_KEY_ALIAS='fixture', COMICVIEWER_STORE_PASSWORD='fixture store pass',
                        COMICVIEWER_KEY_PASSWORD='fixture key pass')
        scripts=self.root/'scripts';scripts.mkdir()
        for name in ['prepare-obtainium-release.sh','prepare_obtainium_release.py','verify-release-apk.sh','verify-reproducible-build.sh']:
            shutil.copyfile(SOURCE/'scripts'/name,scripts/name);(scripts/name).chmod(0o755)
        for name in ['verify-source-security.sh','verify-database-roundtrip.sh']:self.executable(scripts/name, TOOL)
        shutil.copyfile(SOURCE/'build-release.sh',self.root/'build-release.sh');(self.root/'build-release.sh').chmod(0o755)
        self.executable(self.root/'gradlew',TOOL)
        (self.root/'gradle/wrapper').mkdir(parents=True)
        (self.root/'gradle/wrapper/gradle-wrapper.properties').write_text('distributionUrl=gradle-8.14.4-bin.zip\n')
        (self.root/'build.gradle').write_text("id 'com.android.application' version '8.13.2'\n")
        (self.root/'app').mkdir()
        (self.root/'app/build.gradle').write_text("applicationId '"+release.PACKAGE+"'\n")
        (self.root/'.gitignore').write_text('dist/\n**/build/\nsigning/\nlocal.properties\n__pycache__/\n')
        (self.root/'signing').mkdir();(self.root/'signing/test.jks').write_bytes(b'fixture')
        self.write_version('1.3.0',5)
        (self.root/'CHANGELOG.md').write_text('# Changelog\n\n## 1.3.0 - 2026-10-24\n\nPrevious changes.\n')
        self.notes('1.3.0',5)
        self.git('init','-b','main')
        self.git('config','user.name','Release test');self.git('config','user.email','release-test@example.invalid')
        self.git('config','commit.gpgsign','false');self.git('config','tag.gpgsign','false')
        self.git('config','core.hooksPath',str(self.fixture/'no-hooks'))
        self.commit('Published source')
        self.previous=self.git('rev-parse','HEAD')
        self.git('tag','-a','v1.3.0','-m','Published 1.3.0')
        remote=self.fixture/'remote.git'
        subprocess.run(['git','init','--bare','-b','main',str(remote)],check=True,capture_output=True)
        self.git('remote','add','origin','https://github.com/fixture/ComicReader.git')
        self.git('config',f'url.{remote.as_uri()}.insteadOf','https://github.com/fixture/ComicReader.git')
        self.git('push','origin','main','--tags')
        self.published('1.3.0',5,1)
        self.write_version('1.3.1',6);self.notes('1.3.1',6)
        p=self.root/'CHANGELOG.md';p.write_text(p.read_text().replace('## 1.3.0','## 1.3.1 - 2026-10-24\n\nNew changes.\n\n## 1.3.0',1))
        self.commit('Release candidate')
        self.current=self.git('rev-parse','HEAD')
        self.git('tag','v1.3.1');self.git('push','origin','main','--tags')

    def executable(self,path,content):
        path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content);path.chmod(0o755)

    def git(self,*args):
        return subprocess.run(['git',*args],cwd=self.root,env=self.env,check=True,capture_output=True,text=True).stdout.strip()

    def commit(self,message):
        self.git('add','.');self.git('commit','-m',message)

    def write_version(self,name,code):
        (self.root/'gradle.properties').write_text(f'comicViewerVersionName={name}\ncomicViewerVersionCode={code}\n')

    def notes(self,name,code):
        path=self.root/f'docs/RELEASE-NOTES-{name}.md';path.parent.mkdir(exist_ok=True)
        path.write_text(f'# ComicViewer {name}\n\nChanges for readers.\n')
        for locale in ['en-US','fi-FI']:
            path=self.root/f'fastlane/metadata/android/{locale}/changelogs/{code}.txt';path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text('Changes for readers.\n')

    def published(self,name,code,asset_id,**extra):
        payload={'package':release.PACKAGE,'version_name':name,'version_code':code,'debuggable':False,
                 'content':'fixture comic viewer','certificate':CERT}
        payload.update(extra)
        data=(json.dumps(payload,sort_keys=True)+'\n').encode()
        (self.fixture/f'asset-{asset_id}.json').write_bytes(data)
        path=self.fixture/'releases.json';items=json.loads(path.read_text()) if path.exists() else []
        items.insert(0,{'tag_name':'v'+name,'draft':False,'prerelease':'-' in name,
                        'assets':[{'name':f'ComicViewer-Android-v{name}.apk','id':asset_id,
                                   'digest':'sha256:'+hashlib.sha256(data).hexdigest()}]})
        path.write_text(json.dumps(items))

    def invoke(self,*args,ok=True):
        result=subprocess.run([str(self.root/'scripts/prepare-obtainium-release.sh'),*args],
                              cwd=self.fixture,env=self.env,capture_output=True,text=True,timeout=40)
        self.assertEqual(result.returncode==0,ok,result.stdout+'\n'+result.stderr)
        self.assertNotIn(self.env.get('COMICVIEWER_STORE_PASSWORD','unprinted secret'),result.stdout+result.stderr)
        return result

    def bundle(self,development=False):
        return self.root/'dist'/('development-v1.3.1-'+self.current[:12] if development else 'obtainium-v1.3.1')

    def events(self):
        path=self.fixture/'events.jsonl'
        return [json.loads(x) for x in path.read_text().splitlines()] if path.exists() else []

    def retag(self):
        self.commit('Adjusted candidate');self.current=self.git('rev-parse','HEAD')
        self.git('tag','-f','v1.3.1');self.git('push','--force','origin','main','refs/tags/v1.3.1')

    def test_prepare_complete_bundle(self):
        output=self.invoke().stdout
        bundle=self.bundle();manifest=json.loads((bundle/'release.json').read_text())
        self.assertTrue(manifest['publishable']);self.assertEqual(manifest['version_code'],6)
        self.assertEqual(manifest['certificate_sha256'],CERT)
        self.assertEqual(manifest['checks']['connected_android_tests'],'not_run')
        self.assertIn('--notes-file',output);self.assertNotIn('--generate-notes',output)
        for name,digest in manifest['files'].items():self.assertEqual(release.digest(bundle/name),digest)
        self.assertEqual(len(list(bundle.iterdir())),6)
        builds=[event for event in self.events() if event['tool']=='gradlew']
        self.assertTrue(builds);self.assertTrue(all(not event['signing_env'] for event in builds))

    def test_preflight_never_builds_or_signs(self):
        self.invoke('--check')
        self.assertFalse(self.bundle().exists())
        self.assertFalse(any(e['tool']=='gradlew' or (e['tool']=='apksigner' and e['args'][0]=='sign') for e in self.events()))

    def test_development_offline_unsigned_and_separate(self):
        self.env['FAKE_NETWORK_FAILURE']='1';self.env.pop('COMICVIEWER_EXPECTED_CERT_SHA256')
        self.invoke('--development')
        manifest=json.loads((self.bundle(True)/'release.json').read_text())
        self.assertFalse(manifest['publishable']);self.assertIsNone(manifest['certificate_sha256'])
        self.assertFalse(self.bundle().exists());self.assertFalse(any(e['tool']=='gh' for e in self.events()))
        self.assertTrue(any(self.bundle(True).glob('*development-unsigned.apk')))

    def test_identical_rerun_preserves_bundle(self):
        self.invoke();before={p.name:p.read_bytes() for p in self.bundle().iterdir()}
        self.invoke();self.assertEqual(before,{p.name:p.read_bytes() for p in self.bundle().iterdir()})

    def test_identical_rerun_after_publication(self):
        self.invoke();before=(self.bundle()/'release.json').read_bytes()
        self.published('1.3.1',6,2)
        self.assertIn('already published',self.invoke().stdout)
        self.assertEqual(before,(self.bundle()/'release.json').read_bytes())

    def test_conflicting_output_preserved_before_build(self):
        self.bundle().mkdir(parents=True);(self.bundle()/'keep.txt').write_text('keep')
        self.invoke(ok=False)
        self.assertEqual((self.bundle()/'keep.txt').read_text(),'keep')
        self.assertFalse(any(e['tool']=='gradlew' for e in self.events()))

    def test_modified_bundle_preserved(self):
        self.invoke();p=self.bundle()/'release-notes.md';p.write_text('Modified')
        self.invoke(ok=False);self.assertEqual(p.read_text(),'Modified')

    def test_previous_unsigned_candidate_is_preserved(self):
        path=self.root/'dist/ComicViewer-Android-v1.3.1-unsigned.apk';path.parent.mkdir()
        path.write_bytes(b'previous candidate')
        self.invoke();self.assertEqual(path.read_bytes(),b'previous candidate')

    def test_dirty_checkout_rejected(self):
        (self.root/'untracked.txt').write_text('work');self.invoke(ok=False)
        self.assertFalse(self.bundle().exists())

    def test_reused_code_rejected(self):
        self.write_version('1.3.1',5);self.retag()
        self.assertIn('exceed published code 5',self.invoke(ok=False).stderr)

    def test_reused_name_on_different_commit_rejected(self):
        self.write_version('1.3.0',5);self.retag()
        self.assertIn('another commit',self.invoke(ok=False).stderr)

    def test_published_apk_version_disagreement_rejected(self):
        path=self.fixture/'asset-1.json';data=json.loads(path.read_text());data['version_name']='1.2.0';path.write_text(json.dumps(data))
        releases=json.loads((self.fixture/'releases.json').read_text());releases[0]['assets'][0]['digest']='sha256:'+release.digest(path)
        (self.fixture/'releases.json').write_text(json.dumps(releases))
        self.assertIn('APK and tag disagree',self.invoke(ok=False).stderr)

    def test_local_test_tag_does_not_change_baseline(self):
        self.git('tag','v99.0.0');self.git('push','origin','v99.0.0');self.invoke('--check')

    def test_higher_prerelease_code_is_counted(self):
        self.published('1.3.1-rc.1',8,2)
        self.assertIn('exceed published code 8',self.invoke(ok=False).stderr)

    def test_draft_release_does_not_count(self):
        self.published('9.0.0',90,2)
        p=self.fixture/'releases.json';data=json.loads(p.read_text());data[0]['draft']=True;p.write_text(json.dumps(data))
        self.invoke('--check')

    def test_missing_published_apk_current_package_rejected(self):
        p=self.fixture/'releases.json';data=json.loads(p.read_text());data[0]['assets']=[];p.write_text(json.dumps(data))
        self.assertIn('has no APK',self.invoke(ok=False).stderr)

    def test_missing_retired_package_apk_is_allowed(self):
        self.git('checkout','--detach',self.previous)
        (self.root/'app/build.gradle').write_text("applicationId '"+release.RETIRED_PACKAGE+"'\n")
        self.commit('Retired package');self.git('tag','v1.0.0');self.git('push','origin','v1.0.0');self.git('checkout','main')
        p=self.fixture/'releases.json';data=json.loads(p.read_text());data.append({'tag_name':'v1.0.0','draft':False,'prerelease':False,'assets':[]});p.write_text(json.dumps(data))
        self.invoke('--check')

    def test_source_must_be_on_remote_main(self):
        self.git('push','--force','origin',self.previous+':refs/heads/main')
        self.assertIn('not on remote main',self.invoke(ok=False).stderr)

    def test_remote_tag_must_match(self):
        self.git('push','--force','origin',self.previous+':refs/tags/v1.3.1')
        self.assertIn('exact tag',self.invoke(ok=False).stderr)

    def test_missing_remote_tag_rejected(self):
        self.git('push','origin',':refs/tags/v1.3.1');self.assertIn('exact tag',self.invoke(ok=False).stderr)

    def test_network_failure_cannot_pass(self):
        self.env['FAKE_NETWORK_FAILURE']='1';self.invoke(ok=False);self.assertFalse(self.bundle().exists())

    def test_corrupt_cache_is_replaced_with_verified_asset(self):
        path=self.root/'dist/release-cache/1.apk';path.parent.mkdir(parents=True);path.write_bytes(b'corrupt')
        self.invoke('--check');self.assertEqual(path.read_bytes(),(self.fixture/'asset-1.json').read_bytes())

    def test_expected_certificate_required(self):
        self.env.pop('COMICVIEWER_EXPECTED_CERT_SHA256');self.assertIn('EXPECTED_CERT',self.invoke(ok=False).stderr)

    def test_certificate_must_match_published_apk(self):
        self.env['COMICVIEWER_EXPECTED_CERT_SHA256']='c'*64
        self.assertIn('latest published APK',self.invoke(ok=False).stderr)

    def test_signer_mismatch_does_not_stage(self):
        self.env['FAKE_BAD_CERT']='1';self.invoke(ok=False);self.assertFalse(self.bundle().exists())

    def test_wrong_keystore_alias_certificate_fails_before_build(self):
        self.env['FAKE_WRONG_ALIAS']='1'
        self.assertIn('wrong signing certificate',self.invoke(ok=False).stderr)
        self.assertFalse(any(e['tool']=='gradlew' for e in self.events()))

    def test_release_published_during_build_is_counted(self):
        previous=(self.fixture/'releases.json').read_bytes()
        self.published('1.4.0',7,2)
        (self.fixture/'publish-during-build.json').write_bytes((self.fixture/'releases.json').read_bytes())
        (self.fixture/'releases.json').write_bytes(previous)
        self.assertIn('exceed every published version',self.invoke(ok=False).stderr)
        self.assertFalse(self.bundle().exists())

    def test_reproducibility_failure_does_not_stage(self):
        self.env['FAKE_REPRO_MISMATCH']='1';self.invoke(ok=False);self.assertFalse(self.bundle().exists())
        self.assertFalse((self.root/'dist/.release-preparation.lock').exists())

    def test_failed_and_interrupted_checks_do_not_sign(self):
        for flag in ['FAKE_FAIL_GATE','FAKE_INTERRUPT_GATE']:
            with self.subTest(flag=flag):
                self.env[flag]='verify-database-roundtrip.sh';self.invoke(ok=False);self.env.pop(flag)
                self.assertFalse(self.bundle().exists())
        self.assertFalse(any(e['tool']=='apksigner' and e['args'][0]=='sign' for e in self.events()))

    def test_runtime_dependency_debuggable_permission_or_wrong_version_rejected(self):
        for flag in ['FAKE_RUNTIME_DEPENDENCY','FAKE_DEBUGGABLE','FAKE_PERMISSION','FAKE_APK_VERSION','FAKE_ALIGNMENT_FAILURE']:
            with self.subTest(flag=flag):
                self.env[flag]='1';self.invoke(ok=False);self.env.pop(flag);self.assertFalse(self.bundle().exists())

    def test_notes_and_both_locales_are_required(self):
        path=self.root/'fastlane/metadata/android/fi-FI/changelogs/6.txt';path.unlink();self.retag()
        self.assertIn('localized changelog',self.invoke(ok=False).stderr)

    def test_placeholder_notes_rejected(self):
        (self.root/'docs/RELEASE-NOTES-1.3.1.md').write_text('# ComicViewer 1.3.1\n\nTODO: write notes\n');self.retag()
        self.assertIn('TODO',self.invoke(ok=False).stderr)

    def test_head_change_during_build_rejected(self):
        self.env['FAKE_ADVANCE_HEAD']='1';self.assertIn('HEAD changed',self.invoke(ok=False).stderr)
        self.assertFalse(self.bundle().exists())

    def test_lock_is_not_removed_by_competing_run(self):
        lock=self.root/'dist/.release-preparation.lock';lock.mkdir(parents=True)
        self.invoke(ok=False);self.assertTrue(lock.exists())

    def test_bump_patch_creates_notes_and_localized_placeholders(self):
        self.git('checkout','--detach',self.previous)
        self.invoke('--bump','patch')
        self.assertEqual(release.read_version(self.root),('1.3.1',6))
        self.assertIn('TODO',(self.root/'fastlane/metadata/android/fi-FI/changelogs/6.txt').read_text())
        with self.assertRaisesRegex(release.ReleaseError,'TODO'):release.validate_notes(self.root,'1.3.1',6)

    def test_bump_minor_and_major_reset_lower_components(self):
        for kind,expected in [('minor','1.4.0'),('major','2.0.0')]:
            with self.subTest(kind=kind):
                self.git('reset','--hard',self.previous);self.git('clean','-fd')
                self.invoke('--bump',kind);self.assertEqual(release.read_version(self.root),(expected,6))

    def test_bump_does_not_overwrite_existing_notes(self):
        self.git('checkout','--detach',self.previous)
        path=self.root/'fastlane/metadata/android/fi-FI/changelogs/6.txt';path.write_text('Human notes');self.commit('Draft notes')
        self.invoke('--bump','patch',ok=False)
        self.assertEqual(release.read_version(self.root),('1.3.0',5));self.assertEqual(path.read_text(),'Human notes')
        self.assertFalse((self.root/'fastlane/metadata/android/en-US/changelogs/6.txt').exists())

    def test_signing_properties_supported_without_exposing_values(self):
        for key in release.SIGNING_ENV:self.env.pop(key,None)
        config={'storeFile':'signing/test.jks','keyAlias':'fixture','storePassword':'fixture store pass','keyPassword':'fixture key pass'}
        (self.root/'signing/keystore.properties').write_text('\n'.join(k+'='+v for k,v in config.items()))
        self.invoke()

    def test_wrong_jdk_fails_preflight(self):
        self.executable(self.bin/'java','#!/bin/sh\necho \'openjdk version "27"\'\n')
        self.assertIn('JDK 17',self.invoke('--check',ok=False).stderr)

    def test_duplicate_version_property_rejected(self):
        p=self.root/'gradle.properties';p.write_text(p.read_text()+'comicViewerVersionCode=7\n');self.retag()
        self.assertIn('Duplicate property',self.invoke(ok=False).stderr)


class ParsingTests(unittest.TestCase):
    def test_sdk_properties_allow_java_last_value_precedence(self):
        self.assertEqual(release.properties('Pkg.UserSrc=false\nPkg.UserSrc=true\n'), {'Pkg.UserSrc':'true'})

    def test_properties_escapes_and_continuation(self):
        self.assertEqual(release.properties('sdk.dir = /path/with\\ spaces\nkey\\:name:value\\\n  continued\nuni=\\u00e4\n'),
                         {'sdk.dir':'/path/with spaces','key:name':'valuecontinued','uni':'ä'})

    def test_numeric_semantic_version_order(self):
        self.assertGreater(release.stable_version('1.10.0'),release.stable_version('1.9.9'))
        self.assertGreater((release.stable_version('1.4.0'),True),release.published_version('1.4.0-rc.1'))
        for value in ['1.02.3','1.3','1.3.0-rc.1','v1.3.0']:
            with self.assertRaises(release.ReleaseError):release.stable_version(value)


if __name__=='__main__':unittest.main()
