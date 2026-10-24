"""Release workflow tests with real temporary Git remotes and fake external tools."""
import hashlib
import json
import os
import pty
import select
import signal
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
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
 f.write(json.dumps({'tool':name,'args':args,'signing_env':any(os.environ.get(k) for k in ['COMICVIEWER_STORE_PASSWORD','COMICVIEWER_KEY_PASSWORD'])})+'\n')
if os.environ.get('FAKE_FAIL_GATE')==name:sys.exit(9)
if os.environ.get('FAKE_INTERRUPT_GATE')==name:sys.exit(130)
if name=='java':
 if args==['-version']:print('openjdk version "17.0.16"');sys.exit(0)
 assert args[:2]==['--source','17'] and Path(args[2]).name=='SigningKeyProbe.java'
 lines=sys.stdin.read().splitlines()
 if len(lines)!=2 or lines[0]!='fixture store pass':sys.exit(1)
 expected='fixture key pass' if os.environ.get('FAKE_SEPARATE_KEY') else 'fixture store pass'
 names=json.loads(os.environ.get('FAKE_ALIASES','["fixture"]'))
 alias=args[-1]
 names=[alias] if alias and any(n.casefold()==alias.casefold() for n in names) else ([] if alias else names)
 cert=hashlib.sha256(b'fixture certificate').hexdigest()
 if os.environ.get('FAKE_WRONG_ALIAS') or (os.environ.get('FAKE_CHANGED_KEY') and lines[1]==expected):cert='b'*64
 print(json.dumps({'entries':[{'alias':n,'certificate_sha256':cert,'key_unlocked':lines[1]==expected} for n in names]}))
 sys.exit(0)
if name=='javac':print('javac 17.0.16');sys.exit(0)
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
  assert args[args.index('--ks-pass')+1]=='stdin' and args[args.index('--key-pass')+1]=='stdin'
  lines=sys.stdin.read().splitlines();assert len(lines)==2 and lines[0]=='fixture store pass'
  assert lines[1] in ['fixture key pass','fixture store pass']
  with (fixture/'events.jsonl').open('a') as f:
   f.write(json.dumps({'tool':'password-input','reused':lines[0]==lines[1]})+'\n')
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
        self.env = {k:v for k,v in os.environ.items() if k not in release.SIGNING_ENV | {'COMICVIEWER_EXPECTED_CERT_SHA256'}}
        self.env.update(PATH=str(self.bin)+os.pathsep+os.environ['PATH'], JAVA_HOME=str(self.bin.parent),
                        ANDROID_HOME=str(self.sdk), ANDROID_SDK_ROOT=str(self.sdk),
                        RELEASE_TEST_FIXTURE=str(self.fixture),
                        COMICVIEWER_KEYSTORE_PATH=str(self.root/'signing/test.jks'),
                        COMICVIEWER_KEY_ALIAS='fixture', COMICVIEWER_STORE_PASSWORD='fixture store pass',
                        COMICVIEWER_KEY_PASSWORD='fixture store pass')
        scripts=self.root/'scripts';scripts.mkdir()
        for name in ['prepare-obtainium-release.sh','prepare_obtainium_release.py','verify-release-apk.sh','verify-reproducible-build.sh','SigningKeyProbe.java']:
            shutil.copyfile(SOURCE/'scripts'/name,scripts/name);(scripts/name).chmod(0o755)
        (self.root/release.CERTIFICATE_PIN).write_text(CERT+'\n')
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

    def invoke(self,*args,ok=True,non_interactive=True):
        if non_interactive:args=(*args,'--non-interactive')
        result=subprocess.run([str(self.root/'scripts/prepare-obtainium-release.sh'),*args],
                              cwd=self.fixture,env=self.env,capture_output=True,text=True,timeout=40)
        self.assertEqual(result.returncode==0,ok,result.stdout+'\n'+result.stderr)
        self.assertNotIn(self.env.get('COMICVIEWER_STORE_PASSWORD','unprinted secret'),result.stdout+result.stderr)
        return result

    def interactive(self,answers,ok=True,args=(),prompts=None):
        master,slave=pty.openpty()
        process=subprocess.Popen([str(self.root/'scripts/prepare-obtainium-release.sh'),*args],
                                 cwd=self.fixture,env=self.env,stdin=slave,stdout=slave,stderr=slave,
                                 start_new_session=True)
        os.close(slave)
        output=bytearray();deadline=time.monotonic()+40;sent=0
        prompts=prompts or [b'Keystore password: ',b'Key password (different from keystore): ']
        try:
            while time.monotonic()<deadline:
                if select.select([master],[],[],0.1)[0]:
                    try:chunk=os.read(master,65536)
                    except OSError:break  # Linux PTYs report EIO when their child exits.
                    if not chunk:break
                    output.extend(chunk)
                    if sent<len(answers) and prompts[sent] in output:
                        if answers[sent]==b'\x03':os.killpg(process.pid,signal.SIGINT)
                        else:os.write(master,answers[sent])
                        sent+=1
                elif process.poll() is not None:break
            process.wait(timeout=5)
        finally:
            if process.poll() is None:process.kill();process.wait()
            os.close(master)
        text=output.decode('utf-8',errors='replace')
        self.assertEqual(process.returncode==0,ok,text)
        self.assertEqual(sent,len(answers),text)
        self.assertNotIn('fixture store pass',text)
        self.assertNotIn('fixture key pass',text)
        return text

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
        self.env['FAKE_NETWORK_FAILURE']='1'
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

    def test_tracked_certificate_pin_required(self):
        (self.root/release.CERTIFICATE_PIN).unlink();self.retag()
        self.assertIn('Missing tracked release certificate',self.invoke(ok=False).stderr)

    def test_certificate_must_match_published_apk(self):
        (self.root/release.CERTIFICATE_PIN).write_text('c'*64+'\n');self.retag()
        self.assertIn('latest published APK',self.invoke(ok=False).stderr)

    def test_old_certificate_environment_cannot_override_pin(self):
        self.env['COMICVIEWER_EXPECTED_CERT_SHA256']='c'*64
        self.assertIn('disagrees with the tracked certificate pin',self.invoke(ok=False).stderr)

    def test_matching_old_certificate_environment_remains_compatible(self):
        self.env['COMICVIEWER_EXPECTED_CERT_SHA256']=CERT.upper()
        self.invoke('--check')

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
        self.invoke('--bump','patch',non_interactive=False)
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

    def test_nonsecret_signing_properties_supported(self):
        for key in release.SIGNING_ENV-release.PASSWORD_ENV:self.env.pop(key,None)
        config={'storeFile':'signing/test.jks','keyAlias':'fixture'}
        (self.root/'signing/keystore.properties').write_text('\n'.join(k+'='+v for k,v in config.items()))
        self.invoke()

    def test_default_requires_terminal_even_with_environment_passwords(self):
        result=self.invoke('--check',ok=False,non_interactive=False)
        self.assertIn('requires a terminal',result.stderr)
        self.assertFalse(any(e['tool']=='gradlew' for e in self.events()))

    def test_stored_passwords_are_not_accepted_in_automation(self):
        for key in release.PASSWORD_ENV:self.env.pop(key,None)
        config={'storeFile':'signing/test.jks','keyAlias':'fixture','storePassword':'stored pass','keyPassword':'stored key'}
        (self.root/'signing/keystore.properties').write_text('\n'.join(k+'='+v for k,v in config.items()))
        self.assertIn('requires COMICVIEWER_STORE_PASSWORD',self.invoke('--check',ok=False).stderr)

    def test_interactive_passwords_hidden_and_reused(self):
        for key in release.PASSWORD_ENV:self.env[key]='ignored environment password'
        text=self.interactive([b'fixture store pass\n'])
        self.assertNotIn('Key password',text)
        self.assertTrue(self.bundle().is_dir())
        events=self.events()
        self.assertTrue(any(e['tool']=='password-input' and e['reused'] for e in events))
        self.assertTrue(all(not e.get('signing_env',False) for e in events))
        for path in self.bundle().iterdir():
            self.assertNotIn(b'fixture store pass',path.read_bytes())

    def test_interactive_separate_key_password(self):
        self.env['FAKE_SEPARATE_KEY']='1'
        self.interactive([b'fixture store pass\n',b'fixture key pass\n'])
        self.assertTrue(any(e['tool']=='password-input' and not e['reused'] for e in self.events()))

    def test_wrong_store_password_stops_before_build(self):
        self.env['COMICVIEWER_STORE_PASSWORD']='wrong store secret'
        self.assertIn('Cannot unlock or inspect',self.invoke(ok=False).stderr)
        self.assertFalse(any(e['tool']=='gradlew' for e in self.events()))

    def test_noninteractive_separate_key_needs_correct_password_before_build(self):
        self.env['FAKE_SEPARATE_KEY']='1'
        self.env.pop('COMICVIEWER_KEY_PASSWORD')
        self.assertIn('COMICVIEWER_KEY_PASSWORD',self.invoke(ok=False).stderr)
        self.assertFalse(any(e['tool']=='gradlew' for e in self.events()))
        self.env['COMICVIEWER_KEY_PASSWORD']='fixture key pass'
        self.invoke('--check')

    def test_interactive_wrong_key_password_stops_before_build(self):
        self.env['FAKE_SEPARATE_KEY']='1'
        text=self.interactive([b'fixture store pass\n',b'wrong key secret\n'],ok=False)
        self.assertNotIn('wrong key secret',text)
        self.assertIn('Cannot unlock the private key',text)
        self.assertFalse(any(e['tool']=='gradlew' for e in self.events()))

    def test_key_identity_must_survive_password_retry(self):
        self.env.update(FAKE_SEPARATE_KEY='1',FAKE_CHANGED_KEY='1')
        self.interactive([b'fixture store pass\n',b'fixture key pass\n'],ok=False)
        self.assertFalse(any(e['tool']=='gradlew' for e in self.events()))

    def test_setup_discovers_alias_saves_only_metadata_and_supports_next_release(self):
        self.env.pop('COMICVIEWER_KEY_ALIAS')
        self.env['FAKE_NETWORK_FAILURE']='1'
        store=self.root/'signing/test.jks';original=store.read_bytes()
        self.invoke('--setup')
        path=self.root/release.LOCAL_SIGNING
        self.assertEqual(json.loads(path.read_text()),{'keystore':str(store),'alias':'fixture'})
        self.assertEqual(path.stat().st_mode & 0o777,0o600)
        self.assertEqual(store.read_bytes(),original)
        self.assertEqual(self.git('status','--porcelain'),'')
        self.assertFalse(any(e['tool'] in ['gh','gradlew','apksigner'] for e in self.events()))
        for key in release.PASSWORD_ENV:self.assertNotIn(self.env.get(key,'unused'),path.read_text())
        self.env.pop('FAKE_NETWORK_FAILURE');self.env.pop('COMICVIEWER_KEYSTORE_PATH')
        self.invoke()

    def test_interactive_setup_accepts_path_with_no_existing_configuration(self):
        self.env.pop('COMICVIEWER_KEYSTORE_PATH');self.env.pop('COMICVIEWER_KEY_ALIAS')
        self.interactive([b'signing/test.jks\n',b'fixture store pass\n'],args=('--setup',),
                         prompts=[b'Keystore path: ',b'Keystore password: '])
        self.assertEqual(json.loads((self.root/release.LOCAL_SIGNING).read_text())['alias'],'fixture')

    def test_interactive_setup_selects_between_matching_aliases(self):
        self.env.pop('COMICVIEWER_KEY_ALIAS');self.env['FAKE_ALIASES']='["first", "second"]'
        self.interactive([b'\n',b'fixture store pass\n',b'2\n'],args=('--setup',),
                         prompts=[b'Keystore path',b'Keystore password: ',b'Signing alias number: '])
        self.assertEqual(json.loads((self.root/release.LOCAL_SIGNING).read_text())['alias'],'second')

    def test_ambiguous_unattended_setup_preserves_existing_settings(self):
        self.invoke('--setup')
        path=self.root/release.LOCAL_SIGNING;before=path.read_bytes()
        self.env.pop('COMICVIEWER_KEY_ALIAS');self.env['FAKE_ALIASES']='["first", "second"]'
        self.assertIn('Multiple signing aliases',self.invoke('--setup',ok=False).stderr)
        self.assertEqual(path.read_bytes(),before)
        self.assertFalse(list(path.parent.glob('.release.local-*')))

    def test_setup_wrong_certificate_never_saves_settings(self):
        self.env['FAKE_WRONG_ALIAS']='1'
        self.invoke('--setup',ok=False)
        self.assertFalse((self.root/release.LOCAL_SIGNING).exists())

    def test_setup_with_no_private_key_never_saves_settings(self):
        self.env.pop('COMICVIEWER_KEY_ALIAS');self.env['FAKE_ALIASES']='[]'
        self.assertIn('No private signing key',self.invoke('--setup',ok=False).stderr)
        self.assertFalse((self.root/release.LOCAL_SIGNING).exists())

    def test_setup_with_separate_key_password_saves_no_secrets(self):
        self.env['FAKE_SEPARATE_KEY']='1'
        self.interactive([b'\n',b'fixture store pass\n',b'fixture key pass\n'],args=('--setup',),
                         prompts=[b'Keystore path',b'Keystore password: ',b'Key password (different from keystore): '])
        text=(self.root/release.LOCAL_SIGNING).read_text()
        self.assertNotIn('fixture store pass',text);self.assertNotIn('fixture key pass',text)

    def test_setup_preserves_legacy_properties(self):
        path=self.root/'signing/keystore.properties'
        config={'storeFile':'signing/test.jks','keyAlias':'fixture','storePassword':'legacy secret'}
        path.write_text('\n'.join(k+'='+v for k,v in config.items()))
        before=path.read_bytes()
        self.invoke('--setup')
        self.assertEqual(path.read_bytes(),before)

    def test_local_metadata_overrides_legacy_but_explicit_properties_take_precedence(self):
        self.invoke('--setup')
        for key in ['COMICVIEWER_KEYSTORE_PATH','COMICVIEWER_KEY_ALIAS']:self.env.pop(key)
        legacy=self.root/'signing/keystore.properties'
        legacy.write_text('storeFile=does-not-exist.jks\nkeyAlias=wrong\n')
        self.invoke('--check')
        local=self.root/release.LOCAL_SIGNING
        local.write_text(json.dumps({'keystore':'wrong.jks','alias':'wrong'}))
        legacy.write_text('storeFile=signing/test.jks\nkeyAlias=fixture\n')
        self.env['COMICVIEWER_SIGNING_PROPERTIES']=str(legacy)
        self.invoke('--check')

    def test_setup_refuses_settings_with_extra_fields(self):
        path=self.root/release.LOCAL_SIGNING
        path.write_text(json.dumps({'keystore':'signing/test.jks','alias':'fixture','password':'keep private'}))
        before=path.read_bytes();self.invoke('--setup',ok=False)
        self.assertEqual(path.read_bytes(),before)

    def test_setup_refuses_symlink_settings(self):
        target=self.fixture/'other.json';target.write_text(json.dumps({'keystore':'signing/test.jks','alias':'fixture'}))
        (self.root/release.LOCAL_SIGNING).symlink_to(target)
        before=target.read_bytes();self.invoke('--setup',ok=False)
        self.assertEqual(target.read_bytes(),before)

    def test_setup_refuses_tracked_settings(self):
        path=self.root/release.LOCAL_SIGNING
        path.write_text(json.dumps({'keystore':'signing/test.jks','alias':'fixture'}))
        self.git('add','-f',str(release.LOCAL_SIGNING));self.git('commit','-m','Tracked fixture metadata')
        self.assertIn('Git-ignored and untracked',self.invoke('--setup',ok=False).stderr)

    def test_published_certificate_is_checked_again_after_build(self):
        previous=(self.fixture/'releases.json').read_bytes()
        self.published('1.3.1',6,2,certificate='c'*64)
        (self.fixture/'publish-during-build.json').write_bytes((self.fixture/'releases.json').read_bytes())
        (self.fixture/'releases.json').write_bytes(previous)
        self.assertIn('latest published APK',self.invoke(ok=False).stderr)
        self.assertFalse(self.bundle().exists())

    def test_interactive_empty_password_cancels_without_building(self):
        self.interactive([b'\n'],ok=False)
        self.assertFalse(self.bundle().exists())
        self.assertFalse(any(e['tool']=='gradlew' for e in self.events()))

    def test_interactive_eof_does_not_fall_back_to_echoed_input(self):
        self.interactive([b'\x04'],ok=False)
        self.assertFalse(self.bundle().exists())

    def test_interactive_interrupt_does_not_build(self):
        self.interactive([b'\x03'],ok=False)
        self.assertFalse(self.bundle().exists())
        self.assertFalse(any(e['tool']=='gradlew' for e in self.events()))

    def test_development_preflight_needs_no_terminal_or_password(self):
        for key in release.PASSWORD_ENV:self.env.pop(key,None)
        self.invoke('--check','--development',non_interactive=False)

    def test_automation_key_password_can_reuse_store_password(self):
        self.env.pop('COMICVIEWER_KEY_PASSWORD');self.invoke()
        self.assertTrue(any(e['tool']=='password-input' and e['reused'] for e in self.events()))

    def test_multiline_environment_password_rejected(self):
        self.env['COMICVIEWER_STORE_PASSWORD']='fixture store pass\nsecond line'
        self.assertIn('single-line',self.invoke('--check',ok=False).stderr)

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
