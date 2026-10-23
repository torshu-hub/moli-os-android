#!/usr/bin/env python3
"""Security-sensitive identity and downgrade guards."""
import importlib.util
from pathlib import Path
import unittest
spec = importlib.util.spec_from_file_location('beta', Path(__file__).with_name('check-android-beta.py'))
beta = importlib.util.module_from_spec(spec)
spec.loader.exec_module(beta)

class MetadataTest(unittest.TestCase):
    def setUp(self):
        self.text = Path('android/beta.properties').read_text()
        self.current = beta.parse(self.text)
    def test_initial_and_unchanged_verification(self):
        beta.compare(self.current, None)
        beta.compare(self.current, self.current)
    def test_wrong_identity_and_duplicate(self):
        for text in [self.text.replace('.beta', '.beta.v2'), self.text+'\nversionCode=18\n']:
            with self.assertRaises(ValueError): beta.parse(text)
    def test_invalid_version(self):
        for code in ['16', '0', '-1', '2100000001']:
            with self.assertRaises(ValueError): beta.parse(self.text.replace('versionCode='+self.current['versionCode'], 'versionCode='+code))
    def test_downgrade_and_reused_delivery_refused(self):
        previous = dict(self.current, versionCode=str(int(self.current['versionCode'])+1))
        with self.assertRaises(ValueError): beta.compare(self.current, previous)
        with self.assertRaises(ValueError): beta.compare(self.current, self.current, delivery=True)
    def test_increased_delivery_and_same_code_new_name(self):
        beta.compare(dict(self.current,versionCode=str(int(self.current['versionCode'])+1)), self.current, delivery=True)
        with self.assertRaises(ValueError): beta.compare(dict(self.current,versionName='0.14.0-beta.2'), self.current)

unittest.main()
