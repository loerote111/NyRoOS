#!/usr/bin/env python3
"""Keep Quickstep's internal HOME lookup while removing its public HOME entry."""

import argparse
import struct
from dataclasses import dataclass, field
from pathlib import Path
from urllib.request import urlopen
from base64 import b64decode
from zipfile import ZipFile


ANDROID = "http://schemas.android.com/apk/res/android"
ACTIVITY = "com.android.launcher3.uioverrides.QuickstepLauncher"
HOME = "android.intent.category.HOME"
DEFAULT = "android.intent.category.DEFAULT"
KEY_SOURCE = ("https://android.googlesource.com/platform/build/+/refs/tags/android-16.0.0_r1"
              "/target/product/security/")


@dataclass
class Element:
    name: str
    begin: int
    end: int = 0
    attributes: dict = field(default_factory=dict)
    children: list = field(default_factory=list)


def length8(data, offset):
    first = data[offset]
    if first < 128:
        return first, offset + 1
    return ((first & 127) << 8) + data[offset + 1], offset + 2


def string_pool(data, start):
    header, _, count, _, flags, offset = struct.unpack_from("<2xH5I", data, start)
    strings = []
    for index in range(count):
        relative, = struct.unpack_from("<I", data, start + header + index * 4)
        begin = start + offset + relative
        if flags & 0x100:
            _, begin = length8(data, begin)
            size, begin = length8(data, begin)
            strings.append(data[begin:begin + size].decode("utf-8"))
        else:
            size, = struct.unpack_from("<H", data, begin)
            begin += 2
            if size & 0x8000:
                low, = struct.unpack_from("<H", data, begin)
                size = ((size & 0x7fff) << 16) + low
                begin += 2
            strings.append(data[begin:begin + 2 * size].decode("utf-16le"))
    return strings


def parse(data):
    xml_kind, header, total = struct.unpack_from("<HHI", data)
    if xml_kind != 3 or total != len(data):
        raise ValueError("Invalid Android binary manifest")
    stack = []
    root = None
    strings = []
    start = header
    while start < total:
        kind, node_header, size = struct.unpack_from("<HHI", data, start)
        if size < node_header or not size or start + size > total:
            raise ValueError("Invalid XML chunk")
        if kind == 1:
            strings = string_pool(data, start)
        elif kind == 0x102:
            _, tag, offset, width, count = struct.unpack_from("<IIHHH", data, start + node_header)
            item = Element(strings[tag], start)
            for index in range(count):
                position = start + node_header + offset + index * width
                namespace, key, raw, _, _, value_type, value = struct.unpack_from("<IIIHBBI", data, position)
                if namespace == 0xffffffff or strings[namespace] != ANDROID:
                    continue
                text = strings[raw] if raw != 0xffffffff else strings[value] if value_type == 3 else None
                item.attributes[strings[key]] = (text, value_type, value, position)
            if stack:
                stack[-1].children.append(item)
            else:
                root = item
            stack.append(item)
        elif kind == 0x103:
            stack.pop().end = start + size
        start += size
    if root is None or stack:
        raise ValueError("Unbalanced XML elements")
    return root


def one(items):
    result = list(items)
    if len(result) != 1:
        raise ValueError("Unexpected Quickstep manifest layout")
    return result[0]


def trim_home(data):
    root = parse(data)
    app = one(item for item in root.children if item.name == "application")
    activity = one(item for item in app.children if item.name == "activity"
                   and item.attributes.get("name", (None,))[0] == ACTIVITY)
    intent = one(item for item in activity.children if item.name == "intent-filter"
                 and any(child.name == "category" and child.attributes["name"][0] == HOME for child in item.children))
    default = one(item for item in intent.children if item.name == "category"
                  and item.attributes["name"][0] == DEFAULT)
    exported_text, value_type, value, attr = activity.attributes["exported"]
    if exported_text is not None or value_type != 0x12 or not value:
        raise ValueError("Quickstep HOME is not an exported activity with a boolean attribute")
    result = bytearray(data)
    struct.pack_into("<I", result, attr + 16, 0)
    del result[default.begin:default.end]
    struct.pack_into("<I", result, 4, len(result))
    parse(result)
    return result


def rewrite_apk(source, destination):
    with ZipFile(source) as original, ZipFile(destination, "w") as modified:
        for info in original.infolist():
            name = info.filename
            if name.startswith("META-INF/") and name.upper().endswith((".RSA", ".DSA", ".EC", ".SF", "MANIFEST.MF")):
                continue
            payload = original.read(info)
            if name == "AndroidManifest.xml":
                payload = trim_home(payload)
            modified.writestr(info, payload)


def cache_test_key(directory):
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    for filename in ("testkey.x509.pem", "testkey.pk8"):
        path = directory / filename
        if not path.is_file():
            with urlopen(KEY_SOURCE + filename + "?format=TEXT", timeout=15) as response:
                content = b64decode(response.read())
            with path.open("xb") as output:
                output.write(content)
            path.chmod(0o600)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    rewrite = commands.add_parser("rewrite")
    rewrite.add_argument("source", type=Path)
    rewrite.add_argument("destination", type=Path)
    key = commands.add_parser("cache-key")
    key.add_argument("directory", type=Path)
    args = parser.parse_args()
    if args.command == "rewrite":
        rewrite_apk(args.source, args.destination)
    else:
        cache_test_key(args.directory)
