#!/usr/bin/env python3
"""Persist the branding staged in the writable AVD to its read-only product image."""

import argparse
import hashlib
import re
import shutil
import struct
import subprocess
from pathlib import Path

from gapps_image import (
    LP_METADATA_START, SECTOR, Metadata, debugfs_quote, extract, gpt_partition,
    lp_layout, read_at,
)


def partition_extents(image, partition_name):
    _, _, _, start, end, _ = gpt_partition(image, "super")
    base = start * SECTOR
    size, slots = lp_layout(image, base)
    layouts = []
    for slot in range(2 * slots):
        raw = read_at(image, base + LP_METADATA_START + slot * size, size)
        if raw[:4] == bytes(4):
            continue
        metadata = Metadata(raw)
        for record in metadata.entries(80, 52):
            name, _, first, count, _ = struct.unpack_from("<36sIIII", raw, record)
            if name.rstrip(b"\0").decode() != partition_name:
                continue
            entries = metadata.entries(92, 24)[first:first + count]
            extents = []
            for entry in entries:
                sectors, kind, target, device = struct.unpack_from("<QIQI", raw, entry)
                if kind or device or not sectors or target + sectors > end - start + 1:
                    raise ValueError(f"Invalid or non-linear {partition_name} extent")
                extents.append((base + target * SECTOR, sectors * SECTOR))
            if len(extents) != count or not extents:
                raise ValueError(f"Missing {partition_name} extents")
            layouts.append(extents)
    if len(layouts) < 2 or any(layout != layouts[0] for layout in layouts):
        raise ValueError(f"Inconsistent {partition_name} extent metadata")
    return layouts[0]


def digest(path):
    with path.open("rb") as data:
        return hashlib.file_digest(data, "sha256").hexdigest()


def allow_scratch(system):
    # The GApps clone has free super extents but the stock group quota is too
    # small for fs_mgr's scratch partition. Only raise it in the writable copy.
    with system.open("r+b") as image:
        _, _, _, start, end, _ = gpt_partition(image, "super")
        base = start * SECTOR
        size, slots = lp_layout(image, base)
        updates = []
        for slot in range(2 * slots):
            offset = base + LP_METADATA_START + slot * size
            raw = read_at(image, offset, size)
            if raw[:4] == bytes(4):
                continue
            metadata = Metadata(raw)
            groups = [entry for entry in metadata.entries(104, 48)
                      if raw[entry:entry + 36].rstrip(b"\0") == b"emulator_dynamic_partitions"]
            if len(groups) != 1:
                raise ValueError("Unexpected emulator partition groups")
            updated = bytearray(raw)
            struct.pack_into("<Q", updated, groups[0] + 40, (end - start + 1) * SECTOR)
            updates.append((offset, metadata.checksummed(updated)))
        if len(updates) < 2:
            raise ValueError("Missing backup metadata for writable image")
        for offset, raw in updates:
            image.seek(offset)
            image.write(raw)


def write_extents(system, partition, name="product"):
    with system.open("r+b") as image, partition.open("rb") as source:
        extents = partition_extents(image, name)
        if sum(length for _, length in extents) != partition.stat().st_size:
            raise ValueError(f"Patched {name} does not fit its original extents")
        for offset, length in extents:
            image.seek(offset)
            while length:
                data = source.read(min(length, 4 * 1024**2))
                if not data:
                    raise ValueError(f"Short read of the {name} partition")
                image.write(data)
                length -= len(data)
        image.flush()
        checksum = hashlib.sha256()
        for offset, length in extents:
            while length:
                data = read_at(image, offset, min(length, 4 * 1024**2))
                checksum.update(data)
                offset += len(data)
                length -= len(data)
        if checksum.hexdigest() != digest(partition):
            raise ValueError(f"{name} SHA-256 changed while writing the image")
        print(f"{name} SHA-256: {checksum.hexdigest()}", flush=True)


def prepare(system, payload, work, remove_apps):
    partition = work / "product.img"
    extract(system, "product", partition)

    def debug(command):
        return subprocess.run(["debugfs", "-R", command, str(partition)], check=True,
                              capture_output=True, text=True)

    def exists(path):
        return "Inode:" in debug(f"stat {path}").stdout

    label = work / "system-file.label"
    label.write_bytes(b"u:object_r:system_file:s0\0")
    label_argument = debugfs_quote(label)
    paths = sorted(file.relative_to(payload) for file in payload.rglob("*") if file.is_file())
    commands = []
    if not exists("/overlay/config"):
        commands.extend(["mkdir /overlay/config",
                         f"ea_set -f {label_argument} /overlay/config security.selinux"])
    for app in remove_apps:
        directory = f"/app/{app}"
        oat = f"{directory}/oat/x86_64"
        files = (f"{directory}/{app}.apk", f"{oat}/{app}.odex", f"{oat}/{app}.vdex")
        commands.extend(f"rm {path}" for path in files if exists(path))
        commands.extend(f"rmdir {path}" for path in (oat, f"{directory}/oat", directory) if exists(path))
    for relative in paths:
        destination = "/" + relative.as_posix()
        if exists(destination):
            commands.append(f"rm {destination}")
        mode = "0100600" if destination == "/etc/build.prop" else "0100644"
        commands.extend([f"write {debugfs_quote(payload / relative)} {destination}",
                         f"set_inode_field {destination} mode {mode}",
                         f"ea_set -f {label_argument} {destination} security.selinux"])
    batch = work / "product.debugfs"
    batch.write_text("\n".join(commands) + "\n")
    result = subprocess.run(["debugfs", "-w", "-f", str(batch), str(partition)],
                            check=True, capture_output=True, text=True)
    (work / "product.debugfs.log").write_text(result.stdout + result.stderr)
    dumped = work / "check-file"
    for relative in paths:
        destination = "/" + relative.as_posix()
        debug(f"dump {destination} {debugfs_quote(dumped)}")
        if (not dumped.is_file() or digest(dumped) != digest(payload / relative)
                or not re.search(r"security.selinux.*system_file", debug(f"ea_list {destination}").stdout)):
            raise ValueError(f"Failed to verify {destination} in the product image")
        dumped.unlink()
    for app in remove_apps:
        if exists(f"/app/{app}"):
            raise ValueError(f"Failed to remove {app} from product")

    subprocess.run(["e2fsck", "-f", "-n", str(partition)], check=True)
    write_extents(system, partition)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("system", type=Path)
    parser.add_argument("payload", type=Path, nargs="?")
    parser.add_argument("work", type=Path, nargs="?")
    parser.add_argument("--remove-app", action="append", default=[], choices=("Browser2", "QuickSearchBox"))
    parser.add_argument("--allow-scratch", action="store_true")
    args = parser.parse_args()
    if args.allow_scratch:
        allow_scratch(args.system)
    elif args.payload and args.work:
        prepare(args.system, args.payload, args.work, args.remove_app)
    else:
        parser.error("payload and work are required to patch the product image")


if __name__ == "__main__":
    main()
