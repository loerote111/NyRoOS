#!/usr/bin/env python3
"""Prepare the NyRoCore catalog and its GitHub Release assets."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
PUBLIC_KEY = ROOT / "ota/catalog-public.der"
CATALOG = ROOT / "ota/ota.json"
ASSET_LIMIT = 2 * 1024**3
APPS = {
    "NyRoLauncher": "os.nyro.launcher",
    "NyRoCore": "com.nyroos.core",
}


def repository():
    config = (ROOT / "custom/ota_repo.conf").read_text(encoding="utf-8")
    lines = [line.strip() for line in config.splitlines() if line.strip() and not line.lstrip().startswith("#")]
    if len(lines) != 1 or not re.fullmatch(r"[A-Za-z0-9-]+/[A-Za-z0-9_.-]+", lines[0]):
        raise ValueError("Defina um único USUARIO/REPO em custom/ota_repo.conf.")
    if lines[0].split("/")[1] in (".", ".."):
        raise ValueError("Nome de repositório inválido.")
    return lines[0]


def digest(path):
    with path.open("rb") as data:
        return hashlib.file_digest(data, "sha256").hexdigest()


def read_ota(archive):
    if archive.stat().st_size >= ASSET_LIMIT:
        raise ValueError("O ZIP OTA precisa ter menos de 2 GiB para GitHub Releases.")
    with zipfile.ZipFile(archive) as package:
        info = package.getinfo("META-INF/com/android/metadata")
        if info.file_size > 16384:
            raise ValueError("Metadados OTA excedem o limite aceito pelo NyRoCore.")
        fields = dict(line.split("=", 1) for line in package.read(info).decode().splitlines() if "=" in line)
        if (fields.get("ota-type") != "BLOCK" or any(not fields.get(field)
                for field in ("pre-device", "pre-build", "post-build", "post-timestamp"))):
            raise ValueError("Forneça um OTA Android do tipo BLOCK com metadados de origem e destino.")
        if "|" in fields["pre-device"] or "|" in fields["pre-build"] or not fields["post-timestamp"].isdigit():
            raise ValueError("O OTA precisa indicar um único dispositivo e fingerprint de origem válidos.")
    # Android whole-file signatures occupy the ZIP comment. RecoverySystem on-device
    # verifies the cryptographic signature against the system's recovery trust store.
    with archive.open("rb") as data:
        data.seek(-6, os.SEEK_END)
        footer = data.read(6)
        span = int.from_bytes(footer[:2], "little")
        comment = int.from_bytes(footer[4:], "little")
        if footer[2:4] != b"\xff\xff" or not 6 < span <= comment:
            raise ValueError("O ZIP não contém o rodapé de assinatura whole-file do recovery.")
        data.seek(-span, os.SEEK_END)
        if data.read(1) != b"0":
            raise ValueError("A estrutura de assinatura do recovery está inválida.")
    return fields


def verify_catalog(path):
    envelope = json.loads(path.read_bytes())
    body = base64.b64decode(envelope["manifest"], validate=True)
    signature = base64.b64decode(envelope["signature"], validate=True)
    if hashlib.sha256(body).hexdigest() != envelope["manifestSha256"]:
        raise ValueError("SHA-256 do catálogo inválido.")
    with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
        detached = Path(directory) / "signature"
        detached.write_bytes(signature)
        subprocess.run(["openssl", "dgst", "-sha256", "-keyform", "DER", "-verify", str(PUBLIC_KEY),
                        "-signature", str(detached)], input=body, stdout=subprocess.PIPE, check=True)
    return json.loads(body)


def sign_catalog(record, key, destination):
    public = subprocess.check_output(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"])
    if public != PUBLIC_KEY.read_bytes():
        raise ValueError("A chave privada não corresponde à chave pública do NyRoCore.")
    body = json.dumps(record, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    signature = subprocess.check_output(["openssl", "dgst", "-sha256", "-sign", str(key)], input=body)
    envelope = {
        "manifest": base64.b64encode(body).decode("ascii"),
        "signature": base64.b64encode(signature).decode("ascii"),
        "manifestSha256": hashlib.sha256(body).hexdigest(),
    }
    destination.write_text(json.dumps(envelope, indent=2) + "\n", encoding="utf-8")
    verify_catalog(destination)


def stage_release(args):
    if not re.fullmatch(r"(?:0|[1-9][0-9]{0,5})(?:\.(?:0|[1-9][0-9]{0,5})){2}", args.version):
        raise ValueError("Use uma versão numérica no formato X.Y.Z.")
    notes = args.notes.read_text(encoding="utf-8")
    changes = [line.strip().removeprefix("- ").strip() for line in notes.splitlines()
               if line.strip() and not line.lstrip().startswith("#")]
    if not changes or len(changes) > 12 or any(len(line) > 240 for line in changes):
        raise ValueError("Use de 1 a 12 notas de até 240 caracteres no changelog.")

    repo = repository()
    tag = "v" + args.version
    target = ROOT / "out/releases" / tag
    if target.exists() and not args.replace:
        raise ValueError(f"{target} já existe. Use --replace para refazer o pacote local.")
    metadata = read_ota(args.ota) if args.ota else None
    key = Path(os.environ.get("NYROOS_OTA_SIGNING_KEY", str(Path(os.environ["ANDROID_USER_HOME"]) / "nyrocore-catalog.pem")))
    if not key.is_file():
        raise ValueError("Chave privada ausente. Configure NYROOS_OTA_SIGNING_KEY com a chave do projeto.")

    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".stage-", dir=target.parent) as workspace:
        stage = Path(workspace) / tag
        stage.mkdir()
        names = {}
        apk_metadata = {}
        sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0"
        signers = json.loads((ROOT / "ota/apk-signers.json").read_bytes())
        for app, package in APPS.items():
            source = ROOT / "custom/priv-app" / app / (app + ".apk")
            certificates = subprocess.check_output([sdk / "apksigner", "verify", "--print-certs", source], text=True)
            fingerprints = re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", certificates)
            if fingerprints != [signers[app]]:
                raise ValueError(f"{source.name} foi assinado com certificado diferente do release.")
            badging = subprocess.check_output([sdk / "aapt2", "dump", "badging", source], text=True)
            identity = re.match(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
            if not identity or identity[1] != package:
                raise ValueError(f"Identidade inesperada em {source.name}.")
            name = app + ".apk"
            names[name] = source
            apk_metadata[name] = {"packageName": package, "versionCode": int(identity[2]), "versionName": identity[3]}
        ota_name = f"NyRoOS-{args.version}-ota.zip"
        if args.ota:
            names[ota_name] = args.ota
        assets = []
        for name, source in names.items():
            if not 0 < source.stat().st_size < ASSET_LIMIT:
                raise ValueError(f"{source.name} excede o limite de asset do GitHub.")
            file = stage / name
            shutil.copyfile(source, file)
            if args.raw_assets and name in apk_metadata:
                url = f"https://raw.githubusercontent.com/{repo}/main/ota/releases/{tag}/{name}"
            else:
                url = f"https://github.com/{repo}/releases/download/{tag}/{name}"
            assets.append({"name": name, "size": file.stat().st_size, "sha256": digest(file),
                           "url": url, **apk_metadata.get(name, {})})
        record = {"schema": 1, "version": args.version, "demo": False, "otaAvailable": bool(metadata),
                  "changelog": changes, "artifacts": assets}
        if metadata:
            ota = assets[-1]
            record.update(device=metadata["pre-device"], baseFingerprint=metadata["pre-build"],
                          url=ota["url"], size=ota["size"], sha256=ota["sha256"])
        signed = stage / "ota.json"
        sign_catalog(record, key, signed)
        (stage / "SHA256SUMS").write_text("".join(f"{asset['sha256']}  {asset['name']}\n" for asset in assets)
                                        + f"{digest(signed)}  ota.json\n", encoding="utf-8")
        (stage / "RELEASE_NOTES.md").write_text(notes, encoding="utf-8")

        backup = Path(workspace) / "previous"
        if target.exists():
            target.rename(backup)
        try:
            stage.rename(target)
            with tempfile.NamedTemporaryFile(dir=CATALOG.parent, prefix=".catalog-", delete=False) as file:
                temporary = Path(file.name)
                try:
                    file.write((target / "ota.json").read_bytes())
                    file.flush()
                    os.chmod(temporary, 0o644)
                    os.replace(temporary, CATALOG)
                finally:
                    temporary.unlink(missing_ok=True)
        except Exception:
            if target.exists():
                target.rename(stage)
            if backup.exists():
                backup.rename(target)
            raise

    command = ["gh", "release", "create", tag, "--repo", repo, "--verify-tag",
               "--title", f"NyRoOS {tag}", "--notes-file", str(target / "RELEASE_NOTES.md"),
               *(str(target / name) for name in names), str(target / "SHA256SUMS"), str(target / "ota.json")]
    print(f"Release preparada em {target}")
    if args.raw_assets:
        print(f"Publique os APKs do pacote em ota/releases/{tag} junto ao ota/ota.json assinado.")
    print(shlex.join(command))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--feed-url", action="store_true")
    parser.add_argument("--verify-catalog", type=Path)
    parser.add_argument("--version")
    parser.add_argument("--notes", type=Path)
    parser.add_argument("--ota", type=Path)
    parser.add_argument("--replace", action="store_true")
    parser.add_argument("--raw-assets", action="store_true", help="usar APKs versionados na branch main")
    args = parser.parse_args()
    if args.feed_url:
        print(f"https://raw.githubusercontent.com/{repository()}/main/ota/ota.json")
    elif args.verify_catalog:
        print(json.dumps(verify_catalog(args.verify_catalog), ensure_ascii=False, indent=2))
    elif args.version and args.notes:
        stage_release(args)
    else:
        parser.error("Informe --version e --notes.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"OTA: {error}", file=sys.stderr)
        raise SystemExit(1)
