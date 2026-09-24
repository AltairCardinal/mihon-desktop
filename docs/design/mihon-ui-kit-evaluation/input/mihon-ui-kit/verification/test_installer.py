#!/usr/bin/env python3
"""Installer safety tests on disposable synthetic Git repositories, not the real Mihon project."""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

KIT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("install_into_repo", KIT / "tools/install_into_repo.py")
assert spec and spec.loader
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)

class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="mihon-installer-test-")
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name).resolve()
        fixtures = {
            "app/build.gradle.kts": 'android { namespace = "eu.kanade.tachiyomi"\n defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" } }\n',
            "gradle/libs.versions.toml": '\n'.join(a+' = { module = "fixture:fixture", version = "0" }' for a in (
                "androidx-compose-bom", "androidx-test-junit", "androidx-test-espresso-core", "androidx-compose-materialIcons")),
            "app/src/main/java/eu/kanade/presentation/components/AppBar.kt": 'import androidx.compose.material.icons.Icons\n',
            "app/src/main/java/eu/kanade/presentation/theme/TachiyomiTheme.kt": '// synthetic fixture\n',
            "presentation-core/src/main/java/tachiyomi/presentation/core/components/material/Scaffold.kt": '// synthetic fixture\n',
            "AGENTS.md": '# Existing instructions\nPreserve this exact content.\n',
            "app/src/debug/AndroidManifest.xml": '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n<application><activity android:name=".ExistingActivity" android:exported="false" /></application>\n</manifest>\n',
        }
        for path, text in fixtures.items():
            p=self.repo/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
        for args in (("init",), ("add","."), ("-c","user.name=Installer fixture","-c","user.email=fixture@example.invalid","commit","-m","fixture")):
            subprocess.run(["git","-C",str(self.repo),*args],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    def snapshot(self):
        return {str(p.relative_to(self.repo)):p.read_bytes() for p in self.repo.rglob('*') if p.is_file() and '.git' not in p.relative_to(self.repo).parts}
    def plan(self):return installer.build_plan(self.repo, True)
    def test_dry_run_does_not_write(self):
        before=self.snapshot(); plan,base=self.plan()
        self.assertGreater(len(plan),20);self.assertEqual(before,self.snapshot())
        p=subprocess.run([sys.executable,str(KIT/'tools/install_into_repo.py'),str(self.repo),'--allow-different-base'],capture_output=True,text=True)
        self.assertEqual(p.returncode,0,p.stderr);self.assertIn('DRY RUN',p.stdout);self.assertEqual(before,self.snapshot())
    def test_exact_base_required_by_default(self):
        before=self.snapshot()
        with self.assertRaisesRegex(ValueError,'HEAD is'):installer.build_plan(self.repo,False)
        self.assertEqual(before,self.snapshot())
    def test_apply_preserves_instructions_and_existing_manifest(self):
        old=(self.repo/'AGENTS.md').read_bytes();plan,base=self.plan();installer.apply_plan(self.repo,plan,base)
        self.assertTrue((self.repo/'AGENTS.md').read_bytes().startswith(old))
        manifest=(self.repo/'app/src/debug/AndroidManifest.xml').read_text()
        self.assertIn('.ExistingActivity',manifest);self.assertIn(installer.ACTIVITY,manifest)
        self.assertEqual(manifest.count(installer.ACTIVITY),1)
    def test_second_install_is_idempotent(self):
        plan,base=self.plan();installer.apply_plan(self.repo,plan,base)
        before=self.snapshot();again,_=self.plan()
        self.assertEqual(again,[]);self.assertEqual(before,self.snapshot())
    def test_conflicting_file_blocks_all_writes(self):
        p=self.repo/'docs/ui/README.md';p.parent.mkdir(parents=True);p.write_text('User-authored conventions')
        before=self.snapshot()
        with self.assertRaisesRegex(ValueError,'Refusing to overwrite'):self.plan()
        self.assertEqual(before,self.snapshot())
    def test_changed_file_after_preflight_blocks_writes(self):
        plan,base=self.plan();target=self.repo/'AGENTS.md';target.write_text('Concurrent user edit')
        before=self.snapshot()
        with self.assertRaisesRegex(ValueError,'changed after preflight'):installer.apply_plan(self.repo,plan,base)
        self.assertEqual(before,self.snapshot())
    def test_failure_rolls_back_written_files(self):
        plan,base=self.plan();before=self.snapshot();original=installer.atomic_write;calls=0
        def fail_once(path,data):
            nonlocal calls
            calls+=1
            if calls==3:raise OSError('injected write failure')
            original(path,data)
        with patch.object(installer,'atomic_write',side_effect=fail_once):
            with self.assertRaisesRegex(OSError,'injected'):installer.apply_plan(self.repo,plan,base)
        self.assertEqual(before,self.snapshot())
    def test_symlink_destination_is_rejected(self):
        out=Path(self.tmp.name).parent/(Path(self.tmp.name).name+'-out')
        out.mkdir();self.addCleanup(lambda:out.rmdir())
        (self.repo/'docs').symlink_to(out,target_is_directory=True)
        with self.assertRaisesRegex(ValueError,'Path leaves repository|Refusing symlink'):self.plan()
        self.assertEqual(list(out.iterdir()),[])

class RecordingResult(unittest.TextTestResult):
    def __init__(self,*args,**kwargs):super().__init__(*args,**kwargs);self.records=[]
    def addSuccess(self,test):super().addSuccess(test);self.records.append({'name':test.id(),'status':'PASS'})
    def addFailure(self,test,err):super().addFailure(test,err);self.records.append({'name':test.id(),'status':'FAIL'})
    def addError(self,test,err):super().addError(test,err);self.records.append({'name':test.id(),'status':'ERROR'})

if __name__=='__main__':
    result=unittest.TextTestRunner(verbosity=2,resultclass=RecordingResult).run(unittest.defaultTestLoader.loadTestsFromTestCase(InstallerTests))
    data={'kind':'INSTALLER_SYNTHETIC_REPOSITORY_ONLY','passed':sum(c['status']=='PASS' for c in result.records),
          'failed':len(result.failures)+len(result.errors),'checks':result.records,'real_repository_installation':'NOT_RUN','android_build':'NOT_RUN'}
    (KIT/'verification/installer-results.json').write_text(json.dumps(data,ensure_ascii=False,indent=2))
    sys.exit(not result.wasSuccessful())
