import hashlib, importlib.util, unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('safety',ROOT/'tools/check_publish_safety.py')
safety=importlib.util.module_from_spec(spec);spec.loader.exec_module(safety)
class SafetyTests(unittest.TestCase):
    def check(self,name,data):return safety.check_bytes(name,data)
    def test_ordinary_source(self):self.assertEqual([],self.check('src/Policy.java',b'boolean enabled = Settings.SET_CAPTION_COOKIES.get();'))
    def test_variable_name_not_cookie(self):self.assertEqual([],self.check('docs/info.md',b'YSC and VISITOR_INFO1_LIVE are field names only.'))
    def test_apk_case_insensitive(self):self.assertIn('forbidden-file-type',self.check('App.APK',b'not-a-package'))
    def test_smali_rejected(self):self.assertIn('forbidden-file-type',self.check('code.smali',b'.class public Lx;'))
    def test_key_container(self):self.assertIn('forbidden-file-type',self.check('release.P12',b'anything'))
    def test_build_dir(self):self.assertIn('private-or-generated-directory',self.check('patches/build/a.txt',b'build'))
    def test_fixture_dir(self):self.assertIn('private-or-generated-directory',self.check('private-fixtures/input.txt',b'input'))
    def test_local_properties(self):self.assertIn('local-configuration',self.check('local.properties',b'sdk.dir=local'))
    def test_token(self):self.assertIn('credential-token-pattern',self.check('x.txt',b'ghp_'+b'A'*36))
    def test_long_cookie(self):self.assertIn('possible-cookie-value',self.check('x.txt',b'YSC='+b'A'*40))
    def test_private_header(self):self.assertIn('private-key-header',self.check('x.txt',b'-----BEGIN '+b'RSA PRIVATE KEY-----'))
    def test_unknown_binary(self):self.assertIn('unapproved-binary',self.check('data.bin',b'\0x'))
    def test_official_wrapper(self):self.assertEqual([],self.check('gradle/wrapper/gradle-wrapper.jar',(ROOT/'gradle/wrapper/gradle-wrapper.jar').read_bytes()))
    def test_modified_wrapper(self):self.assertIn('unapproved-binary',self.check('gradle/wrapper/gradle-wrapper.jar',b'PK\0bad'))
    def test_arbitrary_jar(self):self.assertIn('unapproved-binary',self.check('other.jar',b'hello'))
    def test_live_manifest_blocked(self):self.assertIn('preparation-has-no-live-manifest',self.check('patches-bundle.json',b'{}'))
    def test_example_manifest_ok(self):self.assertEqual([],self.check('docs/patches-bundle.example.json',b'{}'))
    def test_active_release_blocked(self):self.assertIn('unapproved-active-workflow',self.check('.github/workflows/release.yml',b'on: push'))
    def test_inactive_reference_ok(self):self.assertEqual([],self.check('docs/template-reference/.github/workflows/release.yml',b'permissions:\n  contents: write'))
    def test_ci_readonly_ok(self):self.assertEqual([],self.check('.github/workflows/preparation-ci.yml',b'permissions:\n  contents: read'))
    def test_ci_write_blocked(self):self.assertIn('workflow-exceeds-preparation-scope',self.check('.github/workflows/preparation-ci.yml',b'permissions:\n  contents: write'))
    def test_relative_escape_blocked(self):self.assertIn('unsafe-path',self.check('../outside.txt',b'text'))
    def test_no_registered_feature(self):
        for p in (ROOT/'patches/src').rglob('*.kt'):
            self.assertNotIn('bytecodePatch(',p.read_text('utf8'),str(p))
    def test_no_official_runtime_namespace(self):
        for p in (ROOT/'extensions/extension/src').rglob('*.java'):
            self.assertTrue(p.read_text('utf8').startswith('package io.github.yydarlinker.hansfix;'))
if __name__=='__main__':unittest.main()
