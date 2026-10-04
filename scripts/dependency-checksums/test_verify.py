"""Metadata integrity tests; repository bytes are controlled, validation is real."""
import hashlib
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

from verify import Download, Repository, VerificationError, verify_metadata

NS = 'https://schema.gradle.org/dependency-verification'
OLD = ('org.example', 'library', '1.0', 'library-1.0.jar')
NEW = ('org.example', 'library', '2.0', 'library-2.0.jar')

def digest(body):
    return hashlib.sha256(body).hexdigest()

def metadata(entries, configuration=None):
    config = configuration or '<verify-metadata>true</verify-metadata><verify-signatures>false</verify-signatures>'
    groups = {}
    for key, value in entries:
        groups.setdefault(key[:3], []).append((key[3], value))
    components = ''.join(
        f'<component group="{g}" name="{n}" version="{v}">' + ''.join(
            f'<artifact name="{a}"><sha256 value="{h}" origin="fixture" /></artifact>' for a, h in artifacts
        ) + '</component>' for (g, n, v), artifacts in groups.items())
    return (f'<?xml version="1.0" encoding="UTF-8"?>\n<verification-metadata xmlns="{NS}">'
            f'<configuration>{config}</configuration><components>{components}</components></verification-metadata>\n').encode()

class FakeRepository:
    def __init__(self, artifacts=None, platforms=None):
        self.artifacts = artifacts or {}
        self.platforms = platforms or []
        self.requests = []

    def fetch(self, *key):
        self.requests.append(key)
        if key not in self.artifacts:
            raise VerificationError('artifact unavailable')
        return Download(self.artifacts[key], 'https://repo.maven.apache.org/maven2/fixture')

    def protoc_artifacts(self, version):
        return self.platforms

class VerificationTest(unittest.TestCase):
    def setUp(self):
        self.head = metadata([(OLD, digest(b'old'))])

    def verify(self, candidate, repo=None, base=None, head=None):
        return verify_metadata(head or self.head, base or self.head, candidate, repo or FakeRepository())

    def test_pr191_adds_all_five_artifacts_and_is_idempotent(self):
        coordinates = [
            ('org.apache.commons', 'commons-lang3', '3.21.0', 'commons-lang3-3.21.0.jar'),
            ('org.apache.commons', 'commons-lang3', '3.21.0', 'commons-lang3-3.21.0.pom'),
            ('org.apache.commons', 'commons-parent', '105', 'commons-parent-105.pom'),
            ('org.junit', 'junit-bom', '5.14.4', 'junit-bom-5.14.4.module'),
            ('org.junit', 'junit-bom', '5.14.4', 'junit-bom-5.14.4.pom'),
        ]
        artifacts = {key: key[-1].encode() for key in coordinates}
        candidate = metadata([(OLD, digest(b'old'))] + [(k, digest(v)) for k, v in artifacts.items()])
        result = self.verify(candidate, FakeRepository(artifacts))
        self.assertTrue(result.changed)
        self.assertEqual(len(result.additions), 5)
        self.assertIn(b'Verified against Maven Central', result.xml)
        again = self.verify(result.xml, head=result.xml, base=result.xml)
        self.assertFalse(again.changed)
        self.assertEqual(again.xml, result.xml)

    def test_unchanged_is_byte_identical_without_downloads(self):
        result = self.verify(self.head)
        self.assertFalse(result.changed)
        self.assertEqual(result.xml, self.head)

    def test_base_additions_are_preserved_without_downloads(self):
        base = metadata([(OLD, digest(b'old')), (NEW, digest(b'new'))])
        result = self.verify(base, base=base)
        self.assertTrue(result.changed)
        self.assertIn(digest(b'new').encode(), result.xml)

    def test_addition_to_existing_component_preserves_old_artifact(self):
        pom = OLD[:3] + ('library-1.0.pom',)
        candidate = metadata([(OLD, digest(b'old')), (pom, digest(b'pom'))])
        result = self.verify(candidate, FakeRepository({pom: b'pom'}))
        self.assertIn(digest(b'old').encode(), result.xml)
        self.assertIn(digest(b'pom').encode(), result.xml)
        self.assertEqual(result.xml.count(b'<component '), 1)

    def test_rejects_removal_changed_hash_and_conflicting_base(self):
        for candidate in (metadata([]), metadata([(OLD, digest(b'changed'))])):
            with self.subTest(candidate=candidate), self.assertRaises(VerificationError):
                self.verify(candidate)
        with self.assertRaises(VerificationError):
            self.verify(self.head, base=metadata([(OLD, digest(b'conflict'))]))

    def test_rejects_alternative_hashes_and_duplicate_artifacts(self):
        extra_hash = self.head.replace(b'</artifact>', b'<sha256 value="' + digest(b'other').encode() + b'" /></artifact>')
        artifact = self.head.split(b'<artifact', 1)[1].split(b'</artifact>', 1)[0]
        duplicate = self.head.replace(b'</component>', b'<artifact' + artifact + b'</artifact></component>')
        for candidate in (extra_hash, duplicate, self.head.replace(b'</components>', self.head.split(b'<components>')[1].split(b'</components>')[0] + b'</components>')):
            with self.subTest(candidate=candidate), self.assertRaises(VerificationError):
                self.verify(candidate)

    def test_rejects_changed_policy_or_unexpected_elements(self):
        for before, after in [(b'<verify-metadata>true', b'<verify-metadata>false'),
                              (b'</configuration>', b'<trusted-artifacts /></configuration>'),
                              (b'</artifact>', b'<ignored-keys /></artifact>')]:
            with self.subTest(after=after), self.assertRaises(VerificationError):
                self.verify(self.head.replace(before, after))

    def test_rejects_unsafe_xml_and_paths(self):
        candidates = [b'<bad', b'<!DOCTYPE x []>' + self.head, b'x' * (5 * 1024 * 1024 + 1)]
        for group, name, version, artifact in [('org.example', 'library', '../2', 'library-2.jar'),
                                              ('../evil', 'library', '2', 'library-2.jar'),
                                              ('org.example', 'library', '2', '../../key'),
                                              ('org.example', 'library', '2', 'library-2%2fkey.jar')]:
            candidates.append(metadata([((group, name, version, artifact), digest(b'new'))]))
        for candidate in candidates:
            with self.subTest(prefix=candidate[:50]), self.assertRaises(VerificationError):
                self.verify(candidate)

    def test_rejects_incorrect_repository_bytes(self):
        candidate = metadata([(OLD, digest(b'old')), (NEW, digest(b'new'))])
        with self.assertRaisesRegex(VerificationError, 'mismatch'):
            self.verify(candidate, FakeRepository({NEW: b'changed'}))

    def test_pom_marker_completes_gradle_module_omitted_by_generation(self):
        pom = ('org.junit', 'junit-bom', '5.14.4', 'junit-bom-5.14.4.pom')
        module = pom[:3] + ('junit-bom-5.14.4.module',)
        pom_bytes = b'<project><!-- do_not_remove: published-with-gradle-metadata --></project>'
        candidate = metadata([(OLD, digest(b'old')), (pom, digest(pom_bytes))])
        result = self.verify(candidate, FakeRepository({pom: pom_bytes, module: b'module bytes'}))
        self.assertIn(b'junit-bom-5.14.4.module', result.xml)
        self.assertIn(digest(b'module bytes').encode(), result.xml)
        self.assertEqual(len(result.additions), 2)
        with self.assertRaises(VerificationError):
            self.verify(candidate, FakeRepository({pom: pom_bytes}))

    def test_protoc_completes_all_published_platforms(self):
        group = ('com.google.protobuf', 'protoc', '4.99.0')
        names = ['protoc-4.99.0-linux-x86_64.exe', 'protoc-4.99.0-osx-aarch_64.exe', 'protoc-4.99.0-windows-x86_64.exe']
        artifacts = {group + (name,): name.encode() for name in names}
        candidate = metadata([(OLD, digest(b'old')), (group + (names[0],), digest(names[0].encode()))])
        result = self.verify(candidate, FakeRepository(artifacts, names))
        self.assertEqual(len(result.additions), 3)
        for name in names:
            self.assertIn(name.encode(), result.xml)
        with self.assertRaises(VerificationError):
            self.verify(candidate, FakeRepository({group + (names[0],): names[0].encode()}, names))

class RepositoryTest(unittest.TestCase):
    def test_central_first_and_only_404_falls_back(self):
        repo = Repository()
        with patch.object(repo, '_read', return_value=b'bytes') as read:
            self.assertEqual(repo.fetch(*NEW).body, b'bytes')
            self.assertEqual(read.call_args.args[0], 'https://repo.maven.apache.org/maven2/org/example/library/2.0/library-2.0.jar')
        missing = HTTPError('https://repo.maven.apache.org', 404, 'missing', {}, None)
        self.addCleanup(missing.close)
        with patch.object(repo, '_read', side_effect=[missing, b'plugin']):
            self.assertIn('plugins.gradle.org', repo.fetch(*NEW).source_url)
        denied = HTTPError('https://repo.maven.apache.org', 403, 'denied', {}, None)
        self.addCleanup(denied.close)
        with patch.object(repo, '_read', side_effect=denied) as read:
            with self.assertRaises(VerificationError):
                repo.fetch(*NEW)
            self.assertEqual(read.call_count, 1)

    def test_download_retries_are_bounded(self):
        repo = Repository()
        with patch.object(repo.opener, 'open', side_effect=URLError('offline')) as request, patch('verify.time.sleep'):
            with self.assertRaises(VerificationError):
                repo.fetch(*NEW)
            self.assertEqual(request.call_count, 3)

    def test_redirect_rejects_other_hosts_and_http(self):
        from verify import RepositoryRedirects
        redirects = RepositoryRedirects()
        for url in ['http://repo.maven.apache.org/a', 'https://example.org/a', 'https://repo.maven.apache.org:444/a']:
            with self.subTest(url=url), self.assertRaises(VerificationError):
                redirects.redirect_request(None, None, 302, '', {}, url)

    def test_protoc_listing_filters_sidecars_and_rejects_invalid_entries(self):
        repo = Repository()
        with patch.object(repo, '_read', return_value=b'<a href="../">Parent</a><a href="protoc-4.99.0-linux-x86_64.exe">exe</a><a href="protoc-4.99.0-linux-x86_64.exe.sha256">hash</a>'):
            self.assertEqual(repo.protoc_artifacts('4.99.0'), ['protoc-4.99.0-linux-x86_64.exe'])
        for listing in [b'<a href="../protoc-4.99.0-linux-x86_64.exe">bad</a>', b'<a href="protoc-4.99.0-linux%2fx86.exe">bad</a>', b'']:
            with self.subTest(listing=listing), patch.object(repo, '_read', return_value=listing), self.assertRaises(VerificationError):
                repo.protoc_artifacts('4.99.0')

if __name__ == '__main__':
    unittest.main()
