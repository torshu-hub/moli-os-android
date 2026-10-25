"""Deterministic policy, provenance and isolation tests. No network or owner credentials."""
import copy
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.request
import zipfile
import yaml
import policy as P
import protocol as G
import delivery_gate as D
import bundle as B
import publish as R

ROOT=Path(__file__).resolve().parents[2]
HEAD='a'*40
ENTRY={'path':'android/app/src/main/java/io/moli/android/MainActivity.java','mode':'100644','type':'blob','sha':'b'*40}
def run(**kw):
    return dict(dict(id=100,run_attempt=1,head_sha=HEAD,head_repository={'full_name':P.REPOSITORY},repository={'full_name':P.REPOSITORY},status='completed',conclusion='success',actor={'id':1},triggering_actor={'id':1},event='push',head_branch=P.READY_BRANCH,path='.github/workflows/android-integrations.yml',name='Android profile import'),**kw)

def fixture(reused=False):
    plan=P.select(['android/app/src/main/java/io/moli/android/MainActivity.java'])
    if reused:plan.update(rust=False,web=False,image=False,update=False,classes=[])
    plan.update(candidate=True,source_sha=HEAD,previous=P.BOOTSTRAP,reused={'source_sha':P.PROOF_SHA,'run_id':P.PROOF_RUN} if reused else None)
    reports={name:b'OK (1 test)\n' for name in B.expected_reports(plan)}
    manifest={'format':1,'repository':P.REPOSITORY,'source_sha':HEAD,'run_id':100,'run_attempt':1,'runtime_sha256':P.fingerprint([ENTRY]),'toolchains':P.TOOLCHAINS,'previous_source_sha':P.BOOTSTRAP['source_sha'],'metadata':{'applicationId':P.IDENTITY,'versionCode':18,'versionName':'0.14.1-beta.1'},'plan':plan,'unsigned_sha256':G.digest(b'fictional-apk'),'proofs':{n:G.digest(b) for n,b in reports.items()},'jvm_tests':0 if reused else 24}
    return manifest,plan,{'unsigned.apk':b'fictional-apk',**{'proof/'+n:b for n,b in reports.items()}}
def archive(manifest,files):
    result=io.BytesIO()
    with zipfile.ZipFile(result,'w') as z:
        for name,data in dict(files,**{'candidate.json':json.dumps(manifest).encode()}).items():z.writestr(name,data)
    return result.getvalue()
def artifact(data):return {'expired':False,'digest':'sha256:'+G.digest(data),'workflow_run':{'head_sha':HEAD,'id':100}}

class PolicyTest(unittest.TestCase):
    def test_orchestration_only(self):
        p=P.select(['tools/android-ci/policy.py','.github/workflows/android-integrations.yml','docs/components/android-updates.md'])
        self.assertTrue(p['pipeline']);self.assertFalse(any(p[k] for k in ['rust','web','image','update']));self.assertEqual(p['classes'],[])
    def test_ui_targeted(self):
        p=P.select([ENTRY['path']]);self.assertFalse(p['rust']);self.assertFalse(p['update']);self.assertIn('EngineDiagnosticsTest',p['classes'])
    def test_full_storage_security_and_unknown(self):
        for name in ['android/app/src/main/java/io/moli/android/BackupArchive.java','android/app/build.gradle','Cargo.lock','crates/moli-android/src/lib.rs','unknown-build-input']:
            with self.subTest(name=name):
                p=P.select([name]);self.assertTrue(p['update']);self.assertEqual(p['classes'],P.ALL_CLASSES)
    def test_runtime_fingerprint_ignores_only_orchestration_and_docs(self):
        self.assertEqual(P.fingerprint([ENTRY]),P.fingerprint([ENTRY,dict(ENTRY,path='tools/android-ci/x.py')]))
        for path in ['android/app/src/androidTest/X.java','android/app/build.gradle','Cargo.lock','scripts/build-android.sh','unrecognized-input']:
            with self.subTest(path=path):self.assertNotEqual(P.fingerprint([ENTRY]),P.fingerprint([ENTRY,dict(ENTRY,path=path)]))
    def test_metadata_strict(self):
        base={'applicationId':P.IDENTITY,'versionCode':18,'versionName':'0.14.1-beta.1'}
        self.assertEqual(P.candidate_metadata(base,P.BOOTSTRAP)['versionCode'],18)
        for value in [dict(base,versionCode=17),dict(base,versionCode='018'),dict(base,versionCode=2100000001),dict(base,applicationId='example.test'),dict(base,versionName='x\noutput=bad'),dict(base,other='x')]:
            with self.subTest(value=value),self.assertRaises(ValueError):P.candidate_metadata(value,P.BOOTSTRAP)
    def test_duplicate_properties(self):
        with self.assertRaises(ValueError):P.properties('versionCode=18\nversionCode=19')

class ProvenanceTest(unittest.TestCase):
    def test_run_owner_repository_source_and_status(self):
        G.check_run(run(),HEAD,1,candidate=True)
        for changes in [{'head_sha':'b'*40},{'conclusion':'failure'},{'status':'in_progress'},{'actor':{'id':2}},{'triggering_actor':{'id':2}},{'repository':{'full_name':'fork/repo'}},{'head_repository':{'full_name':'fork/repo'}},{'event':'pull_request'},{'head_branch':'main'},{'path':'.github/workflows/spoof.yml'}]:
            with self.subTest(changes=changes),self.assertRaises(ValueError):G.check_run(run(**changes),HEAD,1,candidate=True)
    def test_latest_check_cannot_be_shadowed_by_same_name_elsewhere(self):
        rows=[run(id=5,conclusion='success'),run(id=6,conclusion='failure'),run(id=7,path='.github/workflows/spoof.yml'),run(id=8,name='Android home and integrations')]
        self.assertEqual(G.latest_checks(rows)['Android profile import']['conclusion'],'failure')
    def test_redirect_does_not_forward_token(self):
        req=urllib.request.Request('https://api.github.com/test',headers={'Authorization':'Bearer fake'})
        redirected=G.StripAuthRedirect().redirect_request(req,None,302,'',{},'https://storage.example.test/signed')
        self.assertIsNone(redirected.get_header('Authorization'))
        with self.assertRaises(ValueError):G.StripAuthRedirect().redirect_request(req,None,302,'',{},'http://insecure.example.test')
    def test_reused_evidence_needs_equal_runtime_and_success(self):
        with patch.object(G,'tree',return_value=[ENTRY]),patch.object(G,'api',return_value=run(head_sha=P.PROOF_SHA)):
            self.assertEqual(G.reusable_proof(HEAD,1)['run_id'],P.PROOF_RUN)
        with patch.object(G,'tree',side_effect=[[ENTRY],[]]),patch.object(G,'api') as query:
            self.assertIsNone(G.reusable_proof(HEAD,1));query.assert_not_called()
        with patch.object(G,'tree',return_value=[ENTRY]),patch.object(G,'api',return_value=run(head_sha=P.PROOF_SHA,conclusion='failure')):
            with self.assertRaises(ValueError):G.reusable_proof(HEAD,1)
    def test_previous_ledger_is_monotonic_and_pinned(self):
        ledger=dict(P.BOOTSTRAP,versionCode=18,source_sha=HEAD)
        release={'tag_name':'moli-beta-code-18','draft':False,'body':json.dumps(ledger)}
        with patch.object(G,'api',side_effect=[[release],{'object':{'type':'commit','sha':HEAD}}]):self.assertEqual(G.previous_release()['versionCode'],18)
        with patch.object(G,'api',side_effect=[[release],{'object':{'type':'commit','sha':'c'*40}}]):
            with self.assertRaises(ValueError):G.previous_release()

class ArtifactTest(unittest.TestCase):
    def check(self,manifest,plan,files,receipt=None):
        data=archive(manifest,files)
        return D.verify_bundle(data,artifact(data) if receipt is None else receipt,run(),P.BOOTSTRAP,[ENTRY],plan)
    def test_normal_and_reused_evidence(self):
        for reused in [False,True]:
            m,p,f=fixture(reused);self.assertEqual(self.check(m,p,f)[2],b'fictional-apk')
    def test_modified_manifest_is_rejected(self):
        m,p,f=fixture()
        for changes in [{'source_sha':'c'*40},{'repository':'fork/repo'},{'run_id':101},{'run_attempt':2},{'format':2},{'runtime_sha256':'0'*64},{'toolchains':{}},{'previous_source_sha':'c'*40},{'jvm_tests':0},{'unsigned_sha256':'0'*64},{'plan':{}},{'proofs':{}},{'metadata':dict(m['metadata'],versionCode=17)}]:
            with self.subTest(changes=changes),self.assertRaises((ValueError,KeyError)):self.check(dict(m,**changes),p,f)
    def test_extra_paths_missing_failed_or_altered_reports(self):
        m,p,f=fixture();name=next(n for n in f if n.startswith('proof/'))
        for changed in [dict(f,**{'../executable':b'bad'}),dict(f,**{'key.p12':b'bad'}),{n:b for n,b in f.items() if n!=name},dict(f,**{name:b'FAILURES!!!\nOK (1 test)'}),dict(f,**{'unsigned.apk':b'altered'})]:
            with self.subTest(files=list(changed)),self.assertRaises((ValueError,KeyError)):self.check(m,p,changed)
    def test_receipt_source_run_expiry_and_digest(self):
        m,p,f=fixture();good=artifact(archive(m,f))
        for changed in [dict(good,expired=True),dict(good,digest='sha256:'+'0'*64),dict(good,workflow_run={'head_sha':'c'*40,'id':100}),dict(good,workflow_run={'head_sha':HEAD,'id':101})]:
            with self.subTest(receipt=changed),self.assertRaises(ValueError):self.check(m,p,f,changed)
    def test_missing_success_marker_is_rejected(self):
        for text in [b'',b'OK (0 tests)',b'OK (1 test)\nINSTRUMENTATION_FAILED']:
            with self.assertRaises(ValueError):B.check_report('fake',text)

class GateTest(unittest.TestCase):
    def exercise(self,change=None):
        m,p,f=fixture(True);data=archive(m,f)
        rows=[run(id=101+i,name=name,path=path) for i,(name,path) in enumerate(G.REQUIRED_WORKFLOWS.items())]
        # Preserve the candidate run id carried by its immutable artifact.
        rows[-1]['id']=100
        if change:change(rows)
        def query(path,binary=False):
            if path.startswith('/git/ref/heads/'):return {'object':{'sha':HEAD}}
            if path.startswith('/git/ref/tags/'):
                raise __import__('urllib.error',fromlist=['HTTPError']).HTTPError(path,404,'not found',{},None)
            if path.startswith('/actions/runs?'):return {'workflow_runs':rows}
            if path.endswith('/artifacts?per_page=100'):return {'artifacts':[dict(artifact(data),id=42,name='android-beta-candidate')]}
            if path.startswith('/actions/artifacts/'):return data
            if path.startswith('/actions/runs/'):
                number=int(path.rsplit('/',1)[1]);return next(x for x in rows if x['id']==number)
            if path.startswith('/compare/'):return {'status':'ahead','files':[{'filename':ENTRY['path']}]}
            raise AssertionError(path)
        with tempfile.TemporaryDirectory() as temp:
            old=os.getcwd();os.chdir(temp)
            try:
                Path('event.json').write_text(json.dumps({'repository':{'full_name':P.REPOSITORY,'owner':{'id':1}},'workflow_run':{'id':100}}))
                with patch.dict(os.environ,{'GITHUB_EVENT_PATH':'event.json','GITHUB_EVENT_NAME':'workflow_run','GITHUB_REF':'refs/heads/main','GITHUB_OUTPUT':'output'}),patch.object(D,'api',side_effect=query),patch.object(D,'previous_release',return_value=P.BOOTSTRAP),patch.object(D,'reusable_proof',return_value=p['reused']),patch.object(D,'tree',return_value=[ENTRY]),patch.object(D,'PIPELINE_PINS',{}),patch.object(D,'file_bytes',return_value=b'applicationId=io.moli.android.app.beta\nversionCode=18\nversionName=0.14.1-beta.1'):
                    D.main()
                self.assertIn('approved=true',Path('output').read_text());self.assertEqual(Path('validated-beta/unsigned.apk').read_bytes(),b'fictional-apk')
            finally:os.chdir(old)
    def test_entire_gate_without_project_execution_or_secrets(self):self.exercise()
    def test_failed_latest_source_check_refuses_candidate(self):
        with self.assertRaises(ValueError):self.exercise(lambda rows:rows[0].update(conclusion='failure'))

class WorkflowTest(unittest.TestCase):
    def load(self,name):return yaml.safe_load((ROOT/'.github/workflows'/name).read_text())
    def test_candidate_name_matches_automatic_trigger_and_provenance(self):
        candidate=self.load('android-integrations.yml')
        signer=self.load('android-beta-sign.yml')
        name=candidate['name']
        self.assertEqual(name,'Android profile import')
        trigger=signer.get('on',signer.get(True))['workflow_run']
        self.assertEqual(trigger,{'workflows':[name],'types':['completed'],'branches':[P.READY_BRANCH]})
        self.assertEqual(set(signer.get('on',signer.get(True))),{'workflow_run','workflow_dispatch'})
        self.assertEqual(G.REQUIRED_WORKFLOWS[name],'.github/workflows/android-integrations.yml')
        self.assertEqual(G.latest_checks([run()])[name]['id'],100)
        for job in signer['jobs'].values():
            for step in job['steps']:
                self.assertNotIn('Android home and integrations',step.get('run',''))
    def test_secret_only_in_main_signer_and_write_only_in_publisher(self):
        w=self.load('android-beta-sign.yml');jobs=w['jobs']
        self.assertEqual(jobs['sign']['environment'],'android-beta-signing')
        self.assertEqual(jobs['sign']['needs'],'gate')
        self.assertIn("github.ref == 'refs/heads/main'",jobs['sign']['if'])
        self.assertIn("needs.gate.outputs.approved == 'true'",jobs['sign']['if'])
        self.assertEqual(w['permissions'],{'contents':'read','actions':'read'})
        for name,job in jobs.items():
            if name=='publish':self.assertEqual(job['permissions']['contents'],'write')
            else:self.assertNotIn('permissions',job)
            for step in job['steps']:
                self.assertNotIn('actions/checkout',step.get('uses',''))
                if 'uses' in step:self.assertRegex(step['uses'],r'@[a-f0-9]{40}$')
                if name!='sign':self.assertNotIn('secrets.',str(step))
        self.assertEqual(sum('BETA_SIGNING_BUNDLE' in step.get('env',{}) for step in jobs['sign']['steps']),1)
        self.assertNotIn('gradlew',str(jobs['sign']))
    def test_candidate_has_no_write_or_secret_and_only_ready_push_builds(self):
        w=self.load('android-integrations.yml')
        self.assertEqual(w['permissions'],{'contents':'read'});self.assertNotIn('secrets.',str(w))
        self.assertIn("needs.plan.outputs.candidate == 'true'",w['jobs']['apk']['if'])
        self.assertEqual(w.get('on',w.get(True))['push']['branches'],[P.READY_BRANCH])
    def test_trusted_inline_compiles_and_pins_match(self):
        w=self.load('android-beta-sign.yml')
        for job in w['jobs'].values():
            for step in job['steps']:
                if step.get('run','').startswith("python3 -I - <<'PYCODE'"):
                    code=step['run'].split('\n',1)[1].rsplit('\nPYCODE',1)[0];compile(code,'trusted-inline','exec')
        gate=w['jobs']['gate']['steps'][0]['run']
        for path in ['tools/android-ci/policy.py','tools/android-ci/plan.py','.github/workflows/check.yml','scripts/test-android-beta-update.sh']:
            self.assertIn(G.digest((ROOT/path).read_bytes()),gate)
    def test_release_publisher_refuses_superseded_source_without_sdk(self):
        m,_,_=fixture()
        with tempfile.TemporaryDirectory() as temp:
            Path(temp,'candidate.json').write_text(json.dumps(m))
            with patch.object(R,'previous_release',return_value=P.BOOTSTRAP),patch.object(R,'api',return_value={'object':{'sha':'c'*40}}),self.assertRaises(ValueError):R.verified_release(Path(temp))

if __name__=='__main__':unittest.main(verbosity=2)
