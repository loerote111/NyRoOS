#!/usr/bin/env bash
set -Eeuo pipefail

project_dir="$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$project_dir"
source ./env.sh

guest=(adb -s emulator-5554)
avd="$ANDROID_AVD_HOME/dev_phone.avd"
config="$avd/config.ini"
gapps_relative=system-images/android-37.0/dev_phone-gapps/x86_64
nyroos_relative=system-images/android-37.0/dev_phone-nyroos/x86_64
gapps="$ANDROID_HOME/$gapps_relative"
nyroos="$ANDROID_HOME/$nyroos_relative"
package_prefix=os.nyro.branding
overlays=(NyRoOSDefaultsOverlay NyRoOSThemeOverlay NyRoOSSystemUIOverlay NyRoOSSettingsOverlay)
packages=(com.android.vending com.google.android.googlequicksearchbox com.android.chrome com.google.android.gms com.google.android.gsf)
legacy_dirs=(Browser2 QuickSearchBox)
legacy_packages=(org.chromium.webview_shell com.android.quicksearchbox)
evidence="$project_dir/work/nyroos"
mkdir -p -- "$evidence"

exec 8>"$ANDROID_AVD_HOME/dev_phone.branding.lock"
flock -n 8
exec > >(tee -- "$project_dir/work/nyroos_branding.log") 2>&1
if ! grep -Fqx -e "image.sysdir.1=$gapps_relative/" -e "image.sysdir.1=$nyroos_relative/" "$config"; then
    printf 'Este script requer a imagem Android 17 com GApps do AVD dev_phone.\n' >&2
    exit 1
fi

export XDG_CACHE_HOME="$ANDROID_EMULATOR_HOME/cache"
export XDG_CONFIG_HOME="$ANDROID_EMULATOR_HOME/config"
export ANDROID_TMP="$TMPDIR"
export ANDROID_EMULATOR_DISCOVERY_DIR="$ANDROID_EMULATOR_HOME/running"
export ANDROID_EMU_CRASH_REPORTING_DATABASE="$TMPDIR/emulator-crash.db"

console_ports() {
    ss -H -ltn '( sport = :5554 or sport = :5555 )'
}

console_name() {
    timeout 4 "${guest[@]}" emu avd name 2>/dev/null | tr -d '\r' | sed -n '1p'
}

stop_guest() {
    if [[ -n $(console_ports) ]]; then
        if [[ $(console_name) != dev_phone ]]; then
            printf 'As portas 5554/5555 estão ocupadas por outro processo.\n' >&2
            return 1
        fi
        "${guest[@]}" emu kill
        timeout 45 bash -c 'while [[ -n $(ss -H -ltn "( sport = :5554 or sport = :5555 )") ]]; do sleep .3; done'
    fi
}

wait_boot() {
    local deadline=$((SECONDS + 150))
    local reconnected=false
    while (( SECONDS < deadline )); do
        if timeout 2 "${guest[@]}" wait-for-device &&
            [[ $(timeout 4 "${guest[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true) == 1 ]]; then
            [[ $(console_name) == dev_phone ]]
            return
        fi
        if ! "$reconnected" && (( SECONDS + 130 >= deadline )) &&
            [[ $("${guest[@]}" get-state 2>/dev/null || true) == offline ]]; then
            "${guest[@]}" reconnect || true
            reconnected=true
        fi
        sleep .6
    done
    printf 'O Android não concluiu a inicialização.\n' >&2
    return 1
}

identity_snapshot() {
    "${guest[@]}" shell getprop | tr -d '\r' |
        grep -E '^\[ro\.(.*\.fingerprint|build\.(id|tags|type|version\.[a-z_]+)|hardware(\.[^]]+)?|product\.(brand|device|manufacturer|model|name|cpu\.[^]]+)|boot\.(hardware|verifiedbootstate|vbmeta[^]]*|flash\.locked))\]:' |
        LC_ALL=C sort
}

wait_root() {
    "${guest[@]}" root
    timeout 35 "${guest[@]}" wait-for-device
    [[ $("${guest[@]}" shell id -u | tr -d '\r') == 0 ]]
}

screenshot() {
    "${guest[@]}" exec-out screencap -p > "$evidence/$1.png"
    python3 -I -B -c 'import sys; from PIL import Image; Image.open(sys.argv[1]).verify()' "$evidence/$1.png"
}

stage=$(mktemp -d "$TMPDIR/dev-phone-nyroos.XXXXXX")
payload="$stage/payload"
mkdir -p -- "$payload/etc" "$payload/media" "$payload/overlay/config"
cp -p -- "$config" "$stage/config.ini"
writable_pid=
stopped=false
normal_started=false
switched=false
committed=false

cleanup() {
    local status=$?
    if [[ -n $writable_pid ]]; then
        stop_guest || kill -TERM "$writable_pid" 2>/dev/null || true
        wait "$writable_pid" 2>/dev/null || true
    fi
    if (( status != 0 )) && ! "$committed"; then
        if "$normal_started"; then stop_guest || true; fi
        if "$switched"; then
            cp -p -- "$stage/config.ini" "$config"
            if [[ -f "$stage/prior-nyroos.img" ]]; then
                mv -T -- "$stage/prior-nyroos.img" "$nyroos/system.img"
            elif [[ -f "$nyroos/system.img" ]]; then
                mv -T -- "$nyroos/system.img" "$stage/failed-nyroos.img"
            fi
        fi
        if "$stopped"; then
            for image in system.img.qcow2 vendor.img.qcow2; do
                if [[ -f "$avd/$image" ]]; then mv -- "$avd/$image" "$stage/failed-$image"; fi
                if [[ -f "$stage/prior-$image" ]]; then mv -- "$stage/prior-$image" "$avd/$image"; fi
            done
        fi
        if [[ -f "$stage/product.debugfs.log" ]]; then
            cp -- "$stage/product.debugfs.log" "$evidence/product.debugfs.log"
        fi
    fi
    rm -r -- "$stage"
    if (( status != 0 )); then printf 'Etapa interrompida com código %s.\n' "$status" >&2; fi
}
trap cleanup EXIT
trap 'printf "Falha na linha %s do script.\n" "$LINENO" >&2' ERR
trap 'exit 130' INT
trap 'exit 143' TERM

printf 'NyRoOS 1.0 | dev_phone | Android 17 | emulator-5554\n'
python3 -I -B scripts/nyroos_media.py render custom/media
bash scripts/build_nyroos_overlays.sh
install -m 0644 custom/media/bootanimation.zip custom/media/nyroos_wallpaper.png "$payload/media/"
for name in "${overlays[@]}"; do
    install -m 0644 "custom/overlays/$name.apk" "$payload/overlay/"
done

stop_guest
stopped=true
for image in system.img.qcow2 vendor.img.qcow2; do
    if [[ -f "$avd/$image" ]]; then mv -- "$avd/$image" "$stage/prior-$image"; fi
done
cp --reflink=auto --sparse=always -- "$gapps/system.img" "$stage/system.img"
python3 -E -s -B scripts/nyroos_image.py "$stage/system.img" --allow-scratch
printf '\nAplicação na cópia gravável com -writable-system.\n'
env "HOME=$ANDROID_EMULATOR_HOME/console-home" emulator @dev_phone -port 5554 \
    -sysdir "$gapps" -system "$stage/system.img" -writable-system \
    -accel on -gpu host -no-window -no-audio -no-snapshot -no-metrics \
    -crash-report-mode disabled </dev/null > "$evidence/writable-emulator.log" 2>&1 8>&- &
writable_pid=$!
wait_boot
identity_snapshot > "$evidence/compat-before.txt"
wait_root
remount_status=0
remount=$(timeout 95 "${guest[@]}" remount 2>&1) || remount_status=$?
printf '%s\n' "$remount"
if [[ $remount == *"reboot your device"* ]]; then
    "${guest[@]}" reboot
    wait_boot
    wait_root
    timeout 95 "${guest[@]}" remount
elif (( remount_status != 0 )); then
    exit "$remount_status"
fi
"${guest[@]}" shell getenforce

"${guest[@]}" pull /product/etc/build.prop "$payload/etc/build.prop"
sed -i '/^ro\.nyroos\.version=/d; /^ro\.build\.display\.id=/d; /^ro\.product\.bootanim\.file=/d; /^ro\.config\.wallpaper=/d' "$payload/etc/build.prop"
cat >> "$payload/etc/build.prop" <<'EOF'

# NyRoOS visual identity
ro.nyroos.version=1.0
ro.build.display.id=NyRoOS 1.0
ro.product.bootanim.file=bootanimation.zip
ro.config.wallpaper=/product/media/nyroos_wallpaper.png
EOF

# Preserve the priority and enabled defaults of the original product RROs.
"${guest[@]}" shell cmd overlay dump --user 0 > "$stage/stock-overlays.txt"
python3 -I -B - "$stage/stock-overlays.txt" "$payload/overlay/config/config.xml" <<'PY'
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

root = ET.Element("config")
stock = []
text = Path(sys.argv[1]).read_text()
for block in re.findall(r"^\S+:0 \{\n(.*?)^\}", text, flags=re.M | re.S):
    fields = dict(re.findall(r"^\s*m(\w+)\.+: (.*)$", block, flags=re.M))
    if (fields.get("BaseCodePath", "").startswith("/product/overlay/")
            and fields.get("IsMutable") == "false"):
        stock.append((int(fields["Priority"]), fields["PackageName"], fields["IsEnabled"]))
if not stock:
    raise SystemExit("Não foi possível ler os overlays originais de /product")
for _, package, enabled in sorted(stock):
    ET.SubElement(root, "overlay", package=package, enabled=enabled, mutable="false")
ET.SubElement(root, "overlay", package="os.nyro.branding.defaults", enabled="true", mutable="false")
for name in ("theme", "systemui", "settings"):
    ET.SubElement(root, "overlay", package=f"os.nyro.branding.{name}", enabled="true", mutable="true")
ET.indent(root)
ET.ElementTree(root).write(sys.argv[2], encoding="utf-8", xml_declaration=True)
PY

remove_apps=()
for index in "${!legacy_dirs[@]}"; do
    name=${legacy_dirs[$index]}
    package=${legacy_packages[$index]}
    path=$("${guest[@]}" shell pm path "$package" | tr -d '\r' || true)
    if [[ $path == "package:/product/app/$name/$name.apk" ]]; then
        printf 'Removendo /product/app/%s (%s).\n' "$name" "$package"
        "${guest[@]}" shell rm -r "/product/app/$name"
        remove_apps+=(--remove-app "$name")
    elif [[ -n $path ]]; then
        printf '%s possui caminho inesperado: %s\n' "$package" "$path" >&2
        exit 1
    fi
done

files=(etc/build.prop media/bootanimation.zip media/nyroos_wallpaper.png overlay/config/config.xml)
for name in "${overlays[@]}"; do files+=("overlay/$name.apk"); done
for file in "${files[@]}"; do
    "${guest[@]}" shell mkdir -p "/product/${file%/*}"
    "${guest[@]}" push "$payload/$file" "/product/$file"
    mode=0644
    if [[ $file == etc/build.prop ]]; then mode=0600; fi
    "${guest[@]}" shell chmod "$mode" "/product/$file"
    "${guest[@]}" shell restorecon -F "/product/${file%/*}" "/product/$file"
done
"${guest[@]}" shell sync
stop_guest
wait "$writable_pid"
writable_pid=
for image in system.img.qcow2 vendor.img.qcow2; do
    if [[ -f "$avd/$image" ]]; then mv -- "$avd/$image" "$stage/writable-$image"; fi
done

printf '\nPersistindo as mudanças apenas na partição product da imagem NyRoOS.\n'
# Build from a fresh inode. The writable emulator was only used to validate staging.
rm -- "$stage/system.img"
cp --reflink=auto --sparse=always -- "$gapps/system.img" "$stage/system.img"
python3 -E -s -B scripts/nyroos_image.py "$stage/system.img" "$payload" "$stage" "${remove_apps[@]}"
mkdir -p -- "$nyroos"
for file in "$gapps"/*; do
    name=${file##*/}
    if [[ $name == system.img || $name == package.xml ]]; then continue; fi
    ln -sfn -- "../../dev_phone-gapps/x86_64/$name" "$nyroos/$name"
done
if [[ -f "$nyroos/system.img" ]]; then
    ln -- "$nyroos/system.img" "$stage/prior-nyroos.img"
fi
switched=true
mv -T -- "$stage/system.img" "$nyroos/system.img"
sed "s|^image\.sysdir\.1=.*|image.sysdir.1=$nyroos_relative/|" "$config" > "$stage/nyroos-config.ini"
mv -T -- "$stage/nyroos-config.ini" "$config"

printf '\nIniciando dev_phone normalmente, com captura da tela desde o boot.\n'
normal_started=true
setsid --fork /bin/bash --noprofile --norc ./scripts/start_emulator.sh -no-window \
    </dev/null > "$evidence/start.log" 2>&1 8>&-
frame_deadline=$((SECONDS + 32))
candidate="$stage/boot-screen.png"
boot_frame=false
while (( SECONDS < frame_deadline )); do
    if [[ $(console_name) == dev_phone ]] &&
        timeout 3 "${guest[@]}" emu screenrecord screenshot "$candidate" >/dev/null 2>&1 &&
        python3 -I -B scripts/nyroos_media.py boot-frame "$candidate" "$project_dir/work/nyroos_boot_frame.png"; then
        boot_frame=true
        printf 'Frame real da animação capturado via console do emulador.\n'
        break
    fi
    if [[ $(timeout 2 "${guest[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true) == 1 ]]; then
        break
    fi
    sleep .2
done

wait_boot
cat "$evidence/start.log"
printf 'sys.boot_completed=%s\n' "$("${guest[@]}" shell getprop sys.boot_completed | tr -d '\r')"
"${guest[@]}" logcat -d -v brief -s 'BootAnimation:*' > "$evidence/bootanimation.log"
cat "$evidence/bootanimation.log"
printf 'SELinux: %s\n' "$("${guest[@]}" shell getenforce)"
[[ $("${guest[@]}" shell getenforce | tr -d '\r') == Enforcing ]]
"${guest[@]}" shell grep -E "' /product '" /proc/mounts | grep -E ' ro,'
[[ $("${guest[@]}" shell getprop ro.nyroos.version | tr -d '\r') == 1.0 ]]
[[ $("${guest[@]}" shell getprop ro.build.display.id | tr -d '\r') == 'NyRoOS 1.0' ]]
"${guest[@]}" shell 'echo "ro.nyroos.version=$(getprop ro.nyroos.version)"; echo "ro.build.display.id=$(getprop ro.build.display.id)"'
identity_snapshot > "$evidence/compat-after.txt"
diff -u "$evidence/compat-before.txt" "$evidence/compat-after.txt"
printf 'Fingerprint, ABI, propriedades de hardware e estado de boot verificado conferidos.\n'

for file in media/bootanimation.zip media/nyroos_wallpaper.png "${files[@]:3}"; do
    host_sha=$(sha256sum "$payload/$file" | awk '{print $1}')
    guest_sha=$("${guest[@]}" shell sha256sum "/product/$file" | awk '{print $1}')
    [[ $host_sha == "$guest_sha" ]]
    printf '%s  /product/%s\n' "$guest_sha" "$file"
done
system_packages=$("${guest[@]}" shell pm list packages -s --user 0 | tr -d '\r')
for package in "${packages[@]}"; do
    grep -Fx "package:$package" <<< "$system_packages"
    "${guest[@]}" shell pm path "$package" | sed -n '1p'
done
for index in "${!legacy_dirs[@]}"; do
    name=${legacy_dirs[$index]}
    package=${legacy_packages[$index]}
    if grep -Fxq "package:$package" <<< "$system_packages"; then
        printf '%s ainda aparece na lista de pacotes.\n' "$package" >&2
        exit 1
    fi
    "${guest[@]}" shell test ! -e "/product/app/$name/$name.apk"
    printf '%s (%s) ausente da imagem iniciada.\n' "$name" "$package"
done
"${guest[@]}" shell cmd package query-activities --brief --user 0 \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.google.android.googlequicksearchbox |
    grep -F 'com.google.android.googlequicksearchbox/.SearchActivity'
"${guest[@]}" shell dumpsys webviewupdate | grep -F 'Current WebView package (name, version): (com.android.webview,'
"${guest[@]}" shell cmd role add-role-holder --user 0 android.app.role.BROWSER com.android.chrome
"${guest[@]}" shell cmd role get-role-holders --user 0 android.app.role.BROWSER | tr -d '\r' | grep -Fx com.android.chrome
"${guest[@]}" shell cmd package resolve-activity --brief --user 0 -a android.intent.action.VIEW -d https://example.com | grep -F com.android.chrome/

printf '\nAtivação e validação dos RROs.\n'
night_mode=$("${guest[@]}" shell cmd uimode night | tr -d '\r')
printf 'Modo noturno ao iniciar: %s\n' "$night_mode"
if [[ $night_mode != 'Night mode: yes' ]]; then
    "${guest[@]}" shell cmd uimode night yes
fi
for target in theme systemui settings; do
    "${guest[@]}" shell cmd overlay enable --user 0 "$package_prefix.$target"
    "${guest[@]}" shell cmd overlay set-priority --user 0 "$package_prefix.$target" highest
done
sleep 2
"${guest[@]}" shell cmd overlay list --user 0 | grep -F "$package_prefix."
"${guest[@]}" shell cmd uimode night | grep -Fx 'Night mode: yes'
lookup() {
    local target=$1 resource=$2 expected=$3 actual
    actual=$("${guest[@]}" shell cmd overlay lookup --user 0 "$target" "$target:$resource" | tr -d '\r')
    printf '%s:%s → %s\n' "$target" "$resource" "$actual"
    [[ ${actual,,} == "$expected" ]]
}
lookup android integer/config_defaultNightMode 2
lookup android color/system_primary_dark '#ffb29aff'
lookup android color/system_background_dark '#ff11131a'
lookup com.android.systemui dimen/common_tile_default_active_tile_corner_radius 32.0dip
lookup com.android.settings dimen/homepage_title_margin_bottom 16.0dip
lookup com.android.settings color/homepage_network_background '#ff433667'
committed=true

printf '\nCapturando início, painel rápido e Configurações.\n'
"${guest[@]}" shell input keyevent KEYCODE_WAKEUP
"${guest[@]}" shell wm dismiss-keyguard
"${guest[@]}" shell input keyevent KEYCODE_HOME
sleep 2
screenshot home
"${guest[@]}" shell cmd statusbar expand-settings
sleep 5
screenshot quick_settings
"${guest[@]}" shell cmd statusbar collapse
"${guest[@]}" shell am start -W -a android.settings.SETTINGS
sleep 2
screenshot settings
python3 -I -B scripts/nyroos_media.py compose "$evidence"
"${guest[@]}" shell input keyevent KEYCODE_HOME
if ! "$boot_frame"; then
    printf 'O console não capturou um frame reconhecível durante este boot.\n' >&2
    exit 1
fi
printf 'Validação concluída. dev_phone segue ligado em modo normal.\n'
