import contextlib
import importlib.util
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('safety', ROOT / 'tools/check_publish_safety.py')
safety = importlib.util.module_from_spec(spec)
spec.loader.exec_module(safety)


def encoded(value):
    return json.dumps(value).encode('utf-8')


def manifest(version='1.2.3'):
    return {
        'version': version,
        'created_at': '2026-09-07T12:34:56',
        'description': 'HansFix release notes.',
        'download_url': f'https://github.com/{safety.REPOSITORY}/releases/download/v{version}/patches-{version}.mpp',
        'signature_download_url': '',
    }


def patch_list(version='1.2.3'):
    return {
        'version': version,
        'patches': [{
            'name': safety.PATCH_NAME,
            'description': 'Requires official Captions in the same session.',
            'default': False,
            'compatiblePackages': [{
                'packageName': 'com.google.android.youtube',
                'targets': [{'version': '21.07.247', 'isExperimental': False}],
            }],
        }],
    }


def fixture_sources():
    return {
        'patches/src/main/kotlin/addon/Patch.kt': b'val hansFixPatch = bytecodePatch(name = "HansFix") {}\nval memory = bytecodePatch(name = "Remember subtitle language") {}\nval shared = bytecodePatch(description = "Shared runtime") {}',
        'extensions/extension/src/main/java/addon/Runtime.java': b'package io.github.yydarlinker.hansfix;\npublic class Runtime {}',
    }


class SafetyTests(unittest.TestCase):
    def test_ordinary_source_and_field_names(self):
        for data in (b'boolean enabled = Settings.SET_CAPTION_COOKIES.get();',
                     b'YSC and VISITOR_INFO1_LIVE are field names only.',
                     b'password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")'):
            self.assertEqual([], safety.check_bytes('src/Policy.java', data))

    def test_forbidden_material(self):
        for suffix in safety.FORBIDDEN_EXTENSIONS:
            with self.subTest(suffix=suffix):
                self.assertIn('forbidden-file-type', safety.check_bytes('input' + suffix.upper(), b'payload'))

    def test_private_and_generated_directories(self):
        for folder in safety.FORBIDDEN_PARTS:
            self.assertIn('private-or-generated-directory', safety.check_bytes(f'{folder}/a.txt', b'payload'))

    def test_local_credentials(self):
        for name in ('local.properties', '.env', '.env.prod', 'local-env.txt', '.npmrc', 'cookies.txt', 'id_rsa'):
            self.assertIn('local-configuration', safety.check_bytes(name, b'payload'))

    def test_tokens_and_keys_withhold_values(self):
        examples = [
            ('credential-token-pattern', b'ghp_' + b'A' * 36),
            ('credential-token-pattern', b'github_pat_' + b'A' * 40),
            ('possible-cookie-value', b'YSC=' + b'A' * 40),
            ('possible-cookie-value', b'SAPISID=' + b'A' * 40),
            ('private-key-header', b'-----BEGIN ' + b'RSA PRIVATE KEY-----'),
            ('possible-literal-credential', b'password=' + b'A' * 32),
        ]
        for rule, data in examples:
            self.assertIn(rule, safety.check_bytes('x.txt', data))
            # A NUL byte must not short-circuit credential scanning.
            self.assertIn(rule, safety.check_bytes('x.bin', data + b'\0'))

    def test_only_github_token_secret_expression(self):
        prefix, suffix = b'$' + b'{{ ', b' }}'
        self.assertEqual([], safety.check_bytes('docs/auth.md', prefix + b'secrets.GITHUB_TOKEN' + suffix))
        for expression in (b'secrets.USER_APK', b'secrets.SIGNING_KEY', b'secrets.PAT',
                           b"secrets['GITHUB_TOKEN']", b'secrets[inputs.key]', b'toJSON(secrets)',
                           b'secrets.GITHUB_TOKEN || secrets.PAT'):
            self.assertIn('unapproved-secret-reference', safety.check_bytes('docs/auth.md', prefix + expression + suffix))

    def test_unknown_binary_and_encoding(self):
        for name, data in (('other.jar', b'hello'), ('data.bin', b'payload'), ('data.txt', b'\0')):
            self.assertIn('unapproved-binary', safety.check_bytes(name, data))
        self.assertIn('unapproved-encoding', safety.check_bytes('data.txt', b'\xff'))

    def test_official_wrapper_only(self):
        name = 'gradle/wrapper/gradle-wrapper.jar'
        self.assertEqual([], safety.check_bytes(name, (ROOT / name).read_bytes()))
        self.assertIn('unapproved-binary', safety.check_bytes(name, b'PK\0bad'))

    def test_unsafe_paths(self):
        for name in ('../outside.txt', '/outside.txt', 'C:/outside.txt', 'a\\b.txt', 'a\nb.txt'):
            self.assertIn('unsafe-path', safety.check_bytes(name, b'text'))

    def test_examples_are_not_live_manifests(self):
        for name in ('docs/patches-bundle.example.json', 'docs/template-reference/patches-bundle.json',
                     'docs/template-reference/patches-list.json'):
            self.assertEqual([], safety.check_bytes(name, b'{}'))
        issues = safety.metadata_contract({'docs/patches-bundle.example.json': encoded(manifest())}, required=True)
        self.assertIn(('patches-bundle.json', 'missing-root-release-manifest'), issues)

    def test_reviewed_release_surface_only(self):
        for name in safety.REVIEWED_TEXT:
            data = (ROOT / name).read_bytes()
            self.assertEqual([], safety.check_bytes(name, data), name)
            self.assertEqual([], safety.check_bytes(name, data.replace(b'\r\n', b'\n').replace(b'\n', b'\r\n')), name)
            self.assertIn('unreviewed-release-surface', safety.check_bytes(name, data + b'\n# changed'))

    def test_unknown_or_renamed_workflow_rejected(self):
        for name in ('.github/workflows/evil.yml', '.github/workflows/release.yaml', '.github/workflows/Release.yml'):
            self.assertIn('unapproved-active-workflow', safety.check_bytes(name, b'on: push'))
        self.assertEqual([], safety.check_bytes('docs/template-reference/.github/workflows/release.yml', b'permissions:\n  contents: write'))

    def test_release_policy_mutations_fail_closed(self):
        name = '.github/workflows/release.yml'
        data = (ROOT / name).read_bytes()
        mutations = [
            data.replace(b'branches: [main, dev]', b'branches: ["**"]'),
            data.replace(b'  workflow_dispatch:', b'  pull_request_target:'),
            data.replace(b'needs: checks', b'needs: []'),
            data.replace(b'contents: read', b'contents: write'),
            data.replace(b'python tools/run_runtime_tests.py', b'echo tools/run_runtime_tests.py'),
            data.replace(b'GITHUB_TOKEN }}', b'SIGNING_KEY }}'),
            data + b'\n# another upload command\n',
        ]
        for mutation in mutations:
            self.assertNotEqual(data, mutation)
            self.assertIn('unreviewed-release-surface', safety.check_bytes(name, mutation))


class ManifestTests(unittest.TestCase):
    def test_stable_and_dev(self):
        for version in ('1.2.3', '1.2.3-dev.1', '0.1.0-dev.0'):
            self.assertEqual([], safety.check_bytes('patches-bundle.json', encoded(manifest(version))))
            self.assertEqual([], safety.check_bytes('patches-list.json', encoded(patch_list(version))))

    def test_required_fields(self):
        for field in ('version', 'created_at', 'download_url', 'description'):
            value = manifest()
            del value[field]
            self.assertTrue(safety.manifest_issues(encoded(value)), field)
            value[field] = ''
            self.assertTrue(safety.manifest_issues(encoded(value)), field)

    def test_bad_json_or_types(self):
        for data in (b'{}', b'[]', b'null', b'{', b'"hello"', b'{"version":"1.2.3","version":"2.3.4"}'):
            self.assertTrue(safety.manifest_issues(data))
            self.assertTrue(safety.patch_list_issues(data))
        for field in ('version', 'created_at', 'download_url', 'description', 'signature_download_url'):
            value = manifest()
            value[field] = []
            self.assertTrue(safety.manifest_issues(encoded(value)))

    def test_invalid_versions(self):
        for version in ('v1.2.3', '1.2', '01.2.3', '1.2.3-dev.01', '1.2.3-beta.1', '1.2.3/evil', '${version}'):
            self.assertTrue(safety.manifest_issues(encoded(manifest(version))))

    def test_valid_and_invalid_timestamps(self):
        for stamp in ('2026-09-07T12:34:56', '2026-09-07T12:34:56Z', '2026-09-07T12:34:56.123Z'):
            value = manifest()
            value['created_at'] = stamp
            self.assertEqual([], safety.manifest_issues(encoded(value)))
        for stamp in ('2026-02-30T12:00:00', '2026-09-07', 'not-a-date', '2026-09-07T99:99:99'):
            value = manifest()
            value['created_at'] = stamp
            self.assertTrue(safety.manifest_issues(encoded(value)))

    def test_download_exact_repository_asset_and_version(self):
        url = manifest()['download_url']
        for changed in (
            url.replace('YYDarlinker', 'other'), url.replace('morphe-hansfix-addon', 'other'),
            url.replace('https:', 'http:'), url.replace('github.com', 'github.com.evil.test'),
            url.replace('/v1.2.3/', '/v1.2.4/'), url.replace('patches-1.2.3', 'patches-1.2.4'),
            url.replace('.mpp', '.apk'), url + '?download=1', url + '#fragment',
            url.replace('/releases/download/', '/blob/'),
        ):
            value = manifest()
            value['download_url'] = changed
            self.assertIn('invalid-release-download-url', safety.manifest_issues(encoded(value)))

    def test_signature_is_optional_native_metadata_not_a_user_key(self):
        value = manifest()
        value['signature_download_url'] = value['download_url'] + '.asc'
        self.assertEqual([], safety.manifest_issues(encoded(value)))
        value['signature_download_url'] = 'https://example.org/key'
        self.assertIn('invalid-release-signature-url', safety.manifest_issues(encoded(value)))

    def test_generated_list_not_placeholder_or_official_bundle(self):
        for patches in ([], [patch_list()['patches'][0]] * 2, None):
            value = patch_list()
            value['patches'] = patches
            self.assertTrue(safety.patch_list_issues(encoded(value)))
        for field, value in (('name', 'Example'), ('default', True), ('description', ''), ('compatiblePackages', [])):
            data = patch_list()
            data['patches'][0][field] = value
            self.assertTrue(safety.patch_list_issues(encoded(data)))
        data = patch_list()
        data['patches'][0]['compatiblePackages'][0]['packageName'] = 'other.package'
        self.assertTrue(safety.patch_list_issues(encoded(data)))

    def test_root_versions_agree(self):
        files = {'patches-bundle.json': encoded(manifest()), 'patches-list.json': encoded(patch_list()),
                 'gradle.properties': b'version = 1.2.3\n'}
        self.assertIn(('patches-list.json', 'release-must-include-both-caption-patches'), safety.metadata_contract(files, required=True, expected_version='1.2.3'))
        self.assertTrue(safety.metadata_contract(files, expected_version='1.2.4'))
        changed = dict(files, **{'patches-list.json': encoded(patch_list('1.2.4'))})
        self.assertIn(('patches-list.json', 'release-version-mismatch'), safety.metadata_contract(changed))
        changed = dict(files, **{'gradle.properties': b'version = 9.0.0\n'})
        self.assertIn(('gradle.properties', 'release-version-mismatch'), safety.metadata_contract(changed))

    def test_first_release_and_partial_metadata(self):
        self.assertEqual([], safety.metadata_contract({}))
        self.assertTrue(safety.metadata_contract({}, required=True))
        self.assertTrue(safety.metadata_contract({'patches-bundle.json': encoded(manifest())}))
        self.assertTrue(safety.metadata_contract({'patches-list.json': encoded(patch_list())}))


class ProductionContractTests(unittest.TestCase):
    def test_one_production_patch_and_own_java_runtime(self):
        files = {}
        for base in ('patches/src/main', 'extensions/extension/src/main'):
            for p in (ROOT / base).rglob('*'):
                if p.is_file():
                    files[p.relative_to(ROOT).as_posix()] = p.read_bytes()
        self.assertEqual([], safety.source_contract(files))

    def test_zero_or_multiple_production_patches_rejected(self):
        files = fixture_sources()
        self.assertEqual([], safety.source_contract(files))
        files['patches/src/main/kotlin/addon/Other.kt'] = b'val another = resourcePatch(name = "Other") {}'
        self.assertIn(('<production>', 'expected-two-features-and-shared-extension'), safety.source_contract(files))
        for key in list(files):
            if key.startswith('patches/'):
                del files[key]
        self.assertIn(('<production>', 'expected-two-features-and-shared-extension'), safety.source_contract(files))

    def test_test_and_template_patch_definitions_are_not_production(self):
        files = fixture_sources()
        for path in ('patches/src/test/kotlin/Fixture.kt', 'docs/template-reference/patches/src/main/Example.kt'):
            files[path] = b'val example = bytecodePatch(name = "Example") {}'
        self.assertEqual([], safety.source_contract(files))

    def test_foreign_or_kotlin_runtime_rejected(self):
        for package in ('app.morphe.extension.youtube', 'kotlin', 'io.github.yydarlinker.hansfixevil'):
            files = fixture_sources()
            files['extensions/extension/src/main/java/Foreign.java'] = f'package {package};'.encode()
            self.assertTrue(safety.source_contract(files))
        files = fixture_sources()
        files['extensions/extension/src/main/kotlin/Extra.kt'] = b'package io.github.yydarlinker.hansfix'
        self.assertTrue(safety.source_contract(files))

    def test_native_release_configuration(self):
        config = json.loads((ROOT / '.releaserc').read_text('utf-8'))
        self.assertEqual(['main', {'name': 'dev', 'prerelease': True}], config['branches'])
        plugins = {p[0]: p[1] for p in config['plugins'] if isinstance(p, list)}
        self.assertIn('gradle-semantic-release-plugin', config['plugins'])
        release_json = plugins['@MorpheApp/changelog']['releaseJson']
        self.assertEqual('patches-bundle.json', release_json['path'])
        self.assertEqual('', release_json['signatureUrlTemplate'])
        self.assertTrue(release_json['downloadUrlTemplate'].endswith('/v${version}/patches-${version}.mpp'))
        prepare = plugins['@semantic-release/exec']['prepareCmd']
        for required in (':patches:buildAndroid', 'generatePatchesList', 'generate_patches_readme.py',
                         '--require-release-metadata', '--expected-version', 'check_addon_runtime.py'):
            self.assertIn(required, prepare)
        self.assertFalse(plugins['@semantic-release/github']['failTitle'])
        self.assertEqual(['CHANGELOG.md', 'gradle.properties', 'patches-bundle.json', 'patches-list.json', 'README.md'],
                         plugins['@semantic-release/git']['assets'])
        # Native MPP-only glob: github v12 does not template asset paths.
        self.assertEqual('patches/build/libs/patches-!(*sources*|*javadoc*).mpp',
                         plugins['@semantic-release/github']['assets'][0]['path'])

    def test_workflow_gates_and_verified_action_pins(self):
        pins = {
            'actions/checkout': '3d3c42e5aac5ba805825da76410c181273ba90b1',
            'actions/setup-python': '5fda3b95a4ea91299a34e894583c3862153e4b97',
            'actions/setup-java': 'dd06d9cba3e5552c54d9f8ea23572deb30010f7c',
            'actions/setup-node': '820762786026740c76f36085b0efc47a31fe5020',
            'cycjimmy/semantic-release-action': 'b12c8f6015dc215fe37bc154d4ad456dd3833c90',
            'actions/attest-build-provenance': '4d101475d8b20a2381f78447822ac1eab6504dd8',
        }
        release = (ROOT / '.github/workflows/release.yml').read_text('utf-8')
        self.assertNotIn('pull_request', release)
        self.assertIn('needs: checks', release)
        self.assertIn('branches: [main, dev]', release)
        self.assertIn('workflow_dispatch:', release)
        self.assertEqual(2, release.count("github.ref == 'refs/heads/main' || github.ref == 'refs/heads/dev'"))
        self.assertEqual(2, release.count("github.event_name == 'push' || github.event_name == 'workflow_dispatch'"))
        self.assertIn('cancel-in-progress: false', release)
        checks = release.split('  release:\n', 1)[0]
        self.assertNotIn('secrets.', checks)
        self.assertNotIn(': write', checks)
        ci = (ROOT / '.github/workflows/preparation-ci.yml').read_text('utf-8')
        self.assertNotIn(': write', ci)
        self.assertNotIn('secrets.', ci)
        for workflow in (checks, ci):
            self.assertLess(workflow.index('tools/check_publish_safety.py'), workflow.index('unittest discover'))
            self.assertLess(workflow.index('unittest discover'), workflow.index('tools/run_runtime_tests.py'))
            self.assertIn('-p "test*.py"', workflow)
            self.assertIn('tools/run_runtime_tests.py --jdk "$JAVA_HOME"', workflow)
            self.assertIn("java-version: '21'", workflow)
        for workflow in (release, ci):
            for action, sha in re.findall(r'uses: ([\w/-]+)@([^\s]+)', workflow):
                self.assertEqual(pins[action], sha)


class ReadmeGeneratorTests(unittest.TestCase):
    def run_generator(self, readme, data=None):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'patches-list.json').write_bytes(encoded(patch_list() if data is None else data))
            (root / 'README.md').write_text(readme, encoding='utf-8')
            result = subprocess.run([
                sys.executable, '-X', 'utf8', '-B', str(ROOT / '.github/scripts/generate_patches_readme.py'),
                safety.REPOSITORY, 'dev', str(root / 'patches-list.json'), str(root / 'README.md'),
            ], capture_output=True, encoding='utf-8', errors='replace')
            return result, (root / 'README.md').read_text('utf-8')

    def test_official_script_is_unchanged(self):
        self.assertEqual((ROOT / 'docs/template-reference/.github/scripts/generate_patches_readme.py').read_bytes(),
                         (ROOT / '.github/scripts/generate_patches_readme.py').read_bytes())

    def test_markers_render_patch_and_preserve_surroundings(self):
        for marker in ('<!-- PATCHES_START -->', '<!-- PATCHES_START EXPANDED -->'):
            result, text = self.run_generator(f'# Before\n{marker}\nold\n<!-- PATCHES_END -->\nAfter\n')
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn(safety.PATCH_NAME, text)
            self.assertIn('/YYDarlinker/morphe-hansfix-addon/releases/tag/v1.2.3', text)
            self.assertIn('<details open>', text)
            self.assertTrue(text.startswith('# Before\n'))
            self.assertTrue(text.endswith('After\n'))
            self.assertNotIn('\nold\n', text)

    def test_missing_markers_fails_without_rewriting_readme(self):
        result, text = self.run_generator('# Do not edit\n')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('# Do not edit\n', text)


class IndexAuditTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.git('init', '-q')
        for name, data in fixture_sources().items():
            self.put(name, data)
        for name in safety.REVIEWED_TEXT:
            self.put(name, (ROOT / name).read_bytes())
        self.put('.gitignore', b'*.apk\n')
        self.git('add', '.')

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.root), *args], check=True, capture_output=True).stdout

    def put(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def audit(self, **kwargs):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = safety.audit(self.root, **kwargs)
        return code, output.getvalue()

    def test_clean_initial_release_source(self):
        code, output = self.audit()
        self.assertEqual(0, code, output)
        self.assertIn('exact Git index', output)
        self.assertNotEqual(0, self.audit(require_release_metadata=True)[0])
        self.assertNotEqual(0, self.audit(release_candidate=True)[0])

    def test_exact_index_not_working_copy(self):
        secret = b'ghp_' + b'A' * 36
        self.put('data.txt', secret)
        self.git('add', 'data.txt')
        self.put('data.txt', b'now clean')
        code, output = self.audit()
        self.assertEqual(1, code)
        self.assertNotIn(secret.decode(), output)
        self.assertEqual(0, self.audit(worktree=True)[0])

    def test_forced_ignored_apk_is_rejected(self):
        self.put('private.apk', b'not a real APK')
        self.assertEqual(0, self.audit()[0])
        self.git('add', '-f', 'private.apk')
        self.assertEqual(1, self.audit()[0])
        self.assertEqual(1, self.audit(worktree=True)[0])

    def test_untracked_preflight_is_distinct(self):
        self.put('data.txt', b'ghp_' + b'A' * 36)
        self.assertEqual(0, self.audit()[0])
        code, output = self.audit(worktree=True)
        self.assertEqual(1, code)
        self.assertIn('UNSTAGED worktree preflight', output)

    def test_index_symlink_is_rejected_without_following(self):
        oid = subprocess.run(['git', '-C', str(self.root), 'hash-object', '-w', '--stdin'],
                             input=b'/outside', check=True, capture_output=True).stdout.decode().strip()
        self.git('update-index', '--add', '--cacheinfo', f'120000,{oid},link')
        code, output = self.audit()
        self.assertEqual(1, code)
        self.assertIn('non-regular-index-entry', output)

    def test_release_candidate_includes_only_native_new_assets(self):
        self.put('patches-bundle.json', encoded(manifest()))
        self.put('patches-list.json', encoded(patch_list()))
        self.put('gradle.properties', b'version = 1.2.3\n')
        self.put('CHANGELOG.md', b'Release notes')
        self.put('README.md', b'Generated patch list')
        self.put('patches/build/libs/patches-1.2.3.mpp', b'fixture, not a real bundle')
        self.put('.kotlin/sessions/cache.salive', b'local cache')
        self.assertEqual(0, self.audit(release_candidate=True, require_release_metadata=True)[0])
        self.assertEqual(1, self.audit(worktree=True, require_release_metadata=True)[0])
        self.put('CHANGELOG.md', b'ghp_' + b'A' * 36)
        self.assertEqual(1, self.audit(release_candidate=True)[0])
        self.put('CHANGELOG.md', b'Release notes')
        self.put('forced.apk', b'not an APK')
        self.git('add', '-f', 'forced.apk')
        self.assertEqual(1, self.audit(release_candidate=True)[0])

    def test_only_current_bundle_matches_native_upload_glob(self):
        self.assertTrue(safety.release_asset_issues(self.root, '1.2.3'))
        self.put('patches/build/libs/patches-1.2.3.mpp', b'fixture, not a real bundle')
        self.assertEqual([], safety.release_asset_issues(self.root, '1.2.3'))
        self.put('patches/build/libs/patches-1.2.3-sources.mpp', b'not uploaded')
        self.assertEqual([], safety.release_asset_issues(self.root, '1.2.3'))
        self.put('patches/build/libs/patches-1.2.2.mpp', b'stale bundle')
        self.assertTrue(safety.release_asset_issues(self.root, '1.2.3'))

    def test_deleted_reviewed_file_cannot_pass_preflight(self):
        (self.root / '.releaserc').unlink()
        self.assertEqual(0, self.audit()[0])
        self.assertEqual(1, self.audit(worktree=True)[0])


if __name__ == '__main__':
    unittest.main()
