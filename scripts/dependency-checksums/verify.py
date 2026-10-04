#!/usr/bin/env python3
"""Verify additive Gradle metadata against fresh canonical repository downloads."""
import argparse
from dataclasses import dataclass
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import build_opener, HTTPRedirectHandler, Request
import xml.etree.ElementTree as ET
from xml.sax.saxutils import quoteattr

NS = 'https://schema.gradle.org/dependency-verification'
CENTRAL = 'https://repo.maven.apache.org/maven2/'
PORTAL = 'https://plugins.gradle.org/m2/'
HOSTS = {'repo.maven.apache.org', 'plugins.gradle.org', 'plugins-artifacts.gradle.org'}
MAX_XML = 5 * 1024 * 1024
MAX_ARTIFACT = 512 * 1024 * 1024
IDENTIFIER = re.compile(r'\w[\w.+\-]*\Z', re.ASCII)
SHA256 = re.compile(r'[a-f0-9]{64}\Z')

class VerificationError(ValueError):
    """A candidate cannot be accepted without changing the trust policy."""

@dataclass
class Download:
    body: bytes
    source_url: str

@dataclass
class VerifiedMetadata:
    xml: bytes
    changed: bool
    additions: list[dict]

def sha256(body):
    return hashlib.sha256(body).hexdigest()

def safe_identifier(value):
    if not isinstance(value, str) or not IDENTIFIER.fullmatch(value) or '..' in value:
        raise VerificationError('Invalid artifact coordinate or name')
    return value

def coordinate(key):
    group, module, version, artifact = map(safe_identifier, key)
    if not all(group.split('.')) or not artifact.startswith(f'{module}-{version}.') and not artifact.startswith(f'{module}-{version}-'):
        raise VerificationError('Artifact name does not match its coordinate')
    return f'{group.replace(".", "/")}/{module}/{version}/{artifact}'

def approved_url(url):
    parts = urlsplit(url)
    if parts.scheme != 'https' or parts.hostname not in HOSTS or parts.port not in (None, 443) or parts.username or parts.password:
        raise VerificationError('Repository redirected outside the approved HTTPS origins')

class RepositoryRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        approved_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)

class Repository:
    def __init__(self):
        self.opener = build_opener(RepositoryRedirects())

    def _read(self, url, limit=MAX_ARTIFACT):
        approved_url(url)
        for attempt in range(3):
            try:
                with self.opener.open(Request(url, headers={'User-Agent': 'home-control-checksums'}), timeout=45) as response:
                    approved_url(response.url)
                    data = response.read(limit + 1)
                    if len(data) > limit:
                        raise VerificationError('Repository response exceeds its size limit')
                    return data
            except HTTPError as error:
                if error.code not in (429, 500, 502, 503, 504):
                    raise
            except OSError:
                pass
            if attempt < 2:
                time.sleep(attempt + 1)
        raise VerificationError('Repository download failed after three attempts')

    def fetch(self, group, module, version, artifact):
        path = coordinate((group, module, version, artifact))
        url = CENTRAL + path
        try:
            try:
                body = self._read(url)
            except HTTPError as error:
                if error.code != 404:
                    raise
                url = PORTAL + path
                body = self._read(url)
        except HTTPError as error:
            raise VerificationError(f'Artifact unavailable: {path} (HTTP {error.code})') from None
        return Download(body, url)

    def protoc_artifacts(self, version):
        safe_identifier(version)
        listing = Links()
        try:
            listing.feed(self._read(f'{CENTRAL}com/google/protobuf/protoc/{version}/', MAX_XML).decode('utf-8'))
        except (HTTPError, UnicodeError) as error:
            raise VerificationError('Cannot read the protoc platform listing') from error
        names = set()
        for name in listing.links:
            if not name.endswith('.exe'):
                continue
            if not re.fullmatch(rf'protoc-{re.escape(version)}-[a-z0-9_]+-[a-z0-9_]+\.exe', name):
                raise VerificationError('Invalid executable in protoc platform listing')
            names.add(name)
        if not names:
            raise VerificationError('No published protoc platform executables found')
        return sorted(names)

class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []

    def handle_starttag(self, tag, attrs):
        if tag == 'a':
            self.links.extend(value for name, value in attrs if name == 'href' and value)

def tag(name):
    return f'{{{NS}}}{name}'

def validate_configuration(root):
    if root.tag != tag('verification-metadata') or [child.tag for child in root] != [tag('configuration'), tag('components')]:
        raise VerificationError('Unexpected verification metadata structure')
    config = root[0]
    if config.attrib or [node.tag for node in config] != [tag('verify-metadata'), tag('verify-signatures')]:
        raise VerificationError('Unexpected verification configuration')
    if [(node.text or '').strip() for node in config] != ['true', 'false'] or any(node.attrib or len(node) for node in config):
        raise VerificationError('Verification configuration changed')
    if root[1].attrib:
        raise VerificationError('Unexpected components attributes')

def parse_artifact(identity, artifact):
    if artifact.tag != tag('artifact') or set(artifact.attrib) != {'name'} or len(artifact) != 1:
        raise VerificationError('Unexpected artifact structure or alternate hashes')
    key = identity + (artifact.get('name'),)
    coordinate(key)
    checksum = artifact[0]
    if checksum.tag != tag('sha256') or len(checksum) or not set(checksum.attrib) <= {'value', 'origin'} or not SHA256.fullmatch(checksum.get('value', '')):
        raise VerificationError('Unexpected artifact checksum')
    return key, dict(checksum.attrib)

def parse(data):
    if len(data) > MAX_XML or b'<!DOCTYPE' in data.upper() or b'<!ENTITY' in data.upper() or b'\x00' in data:
        raise VerificationError('Unsafe or oversized verification XML')
    try:
        root = ET.fromstring(data)
    except ET.ParseError as error:
        raise VerificationError('Malformed verification XML') from error
    validate_configuration(root)
    components, artifacts = set(), {}
    for component in root[1]:
        if component.tag != tag('component') or set(component.attrib) != {'group', 'name', 'version'}:
            raise VerificationError('Unexpected component structure')
        identity = tuple(safe_identifier(component.get(name)) for name in ('group', 'name', 'version'))
        if identity in components or not len(component):
            raise VerificationError('Duplicate or empty component')
        components.add(identity)
        for artifact in component:
            key, attributes = parse_artifact(identity, artifact)
            if key in artifacts:
                raise VerificationError('Duplicate artifact')
            artifacts[key] = attributes
    return root.attrib, artifacts

def insert_before_closing(text, end, block, indentation):
    line = text.rfind('\n', 0, end) + 1
    start = line if text[line:end].isspace() else end
    return text[:start] + block + indentation + text[end:]

def append_component_artifacts(text, identity, artifacts):
    # Match parsed attributes independently of their order/quotes, retaining the original bytes.
    for match in re.finditer(r'<component\s[^>]*>.*?</component>', text, re.S):
        element = ET.fromstring(match.group())
        if tuple(element.get(n) for n in ('group', 'name', 'version')) == identity:
            end = match.end() - len('</component>')
            return insert_before_closing(text, end, artifacts, '      ')
    raise VerificationError('Cannot locate the existing component')

def render_additions(head, trusted, accepted):
    """Insert additions while keeping the committed file's existing bytes and hashes."""
    text = head.decode('utf-8')
    grouped = {}
    for key in sorted(accepted):
        if key not in trusted:
            grouped.setdefault(key[:3], []).append((key[3], accepted[key]))
    for (group, module, version), additions in grouped.items():
        artifacts = ''.join(
            f'         <artifact name={quoteattr(name)}>\n'
            f'            <sha256 value={quoteattr(attrs["value"])} origin={quoteattr(attrs.get("origin", "Verified metadata from base"))} />\n'
            '         </artifact>\n' for name, attrs in additions)
        if any(key[:3] == (group, module, version) for key in trusted):
            text = append_component_artifacts(text, (group, module, version), artifacts)
        else:
            block = (f'      <component group={quoteattr(group)} name={quoteattr(module)} version={quoteattr(version)}>\n'
                     + artifacts + '      </component>\n')
            end = text.index('</components>')
            text = insert_before_closing(text, end, block, '   ')
    return text.encode('utf-8')

def trusted_metadata(head, base, candidate):
    trusted = dict(head)
    for key, attrs in base.items():
        if key in trusted and trusted[key]['value'] != attrs['value']:
            raise VerificationError('Conflicting trusted checksums in head and base')
        trusted.setdefault(key, attrs)
    for key, attrs in trusted.items():
        if key not in candidate or candidate[key]['value'] != attrs['value']:
            raise VerificationError(f'Trusted checksum removed or changed: {key[-1]}')
    return trusted

def verify_candidate_artifacts(candidate, trusted, accepted, additions, repository):
    module_companions = set()
    for key, attrs in candidate.items():
        if key in trusted:
            continue
        download = repository.fetch(*key)
        if sha256(download.body) != attrs['value']:
            raise VerificationError(f'Checksum mismatch: {key[-1]}')
        accept(accepted, additions, key, download)
        if key[3] == f'{key[1]}-{key[2]}.pom' and b'do_not_remove: published-with-gradle-metadata' in download.body:
            module_companions.add(key[:3] + (f'{key[1]}-{key[2]}.module',))
    # Gradle's generation can omit a module fetched while resolving an imported BOM.
    for key in sorted(module_companions):
        if key not in accepted:
            accept(accepted, additions, key, repository.fetch(*key))

def complete_protoc_platforms(candidate, trusted, accepted, additions, repository):
    compiler = ('com.google.protobuf', 'protoc')
    candidate_versions = {key[2] for key in candidate if key[:2] == compiler}
    trusted_versions = {key[2] for key in trusted if key[:2] == compiler}
    for version in sorted(candidate_versions - trusted_versions):
        for artifact in repository.protoc_artifacts(version):
            key = ('com.google.protobuf', 'protoc', version, artifact)
            coordinate(key)
            if key not in accepted:
                accept(accepted, additions, key, repository.fetch(*key))

def verify_metadata(head_xml, base_xml, candidate_xml, repository):
    head_attrs, head = parse(head_xml)
    base_attrs, base = parse(base_xml)
    candidate_attrs, candidate = parse(candidate_xml)
    if head_attrs != base_attrs or head_attrs != candidate_attrs:
        raise VerificationError('Verification root attributes changed')
    trusted = trusted_metadata(head, base, candidate)
    accepted = dict(trusted)
    additions = []
    verify_candidate_artifacts(candidate, trusted, accepted, additions, repository)
    complete_protoc_platforms(candidate, trusted, accepted, additions, repository)
    output = render_additions(head_xml, head, accepted) if set(accepted) != set(head) else head_xml
    _, rendered = parse(output)
    if {k: v['value'] for k, v in rendered.items()} != {k: v['value'] for k, v in accepted.items()}:
        raise VerificationError('Rendered metadata does not match the verified entries')
    return VerifiedMetadata(output, output != head_xml, additions)

def accept(accepted, additions, key, download):
    approved_url(download.source_url)
    value = sha256(download.body)
    source = 'Maven Central' if download.source_url.startswith(CENTRAL) else 'Gradle Plugin Portal'
    accepted[key] = {'value': value, 'origin': f'Verified against {source}'}
    additions.append(dict(zip(('group', 'module', 'version', 'artifact'), key), sha256=value, source_url=download.source_url))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('head', 'base', 'candidate', 'output', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    try:
        # This local verifier deliberately reads/writes caller-selected files; it has no elevated credential or agent/web input.
        result = verify_metadata(args.head.read_bytes(), args.base.read_bytes(), args.candidate.read_bytes(), Repository())  # NOSONAR(S8707)
        # Explicit local CLI outputs, not paths taken from candidate XML.
        args.output.write_bytes(result.xml)  # NOSONAR(S8707)
        args.report.write_text(json.dumps({'changed': result.changed, 'additions': result.additions}, indent=2) + '\n')  # NOSONAR(S8707)
    except (VerificationError, OSError) as error:
        parser.exit(1, f'Checksum verification failed: {error}\n')

if __name__ == '__main__':
    main()
