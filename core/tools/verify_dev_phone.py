#!/usr/bin/env python3
"""Test NyRoCore on emulator-5554 with a signed non-installable HTTP release."""

import argparse
import base64
import hashlib
import http.server
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_STORED


ADB = ["adb", "-s", "emulator-5554"]
CORE = "com.nyroos.core"
TEST_APP = "com.nyroos.core.fixture"
REPORT = "/sdcard/NyRoOS/trusted_apps.json"


def run(*args, check=True, **options):
    return subprocess.run([str(arg) for arg in args], capture_output=True, check=check, timeout=25, **options)


def adb(*args, check=True):
    return run(*ADB, *args, check=check)


def shell(*args, check=True):
    return adb("shell", *args, check=check).stdout.decode().strip()


def ui():
    data = adb("exec-out", "uiautomator", "dump", "--compressed", "/dev/tty").stdout
    end = b"</hierarchy>"
    start, stop = data.find(b"<hierarchy"), data.find(end)
    if start < 0 or stop < 0:
        raise RuntimeError(data[:300])
    return list(ET.fromstring(data[start:stop + len(end)]).iter("node"))


def attributes(nodes):
    return [node.get("text") or node.get("content-desc") for node in nodes
            if node.get("text") or node.get("content-desc")]


def find(label, timeout=10):
    until = time.monotonic() + timeout
    while True:
        nodes = ui()
        for node in nodes:
            if any(value is not None and value.casefold() == label.casefold()
                   for value in (node.get("text"), node.get("content-desc"), node.get("hint"))):
                return node
        if time.monotonic() >= until:
            raise AssertionError(f"Ausente {label!r}. Visíveis: {attributes(nodes)}")


def click(label):
    node = find(label)
    x, y, w, h = map(int, re.findall(r"\d+", node.get("bounds")))
    shell("input", "tap", (x + w) // 2, (y + h) // 2)


def shot(name):
    path = Path("work") / f"nyro_core_{name}.png"
    path.write_bytes(adb("exec-out", "screencap", "-p").stdout)
    from PIL import Image
    with Image.open(path) as image:
        image.verify()


def report(line):
    print(line, flush=True)


def open_core(fresh=False):
    flags = ("-S", "-f", "0x10008000") if fresh else ()
    shell("am", "start", "-W", *flags, "-n", CORE + "/.CoreActivity")


def compile_fixture(work):
    sdk = Path(os.environ["ANDROID_HOME"])
    build = sdk / "build-tools/37.0.0"
    manifest = work / "fixture.xml"
    manifest.write_text(f"""
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{TEST_APP}">
  <uses-permission android:name="android.permission.CAMERA" />
  <application android:label="Projeto de teste" android:hasCode="false">
    <activity android:name="android.app.Activity" android:exported="true">
      <intent-filter><action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" /></intent-filter>
    </activity>
  </application>
</manifest>
""")
    unsigned, signed = work / "unsigned.apk", work / "fixture.apk"
    run(build / "aapt2", "link", "-I", sdk / "platforms/android-37.0/android.jar", "--manifest", manifest,
        "--min-sdk-version", "34", "--target-sdk-version", "37", "-o", unsigned)
    run(build / "apksigner", "sign", "--ks", Path(os.environ["ANDROID_USER_HOME"]) / "nyrocore.p12",
        "--ks-key-alias", "nyrocore", "--ks-pass", "pass:android", "--out", signed, unsigned)
    return signed


def catalog():
    version = "1.1"
    device = shell("getprop", "ro.product.device")
    fingerprint = shell("getprop", "ro.build.fingerprint")
    buffer = io.BytesIO()
    with ZipFile(buffer, "w", compression=ZIP_STORED) as package:
        package.writestr("DEMO.txt", "Pacote de demonstração. Não é uma imagem OTA.\n")
        package.writestr("demo.bin", b"NyRoCore OTA demonstration.\0" * 80000)
    content = buffer.getvalue()
    manifest = json.dumps({
        "version": version, "device": device, "baseFingerprint": fingerprint, "demo": True,
        "url": "http://127.0.0.1:8765/nyroos-demo.zip", "size": len(content),
        "sha256": hashlib.sha256(content).hexdigest(),
        "changelog": ["Catálogo de versões assinado", "Verificação de integridade no download"],
    }, ensure_ascii=False, separators=(",", ":")).encode()
    key = Path(os.environ["ANDROID_USER_HOME"]) / "nyrocore-catalog.pem"
    signature = run("openssl", "dgst", "-sha256", "-sign", key, input=manifest).stdout
    def envelope(signature):
        return json.dumps({"manifest": base64.b64encode(manifest).decode(),
                           "signature": base64.b64encode(signature).decode()}).encode()
    tampered = bytes([signature[0] ^ 1]) + signature[1:]
    return content, envelope(signature), envelope(tampered)


def test_ota(catalog_file=None):
    config = "/sdcard/NyRoOS/ota_config.json"
    original = adb("exec-out", "cat", config).stdout
    with tempfile.TemporaryDirectory(prefix="nyrocore-feed.", dir=os.environ.get("TMPDIR")) as directory:
        backup = Path(directory) / "ota_config.json"
        backup.write_bytes(original)
        adb("push", "core/examples/ota_config.json", config)
        try:
            verify_ota(catalog_file)
        finally:
            adb("push", backup, config)


def verify_ota(catalog_file):
    if catalog_file:
        package=b""
        accepted=catalog_file.read_bytes()
        invalid=json.loads(accepted)
        original=invalid["manifestSha256"]
        invalid["manifestSha256"]=("0" if original[0] != "0" else "1") + original[1:]
        tampered=json.dumps(invalid).encode()
        rejection="SHA-256 do catálogo inválido."
    else:
        package, accepted, tampered = catalog()
        rejection="Assinatura do catálogo inválida."
    events = []
    feed = tampered

    class Server(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path not in ("/ota.json", "/nyroos-demo.zip"):
                self.send_error(404)
                return
            data = feed if self.path == "/ota.json" else package
            events.append((self.path, len(data)))
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            for offset in range(0, len(data), 65536):
                self.wfile.write(data[offset:offset + 65536])
                if self.path != "/ota.json":
                    time.sleep(.07)

        def log_message(self, *args):
            pass

    with http.server.ThreadingHTTPServer(("127.0.0.1",0),Server) as server:
        threading.Thread(target=server.serve_forever,daemon=True).start()
        adb("reverse","tcp:8765",f"tcp:{server.server_port}")
        try:
            open_core(fresh=True)
            click("Atualização")
            click("Selecionar configuração")
            click("ota_config.json")
            find("Configuração vinculada. Verifique o catálogo.")
            click("Verificar atualizações")
            find(rejection)
            report("Catálogo adulterado rejeitado.")
            feed = accepted
            click("Verificar atualizações")
            if catalog_file:
                find("Catálogo assinado verificado. Nenhum OTA disponível.")
                assert [path for path, _ in events] == ["/ota.json", "/ota.json"]
                report(f"NyRoCore leu {catalog_file} e confirmou a assinatura e o SHA-256 do catálogo oficial.")
                report("Catálogo inicial exibiu corretamente que ainda não há OTA de sistema disponível.")
                return
            find("NyRoOS 1.1")
            find("SIMULAÇÃO")
            click("Baixar pacote")
            find(f"Download verificado  •  {len(package) / 1048576:.1f} MiB".replace(".", ","))
            assert find("Instalar Agora").get("enabled") == "false"
            assert ("/nyroos-demo.zip",len(package)) in events
            shot("ota")
            report(f"NyRoOS 1.1 detectado por HTTP local. {len(package)} bytes transferidos e SHA-256 verificado.")
            report("Instalar Agora desativado para o pacote de demonstração.")
        finally:
            adb("reverse","--remove","tcp:8765",check=False)
            server.shutdown()


def permission_granted():
    return "android.permission.CAMERA: granted=true" in shell("dumpsys","package",TEST_APP)


def test_orchestrator(work):
    assert not shell("pm","path",TEST_APP,check=False)
    fixture=compile_fixture(work)
    adb("install",fixture)
    approved=False
    try:
        open_core(fresh=True)
        click("Orquestrador")
        find("Projeto de teste")
        if "trusted_apps.json vinculado" not in attributes(ui()):
            click("Vincular relatório")
            click("Save")
            find("trusted_apps.json vinculado")
        if find("Acompanhar Projeto de teste").get("checked") == "true":
            click("Acompanhar Projeto de teste")
            click("REMOVER")
        click("Acompanhar Projeto de teste")
        click("ACOMPANHAR")
        select=find("Acompanhar Projeto de teste")
        assert select.get("checked") == "true"
        approved=True
        assert not permission_granted()
        payload=adb("exec-out","cat",REPORT).stdout
        exported=json.loads(payload)
        assert exported["mode"]=="supervised"
        assert any(item["package"] == TEST_APP for item in exported["apps"])
        Path("work/nyro_core_trusted_apps_test.json").write_bytes(payload)
        find("Acompanhado  •  Bateria gerida pelo Android")
        shell("am","force-stop",CORE)
        open_core()
        click("Orquestrador")
        assert find("Acompanhar Projeto de teste").get("checked") == "true"
        shot("orchestrator")
        report("Acompanhamento persistiu após fechar o app e foi exportado em " + REPORT + ".")
        report("A aprovação não concedeu CAMERA nem alterou a otimização de bateria.")
        click("Permissões")
        find("App info")
        click("Permissions")
        click("Camera")
        click("Allow only while using the app")
        assert permission_granted()
        report("CAMERA concedida somente após selecionar a autorização individual nas Configurações.")
    finally:
        try:
            if approved:
                open_core(fresh=True)
                click("Orquestrador")
                click("Acompanhar Projeto de teste")
                click("REMOVER")
        finally:
            adb("uninstall",TEST_APP,check=False)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog",type=Path,help="Testa o catálogo inicial sem OTA completo.")
    args=parser.parse_args()
    assert adb("emu","avd","name").stdout.decode().splitlines()[0] == "dev_phone"
    assert shell("getprop","sys.boot_completed") == "1" and shell("getenforce") == "Enforcing"
    temporary = os.environ.get("TMPDIR")
    test_ota(args.catalog)
    if not args.catalog:
        with tempfile.TemporaryDirectory(prefix="nyrocore-qa.",dir=temporary) as files:
            test_orchestrator(Path(files))
    crashes=adb("logcat","-b","crash","-d").stdout.decode()
    assert not re.search(r"Process: com\.nyroos\.core(?:[,\s]|$)",crashes)
    traces=adb("logcat","-d","-v","threadtime","-s","NyRoCore:*").stdout
    Path("work/nyro_core_logcat.txt").write_bytes(traces)
    report("Sem crashes do NyRoCore. Emulador permanece ligado.")


if __name__ == "__main__":
    main()
