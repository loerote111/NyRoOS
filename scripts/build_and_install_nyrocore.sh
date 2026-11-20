#!/usr/bin/env bash
set -Eeuo pipefail

cd -- "$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
project=$PWD
source ./env.sh
build_only=false
case "${1-}" in
    '') ;;
    --build-only) build_only=true ;;
    *) printf 'Uso: %s [--build-only]\n' "$0" >&2; exit 2 ;;
esac
(( $# <= 1 ))
mkdir -p work
exec 8>"$ANDROID_AVD_HOME/dev_phone.nyrocore.lock"
flock -n 8
if ! "$build_only"; then
    exec 7>"$ANDROID_AVD_HOME/dev_phone.branding.lock"
    flock -n 7
    exec > >(tee "$project/work/nyro_core_install.log") 2>&1
fi
trap 'printf "Falha na linha %s do script.\n" "$LINENO" >&2' ERR
trap 'exit 130' INT
trap 'exit 143' TERM

stage=$(mktemp -d "$TMPDIR/dev-phone-nyrocore.XXXXXX")
guest=(adb -s emulator-5554)
prior_relative=system-images/android-37.0/dev_phone-nyroos-launcher/x86_64
target_relative=system-images/android-37.0/dev_phone-nyroos-core/x86_64
prior="$ANDROID_HOME/$prior_relative"
target="$ANDROID_HOME/$target_relative"
config="$ANDROID_AVD_HOME/dev_phone.avd/config.ini"
swapped=false
committed=false

ports() {
    ss -H -ltn '( sport = :5554 or sport = :5555 )'
}

assert_guest() {
    [[ $(timeout 4 "${guest[@]}" emu avd name | tr -d '\r' | sed -n '1p') == dev_phone ]]
}

stop_guest() {
    if [[ -n $(ports) ]]; then
        assert_guest || return 1
        "${guest[@]}" emu kill
        timeout 45 bash -c 'while [[ -n $(ss -H -ltn "( sport = :5554 or sport = :5555 )") ]]; do sleep .3; done'
    fi
}

start_guest() {
    setsid --fork /bin/bash --noprofile --norc ./scripts/start_emulator.sh -no-window \
        </dev/null > "$project/work/nyro_core_emulator_start.log" 2>&1 7>&- 8>&-
}

cleanup() {
    local result=$?
    if (( result != 0 )); then
        if "$swapped" && ! "$committed"; then
            stop_guest || true
            cp -p -- "$stage/config.ini" "$config"
            if [[ -f "$stage/previous.img" ]]; then
                mv -T -- "$stage/previous.img" "$target/system.img"
            else
                rm -f -- "$target/system.img"
            fi
            printf 'Imagem anterior restaurada.\n' >&2
            if [[ -z $(ports) ]]; then start_guest; fi
        fi
        if [[ -f "$stage/system.debugfs.log" ]]; then
            cp -- "$stage/system.debugfs.log" "$project/work/nyro_core_debugfs.log"
        fi
    fi
    rm -r -- "$stage"
}
trap cleanup EXIT

sdk="$ANDROID_HOME/build-tools/37.0.0"
platform="$ANDROID_HOME/platforms/android-37.0/android.jar"
payload="$project/custom/priv-app/NyRoCore"
apk_key="$ANDROID_USER_HOME/nyrocore.p12"
export JDK_JAVA_OPTIONS="-XX:+PerfDisableSharedMem \"-Djava.io.tmpdir=$TMPDIR\""
mkdir -p "$stage/assets" "$stage/dex" "$stage/generated" "$stage/classes" "$payload"

install -m 0644 "$project/ota/catalog-public.der" "$stage/assets/ota_public.der"
python3 -I -B "$project/ota/package_release.py" --feed-url > "$stage/assets/ota_feed.txt"
if [[ ! -s $apk_key ]]; then
    (umask 077; keytool -genkeypair -keystore "$apk_key" -storetype PKCS12 -storepass android \
        -alias nyrocore -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=NyRoCore Development')
fi

printf 'Compilando NyRoCore com AAPT2, javac e D8.\n'
"$sdk/aapt2" compile --dir "$project/core/res" -o "$stage/res.zip"
"$sdk/aapt2" link -I "$platform" --manifest "$project/core/AndroidManifest.xml" \
    --min-sdk-version 34 --target-sdk-version 37 --java "$stage/generated" \
    -A "$stage/assets" -o "$stage/unsigned.apk" "$stage/res.zip"
mapfile -d '' -t sources < <(find "$project/core/src" "$stage/generated" -name '*.java' -print0)
javac --release 17 -cp "$platform" -d "$stage/classes" "${sources[@]}"
jar --create --file "$stage/classes.jar" -C "$stage/classes" .
"$sdk/d8" --release --min-api 34 --lib "$platform" --output "$stage/dex" "$stage/classes.jar"
(cd "$stage/dex" && zip -q "$stage/unsigned.apk" classes.dex)
"$sdk/zipalign" -f 4 "$stage/unsigned.apk" "$stage/aligned.apk"
"$sdk/apksigner" sign --ks "$apk_key" --ks-key-alias nyrocore --ks-pass pass:android \
    --v4-signing-enabled false --out "$stage/NyRoCore.apk" "$stage/aligned.apk"
"$sdk/apksigner" verify --verbose "$stage/NyRoCore.apk"
install -m 0644 "$stage/NyRoCore.apk" "$payload/NyRoCore.apk"
sha256sum "$payload/NyRoCore.apk"
if "$build_only"; then exit 0; fi

grep -Fxq -e "image.sysdir.1=$prior_relative/" -e "image.sysdir.1=$target_relative/" "$config"
if [[ -n $(ports) ]]; then assert_guest; fi
cp -p "$config" "$stage/config.ini"
cp --reflink=auto --sparse=always "$prior/system.img" "$stage/system.img"
python3 -E -s -B - "$stage" "$payload/NyRoCore.apk" "$project/core/system/privapp-permissions-nyrocore.xml" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0, str(Path("scripts").resolve()))
from nyroos_launcher_image import install_files, prepare_partition
from nyroos_image import write_extents

work, apk, policy = map(Path, sys.argv[1:])
image = work / "system.img"
partition = prepare_partition(image, "system", work)
directory = "/system/priv-app/NyRoCore"
install_files(partition, {directory + "/NyRoCore.apk": apk, "/system/etc/permissions/" + policy.name: policy},
              work, directory)
write_extents(image, partition, "system")
PY

stop_guest
mkdir -p "$target"
for file in "$prior"/*; do
    name=${file##*/}
    if [[ $name == system.img || $name == package.xml ]]; then continue; fi
    ln -sfn "../../dev_phone-nyroos-launcher/x86_64/$name" "$target/$name"
done
if [[ -f $target/system.img ]]; then ln "$target/system.img" "$stage/previous.img"; fi
swapped=true
mv -T "$stage/system.img" "$target/system.img"
sed "s|^image\.sysdir\.1=.*|image.sysdir.1=$target_relative/|" "$config" > "$stage/new-config.ini"
mv -T "$stage/new-config.ini" "$config"
start_guest

deadline=$((SECONDS+150))
boot=
while (( SECONDS < deadline )); do
    boot=$(timeout 3 "${guest[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    [[ $boot != 1 ]] || break
    sleep .7
done
[[ $boot == 1 ]]
assert_guest
printf 'sys.boot_completed=1\n'
"${guest[@]}" shell getenforce | tr -d '\r' | grep -Fx Enforcing
"${guest[@]}" shell 'awk '\''$2 == "/" { print; found = ($4 ~ /^ro,/) } END { exit !found }'\'' /proc/mounts'
expected_path=/system/priv-app/NyRoCore/NyRoCore.apk
"${guest[@]}" shell pm path com.nyroos.core | tr -d '\r' | grep -Fx "package:$expected_path"
expected=$(sha256sum "$payload/NyRoCore.apk" | awk '{print $1}')
found=$("${guest[@]}" shell sha256sum "$expected_path" | awk '{print $1}')
[[ $expected == "$found" ]]
printf '%s  %s\n' "$found" "$expected_path"
dumps=$("${guest[@]}" shell dumpsys package com.nyroos.core)
grep -E 'privateFlags=\[.*PRIVILEGED' <<< "$dumps"
grep -F 'android.permission.RECOVERY: granted=true' <<< "$dumps"
grep -F 'android.permission.REBOOT: granted=true' <<< "$dumps"
"${guest[@]}" shell cmd role get-role-holders --user 0 android.app.role.HOME | tr -d '\r' | grep -Fx os.nyro.launcher
committed=true

"${guest[@]}" shell mkdir -p /sdcard/NyRoOS
if ! "${guest[@]}" shell test -e /sdcard/NyRoOS/ota_config.json; then
    "${guest[@]}" push "$project/core/examples/ota_config.json" /sdcard/NyRoOS/ota_config.json
fi
"${guest[@]}" shell input keyevent KEYCODE_WAKEUP
"${guest[@]}" shell wm dismiss-keyguard
"${guest[@]}" shell am start -W -n com.nyroos.core/.CoreActivity
printf 'NyRoCore instalado e dev_phone ligado.\n'
