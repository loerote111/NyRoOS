"""Read the Android Emulator GPT and its single-device super partition."""
import argparse
import hashlib
import re
import shutil
import struct
import subprocess
from pathlib import Path
from uuid import UUID

SECTOR = 512
LP_GEOMETRY_SIZE = 4096
LP_METADATA_START = 3 * LP_GEOMETRY_SIZE
LP_MAGIC = 0x414C5030
GROWTH = 2 * 1024**3


def read_at(image, offset, length):
    image.seek(offset)
    data = image.read(length)
    if len(data) != length:
        raise ValueError(f"Short image read at byte {offset}")
    return data


def gpt_partition(image, name):
    header = read_at(image, SECTOR, 92)
    if header[:8] != b"EFI PART":
        raise ValueError("Expected an emulator system image with GPT")
    entry_lba, count, width = struct.unpack_from("<QII", header, 72)
    if not 0 < count <= 1024 or width != 128:
        raise ValueError("Unexpected GPT partition table dimensions")
    entries = read_at(image, entry_lba * SECTOR, count * width)
    for index in range(count):
        entry = entries[index * width:(index + 1) * width]
        if entry[56:].decode("utf-16le").rstrip("\0") == name:
            start, end, attributes = struct.unpack_from("<QQQ", entry, 32)
            return index + 1, UUID(bytes_le=entry[:16]), UUID(bytes_le=entry[16:32]), start, end, attributes
    raise ValueError(f"Missing GPT partition: {name}")


def lp_layout(image, super_start):
    geometry = read_at(image, super_start + LP_GEOMETRY_SIZE, 52)
    magic, = struct.unpack_from("<I", geometry)
    size, slots, _ = struct.unpack_from("<III", geometry, 40)
    if magic != 0x616C4467 or not 0 < slots <= 4 or not 4096 <= size <= 2**20:
        raise ValueError("Unexpected Android logical partition geometry")
    return size, slots


class Metadata:
    def __init__(self, raw):
        self.raw = raw
        magic, _, _, self.header_size = struct.unpack_from("<IHHI", raw)
        self.table_size, = struct.unpack_from("<I", raw, 44)
        if magic != LP_MAGIC or not 128 <= self.header_size <= len(raw):
            raise ValueError("Invalid Android logical partition metadata")
        if self.header_size + self.table_size > len(raw):
            raise ValueError("Logical partition table extends beyond its slot")
        header = bytearray(raw[:self.header_size])
        checksum = bytes(header[12:44])
        header[12:44] = bytes(32)
        tables = raw[self.header_size:self.header_size + self.table_size]
        if hashlib.sha256(header).digest() != checksum or hashlib.sha256(tables).digest() != raw[48:80]:
            raise ValueError("Invalid logical partition metadata checksum")

    def entries(self, descriptor, width):
        offset, count, entry_size = struct.unpack_from("<III", self.raw, descriptor)
        if entry_size != width or offset + count * width > self.table_size:
            raise ValueError("Unexpected logical partition table dimensions")
        start = self.header_size + offset
        return [start + index * width for index in range(count)]

    def expand_super(self, size):
        devices = self.entries(116, 64)
        if len(devices) != 1 or self.raw[devices[0]+24:devices[0]+60].split(b"\0")[0] != b"super":
            raise ValueError("Expected a single super block device")
        updated = bytearray(self.raw)
        struct.pack_into("<Q", updated, devices[0] + 16, size)
        return self.checksummed(updated)

    def checksummed(self, updated):
        tables = updated[self.header_size:self.header_size + self.table_size]
        updated[48:80] = hashlib.sha256(tables).digest()
        updated[12:44] = bytes(32)
        updated[12:44] = hashlib.sha256(updated[:self.header_size]).digest()
        return updated


def extract(image_path, name, destination):
    with image_path.open("rb") as image:
        *_, start, end, _ = gpt_partition(image, "super")
        super_start = start * SECTOR
        slot_size, _ = lp_layout(image, super_start)
        metadata = Metadata(read_at(image, super_start + LP_METADATA_START, slot_size))
        for offset in metadata.entries(80, 52):
            label, _, first, count, _ = struct.unpack_from("<36sIIII", metadata.raw, offset)
            if label.rstrip(b"\0").decode() != name:
                continue
            extents = metadata.entries(92, 24)[first:first + count]
            if len(extents) != count or not extents:
                raise ValueError(f"No extents for {name}")
            with destination.open("xb") as output:
                for extent in extents:
                    sectors, kind, target, device = struct.unpack_from("<QIQI", metadata.raw, extent)
                    if kind != 0 or device != 0 or target + sectors > end - start + 1:
                        raise ValueError(f"Non-linear or invalid extent for {name}")
                    image.seek(super_start + target * SECTOR)
                    left = sectors * SECTOR
                    while left:
                        chunk = image.read(min(left, 4 * 1024**2))
                        if not chunk:
                            raise ValueError(f"Short extent while extracting {name}")
                        output.write(chunk)
                        left -= len(chunk)
            return
    raise ValueError(f"Logical partition not found: {name}")


def expand(image_path):
    with image_path.open("r+b") as image:
        index, type_id, part_id, start, end, attributes = gpt_partition(image, "super")
        if attributes:
            raise ValueError("Unexpected attributes on the super partition")
        super_start = start * SECTOR
        slot_size, slots = lp_layout(image, super_start)
        offsets = [super_start + LP_METADATA_START + slot * slot_size for slot in range(2 * slots)]
        new_size = (end - start + 1) * SECTOR + GROWTH
        expanded = []
        for offset in offsets:
            raw = read_at(image, offset, slot_size)
            if raw[:4] == b"\0" * 4:
                continue
            expanded.append((offset, Metadata(raw).expand_super(new_size)))
        if len(expanded) < 2:
            raise ValueError("Missing primary or backup Android partition metadata")
        original_size = image.seek(0, 2)
        image.truncate(original_size + GROWTH)

    new_end = end + GROWTH // SECTOR
    subprocess.run([
        "sgdisk", "--move-second-header", f"--delete={index}",
        f"--new={index}:{start}:{new_end}", f"--typecode={index}:{type_id}",
        f"--partition-guid={index}:{part_id}", f"--change-name={index}:super",
        str(image_path),
    ], check=True)
    with image_path.open("r+b") as image:
        if gpt_partition(image, "super") != (index, type_id, part_id, start, new_end, attributes):
            raise ValueError("Expanded GPT failed verification")
        for offset, raw in expanded:
            image.seek(offset)
            image.write(raw)
        for offset, raw in expanded:
            Metadata(read_at(image, offset, len(raw)))


def debugfs_quote(path):
    value = str(path)
    if any(character in value for character in '\n\r"'):
        raise ValueError(f"Unsupported path character for debugfs: {value!r}")
    return '"' + value + '"'


def bake_partition(system, name, payload, work, paths):
    path = work / f"{name}.img"
    extract(system, name, path)
    additional = sum((payload / file).stat().st_size for file in paths)
    with path.open("r+b") as filesystem:
        footer = bytearray(read_at(filesystem, path.stat().st_size - 64, 64))
        if footer[:4] != b"AVBf":
            raise ValueError(f"{name} has no AVB footer")
        _, vbmeta_offset, vbmeta_size = struct.unpack_from(">QQQ", footer, 12)
        footer_vbmeta = read_at(filesystem, vbmeta_offset, vbmeta_size)
        unit = 64 * 1024**2
        fs_size = (path.stat().st_size + additional + 2 * unit - 1) // unit * unit
        filesystem.truncate(fs_size)
    subprocess.run(["resize2fs", "-f", str(path)], check=True)
    check = subprocess.run(["e2fsck", "-f", "-y", "-E", "unshare_blocks", str(path)],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if check.returncode not in (0, 1):
        raise RuntimeError(check.stdout)

    label = work / "system-file.label"
    label.write_bytes(b"u:object_r:system_file:s0\0")
    operations = []
    for file in paths:
        relative = file.relative_to(name)
        destination = "/" + relative.as_posix()
        if relative.parts[0] in ("app", "priv-app"):
            directory = "/" + relative.parent.as_posix()
            operations.extend([f"mkdir {directory}", f"ea_set -f {debugfs_quote(label)} {directory} security.selinux"])
        operations.extend([
            f"write {debugfs_quote(payload / file)} {destination}",
            f"ea_set -f {debugfs_quote(label)} {destination} security.selinux",
            f"stat {destination}",
        ])
    batch = work / f"{name}.debugfs"
    batch.write_text("\n".join(operations) + "\n")
    outputs = subprocess.run(["debugfs", "-w", "-f", str(batch), str(path)], check=True,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True).stdout
    sizes = [int(size) for size in re.findall(r"Project: +\d+ +Size: (\d+)", outputs)]
    if sizes != [(payload / file).stat().st_size for file in paths] or outputs.count('security.selinux (26) = "u:object_r:system_file:s0') < len(paths):
        raise ValueError(f"Files or labels failed verification in {name}:\n{outputs}")
    subprocess.run(["e2fsck", "-f", "-n", str(path)], check=True)
    with path.open("r+b") as filesystem:
        extent_size = fs_size + 1024**2
        filesystem.truncate(extent_size)
        struct.pack_into(">QQQ", footer, 12, fs_size, fs_size, vbmeta_size)
        filesystem.seek(fs_size)
        filesystem.write(footer_vbmeta)
        filesystem.seek(extent_size - len(footer))
        filesystem.write(footer)
    print(f"{name}: {len(paths)} files persisted in ext4", flush=True)
    return path


def bake(system, mounted, payload, work, paths):
    names = ("product", "system_ext")
    if set(file.parts[0] for file in paths) != set(names):
        raise ValueError("Expected product and system_ext payloads only")
    images = {name: bake_partition(system, name, payload, work, [f for f in paths if f.parts[0] == name])
              for name in names}
    with system.open("r+b") as image, mounted.open("rb") as installed:
        *_, vb_start, vb_end, _ = gpt_partition(image, "vbmeta")
        if gpt_partition(installed, "vbmeta") != gpt_partition(image, "vbmeta"):
            raise ValueError("The installation VM used another vbmeta partition")
        vbmeta = read_at(installed, vb_start * SECTOR, (vb_end - vb_start + 1) * SECTOR)
        if vbmeta[:4] != b"AVB0" or not struct.unpack_from(">I", vbmeta, 120)[0] & 1:
            raise ValueError("Expected ADB's verity-disabled metadata for this AVD copy")
        *_, start, end, _ = gpt_partition(image, "super")
        super_start = start * SECTOR
        slot_size, slots = lp_layout(image, super_start)
        first = super_start + LP_METADATA_START
        slot_offsets = [first + i * slot_size for i in range(2 * slots)]
        slots_to_write = []
        layouts = {}

        for offset in slot_offsets:
            raw = read_at(image, offset, slot_size)
            if raw[:4] == b"\0" * 4:
                continue
            metadata = Metadata(raw)
            extents = metadata.entries(92, 24)
            used = [struct.unpack_from("<QIQI", raw, e) for e in extents]
            if any(kind != 0 or device != 0 for _, kind, _, device in used):
                raise ValueError("Expected single-device linear extents")
            next_sector = (max(target + count for count, _, target, _ in used) + 2047) // 2048 * 2048
            replacements = {}
            updated = bytearray(raw)
            for record in metadata.entries(80, 52):
                label, _, extent_index, extent_count, _ = struct.unpack_from("<36sIIII", raw, record)
                name = label.rstrip(b"\0").decode()
                if name not in images:
                    continue
                if extent_count != 1:
                    raise ValueError(f"Unexpected extent count for {name}")
                size = images[name].stat().st_size // SECTOR
                struct.pack_into("<QIQI", updated, extents[extent_index], size, 0, next_sector, 0)
                replacements[name] = next_sector
                next_sector += size
            if set(replacements) != set(names) or next_sector > end - start + 1:
                raise ValueError("The expanded super partition is too small or incomplete")
            if layouts and layouts != replacements:
                raise ValueError("Inconsistent Android metadata slots")
            layouts = replacements
            slots_to_write.append((offset, metadata.checksummed(updated)))

        if len(slots_to_write) < 2:
            raise ValueError("Missing Android metadata slots")
        for name, sector in layouts.items():
            image.seek(super_start + sector * SECTOR)
            with images[name].open("rb") as partition:
                shutil.copyfileobj(partition, image, 4 * 1024**2)
        for offset, raw in slots_to_write:
            image.seek(offset)
            image.write(raw)
        image.seek(vb_start * SECTOR)
        image.write(vbmeta)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    unpack = commands.add_parser("extract")
    unpack.add_argument("source", type=Path)
    unpack.add_argument("partition")
    unpack.add_argument("destination", type=Path)
    grow = commands.add_parser("expand")
    grow.add_argument("image", type=Path)
    persist = commands.add_parser("bake")
    persist.add_argument("system", type=Path)
    persist.add_argument("mounted", type=Path)
    persist.add_argument("payload", type=Path)
    persist.add_argument("work", type=Path)
    persist.add_argument("paths", type=Path, nargs="+")
    args = parser.parse_args()
    if args.command == "extract":
        extract(args.source, args.partition, args.destination)
    elif args.command == "expand":
        expand(args.image)
    else:
        bake(args.system, args.mounted, args.payload, args.work, args.paths)


if __name__ == "__main__":
    main()
