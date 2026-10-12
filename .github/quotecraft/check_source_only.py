#!/usr/bin/env python3
"""Fail closed when a Git index contains release binaries, archives, or secrets.

Usage: python .github/quotecraft/check_source_only.py [repository_root]
The selected directory must itself be the Git working-tree root. This scans
indexed blobs, including staged files whose working copies have been removed.
It never reads working-tree file contents or changes the Git index.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

MAX_BLOB_BYTES = 64 * 1024 * 1024
BLOCKED_SUFFIXES = frozenset({
    ".apk", ".aab", ".apks", ".xapk", ".zip", ".7z", ".rar",
    ".tar", ".gz", ".tgz", ".bz2", ".xz", ".exe", ".msi",
    ".dll", ".so", ".dylib", ".jks", ".keystore", ".p12", ".pfx",
    ".bak", ".backup", ".old", ".orig", ".rej", ".tmp", ".temp",
    ".save", ".swp", ".swo", ".bz", ".lz", ".lzma", ".zst",
    ".z", ".cab", ".iso", ".img", ".dmg", ".deb", ".rpm",
})
MAGIC_PREFIXES = (
    (b"PK\x03\x04", "ZIP/APK archive"),
    (b"PK\x05\x06", "empty ZIP archive"),
    (b"PK\x07\x08", "ZIP archive"),
    (b"Rar!\x1a\x07\x00", "RAR archive"),
    (b"Rar!\x1a\x07\x01\x00", "RAR archive"),
    (b"7z\xbc\xaf\x27\x1c", "7-Zip archive"),
    (b"\x1f\x8b", "gzip archive"),
    (b"\x7fELF", "ELF executable/library"),
    (b"MZ", "Windows executable/library"),
)
OID_RE = re.compile(rb"[0-9a-f]{40}|[0-9a-f]{64}")


class GuardError(Exception):
    """A Git operation or unexpected index record prevents verification."""


def git(root: Path, *args: str) -> bytes:
    result = subprocess.run(
        ["git", "-C", str(root), *args],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
    )
    if result.returncode:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise GuardError(f"git {' '.join(args)} failed: {detail}")
    return result.stdout


def same_directory(left: Path, right: Path) -> bool:
    return os.path.normcase(str(left.resolve())) == os.path.normcase(str(right.resolve()))


def blocked_name(path: str) -> str | None:
    name = path.rsplit("/", 1)[-1].casefold()
    if name == ".env" or name.startswith(".env.") or name.endswith(".env"):
        return "environment file"
    if name.endswith("~") or (name.startswith("#") and name.endswith("#")):
        return "editor backup"
    for suffix in BLOCKED_SUFFIXES:
        if name.endswith(suffix):
            return f"blocked file suffix {suffix}"
    return None


def inspect_repository(root: Path) -> tuple[int, list[str]]:
    if not root.is_dir():
        raise GuardError("repository root does not exist or is not a directory")
    actual_root = Path(os.fsdecode(git(root, "rev-parse", "--show-toplevel").rstrip(b"\r\n")))
    if not same_directory(root, actual_root):
        raise GuardError(f"selected directory is not the Git root; actual root: {actual_root}")
    entries = git(root, "ls-files", "--stage", "-z").split(b"\0")
    violations: list[str] = []
    seen_objects: dict[str, str | None] = {}
    scanned = 0
    for entry in entries:
        if not entry:
            continue
        try:
            metadata, raw_path = entry.split(b"\t", 1)
            mode, oid, stage = metadata.split(b" ")
        except ValueError as exc:
            raise GuardError("malformed Git index record") from exc
        path = os.fsdecode(raw_path)
        label = repr(path)
        if stage != b"0":
            violations.append(f"{label}: unresolved merge entry (stage {stage.decode('ascii', 'replace')})")
            continue
        if mode == b"120000":
            violations.append(f"{label}: symlinks are not allowed")
            continue
        if mode == b"160000":
            violations.append(f"{label}: submodule/gitlink entries are not allowed")
            continue
        if mode not in (b"100644", b"100755") or not OID_RE.fullmatch(oid):
            raise GuardError(f"unexpected Git index metadata for {label}")
        name_reason = blocked_name(path)
        if name_reason:
            violations.append(f"{label}: {name_reason}")
            continue
        object_id = oid.decode("ascii")
        if object_id not in seen_objects:
            size_bytes = git(root, "cat-file", "-s", object_id).strip()
            if not size_bytes.isdigit():
                raise GuardError(f"invalid blob size for {label}")
            size = int(size_bytes)
            if size > MAX_BLOB_BYTES:
                seen_objects[object_id] = f"blob exceeds {MAX_BLOB_BYTES} byte inspection limit"
            else:
                content = git(root, "cat-file", "blob", object_id)
                if len(content) != size:
                    raise GuardError(f"blob size changed while inspecting {label}")
                reason = None
                for prefix, kind in MAGIC_PREFIXES:
                    if content.startswith(prefix):
                        reason = f"{kind} detected in indexed blob"
                        break
                lfs_head = content[:256].lstrip(b"\xef\xbb\xbf \t\r\n")
                if lfs_head.startswith(b"version https://git-lfs.github.com/spec/v1"):
                    reason = "Git LFS pointer is not allowed"
                seen_objects[object_id] = reason
        if seen_objects[object_id]:
            violations.append(f"{label}: {seen_objects[object_id]}")
        scanned += 1
    return scanned, violations


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", default=".", help="exact Git repository root (default: current directory)")
    args = parser.parse_args()
    try:
        scanned, violations = inspect_repository(Path(args.root).resolve())
    except (GuardError, OSError) as exc:
        print(f"SOURCE-ONLY CHECK BLOCKED: {exc}", file=sys.stderr)
        return 2
    if violations:
        print("SOURCE-ONLY CHECK FAILED:", file=sys.stderr)
        for violation in violations:
            print(f"  {violation}", file=sys.stderr)
        return 1
    print(f"SOURCE-ONLY CHECK PASSED: inspected {scanned} indexed source/assets/docs files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
