#!/usr/bin/env python3
"""Extract default.xbe from a local Xbox XDVDFS image without mounting or unpacking game data.

The script accepts a game-partition XISO or a whole original-Xbox image whose game partition starts at
0x18300000. It is intentionally limited to the one build input Madagascar needs. No game image is committed,
logged, or copied outside of the output path supplied by the caller.
"""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import struct
import sys
import tempfile
from typing import BinaryIO, Iterator

SECTOR_SIZE = 2048
VOLUME_OFFSET = 32 * SECTOR_SIZE
VOLUME_SIZE = SECTOR_SIZE
MAGIC = b"MICROSOFT*XBOX*MEDIA"
PARTITION_OFFSETS = (0, 0x18300000)
ENTRY_HEADER_SIZE = 0x0E
DIRECTORY_ATTRIBUTE = 0x10
MAX_DIRECTORY_SIZE = 16 << 20
MAX_DIRECTORY_DEPTH = 32
COPY_BLOCK_SIZE = 1 << 20


class XdvdfsError(RuntimeError):
    """The selected file is not a usable original-Xbox XDVDFS image."""


def read_exact(image: BinaryIO, offset: int, size: int) -> bytes:
    image.seek(offset)
    data = image.read(size)
    if len(data) != size:
        raise XdvdfsError("image ended while reading its XDVDFS filesystem")
    return data


def image_size(image: BinaryIO) -> int:
    image.seek(0, os.SEEK_END)
    return image.tell()


def find_partition(image: BinaryIO) -> int:
    size = image_size(image)
    for base in PARTITION_OFFSETS:
        if base + VOLUME_OFFSET + VOLUME_SIZE > size:
            continue
        volume = read_exact(image, base + VOLUME_OFFSET, VOLUME_SIZE)
        if volume.startswith(MAGIC) and volume[0x7EC : 0x7EC + len(MAGIC)] == MAGIC:
            return base
    raise XdvdfsError("no Xbox XDVDFS game partition was found (supported layouts: XISO or original disc image)")


def entries(table: bytes) -> Iterator[tuple[str, int, int, bool]]:
    """Yield (name, sector, byte_size, is_directory) entries from XDVDFS's binary-tree directory table."""
    todo = [0]
    seen: set[int] = set()
    while todo:
        at = todo.pop()
        if at in seen:
            continue
        seen.add(at)
        if at < 0 or at + ENTRY_HEADER_SIZE > len(table):
            raise XdvdfsError("directory contains an entry outside its table")
        left, right = struct.unpack_from("<HH", table, at)
        if left == 0xFFFF:  # XDVDFS empty-table marker
            continue
        sector, byte_size = struct.unpack_from("<II", table, at + 4)
        attributes = table[at + 0x0C]
        name_size = table[at + 0x0D]
        name_end = at + ENTRY_HEADER_SIZE + name_size
        if not name_size or name_end > len(table):
            raise XdvdfsError("directory contains an invalid file name")
        try:
            name = table[at + ENTRY_HEADER_SIZE : name_end].decode("ascii")
        except UnicodeDecodeError as error:
            raise XdvdfsError("directory contains a non-ASCII file name") from error
        if left:
            todo.append(left * 4)
        if right:
            todo.append(right * 4)
        yield name, sector, byte_size, bool(attributes & DIRECTORY_ATTRIBUTE)


def find_default_xbe(image: BinaryIO) -> tuple[int, int]:
    """Return (file_offset, file_size) for default.xbe, walking directories defensively."""
    base = find_partition(image)
    volume = read_exact(image, base + VOLUME_OFFSET, VOLUME_SIZE)
    root_sector, root_size = struct.unpack_from("<II", volume, 0x14)
    if root_size <= 0 or root_size > MAX_DIRECTORY_SIZE:
        raise XdvdfsError("the XDVDFS root directory has an invalid size")

    pending: list[tuple[int, int, int]] = [(base + root_sector * SECTOR_SIZE, root_size, 0)]
    visited: set[tuple[int, int]] = set()
    total_size = image_size(image)
    while pending:
        directory_offset, directory_size, depth = pending.pop()
        key = (directory_offset, directory_size)
        if key in visited:
            continue
        visited.add(key)
        if depth > MAX_DIRECTORY_DEPTH or directory_size <= 0 or directory_size > MAX_DIRECTORY_SIZE:
            raise XdvdfsError("the XDVDFS directory hierarchy is invalid")
        if directory_offset < 0 or directory_offset + directory_size > total_size:
            raise XdvdfsError("a directory points outside the image")
        table = read_exact(image, directory_offset, directory_size)
        for name, sector, byte_size, is_directory in entries(table):
            offset = base + sector * SECTOR_SIZE
            if offset + byte_size > total_size:
                raise XdvdfsError(f"{name} points outside the image")
            if not is_directory and name.casefold() == "default.xbe":
                if byte_size <= 0:
                    raise XdvdfsError("default.xbe is empty")
                return offset, byte_size
            if is_directory:
                pending.append((offset, byte_size, depth + 1))
    raise XdvdfsError("default.xbe was not found in the XDVDFS image")


def extract_default_xbe(source: Path, destination: Path, expected_sha256: str | None = None) -> str:
    if not source.is_file():
        raise XdvdfsError(f"input image does not exist: {source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".part")
    temporary.unlink(missing_ok=True)
    try:
        with source.open("rb") as image:
            offset, remaining = find_default_xbe(image)
            image.seek(offset)
            digest = hashlib.sha256()
            with temporary.open("wb") as output:
                while remaining:
                    chunk = image.read(min(remaining, COPY_BLOCK_SIZE))
                    if not chunk:
                        raise XdvdfsError("image ended while extracting default.xbe")
                    output.write(chunk)
                    digest.update(chunk)
                    remaining -= len(chunk)
                output.flush()
                os.fsync(output.fileno())
        sha256 = digest.hexdigest()
        if expected_sha256 and sha256.casefold() != expected_sha256.casefold():
            raise XdvdfsError(
                "default.xbe has SHA-256 "
                f"{sha256}, but this Madagascar port expects {expected_sha256.casefold()}"
            )
        os.replace(temporary, destination)
        return sha256
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def write_synthetic_xiso(path: Path, payload: bytes, partition_base: int = 0) -> None:
    """Create a harmless miniature XDVDFS fixture for --self-test; it contains only synthetic bytes."""
    root_sector, file_sector = 33, 34
    root_name = b"default.xbe"
    root_size = ENTRY_HEADER_SIZE + len(root_name)
    volume = partition_base + VOLUME_OFFSET
    entry = partition_base + root_sector * SECTOR_SIZE
    file_offset = partition_base + file_sector * SECTOR_SIZE
    with path.open("wb") as image:
        # Sparse on normal CI filesystems: this also validates whole-disc partition-offset support without
        # allocating a real Xbox image.
        image.truncate(file_offset + len(payload))
        image.seek(volume)
        image.write(MAGIC)
        image.seek(volume + 0x7EC)
        image.write(MAGIC)
        image.seek(volume + 0x14)
        image.write(struct.pack("<II", root_sector, root_size))
        image.seek(entry)
        image.write(struct.pack("<HHII", 0, 0, file_sector, len(payload)))
        image.write(bytes((0, len(root_name))))
        image.write(root_name)
        image.seek(file_offset)
        image.write(payload)


def self_test() -> None:
    payload = b"synthetic Madagascar XBE fixture\n"
    expected = hashlib.sha256(payload).hexdigest()
    with tempfile.TemporaryDirectory(prefix="xdvdfs-selftest-") as directory:
        directory_path = Path(directory)
        for name, base in (("fixture.xiso", 0), ("fixture-disc.iso", 0x18300000)):
            image = directory_path / name
            output = directory_path / (name + ".xbe")
            write_synthetic_xiso(image, payload, base)
            result = extract_default_xbe(image, output, expected)
            if result != expected or output.read_bytes() != payload:
                raise XdvdfsError("self-test extracted different bytes")
    print("XDVDFS default.xbe extractor self-test: PASS")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, help="local Xbox XISO/original-disc image")
    parser.add_argument("--output", type=Path, help="destination for default.xbe")
    parser.add_argument("--expected-sha256", help="fail unless the extracted XBE has this SHA-256")
    parser.add_argument("--self-test", action="store_true", help="test extraction using a generated non-game fixture")
    args = parser.parse_args()

    try:
        if args.self_test:
            if args.input or args.output or args.expected_sha256:
                parser.error("--self-test cannot be combined with input/output options")
            self_test()
            return 0
        if not args.input or not args.output:
            parser.error("--input and --output are required unless --self-test is used")
        sha256 = extract_default_xbe(args.input, args.output, args.expected_sha256)
        print(f"Extracted default.xbe to {args.output} (SHA-256 {sha256})")
        return 0
    except XdvdfsError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
