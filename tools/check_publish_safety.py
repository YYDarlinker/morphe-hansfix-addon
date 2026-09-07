"""Check the exact Git index before upload. Never prints secret values."""
from pathlib import Path, PurePosixPath
import argparse, hashlib, re, subprocess, sys

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN_EXTENSIONS = {'.apk','.apkm','.aab','.dex','.smali','.mpp','.mpe','.keystore','.jks','.p12','.pfx','.pem','.key','.idsig','.jpg','.jpeg','.mp4','.zip','.log','.pyc'}
FORBIDDEN_PARTS = {'upload','apk-work','decoded','private-fixtures','private-signing','local-build','build','.gradle','.kotlin','__pycache__'}
ALLOWED_BINARY = {'gradle/wrapper/gradle-wrapper.jar': '7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d'}
TOKEN = re.compile(rb'(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{30,})')
PRIVATE_KEY = re.compile(rb'-----BEGIN [A-Z ]*PRIVATE KEY-----')
COOKIE_VALUE = re.compile(rb'(?:YSC|VISITOR_INFO1_LIVE|VISITOR_PRIVACY_METADATA|__Secure-ROLLOUT_TOKEN)\s*[=:]\s*[\"\x27]?[A-Za-z0-9_%+/-]{24,}')

def check_bytes(name, data):
    issues=[]; p=PurePosixPath(name); low=name.lower()
    if p.is_absolute() or '..' in p.parts or '\\' in name: issues.append('unsafe-path')
    if any(part.lower() in FORBIDDEN_PARTS for part in p.parts): issues.append('private-or-generated-directory')
    if p.suffix.lower() in FORBIDDEN_EXTENSIONS: issues.append('forbidden-file-type')
    if p.name.lower()=='local.properties' or p.name.lower().startswith(('.env','local-env.')): issues.append('local-configuration')
    if low=='patches-bundle.json': issues.append('preparation-has-no-live-manifest')
    if low.startswith('.github/workflows/'):
        if name!='.github/workflows/preparation-ci.yml': issues.append('unapproved-active-workflow')
        if re.search(rb'(?:contents|packages|actions|attestations|id-token)\s*:\s*write\b|write-all|pull_request_target|secrets\.|gh\s+release|semantic-release|upload-artifact',data,re.I): issues.append('workflow-exceeds-preparation-scope')
    if p.suffix.lower() in {'.jar','.png','.webp','.gif'} or b'\0' in data:
        if ALLOWED_BINARY.get(name)!=hashlib.sha256(data).hexdigest():issues.append('unapproved-binary')
        return issues
    try:data.decode('utf-8-sig')
    except UnicodeDecodeError:issues.append('unapproved-encoding');return issues
    if len(data)>2_000_000:issues.append('unexpected-large-text-file')
    if TOKEN.search(data):issues.append('credential-token-pattern')
    if PRIVATE_KEY.search(data):issues.append('private-key-header')
    if COOKIE_VALUE.search(data):issues.append('possible-cookie-value')
    return issues

def git(root,*args):
    return subprocess.run(['git','-C',str(root),*args],check=True,capture_output=True).stdout

def audit(root):
    records=git(root,'ls-files','--stage','-z').split(b'\0'); failures=[];count=0
    for record in records:
        if not record:continue
        metadata,path=record.split(b'\t',1);mode,oid,stage=metadata.split();name=path.decode('utf8')
        count+=1
        if mode==b'120000': failures.append((name,'symlink-not-allowed'));continue
        if stage!=b'0':failures.append((name,'unmerged-index'));continue
        data=git(root,'cat-file','blob',oid.decode())
        failures.extend((name,rule) for rule in check_bytes(name,data))
    if not count:failures.append(('<index>','empty-index-not-a-safety-pass'))
    for name,rule in failures:print(f'BLOCK: {name}: {rule}')
    if failures:print(f'FAIL: {len(failures)} rules across {count} index files; values withheld.');return 1
    print(f'PASS: {count} exact Git-index files checked; no forbidden material detected.');return 0

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',type=Path,default=ROOT)
    parser.add_argument('--staged',action='store_true',help='Compatibility flag: the exact staged index is always used.')
    args=parser.parse_args()
    try:sys.exit(audit(args.root.resolve()))
    except subprocess.CalledProcessError:print('FAIL: Git inspection failed; raw output withheld.');sys.exit(1)
