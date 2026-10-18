#!/usr/bin/env python3
"""Publication guard; errors show paths/line numbers/categories, never matching values.

Without Git, scan every actual file, including hidden and ignored inputs. In a
repository, scan indexed blobs so worktree cleanup cannot hide a staged secret.
Use --tree as an additional check before publication. This is a deny-list review
aid; it does not establish absence of arbitrary encodings, unknown identifiers,
embedded binary payloads or secrets in Git history. Python 3.8+; no dependencies.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MAX_FILE_BYTES = 8 * 1024 * 1024
WRAPPER = "gradle/wrapper/gradle-wrapper.jar"
WRAPPER_SHA256 = "76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3"
GENERATED_PNG_SHA256 = {
    "common/src/main/res/raw/ic_car_home.png": "009da1f161862c5a58448b5527bde39a94a412e49a9c6f26e61a69837f7bc362",
    "common/src/main/res/raw/placeholder_icon.png": "009da1f161862c5a58448b5527bde39a94a412e49a9c6f26e61a69837f7bc362",
}
NATIVE_NAMES = {"libusbiface.so", "libscreencrypto.so"}
NATIVE_DIR = "adaptation/receiver/assets/native"
BLOCKED_DIRS = {
    ".private", "private-inputs", "secrets", "credentials", "auth-assets", "offline-mfi",
    "build", ".gradle", ".kotlin", ".cxx", ".externalnativebuild", "__pycache__",
    ".cache", "node_modules", "downloads", "tools", ".idea", ".vscode", "captures",
    "device-reports", "vehicle-input", "emulator-report", "reports", "logs", "analysis",
    "output", "dist", "target", ".android", ".ssh", ".aws", ".azure", "shared_prefs",
    "no_backup", "input-samples",
}
BLOCKED_SUFFIXES = {
    ".pk8", ".p7b", ".p7c", ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore",
    ".crt", ".cer", ".csr", ".cert", ".der", ".p8", ".ppk", ".mobileprovision",
    ".asc", ".gpg", ".kdbx", ".apk", ".aab",
    ".apks", ".aar", ".jar", ".zip", ".7z", ".rar", ".tar", ".tgz", ".gz",
    ".bz2", ".xz", ".zst", ".dex", ".class", ".log", ".pcap", ".pcapng", ".ab",
    ".sqlite", ".sqlite3", ".db", ".db3", ".dmp", ".hprof", ".bin", ".pyc",
    ".mp4", ".m4a", ".mp3", ".wav", ".aac", ".mov", ".mkv", ".avi", ".3gp",
}
BLOCKED_NAMES = {"local.properties", "release-signing.properties", "signing.properties",
                 "credentials.json", "secrets.json", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519"}
PRIVATE_INPUT_NAME = re.compile(
    r"(?i)(?:super[-_]diag|learning[-_]diag|bugreport|logcat|dumpsys|pair[-_]records?)|"
    r"^[a-f0-9]{32}\.(?:jpe?g|png|webp)$")
CONTENT_PATTERNS = [
    ("private-key-pem", re.compile(rb"-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{48,}")),
    ("certificate-pem", re.compile(rb"-----BEGIN (?:TRUSTED CERTIFICATE|CERTIFICATE|PKCS7|CERTIFICATE REQUEST)-----\s+[A-Za-z0-9+/=\r\n]{48,}")),
    ("github-token", re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})\b")),
    ("gitlab-token", re.compile(rb"\bglpat-[A-Za-z0-9_-]{20,}\b")),
    ("slack-token", re.compile(rb"\bxox[baprs]-[A-Za-z0-9-]{20,}\b")),
    ("cloud-access-key", re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")),
    ("google-api-key", re.compile(rb"\bAIza[A-Za-z0-9_-]{30,}\b")),
    ("openai-api-key", re.compile(rb"\bsk-(?:(?:proj|svcacct)-)?[A-Za-z0-9_-]{24,}\b")),
    ("jwt-token", re.compile(rb"\beyJ[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]{16,}\b")),
    ("user-absolute-path", re.compile(rb"(?i)\b[A-Z]:[\\/]+(?:Users|Documents and Settings)[\\/]+[^\s\"'<>]+|(?<![A-Za-z0-9_])/(?:Users|home)/[A-Za-z0-9._-]+/")),
    ("private-workspace-path", re.compile(rb"(?i)\b[A-Z]:[\\/]+DiPlay-[a-f0-9]{16,}")),
    ("url-embedded-credential", re.compile(rb"(?i)\b(?:https?|ssh)://[^\s/@:\"']+:[^\s/@\"']+@")),
    ("literal-signing-password", re.compile(rb"(?i)(?:--(?:ks|key)-pass\s+[\"']?pass:[^\s\\]+|-(?:storepass|keypass)\s+[\"']?(?!env:|file:|\$|%)[A-Za-z0-9_.@!+-]{4,})")),
    ("literal-service-secret", re.compile(rb"(?i)\b(?:api[_-]?key|client[_-]?secret|access[_-]?token|refresh[_-]?token|auth[_-]?token|storepassword|keypassword)\b\s*[:=]\s*[\"']([A-Za-z0-9_./+@=-]{8,})[\"']")),
]


def display_path(name):
    # A secret may itself be a filename. Escape control characters as well.
    encoded = name.encode("utf-8", errors="backslashreplace")
    for _category, pattern in CONTENT_PATTERNS:
        encoded = pattern.sub(b"<redacted>", encoded)
    name = re.sub(r"[a-fA-F0-9]{24,}|[A-Za-z0-9_-]{48,}", "<long-identifier>", encoded.decode("utf-8"))
    return json.dumps(name, ensure_ascii=True)


def is_placeholder(value):
    return re.fullmatch(r"|<[^<>\r\n]+>|(?:YOUR_|REPLACE_|CHANGE_ME|CHANGEME)[A-Z0-9_]*|__[A-Z0-9_]+__|\$\{[A-Z0-9_]+\}", value) is not None


def path_category(name):
    path = PurePosixPath(name)
    lower = name.lower()
    if path.is_absolute() or ".." in path.parts:
        return "unsafe-relative-path"
    if any(part.lower() in BLOCKED_DIRS for part in path.parts[:-1]):
        return "private-or-generated-directory"
    base = path.name.lower()
    if base in BLOCKED_NAMES or (base.startswith(".env") and base != ".env.example"):
        return "private-configuration"
    if PRIVATE_INPUT_NAME.search(base):
        return "private-diagnostic-or-input"
    if lower != WRAPPER and path.suffix.lower() in BLOCKED_SUFFIXES:
        return "credential-archive-or-output"
    if path.suffix.lower() == ".so":
        if path.parent.as_posix() not in {NATIVE_DIR, NATIVE_DIR + "/x86", NATIVE_DIR + "/armeabi-v7a"} or base not in NATIVE_NAMES:
            return "unreviewed-native-binary"
    return None


def der_tlv(data, offset, end):
    if offset + 2 > end:
        raise ValueError()
    tag, length = data[offset], data[offset + 1]
    offset += 2
    if length & 0x80:
        count = length & 0x7f
        if count == 0 or count > 4 or offset + count > end:
            raise ValueError()
        length = int.from_bytes(data[offset:offset + count], "big")
        offset += count
    if offset + length > end:
        raise ValueError()
    return tag, offset, offset + length


def der_children(data, start, end):
    children = []
    while start < end:
        if len(children) >= 16:
            raise ValueError()
        child = der_tlv(data, start, end)
        children.append(child)
        start = child[2]
    return children


def der_category(data):
    """Recognize whole-file PKCS8/SEC1/RSA/X509/CMS, not arbitrary byte substrings."""
    if not data or data[0] != 0x30:
        return None
    try:
        outer = der_tlv(data, 0, len(data))
        if outer[2] != len(data):
            return None
        children = der_children(data, outer[1], outer[2])
        tags = [row[0] for row in children]
        if tags[:3] == [0x02, 0x30, 0x04] or tags[:2] == [0x02, 0x04] or tags == [0x30, 0x04] or (len(tags) >= 9 and all(tag == 0x02 for tag in tags)):
            return "private-key-der"
        if tags == [0x30, 0x30, 0x03]:
            return "certificate-or-request-der"
        if tags == [0x06, 0xa0] and data[children[0][1]:children[0][2]] == bytes.fromhex("2a864886f70d010702"):
            return "certificate-cms-der"
    except (ValueError, IndexError):
        pass
    return None


def content_findings(name, data):
    findings = []
    for category, pattern in CONTENT_PATTERNS:
        for index, match in enumerate(pattern.finditer(data)):
            if index >= 16:
                findings.append((name, 0, category + "-additional-matches-omitted"))
                break
            if category == "literal-service-secret" and is_placeholder(match.group(1).decode("ascii")):
                continue
            findings.append((name, data.count(b"\n", 0, match.start()) + 1, category))
    category = der_category(data)
    if category:
        findings.append((name, 0, category))
    if name == WRAPPER:
        if hashlib.sha256(data).hexdigest() != WRAPPER_SHA256:
            findings.append((name, 0, "wrapper-jar-fingerprint-changed"))
        return findings
    if name.endswith(".so"):
        machine = 3 if "/x86/" in name else 40
        if not data.startswith(b"\x7fELF\x01\x01") or len(data) < 20 or int.from_bytes(data[18:20], "little") != machine:
            findings.append((name, 0, "native-file-not-expected-elf32"))
        return findings
    if name in GENERATED_PNG_SHA256:
        if hashlib.sha256(data).hexdigest() != GENERATED_PNG_SHA256[name]:
            findings.append((name, 0, "generated-placeholder-fingerprint-changed"))
        return findings
    try:
        text = data.decode("utf-8-sig")
        if "\x00" in text:
            raise UnicodeError()
    except UnicodeError:
        findings.append((name, 0, "unreviewed-binary-or-text-encoding"))
        return findings
    if PurePosixPath(name).name == ".env.example":
        for line_no, line in enumerate(text.splitlines(), 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            match = re.fullmatch(r"(?:export\s+)?[A-Za-z_][A-Za-z0-9_]*\s*=\s*(.*?)\s*", line)
            value = match.group(1).strip("\"'") if match else None
            if value is None or not is_placeholder(value):
                findings.append((name, line_no, "env-example-must-be-placeholder"))
    return findings


def git_command(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)


def has_own_git_repository():
    if not shutil.which("git"):
        return False
    result = git_command("rev-parse", "--show-toplevel")
    return result.returncode == 0 and Path(os.fsdecode(result.stdout.strip())).resolve() == ROOT


def is_link(path):
    # Windows junctions/reparse points must not escape the reviewed source tree.
    return path.is_symlink() or bool(getattr(path.lstat(), "st_file_attributes", 0) & 0x400)


def tree_inputs(findings):
    def walk_error(_error):
        findings.append(("<tree>", 0, "directory-unreadable"))
    for directory, dirs, files in os.walk(ROOT, followlinks=False, onerror=walk_error):
        relative_dir = Path(directory).relative_to(ROOT)
        for child in list(dirs):
            if child == ".git":
                dirs.remove(child)
                continue
            path = Path(directory) / child
            name = (relative_dir / child).as_posix()
            try:
                category = "symlink-or-junction-not-reviewed" if is_link(path) else (
                    "private-or-generated-directory" if child.lower() in BLOCKED_DIRS else None)
            except OSError:
                category = "directory-unreadable"
            if category:
                findings.append((name, 0, category))
                dirs.remove(child)
        for child in files:
            path = Path(directory) / child
            name = (relative_dir / child).as_posix()
            category = path_category(name)
            if category:
                findings.append((name, 0, category))
                continue
            try:
                if is_link(path):
                    findings.append((name, 0, "symlink-not-reviewed"))
                    continue
                if path.stat().st_size > MAX_FILE_BYTES:
                    findings.append((name, 0, "oversized-file-needs-review"))
                    continue
                with path.open("rb") as source:
                    data = source.read(MAX_FILE_BYTES + 1)
                if len(data) > MAX_FILE_BYTES:
                    findings.append((name, 0, "oversized-file-needs-review"))
                    continue
                yield name, data
            except OSError:
                findings.append((name, 0, "file-unreadable"))


def indexed_inputs(findings):
    result = git_command("ls-files", "--stage", "-z")
    if result.returncode:
        findings.append(("<index>", 0, "git-index-unreadable"))
        return
    # One persistent cat-file process avoids a Git launch for every source file.
    with subprocess.Popen(["git", "-C", str(ROOT), "cat-file", "--batch"], stdin=subprocess.PIPE,
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as reader:
        try:
            for row in filter(None, result.stdout.split(b"\0")):
                meta, raw_name = row.split(b"\t", 1)
                mode, object_id, stage = meta.split()
                name = os.fsdecode(raw_name)
                if mode not in {b"100644", b"100755"} or stage != b"0":
                    findings.append((name, 0, "nonregular-or-unmerged-index-entry"))
                    continue
                category = path_category(name)
                if category:
                    findings.append((name, 0, category))
                    continue
                reader.stdin.write(object_id + b"\n")
                reader.stdin.flush()
                header = reader.stdout.readline().split()
                if len(header) != 3 or header[1] != b"blob":
                    raise ValueError()
                size = int(header[2])
                if size > MAX_FILE_BYTES:
                    findings.append((name, 0, "oversized-file-needs-review"))
                    remaining = size
                    while remaining:
                        chunk = reader.stdout.read(min(65536, remaining))
                        if not chunk:
                            raise ValueError()
                        remaining -= len(chunk)
                    if reader.stdout.read(1) != b"\n":
                        raise ValueError()
                    continue
                data = reader.stdout.read(size)
                if len(data) != size or reader.stdout.read(1) != b"\n":
                    raise ValueError()
                yield name, data
        except (OSError, ValueError):
            findings.append(("<index>", 0, "git-blob-unreadable"))
        finally:
            try:
                reader.stdin.close()
            except OSError:
                pass
            reader.wait()
            if reader.returncode:
                findings.append(("<index>", 0, "git-reader-failed"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--tree", action="store_true", help="scan actual files, including untracked and ignored files")
    mode.add_argument("--tracked", action="store_true", help="scan Git index blobs, fail without a repository at this root")
    args = parser.parse_args()
    own_git = has_own_git_repository()
    if args.tracked and not own_git:
        print('FAIL "<repository>":0:git-repository-required')
        return 1
    use_index = own_git and not args.tree
    findings = []
    scanned = 0
    for name, data in (indexed_inputs(findings) if use_index else tree_inputs(findings)):
        scanned += 1
        findings.extend(content_findings(name, data))
    if use_index and scanned == 0 and not findings:
        findings.append(("<index>", 0, "empty-index-not-reviewed"))
    for name, line, category in sorted(set(findings)):
        print("FAIL {}:{}:{}".format(display_path(name), line, category))
    label = "git-index" if use_index else "actual-tree"
    print("{} public tree guard mode={} scanned={} findings={}".format(
        "FAIL" if findings else "PASS", label, scanned, len(set(findings))))
    print("Scope: {}. Git history is not scanned.".format(
        "indexed blobs only; also run --tree before publication" if use_index else "actual tree, excluding Git metadata"))
    return 1 if findings else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        # Fail closed without an exception repr containing file contents or local paths.
        print('FAIL "<guard>":0:scan-internal-failure')
        sys.exit(1)
