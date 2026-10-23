"""Targeted tests; ephemeral keys only. Never use owner credentials."""
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import contextlib
import io
import yaml

BASE=Path(__file__).resolve().parent
ROOT=BASE.parents[1]
spec=importlib.util.spec_from_file_location('owner_key',BASE/'owner_key.py')
owner=importlib.util.module_from_spec(spec);spec.loader.exec_module(owner)

class SigningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.password=secrets.token_urlsafe(32)
        cls.blob,cls.fingerprint=owner.create_key(cls.password) # Disposable fixture, never retained.
        cls.bundle=owner.recovery_bundle(cls.blob,cls.password,cls.fingerprint,nordpass_confirmed=True)
    def test_container_roundtrip(self):
        parsed=json.loads(self.bundle)
        self.assertEqual(parsed['certificate_sha256'],self.fingerprint)
        self.assertTrue(parsed['nordpass_confirmed'])
        self.assertEqual(parsed['format'],1)
    def test_wrong_password_fingerprint_and_missing_owner_confirmation(self):
        for password,fingerprint,confirmed in [(self.password+'x',self.fingerprint,True),(self.password,'0'*64,True),(self.password,self.fingerprint,False)]:
            with self.assertRaises(ValueError):owner.recovery_bundle(self.blob,password,fingerprint,nordpass_confirmed=confirmed)
    def test_weak_or_multiline_password_refused(self):
        for password in ['1234','a'*23,'a'*32+'\n','é'*32]:
            with self.assertRaises(ValueError):owner.password_bytes(password)
    def test_notebook_has_no_credentials_or_saved_outputs(self):
        nb=json.loads((BASE/'moli-beta-key.ipynb').read_text())
        self.assertTrue(nb['metadata']['colab']['private_outputs'])
        code=''.join(''.join(c['source']) for c in nb['cells'] if c['cell_type']=='code')
        self.assertIn((BASE/'owner_key.py').read_text(),code)
        for c in nb['cells']:
            if c['cell_type']=='code':
                self.assertEqual(c['outputs'],[]);self.assertIsNone(c['execution_count'])
                compile(''.join(c['source']),'notebook','exec')
        self.assertNotIn(self.password,code)
        self.assertNotIn('drive.mount',code)
        self.assertNotIn('requests.post',code)
    def test_workflow_only_manual_owner_main_and_isolated_signing(self):
        workflow=(ROOT/'.github/workflows/android-beta-sign.yml').read_text()
        data=yaml.safe_load(workflow)
        trigger=data.get('on',data.get(True))
        self.assertEqual(set(trigger),{'workflow_dispatch'})
        jobs=data['jobs'];sign=jobs['sign']
        self.assertEqual(sign['environment'],'android-beta-signing')
        self.assertIn("github.ref == 'refs/heads/main'",sign['if'])
        self.assertIn('github.actor == github.repository_owner',sign['if'])
        self.assertEqual(jobs['sign']['needs'],'build')
        steps=sign['steps'];secretsteps=[s for s in steps if 'BETA_SIGNING_BUNDLE' in s.get('env',{})]
        self.assertEqual(len(secretsteps),1)
        script=secretsteps[0]['run'];self.assertIn((BASE/'sign_step.py').read_text(),script)
        self.assertFalse(any('actions/checkout' in s.get('uses','') for s in steps))
        self.assertFalse(any('gradle' in s.get('run','') for s in steps))
        self.assertEqual(data['permissions'],{'contents':'read','actions':'read'})
        for job in jobs.values():
            self.assertEqual(job['runs-on'],'ubuntu-24.04')
            for step in job['steps']:
                if 'uses' in step:self.assertRegex(step['uses'],r'@[0-9a-f]{40}$')
        verify=jobs['verify-and-deliver']['steps']
        proof=next(s for s in verify if s.get('name','').startswith('Prepare trusted'))
        self.assertIn((BASE/'verify_prebuilt.sh').read_text(),proof['run'])
        emulator=next(s for s in verify if s.get('name','').startswith('Verify migration'))
        self.assertEqual(emulator['with']['script'],'sh verify-beta-signed.sh')
        delivery=next(s for s in verify if s.get('name','').startswith('Deliver only'))
        self.assertNotIn('always()',delivery.get('if',''))
    def test_source_gate_rejects_missing_failed_latest_and_fork_runs(self):
        workflow=yaml.safe_load((ROOT/'.github/workflows/android-beta-sign.yml').read_text())
        body=workflow['jobs']['build']['steps'][0]['run']
        code=body.split("<<'PYCODE'\n",1)[1].rsplit('\nPYCODE',1)[0]
        required=['check','audit','Source hygiene','Android home and integrations']
        good=[dict(name=name,head_sha='a'*40,status='completed',conclusion='success',head_repository={'id':123}) for name in required]
        cases=[(good,True),(good[:-1],False),([dict(good[0],conclusion='failure')]+good,False),([dict(r,head_repository={'id':999}) for r in good],False)]
        for runs,allowed in cases:
            response=io.BytesIO(json.dumps({'workflow_runs':runs}).encode())
            with patch.dict(os.environ,{'SOURCE_SHA':'a'*40,'PREVIOUS_SHA':'','GH_TOKEN':'invented-test-token','REPOSITORY':'example/example','REPOSITORY_ID':'123'}),patch('urllib.request.urlopen',return_value=response),contextlib.redirect_stdout(io.StringIO()):
                if allowed:exec(compile(code,'source-gate','exec'),{})
                else:
                    with self.assertRaises(SystemExit):exec(compile(code,'source-gate','exec'),{})
    def test_sdk_signing_and_rejection_without_leaking_inputs(self):
        source=ROOT/'android/app/build/outputs/apk/beta/app-beta-unsigned.apk'
        self.assertTrue(source.exists(),'Prepare the unchanged unsigned beta once before SDK signing self-test')
        metadata={'applicationId':'io.moli.android.app.beta','versionCode':'17','versionName':'0.14.0-beta.1'}
        with tempfile.TemporaryDirectory(prefix='moli-sign-test-') as temp:
            env=dict(os.environ,BETA_SIGNING_BUNDLE=self.bundle,BETA_METADATA=json.dumps(metadata),UNSIGNED_APK=str(source),SIGNED_OUTPUT=temp+'/valid',RUNNER_TEMP=temp)
            result=subprocess.run([sys.executable,'-I',str(BASE/'sign_step.py')],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
            self.assertEqual(result.returncode,0,'SDK signing failed without exposing its private diagnostic')
            self.assertNotIn(self.password,result.stdout+result.stderr)
            files=list(Path(temp,'valid/release').glob('*.apk'));self.assertEqual(len(files),1)
            self.assertEqual(Path(temp,'valid/release/CERTIFICATE-SHA256.txt').read_text().strip(),self.fingerprint)
            self.assertEqual(list(Path(temp).glob('moli-sign-*')),[])
            for label,bundle in [('missing',''),('wrong-fingerprint',json.dumps(dict(json.loads(self.bundle),certificate_sha256='0'*64))),('no-backup',json.dumps(dict(json.loads(self.bundle),nordpass_confirmed=False))),('wrong-password',json.dumps(dict(json.loads(self.bundle),password=self.password+'x')))]:
                env.update(BETA_SIGNING_BUNDLE=bundle,SIGNED_OUTPUT=temp+'/'+label)
                result=subprocess.run([sys.executable,'-I',str(BASE/'sign_step.py')],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
                self.assertNotEqual(result.returncode,0)
                self.assertNotIn(self.password,result.stdout+result.stderr)
                self.assertFalse(Path(temp,label).exists())
            self.assertEqual(list(Path(temp).glob('moli-sign-*')),[])

if __name__=='__main__':unittest.main(verbosity=2)
