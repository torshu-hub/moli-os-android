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
import types
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
    def test_notebook_recovery_in_fresh_session_without_key_generation(self):
        nb=json.loads((BASE/'moli-beta-key.ipynb').read_text())
        cells=[''.join(c['source']) for c in nb['cells'] if c['cell_type']=='code']
        self.assertEqual(len(cells),3)
        colab=types.ModuleType('google.colab')
        google=types.ModuleType('google');google.colab=colab
        colab.files=types.SimpleNamespace(upload=lambda:{'recovered.p12':self.blob})
        colab.output=types.SimpleNamespace(eval_js=lambda js:False)
        # Each case starts with an empty kernel. Creation cell is never executed.
        for expected,allowed in [(self.fingerprint,True),('0'*64,False),('',False)]:
            namespace={};captured=io.StringIO()
            with patch('cryptography.hazmat.primitives.asymmetric.rsa.generate_private_key',side_effect=AssertionError('Recovery must never generate a key')) as generate,patch.dict(sys.modules,{'google':google,'google.colab':colab}),patch('builtins.input',side_effect=['OUI',expected]) as prompts,patch('getpass.getpass',return_value=self.password),contextlib.redirect_stdout(captured),patch.object(colab.output,'eval_js',return_value=False) as clipboard:
                exec(compile(cells[0],'initialization','exec'),namespace)
                self.assertNotIn('_moli_pending_p12',namespace)
                self.assertNotIn('_moli_fingerprint',namespace)
                # Even a stale in-memory fingerprint must not bypass NordPass input.
                namespace['_moli_fingerprint']=self.fingerprint
                if allowed:
                    exec(compile(cells[2],'verification','exec'),namespace)
                    self.assertTrue(clipboard.called)
                    script=clipboard.call_args.args[0]
                    self.assertEqual(script.count(json.dumps(self.bundle)),1)
                    self.assertIn('Secret BETA_SIGNING_BUNDLE.',script)
                    self.assertNotIn('__MOLI_CLIPBOARD_VALUE__',script)
                    self.assertNotIn('bundle',namespace)
                else:
                    with self.assertRaises(ValueError):exec(compile(cells[2],'verification','exec'),namespace)
                    clipboard.assert_not_called()
                self.assertEqual(prompts.call_count,2)
                generate.assert_not_called()
            self.assertNotIn(self.password,captured.getvalue())
            self.assertNotIn(self.bundle,captured.getvalue())
    def test_workflow_isolated_existing_certificate_and_validated_bytes(self):
        workflow=yaml.safe_load((ROOT/'.github/workflows/android-beta-sign.yml').read_text())
        sign=workflow['jobs']['sign'];self.assertEqual(sign['environment'],'android-beta-signing')
        step=next(s for s in sign['steps'] if 'BETA_SIGNING_BUNDLE' in s.get('env',{}))
        self.assertIn((BASE/'sign_step.py').read_text(),step['run'])
        self.assertIn('UNSIGNED_SHA256',step['env']);self.assertEqual(len(step['env']['EXPECTED_CERTIFICATE']),64)
        self.assertFalse(any('actions/checkout' in s.get('uses','') for s in sign['steps']))
    def test_sdk_signing_and_rejection_without_leaking_inputs(self):
        source=ROOT/'android/app/build/outputs/apk/beta/app-beta-unsigned.apk'
        self.assertTrue(source.exists(),'Prepare the unchanged unsigned beta once before SDK signing self-test')
        metadata=dict(line.split('=',1) for line in (ROOT/'android/beta.properties').read_text().splitlines() if line and not line.startswith('#'))
        with tempfile.TemporaryDirectory(prefix='moli-sign-test-') as temp:
            env=dict(os.environ,BETA_SIGNING_BUNDLE=self.bundle,BETA_METADATA=json.dumps(metadata),UNSIGNED_APK=str(source),SIGNED_OUTPUT=temp+'/valid',RUNNER_TEMP=temp,EXPECTED_CERTIFICATE=self.fingerprint,UNSIGNED_SHA256=__import__('hashlib').sha256(source.read_bytes()).hexdigest())
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
            env['BETA_SIGNING_BUNDLE']=self.bundle
            for key in ['EXPECTED_CERTIFICATE','UNSIGNED_SHA256']:
                old=env[key];env[key]='0'*64;env['SIGNED_OUTPUT']=temp+'/pin-refused'
                result=subprocess.run([sys.executable,'-I',str(BASE/'sign_step.py')],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
                self.assertNotEqual(result.returncode,0);self.assertFalse(Path(temp,'pin-refused').exists());env[key]=old

if __name__=='__main__':unittest.main(verbosity=2)
