"""Audit publishable source without printing secret values.

Default: exact Git index, including forced-added ignored files. --worktree is
an UNSTAGED preflight over tracked and nonignored untracked files, not proof of
what will be pushed. Build artifacts stay ignored and are never upload inputs.
This is a heuristic safety gate, not a substitute for a history/privacy review.
"""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'YYDarlinker/morphe-hansfix-addon'
PATCH_NAME = 'HansFix - Simplified Chinese captions'
NAMESPACE = 'io.github.yydarlinker.hansfix'
FORBIDDEN_EXTENSIONS = {
    '.apk', '.apkm', '.apks', '.xapk', '.aab', '.dex', '.smali', '.mpp', '.mpe',
    '.keystore', '.jks', '.p12', '.pfx', '.pem', '.key', '.asc', '.idsig',
    '.jpg', '.jpeg', '.mp4', '.m4a', '.mp3', '.wav', '.webm', '.mov',
    '.zip', '.7z', '.rar', '.tar', '.gz', '.log', '.pyc', '.class', '.sqlite', '.db',
}
FORBIDDEN_PARTS = {
    'upload', 'apk-work', 'decoded', 'private-fixtures', 'private-signing',
    'local-build', 'build', '.gradle', '.kotlin', '__pycache__', 'node_modules',
}
ALLOWED_BINARY = {
    'gradle/wrapper/gradle-wrapper.jar':
        '7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d',
}
# Exact reviewed executable release surface. Hashes normalize CRLF only, so a
# Windows checkout and the Git blob agree. Changes require a deliberate review
# and updates here; a filename alone never authorizes a publishing workflow.
REVIEWED_TEXT = {
    '.github/workflows/release.yml': 'f7c2b7887eda25ad5c5a284a1586ac7a4b5d643f386677613411d00f8aae61db',
    '.github/workflows/preparation-ci.yml': '20a0074417fff4700b59a2881ac3779ccab29b3a3a2d5196ef09100918775e42',
    '.releaserc': 'd74848998004d8672665f56c7ec865f5abd918c381fe913c291a57b1392b6594',
    '.github/scripts/generate_patches_readme.py': 'd3adb116afc2d5abd3d14218efd14c778dd7bf34ff837eea4e969e48d536fc60',
}
TOKEN = re.compile(rb'(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{30,}|AIza[A-Za-z0-9_-]{35}|AKIA[A-Z0-9]{16})')
PRIVATE_KEY = re.compile(rb'-----BEGIN [A-Z ]*PRIVATE KEY-----')
COOKIE_VALUE = re.compile(rb'(?:YSC|VISITOR_INFO1_LIVE|VISITOR_PRIVACY_METADATA|__Secure-ROLLOUT_TOKEN|SAPISID|__Secure-3PAPISID)\s*[=:]\s*["\x27]?[A-Za-z0-9_%+/-]{24,}')
LITERAL_CREDENTIAL = re.compile(
    rb'(?im)^\s*["\x27]?(?:gpr\.key|api[_-]?key|access[_-]?token|password|authorization|cookie)["\x27]?\s*[:=]\s*["\x27]?[A-Za-z0-9_+/.=-]{16,}[\"\x27]?\s*[,;]?\s*(?:#.*)?$'
)
SECRET_EXPRESSION = re.compile(rb'\$\{\{(.*?)\}\}', re.S)
SEMVER = re.compile(r'(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-dev\.(?:0|[1-9]\d*))?')


def text_digest(data):
    return hashlib.sha256(data.replace(b'\r\n', b'\n')).hexdigest()


def load_json(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON key')
            result[key] = value
        return result
    return json.loads(data.decode('utf-8-sig'), object_pairs_hook=unique)


def valid_version(value):
    return isinstance(value, str) and SEMVER.fullmatch(value) is not None


def manifest_issues(data):
    try:
        value = load_json(data)
        if not isinstance(value, dict):
            return ['invalid-release-manifest']
        required = {'version', 'created_at', 'download_url', 'description'}
        if not required.issubset(value) or set(value) - required - {'signature_download_url'}:
            return ['invalid-release-manifest-fields']
        version = value['version']
        if not valid_version(version):
            return ['invalid-release-version']
        if not isinstance(value['description'], str) or not value['description'].strip():
            return ['empty-release-description']
        timestamp = value['created_at']
        # MorpheApp/changelog emits UTC ISO text WITHOUT a trailing Z.
        if not isinstance(timestamp, str) or not re.fullmatch(
            r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|\+00:00)?', timestamp
        ):
            return ['invalid-release-created-at']
        datetime.fromisoformat(timestamp.replace('Z', '+00:00'))
        url = f'https://github.com/{REPOSITORY}/releases/download/v{version}/patches-{version}.mpp'
        if value['download_url'] != url:
            return ['invalid-release-download-url']
        if value.get('signature_download_url', '') not in ('', url + '.asc'):
            return ['invalid-release-signature-url']
        return []
    except (ValueError, TypeError, UnicodeError, RecursionError):
        return ['invalid-release-manifest']


def patch_list_issues(data):
    try:
        value = load_json(data)
        if not isinstance(value, dict) or not valid_version(value.get('version')):
            return ['invalid-patch-list-version']
        patches = value.get('patches')
        if not isinstance(patches, list) or len(patches) != 1:
            return ['expected-one-generated-patch']
        patch = patches[0]
        if not isinstance(patch, dict) or patch.get('name') != PATCH_NAME or patch.get('default') is not False:
            return ['unexpected-generated-patch']
        if not isinstance(patch.get('description'), str) or not patch['description'].strip():
            return ['missing-generated-patch-description']
        packages = patch.get('compatiblePackages')
        if not isinstance(packages, list) or len(packages) != 1 or not isinstance(packages[0], dict):
            return ['unexpected-generated-package']
        package = packages[0]
        if package.get('packageName') != 'com.google.android.youtube':
            return ['unexpected-generated-package']
        targets = package.get('targets')
        if not isinstance(targets, list) or len(targets) != 1 or not isinstance(targets[0], dict) or targets[0].get('version') != '21.07.247':
            return ['unexpected-generated-target']
        return []
    except (ValueError, TypeError, UnicodeError, RecursionError):
        return ['invalid-patch-list']


def check_bytes(name, data):
    issues = []
    p = PurePosixPath(name)
    low = name.lower()
    if p.is_absolute() or '..' in p.parts or '\\' in name or ':' in name or any(ord(c) < 32 for c in name):
        issues.append('unsafe-path')
    if any(part.lower() in FORBIDDEN_PARTS for part in p.parts):
        issues.append('private-or-generated-directory')
    if p.suffix.lower() in FORBIDDEN_EXTENSIONS:
        issues.append('forbidden-file-type')
    if p.name.lower() in {'local.properties', '.npmrc', '.pypirc', 'credentials', 'cookies.txt', 'cookies.json', 'id_rsa', 'id_ed25519'} or p.name.lower().startswith(('.env', 'local-env.')):
        issues.append('local-configuration')
    if low.startswith('.github/workflows/') and name not in REVIEWED_TEXT:
        issues.append('unapproved-active-workflow')
    if name in REVIEWED_TEXT and text_digest(data) != REVIEWED_TEXT[name]:
        issues.append('unreviewed-release-surface')
    if p.suffix.lower() in {'.jar', '.png', '.webp', '.gif', '.pdf', '.bin'} or b'\0' in data:
        if ALLOWED_BINARY.get(name) != hashlib.sha256(data).hexdigest():
            issues.append('unapproved-binary')
        # Still inspect binary bytes for credential markers below.
    else:
        try:
            data.decode('utf-8-sig')
        except UnicodeDecodeError:
            issues.append('unapproved-encoding')
    if len(data) > 2_000_000:
        issues.append('unexpected-large-file')
    if TOKEN.search(data):
        issues.append('credential-token-pattern')
    if PRIVATE_KEY.search(data):
        issues.append('private-key-header')
    if COOKIE_VALUE.search(data):
        issues.append('possible-cookie-value')
    if LITERAL_CREDENTIAL.search(data.replace(b'\0', b'\n')):
        issues.append('possible-literal-credential')
    for expression in SECRET_EXPRESSION.findall(data):
        if re.search(rb'\bsecrets\b', expression, re.I) and expression.strip() != b'secrets.GITHUB_TOKEN':
            issues.append('unapproved-secret-reference')
            break
    # Only exact root paths are live metadata; template/example files are not.
    if low == 'patches-bundle.json':
        if name != low:
            issues.append('noncanonical-manifest-path')
        issues.extend(manifest_issues(data))
    if low == 'patches-list.json':
        if name != low:
            issues.append('noncanonical-patch-list-path')
        issues.extend(patch_list_issues(data))
    return issues


def source_contract(files):
    issues = []
    registrations = 0
    runtime_count = 0
    for name, data in files.items():
        text = data.decode('utf-8-sig', errors='replace')
        if name.startswith('patches/src/main/') and name.endswith('.kt'):
            # A static contract, not a substitute for loading the built bundle.
            code = re.sub(r'/\*.*?\*/|//[^\n]*', '', text, flags=re.S)
            registrations += len(re.findall(r'\b(?:bytecodePatch|resourcePatch|rawResourcePatch)\s*\(', code))
        if name.startswith('extensions/') and '/src/main/' in name and name.endswith(('.java', '.kt')):
            runtime_count += 1
            package = re.search(r'^\s*package\s+([\w.]+)\s*;', text, flags=re.M)
            if name.endswith('.kt') or not package or not (
                package[1] == NAMESPACE or package[1].startswith(NAMESPACE + '.')
            ):
                issues.append((name, 'foreign-or-non-java-runtime-source'))
    if registrations != 1:
        issues.append(('<production>', 'expected-exactly-one-production-patch'))
    if runtime_count == 0:
        issues.append(('<production>', 'missing-addon-runtime-source'))
    return issues


def metadata_contract(files, required=False, expected_version=None):
    issues = []
    manifest = files.get('patches-bundle.json')
    patch_list = files.get('patches-list.json')
    if manifest is None and patch_list is None and not required and expected_version is None:
        return []  # First release: the native prepare phase creates both files.
    if manifest is None:
        issues.append(('patches-bundle.json', 'missing-root-release-manifest'))
    if patch_list is None:
        issues.append(('patches-list.json', 'missing-root-patch-list'))
    if issues or manifest_issues(manifest) or patch_list_issues(patch_list):
        return issues  # Per-file validation reports malformed metadata.
    version = load_json(manifest)['version']
    if version != load_json(patch_list)['version']:
        issues.append(('patches-list.json', 'release-version-mismatch'))
    if expected_version is not None and version != expected_version:
        issues.append(('patches-bundle.json', 'unexpected-release-version'))
    properties = files.get('gradle.properties', b'')
    match = re.search(rb'^\s*version\s*=\s*([^\s]+)\s*$', properties, re.M)
    if not match or match[1].decode('utf-8', errors='replace') != version:
        issues.append(('gradle.properties', 'release-version-mismatch'))
    return issues


def release_asset_issues(root, version):
    """Bound the native GitHub asset glob to exactly this candidate's MPP."""
    directory = root / 'patches/build/libs'
    expected = directory / f'patches-{version}.mpp'
    candidates = sorted(p for p in directory.glob('patches-*.mpp')
                        if 'sources' not in p.name and 'javadoc' not in p.name)
    if candidates != [expected] or not expected.is_file() or expected.is_symlink():
        return [('patches/build/libs', 'expected-only-current-release-mpp')]
    if not expected.resolve().is_relative_to(root.resolve()):
        return [('patches/build/libs', 'unsafe-release-asset-path')]
    return []


def git(root, *args):
    return subprocess.run(['git', '-C', str(root), *args], check=True, capture_output=True).stdout


def collect_files(root, worktree=False, release_candidate=False):
    records = git(root, 'ls-files', '--stage', '-z').split(b'\0')
    files, failures = {}, []
    names = set()
    for record in filter(None, records):
        metadata, path = record.split(b'\t', 1)
        mode, oid, stage = metadata.split()
        name = path.decode('utf-8')
        names.add(name)
        if mode not in (b'100644', b'100755'):
            failures.append((name, 'non-regular-index-entry'))
            continue
        if stage != b'0':
            failures.append((name, 'unmerged-index'))
            continue
        if not (worktree or release_candidate):
            files[name] = git(root, 'cat-file', 'blob', oid.decode())
    if worktree:
        names.update(p.decode('utf-8') for p in git(root, 'ls-files', '--others', '--exclude-standard', '-z').split(b'\0') if p)
    if release_candidate:
        # Exact native @semantic-release/git assets, not arbitrary build output.
        names.update({'CHANGELOG.md', 'gradle.properties', 'patches-bundle.json', 'patches-list.json', 'README.md'})
    if worktree or release_candidate:
        for name in sorted(names):
            path = root / name
            if path.is_symlink() or not path.resolve().is_relative_to(root):
                failures.append((name, 'unsafe-worktree-path'))
            elif path.is_file():
                files[name] = path.read_bytes()
            elif path.exists():
                failures.append((name, 'non-regular-worktree-entry'))
            # Deleted tracked files are absent from the proposed worktree scope.
    return files, failures


def audit(root, worktree=False, require_release_metadata=False, expected_version=None, release_candidate=False):
    files, failures = collect_files(root, worktree, release_candidate)
    for name, data in files.items():
        failures.extend((name, rule) for rule in check_bytes(name, data))
    for name in REVIEWED_TEXT:
        if name not in files:
            failures.append((name, 'missing-reviewed-release-surface'))
    failures.extend(source_contract(files))
    failures.extend(metadata_contract(files, require_release_metadata or release_candidate, expected_version))
    if release_candidate and 'patches-bundle.json' in files and not manifest_issues(files['patches-bundle.json']):
        failures.extend(release_asset_issues(root, load_json(files['patches-bundle.json'])['version']))
    if not files:
        failures.append(('<index>', 'empty-scope-not-a-safety-pass'))
    scope = ('native release candidate (tracked worktree + explicit git assets)' if release_candidate
             else 'UNSTAGED worktree preflight' if worktree else 'exact Git index')
    for name, rule in failures:
        # Escape paths as well: a malicious filename must not inject log lines.
        print(f'BLOCK: {ascii(name)}: {rule}')
    if failures:
        print(f'FAIL: {len(failures)} rules across {len(files)} {scope} files; values withheld.')
        return 1
    print(f'PASS: {len(files)} {scope} files; no forbidden material detected by configured rules.')
    if 'patches-bundle.json' not in files:
        print('INFO: No live root metadata yet; native release prepare must generate and validate it.')
    return 0


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    scope = parser.add_mutually_exclusive_group()
    scope.add_argument('--staged', action='store_true', help='Default: inspect the exact Git index.')
    scope.add_argument('--worktree', action='store_true', help='Unstaged preflight, never a staged audit.')
    scope.add_argument('--release-candidate', action='store_true', help='Overlay tracked source and only native git assets after prepare; ignore untracked build caches.')
    parser.add_argument('--require-release-metadata', action='store_true', help='Require both live root metadata files, never examples.')
    parser.add_argument('--expected-version', help='Require the semantic-release candidate version.')
    args = parser.parse_args()
    try:
        sys.exit(audit(args.root.resolve(), args.worktree, args.require_release_metadata or args.release_candidate, args.expected_version, args.release_candidate))
    except (subprocess.CalledProcessError, OSError, UnicodeError, ValueError):
        print('FAIL: Source inspection failed; raw output withheld.')
        sys.exit(1)
