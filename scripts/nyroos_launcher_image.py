#!/usr/bin/env python3
"""Install the launcher and its allowlist in the system ext4 partition of the AVD copy."""

import argparse
from pathlib import Path
from pathlib import PurePosixPath
import re
import subprocess
import time

from gapps_image import debugfs_quote, extract
from nyroos_image import digest, write_extents
from nyroos_quickstep import cache_test_key, rewrite_apk


QUICKSTEP = "/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk"
TEST_CERT = "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"


def prepare_partition(image, name, work):
    partition = work / f"{name}.partition"
    extract(image, name, partition)
    check = subprocess.run(["e2fsck", "-f", "-y", "-E", "unshare_blocks", str(partition)],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (work / f"{name}.unshare.log").write_text(check.stdout)
    if check.returncode not in (0, 1):
        raise RuntimeError(check.stdout)
    return partition


def debug(partition, command):
    proc = subprocess.run(["debugfs", "-R", command, str(partition)],
                          check=True, capture_output=True, text=True)
    return proc.stdout


def install_files(partition, files, work, directory=None):

    label = work / "system.label"
    label.write_bytes(b"u:object_r:system_file:s0\0")
    label_arg = debugfs_quote(label)
    commands = []
    if directory and "Inode:" not in debug(partition, f"stat {directory}"):
        commands.extend([f"mkdir {directory}", f"set_inode_field {directory} mode 040755",
                         f"ea_set -f {label_arg} {directory} security.selinux"])
    for path, source in files.items():
        if "Inode:" in debug(partition, f"stat {path}"):
            commands.append(f"rm {path}")
        commands.extend([f"write {debugfs_quote(source)} {path}",
                         f"set_inode_field {path} mode 0100644",
                         f"ea_set -f {label_arg} {path} security.selinux"])
        if path.endswith(".apk"):
            # Android caches clustered APKs using their containing directory's mtime.
            parent = PurePosixPath(path).parent
            commands.append(f"set_inode_field {parent} mtime @{int(time.time())}")
    batch = work / f"{partition.stem}.debugfs"
    batch.write_text("\n".join(commands) + "\n")
    result = subprocess.run(["debugfs", "-w", "-f", str(batch), str(partition)],
                            check=True, capture_output=True, text=True)
    (work / f"{partition.stem}.debugfs.log").write_text(result.stdout + result.stderr)
    dumped = work / "check-file"
    for path, source in files.items():
        debug(partition, f"dump {path} {debugfs_quote(dumped)}")
        if not dumped.is_file() or digest(dumped) != digest(source):
            raise ValueError(f"Payload failed verification at {path}")
        if "system_file" not in debug(partition, f"ea_list {path}"):
            raise ValueError(f"Incorrect SELinux context at {path}")
        dumped.unlink()
    if directory and "system_file" not in debug(partition, f"ea_list {directory}"):
        raise ValueError("Incorrect SELinux context on the app directory")
    subprocess.run(["e2fsck", "-f", "-n", str(partition)], check=True)


def certs(apksigner, package):
    text = subprocess.run([apksigner, "verify", "--print-certs", str(package)],
                          check=True, capture_output=True, text=True).stdout
    return re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", text)


def patch_quickstep(image, work, build_tools, key):
    partition = prepare_partition(image, "system_ext", work)
    original = work / "stock-quickstep.apk"
    debug(partition, f"dump {QUICKSTEP} {debugfs_quote(original)}")
    apksigner = str(build_tools / "apksigner")
    if certs(apksigner, original) != [TEST_CERT]:
        raise ValueError("This AVD's Quickstep does not use the expected AOSP development certificate")
    cache_test_key(key)
    unsigned = work / "quickstep-unsigned.apk"
    aligned = work / "quickstep-aligned.apk"
    signed = work / "quickstep.apk"
    rewrite_apk(original, unsigned)
    subprocess.run([str(build_tools / "zipalign"), "-P", "16", "-f", "4", str(unsigned), str(aligned)],
                   check=True)
    subprocess.run([apksigner, "sign", "--key", str(key / "testkey.pk8"), "--cert",
                    str(key / "testkey.x509.pem"), "--v4-signing-enabled", "false", "--out",
                    str(signed), str(aligned)], check=True)
    if certs(apksigner, signed) != [TEST_CERT]:
        raise ValueError("Quickstep's certificate changed")
    install_files(partition, {QUICKSTEP: signed}, work)
    write_extents(image, partition, "system_ext")
    print(f"Quickstep: HOME interno, certificado AOSP original. APK SHA-256: {digest(signed)}", flush=True)


def patch(image, apk, policy, work, build_tools, key):
    partition = prepare_partition(image, "system", work)
    directory = "/system/priv-app/NyRoLauncher"
    files = {
        f"{directory}/NyRoLauncher.apk": apk,
        "/system/etc/permissions/privapp-permissions-nyrolauncher.xml":
            policy / "privapp-permissions-nyrolauncher.xml",
        "/system/etc/sysconfig/nyrolauncher-home.xml": policy / "nyrolauncher-home.xml",
    }
    install_files(partition, files, work, directory)
    write_extents(image, partition, "system")
    patch_quickstep(image, work, build_tools, key)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument("apk", type=Path)
    parser.add_argument("policy", type=Path)
    parser.add_argument("work", type=Path)
    parser.add_argument("build_tools", type=Path)
    parser.add_argument("test_key", type=Path)
    args = parser.parse_args()
    patch(args.image, args.apk, args.policy, args.work, args.build_tools, args.test_key)
