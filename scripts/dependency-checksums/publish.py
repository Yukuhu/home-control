#!/usr/bin/env python3
"""Publish verified metadata to the captured head of the existing Dependabot PR."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

from verify import MAX_XML, Repository, VerificationError, sha256, verify_metadata

META = 'gradle/verification-metadata.xml'
CATALOG = 'gradle/libs.versions.toml'
SUBJECT = 'build(deps): update dependency verification metadata [dependabot skip]'
BOT = 'yukuhu-home-control-checksums[bot]'
ALLOWED = {META, CATALOG}

class GitHub:
    def __init__(self):
        self.executable = shutil.which('gh')
        if not self.executable:
            raise VerificationError('GitHub CLI is not installed')

    def api(self, endpoint):
        result = subprocess.run([self.executable, 'api', '--hostname', 'github.com', endpoint], capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise VerificationError(f'GitHub API request failed: {endpoint}')
        return json.loads(result.stdout)

    def pull(self, repository, number):
        return self.api(f'repos/{repository}/pulls/{number}')

    def bot_id(self):
        return self.api(f'users/{BOT}')['id']

    def setup_git(self):
        result = subprocess.run([self.executable, 'auth', 'setup-git', '--hostname', 'github.com'], capture_output=True, timeout=60)
        if result.returncode:
            raise VerificationError('Could not configure git authentication through gh')

def git(checkout, *args):
    result = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false',
                             '-C', str(checkout), *args], capture_output=True, timeout=120)
    if result.returncode:
        raise VerificationError(f'Git {args[0]} failed; the branch may have changed or access was denied')
    return result.stdout

def text_git(checkout, *args):
    return git(checkout, *args).decode('utf-8').strip()

def validate_args(args):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository) or args.pr <= 0:
        raise VerificationError('Invalid repository or PR number')
    for name in ('head', 'base', 'tested'):
        value = getattr(args, name, None)
        if value is not None and not re.fullmatch(r'[a-f0-9]{40}', value):
            raise VerificationError('Invalid commit SHA')

def live_pr(args, github, base):
    pull = github.pull(args.repository, args.pr)
    if pull['state'] != 'open' or pull['head']['sha'] != args.head or pull['base']['sha'] != base:
        return None
    if (pull['user']['login'] != 'dependabot[bot]' or pull['head'].get('repo', {}).get('full_name') != args.repository
            or pull['base'].get('repo', {}).get('full_name') != args.repository or pull['base']['ref'] != 'main'):
        raise VerificationError('Automatic checksums require a same-repository Dependabot PR targeting main')
    branch = pull['head']['ref']
    if branch == 'main':
        raise VerificationError('Cannot publish to main')
    git(args.checkout, 'check-ref-format', 'refs/heads/' + branch)
    return branch

def read_file(path, limit=MAX_XML):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > limit:
        raise VerificationError(f'Expected a bounded regular file: {path.name}')
    # Do not follow a symlinked artifact directory either.
    if any(parent.is_symlink() for parent in path.parents):
        raise VerificationError('Symlinked input directory')
    return path.read_bytes()

def tracked_file(checkout, revision, path):
    mode = text_git(checkout, 'ls-tree', revision, '--', path).split(' ', 1)[0]
    if mode != '100644':
        raise VerificationError(f'Expected an ordinary tracked file: {path}')
    data = git(checkout, 'show', f'{revision}:{path}')
    if len(data) > MAX_XML:
        raise VerificationError(f'Oversized tracked file: {path}')
    return data

def clean_head(args):
    if text_git(args.checkout, 'rev-parse', 'HEAD') != args.head or text_git(args.checkout, 'status', '--porcelain'):
        raise VerificationError('PR checkout must be clean at the captured head')

def prepare(args, github=None, repository=None):
    validate_args(args)
    github = github or GitHub()
    clean_head(args)
    branch = live_pr(args, github, args.base)
    if branch is None:
        return {'state': 'superseded'}
    expected = {name: getattr(args, name) for name in ('repository', 'pr', 'head', 'base', 'tested', 'run_attempt')}
    manifest = json.loads(read_file(args.artifact / 'manifest.json', 16384))
    if manifest != expected:
        raise VerificationError('Candidate manifest does not match the workflow event')
    parents = text_git(args.checkout, 'rev-list', '--parents', '-n', '1', args.tested).split()
    if parents != [args.tested, args.base, args.head]:
        raise VerificationError('Tested revision is not the expected base/head merge')
    changed = set(git(args.checkout, 'diff', '--no-renames', '--name-only', '-z', f'{args.base}...{args.head}').decode().strip('\0').split('\0'))
    if not changed <= ALLOWED or CATALOG not in changed:
        raise VerificationError('Automatic checksums allow only version catalog and metadata changes')
    head_xml = tracked_file(args.checkout, args.head, META)
    base_xml = tracked_file(args.checkout, args.base, META)
    catalog = sha256(tracked_file(args.checkout, args.head, CATALOG))
    message = text_git(args.checkout, 'show', '-s', '--format=%B', args.head)
    email = text_git(args.checkout, 'show', '-s', '--format=%ae', args.head)
    if (message.splitlines()[0] == SUBJECT and email.endswith(f'+{BOT}@users.noreply.github.com')
            and f'Checksum-Base: {args.base}' in message.splitlines()
            and f'Checksum-Catalog: {catalog}' in message.splitlines()):
        raise VerificationError('This App already updated checksums for these inputs; refusing a commit loop')
    result = verify_metadata(head_xml, base_xml, read_file(args.artifact / 'verification-metadata.xml'), repository or Repository())
    if not result.changed:
        return {'state': 'unchanged'}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'verification-metadata.xml').write_bytes(result.xml)
    prepared = dict(expected, branch=branch, catalog=catalog, metadata_sha256=sha256(result.xml), additions=result.additions)
    (args.output / 'prepared.json').write_text(json.dumps(prepared, indent=2) + '\n')
    return {'state': 'ready'}

def commit(args, github=None):
    validate_args(args)
    github = github or GitHub()
    clean_head(args)
    prepared = json.loads(read_file(args.prepared / 'prepared.json'))
    if any(prepared.get(name) != getattr(args, name) for name in ('repository', 'pr', 'head')):
        raise VerificationError('Prepared update belongs to a different PR or head')
    branch = live_pr(args, github, prepared['base'])
    if branch is None:
        return {'state': 'superseded'}
    if branch != prepared['branch']:
        raise VerificationError('PR branch changed during verification')
    data = read_file(args.prepared / 'verification-metadata.xml')
    if sha256(data) != prepared['metadata_sha256']:
        raise VerificationError('Verified metadata changed before commit')
    tracked_file(args.checkout, args.head, META)
    destination = args.checkout / META
    if destination.is_symlink() or destination.parent.is_symlink():
        raise VerificationError('Metadata destination is a symlink')
    github.setup_git()
    bot_id = github.bot_id()
    if not isinstance(bot_id, int) or bot_id <= 0:
        raise VerificationError('Invalid App bot identity')
    git(args.checkout, 'config', 'user.name', BOT)
    git(args.checkout, 'config', 'user.email', f'{bot_id}+{BOT}@users.noreply.github.com')
    destination.write_bytes(data)
    git(args.checkout, 'add', '--', META)
    if text_git(args.checkout, 'diff', '--cached', '--name-only') != META:
        raise VerificationError('The update must stage only verification metadata')
    body = f'Checksum-Base: {prepared["base"]}\nChecksum-Catalog: {prepared["catalog"]}'
    git(args.checkout, 'commit', '-m', SUBJECT, '-m', body)
    new = text_git(args.checkout, 'rev-parse', 'HEAD')
    if text_git(args.checkout, 'rev-list', '--parents', '-n', '1', new).split() != [new, args.head]:
        raise VerificationError('The update must be a direct child of the captured PR head')
    if live_pr(args, github, prepared['base']) != branch:
        return {'state': 'superseded'}
    # A lease is a compare-and-swap guard here. The sole-parent check above proves this is
    # an append, never a history rewrite; even a concurrent rewind/deletion must reject it.
    ref = 'refs/heads/' + branch
    git(args.checkout, 'push', f'--force-with-lease={ref}:{args.head}', 'origin', f'{new}:{ref}')
    return {'state': 'committed', 'commit_sha': new}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('prepare', 'commit'):
        command = commands.add_parser(name)
        command.add_argument('--repository', required=True)
        command.add_argument('--pr', type=int, required=True)
        command.add_argument('--head', required=True)
        command.add_argument('--checkout', type=Path, required=True)
        if name == 'prepare':
            for option in ('base', 'tested'):
                command.add_argument('--' + option, required=True)
            command.add_argument('--run-attempt', type=int, required=True)
            command.add_argument('--artifact', type=Path, required=True)
            command.add_argument('--output', type=Path, required=True)
        else:
            command.add_argument('--prepared', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = prepare(args) if args.command == 'prepare' else commit(args)
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                for name, value in result.items():
                    output.write(f'{name}={value}\n')
        print(json.dumps(result))
    except (VerificationError, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        parser.exit(1, f'Checksum update failed: {error}\n')

if __name__ == '__main__':
    main()
