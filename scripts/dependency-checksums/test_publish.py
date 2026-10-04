"""Use real git remotes to test the PR update and races; only GitHub/network are fake."""
import argparse
import copy
from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import publish
from publish import prepare, commit, GitHub, VerificationError, SUBJECT, BOT
from test_verify import metadata, digest, FakeRepository, OLD, NEW

META = 'gradle/verification-metadata.xml'
CATALOG = 'gradle/libs.versions.toml'

def git(path, *args):
    result = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false', '-C', str(path), *args], capture_output=True, text=True)
    if result.returncode:
        raise AssertionError(result.stderr)
    return result.stdout.strip()

class FakeGitHub:
    def __init__(self, pull):
        self.data = pull
    def pull(self, repository, number):
        return copy.deepcopy(self.data)
    def setup_git(self):
        pass
    def bot_id(self):
        return 1234

class PublishTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / 'repo'
        self.repo.mkdir()
        git(self.repo, 'init', '-b', 'main')
        git(self.repo, 'config', 'user.name', 'Test Author')
        git(self.repo, 'config', 'user.email', 'test@example.org')
        (self.repo / 'gradle').mkdir()
        (self.repo / META).write_bytes(metadata([(OLD, digest(b'old'))]))
        (self.repo / CATALOG).write_text('version = "1"\n')
        git(self.repo, 'add', 'gradle')
        git(self.repo, 'commit', '-m', 'build: baseline')
        self.base = git(self.repo, 'rev-parse', 'HEAD')
        self.branch = 'dependabot/gradle/library-2'
        git(self.repo, 'checkout', '-b', self.branch)
        (self.repo / CATALOG).write_text('version = "2"\n')
        git(self.repo, 'commit', '-am', 'build(deps): update library')
        self.head = git(self.repo, 'rev-parse', 'HEAD')
        git(self.repo, 'checkout', 'main')
        git(self.repo, 'merge', '--no-ff', self.branch, '-m', 'Merge candidate')
        self.tested = git(self.repo, 'rev-parse', 'HEAD')
        git(self.repo, 'checkout', '--detach', self.head)
        self.remote = self.root / 'remote.git'
        self.remote.mkdir()
        git(self.remote, 'init', '--bare')
        git(self.repo, 'remote', 'add', 'origin', str(self.remote))
        git(self.repo, 'push', 'origin', f'{self.head}:refs/heads/{self.branch}')
        self.api = FakeGitHub({'state':'open', 'user':{'login':'dependabot[bot]'},
                              'head':{'sha':self.head, 'ref':self.branch, 'repo':{'full_name':'Yukuhu/home-control'}},
                              'base':{'sha':self.base, 'ref':'main', 'repo':{'full_name':'Yukuhu/home-control'}}})
        self.artifact = self.root / 'artifact'
        self.artifact.mkdir()
        self.output = self.root / 'prepared'
        self.manifest = {'repository':'Yukuhu/home-control', 'pr':191, 'head':self.head, 'base':self.base,
                         'tested':self.tested, 'run_attempt':1}
        self.candidate = metadata([(OLD, digest(b'old')), (NEW, digest(b'new'))])
        self.write_artifact()
        self.args = argparse.Namespace(repository='Yukuhu/home-control', pr=191, head=self.head, base=self.base,
                                       tested=self.tested, run_attempt=1, artifact=self.artifact,
                                       checkout=self.repo, output=self.output, prepared=self.output)
        self.repository = FakeRepository({NEW: b'new'})

    def write_artifact(self):
        (self.artifact / 'manifest.json').write_text(json.dumps(self.manifest))
        (self.artifact / 'verification-metadata.xml').write_bytes(self.candidate)

    def prepare(self):
        return prepare(self.args, self.api, self.repository)

    def publish(self):
        return commit(self.args, self.api)

    def test_cli_loads_only_trusted_helpers_in_isolated_python(self):
        script = Path(__file__).with_name('publish.py').resolve()
        (self.root / 'verify.py').write_text('raise RuntimeError("untrusted import")')
        result = subprocess.run(['python3', '-I', str(script), '--help'], cwd=self.root, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('prepare', result.stdout)

    def test_publishes_one_metadata_commit_to_same_branch(self):
        self.assertEqual(self.prepare()['state'], 'ready')
        result = self.publish()
        new = git(self.remote, 'rev-parse', f'refs/heads/{self.branch}')
        self.assertEqual(result, {'state':'committed', 'commit_sha':new})
        self.assertEqual(git(self.repo, 'rev-parse', f'{new}^'), self.head)
        self.assertEqual(git(self.repo, 'diff-tree', '--no-commit-id', '--name-only', '-r', new), META)
        message = git(self.repo, 'show', '-s', '--format=%B', new)
        self.assertIn('[dependabot skip]', message)
        self.assertNotIn('[skip ci]', message)
        self.assertIn('Checksum-Base: ' + self.base, message)
        self.assertIn('Checksum-Catalog: ' + digest(b'version = "2"\n'), message)
        self.assertEqual(git(self.repo, 'status', '--porcelain'), '')

    def test_unchanged_candidate_creates_no_commit(self):
        self.candidate = (self.repo / META).read_bytes()
        self.write_artifact()
        self.assertEqual(self.prepare()['state'], 'unchanged')
        self.assertEqual(git(self.repo, 'rev-parse', 'HEAD'), self.head)

    def test_closed_or_moved_pr_is_superseded(self):
        for field in ('closed', 'moved', 'new_base'):
            with self.subTest(field=field):
                original = copy.deepcopy(self.api.data)
                if field == 'closed':
                    self.api.data['state'] = 'closed'
                elif field == 'moved':
                    self.api.data['head']['sha'] = '1' * 40
                else:
                    self.api.data['base']['sha'] = '1' * 40
                self.assertEqual(self.prepare()['state'], 'superseded')
                self.api.data = original

    def test_rejects_wrong_author_fork_and_wrong_target(self):
        for change in ('author', 'fork', 'target'):
            with self.subTest(change=change):
                original = copy.deepcopy(self.api.data)
                if change == 'author': self.api.data['user']['login'] = 'someone'
                elif change == 'fork': self.api.data['head']['repo']['full_name'] = 'someone/fork'
                else: self.api.data['base']['ref'] = 'other'
                with self.assertRaises(VerificationError): self.prepare()
                self.api.data = original

    def test_rejects_manifest_tampering_and_symlink(self):
        self.manifest['head'] = '1' * 40
        self.write_artifact()
        with self.assertRaises(VerificationError): self.prepare()
        self.manifest['head'] = self.head
        self.write_artifact()
        path = self.artifact / 'verification-metadata.xml'
        path.unlink()
        path.symlink_to(self.repo / META)
        with self.assertRaises(VerificationError): self.prepare()

    def test_rejects_executable_file_changes(self):
        (self.repo / 'build.gradle.kts').write_text('untrusted()')
        git(self.repo, 'add', 'build.gradle.kts')
        git(self.repo, 'commit', '-m', 'build: altered script')
        new = git(self.repo, 'rev-parse', 'HEAD')
        self.args.head = new
        self.manifest['head'] = new
        self.api.data['head']['sha'] = new
        git(self.repo, 'checkout', 'main')
        git(self.repo, 'reset', '--hard', self.base)
        git(self.repo, 'merge', '--no-ff', new, '-m', 'Merge changed head')
        self.args.tested = self.manifest['tested'] = git(self.repo, 'rev-parse', 'HEAD')
        git(self.repo, 'checkout', '--detach', new)
        self.write_artifact()
        with self.assertRaisesRegex(VerificationError, 'allow only version catalog and metadata'):
            self.prepare()

    def cli(self, command):
        argv = ['publish.py', command, '--repository', self.args.repository, '--pr', str(self.args.pr),
                '--head', self.head, '--checkout', str(self.repo)]
        if command == 'prepare':
            argv += ['--base', self.base, '--tested', self.tested, '--run-attempt', '1',
                     '--artifact', str(self.artifact), '--output', str(self.output)]
        else:
            argv += ['--prepared', str(self.output)]
        stdout = io.StringIO()
        with patch('sys.argv', argv), patch('publish.GitHub', return_value=self.api), \
                patch('publish.Repository', return_value=self.repository), redirect_stdout(stdout):
            publish.main()
        return json.loads(stdout.getvalue())

    def test_cli_prepares_and_commits_with_machine_readable_outputs(self):
        output = self.root / 'github-output'
        with patch.dict(os.environ, {'GITHUB_OUTPUT': str(output)}):
            self.assertEqual(self.cli('prepare'), {'state': 'ready'})
            result = self.cli('commit')
        self.assertEqual(result['state'], 'committed')
        self.assertIn('state=ready\nstate=committed\n', output.read_text())
        self.assertIn('commit_sha=' + result['commit_sha'], output.read_text())
        self.assertEqual(git(self.remote, 'rev-parse', f'refs/heads/{self.branch}'), result['commit_sha'])

    def test_invalid_cli_repository_is_rejected_before_github_or_git(self):
        self.args.repository = 'owner/repo;--exec=malicious'
        stderr = io.StringIO()
        with patch.object(self.api, 'pull') as request, redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as error:
                self.cli('prepare')
        self.assertEqual(error.exception.code, 1)
        self.assertIn('Invalid repository', stderr.getvalue())
        request.assert_not_called()

    def test_invalid_revision_is_rejected_before_github_or_git(self):
        for name in ('head', 'base', 'tested'):
            with self.subTest(name=name):
                args = copy.copy(self.args)
                setattr(args, name, '--output=/tmp/injected')
                with patch.object(self.api, 'pull') as request:
                    with self.assertRaisesRegex(VerificationError, 'Invalid commit SHA'):
                        prepare(args, self.api, self.repository)
                    request.assert_not_called()

    def test_rejects_metadata_changed_after_verification(self):
        self.prepare()
        (self.output / 'verification-metadata.xml').write_bytes(b'tampered')
        with self.assertRaises(VerificationError): self.publish()
        self.assertEqual(git(self.remote, 'rev-parse', f'refs/heads/{self.branch}'), self.head)

    def test_rechecks_head_before_pushing(self):
        self.prepare()
        self.api.data['head']['sha'] = '1' * 40
        self.assertEqual(self.publish()['state'], 'superseded')
        self.assertEqual(git(self.repo, 'rev-parse', 'HEAD'), self.head)

    def test_remote_rewind_between_api_check_and_push_is_not_overwritten(self):
        self.prepare()
        git(self.remote, 'update-ref', f'refs/heads/{self.branch}', self.base)
        with self.assertRaises(VerificationError): self.publish()
        self.assertEqual(git(self.remote, 'rev-parse', f'refs/heads/{self.branch}'), self.base)

    def test_deleted_or_advanced_remote_is_not_overwritten(self):
        self.prepare()
        git(self.repo, 'commit', '--allow-empty', '-m', 'build: concurrent update')
        advanced = git(self.repo, 'rev-parse', 'HEAD')
        git(self.repo, 'push', 'origin', f'{advanced}:refs/heads/{self.branch}')
        git(self.repo, 'reset', '--hard', self.head)
        with self.assertRaises(VerificationError): self.publish()
        self.assertEqual(git(self.remote, 'rev-parse', f'refs/heads/{self.branch}'), advanced)
        git(self.repo, 'reset', '--hard', self.head)
        git(self.remote, 'update-ref', '-d', f'refs/heads/{self.branch}')
        with self.assertRaises(VerificationError): self.publish()
        self.assertEqual(git(self.remote, 'for-each-ref', '--format=%(refname)'), '')

    def test_rejects_repeat_update_for_same_inputs(self):
        self.prepare()
        result = self.publish()
        self.head = result['commit_sha']
        self.args.head = self.head
        self.api.data['head']['sha'] = self.head
        self.manifest['head'] = self.head
        # Build a new merge commit corresponding to the generated head.
        git(self.repo, 'checkout', 'main')
        git(self.repo, 'reset', '--hard', self.base)
        git(self.repo, 'merge', '--no-ff', self.head, '-m', 'Merge new head')
        self.args.tested = self.manifest['tested'] = git(self.repo, 'rev-parse', 'HEAD')
        git(self.repo, 'checkout', '--detach', self.head)
        self.write_artifact()
        with self.assertRaisesRegex(VerificationError, 'already updated'):
            self.prepare()

class GitHubTest(unittest.TestCase):
    def setUp(self):
        with patch('publish.shutil.which', return_value='/custom/bin/gh'):
            self.github = GitHub()

    def test_cli_is_located_and_missing_cli_fails_clearly(self):
        self.assertEqual(self.github.executable, '/custom/bin/gh')
        with patch('publish.shutil.which', return_value=None):
            with self.assertRaisesRegex(VerificationError, 'not installed'):
                GitHub()

    def test_pull_and_bot_id_use_json_from_github(self):
        responses = [subprocess.CompletedProcess([], 0, '{"state":"open"}'),
                     subprocess.CompletedProcess([], 0, '{"id":1234}')]
        with patch('publish.subprocess.run', side_effect=responses) as run:
            self.assertEqual(self.github.pull('Yukuhu/home-control', 191), {'state':'open'})
            self.assertEqual(self.github.bot_id(), 1234)
        self.assertEqual(run.call_args_list[0].args[0],
                         ['/custom/bin/gh', 'api', '--hostname', 'github.com',
                          'repos/Yukuhu/home-control/pulls/191'])
        self.assertEqual(run.call_args_list[1].args[0][-1], 'users/' + BOT)

    def test_api_failure_does_not_echo_sensitive_subprocess_output(self):
        response = subprocess.CompletedProcess([], 1, 'sensitive stdout', 'sensitive stderr')
        with patch('publish.subprocess.run', return_value=response):
            with self.assertRaisesRegex(VerificationError, 'GitHub API request failed') as error:
                self.github.pull('Yukuhu/home-control', 191)
        self.assertNotIn('sensitive', str(error.exception))

    def test_git_credentials_are_configured_by_gh_and_errors_propagate(self):
        success = subprocess.CompletedProcess([], 0)
        with patch('publish.subprocess.run', return_value=success) as run:
            self.github.setup_git()
        self.assertEqual(run.call_args.args[0], ['/custom/bin/gh', 'auth', 'setup-git', '--hostname', 'github.com'])
        failure = subprocess.CompletedProcess([], 1)
        with patch('publish.subprocess.run', return_value=failure):
            with self.assertRaisesRegex(VerificationError, 'configure git authentication'):
                self.github.setup_git()

if __name__ == '__main__':
    unittest.main()
