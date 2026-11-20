#!/usr/bin/env bash
set -Eeuo pipefail

project="$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$project"
source ./env.sh

build_only=false
case "${1-}" in
    '') ;;
    --build-only) build_only=true ;;
    *) printf 'Uso: %s [--build-only]\n' "$0" >&2; exit 2 ;;
esac
(( $# <= 1 ))
mkdir -p -- "$project/work"
exec 8>"$ANDROID_AVD_HOME/dev_phone.launcher.lock"
flock -n 8
if ! "$build_only"; then
    exec 7>"$ANDROID_AVD_HOME/dev_phone.branding.lock"
    flock -n 7
    exec > >(tee "$project/work/nyro_launcher_install.log") 2>&1
fi
trap 'printf "Falha na linha %s do script.\n" "$LINENO" >&2' ERR
trap 'exit 130' INT
trap 'exit 143' TERM

stage=$(mktemp -d "$TMPDIR/dev-phone-launcher.XXXXXX")
guest=(adb -s emulator-5554)
package=os.nyro.launcher
component="$package/.HomeActivity"
config="$ANDROID_AVD_HOME/dev_phone.avd/config.ini"
prior_relative=system-images/android-37.0/dev_phone-nyroos/x86_64
target_relative=system-images/android-37.0/dev_phone-nyroos-launcher/x86_64
prior="$ANDROID_HOME/$prior_relative"
target="$ANDROID_HOME/$target_relative"
swapped=false
committed=false

ports() {
    ss -H -ltn '( sport = :5554 or sport = :5555 )'
}

assert_guest() {
    local name
    name=$(timeout 4 "${guest[@]}" emu avd name | tr -d '\r' | sed -n '1p')
    [[ $name == dev_phone ]] || { printf 'As portas 5554/5555 não pertencem a dev_phone.\n' >&2; return 1; }
}

stop_guest() {
    if [[ -n $(ports) ]]; then
        assert_guest || return 1
        "${guest[@]}" emu kill
        timeout 45 bash -c 'while [[ -n $(ss -H -ltn "( sport = :5554 or sport = :5555 )") ]]; do sleep .3; done'
    fi
}

cleanup() {
    local result=$?
    if (( result != 0 )); then
        if "$swapped" && ! "$committed"; then
            stop_guest || true
            cp -p -- "$stage/previous-config.ini" "$config"
            if [[ -f "$stage/previous-launcher.img" ]]; then
                mv -T -- "$stage/previous-launcher.img" "$target/system.img"
            elif [[ -f "$target/system.img" ]]; then
                mv -T -- "$target/system.img" "$stage/failed.img"
            fi
            printf 'A configuração anterior do AVD foi restaurada.\n' >&2
        fi
        for file in system.debugfs.log system.unshare.log system_ext.debugfs.log system_ext.unshare.log; do
            if [[ -f "$stage/$file" ]]; then cp -- "$stage/$file" "$project/work/nyro_launcher_$file"; fi
        done
    fi
    rm -r -- "$stage"
}
trap cleanup EXIT

build_tools="$ANDROID_HOME/build-tools/37.0.0"
platform="$ANDROID_HOME/platforms/android-37.0/android.jar"
payload="$project/custom/priv-app/NyRoLauncher"
keystore="$ANDROID_USER_HOME/nyrolauncher.p12"
export JDK_JAVA_OPTIONS="-XX:+PerfDisableSharedMem -Djava.io.tmpdir=$TMPDIR"
mkdir -p -- "$stage/generated" "$stage/classes" "$stage/dex" "$payload"

printf 'Compilando NyRoLauncher com javac, AAPT2 e D8.\n'
"$build_tools/aapt2" compile --dir "$project/launcher/res" -o "$stage/resources.zip"
"$build_tools/aapt2" link -I "$platform" --manifest "$project/launcher/AndroidManifest.xml" \
    --min-sdk-version 34 --target-sdk-version 37 --java "$stage/generated" \
    -A "$project/launcher/assets" -o "$stage/unsigned.apk" "$stage/resources.zip"
mapfile -d '' -t sources < <(find "$project/launcher/src" "$stage/generated" -name '*.java' -print0)
javac --release 17 -cp "$platform" -d "$stage/classes" "${sources[@]}"
jar --create --file "$stage/classes.jar" -C "$stage/classes" .
"$build_tools/d8" --release --min-api 34 --lib "$platform" --output "$stage/dex" "$stage/classes.jar"
(
    cd -- "$stage/dex"
    zip -q "$stage/unsigned.apk" classes.dex
)
"$build_tools/zipalign" -f 4 "$stage/unsigned.apk" "$stage/aligned.apk"
if [[ ! -s $keystore ]]; then
    (
        umask 077
        keytool -genkeypair -keystore "$keystore" -storetype PKCS12 -storepass android \
            -alias nyrolauncher -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=NyRoLauncher Development'
    )
fi
"$build_tools/apksigner" sign --ks "$keystore" --ks-key-alias nyrolauncher --ks-pass pass:android \
    --v4-signing-enabled false --out "$stage/NyRoLauncher.apk" "$stage/aligned.apk"
"$build_tools/apksigner" verify --verbose --print-certs "$stage/NyRoLauncher.apk"
install -m 0644 -- "$stage/NyRoLauncher.apk" "$payload/NyRoLauncher.apk"
sha256sum "$payload/NyRoLauncher.apk"
if "$build_only"; then exit 0; fi

if ! grep -Fxq -e "image.sysdir.1=$prior_relative/" -e "image.sysdir.1=$target_relative/" "$config"; then
    printf 'Instalação requer a imagem NyRoOS do dev_phone.\n' >&2
    exit 1
fi
if [[ -n $(ports) ]]; then assert_guest; fi
cp -p -- "$config" "$stage/previous-config.ini"
cp --reflink=auto --sparse=always -- "$prior/system.img" "$stage/system.img"
printf '\nInstalando o APK privilegiado e as configurações HOME numa cópia da imagem NyRoOS.\n'
python3 -E -s -B scripts/nyroos_launcher_image.py "$stage/system.img" \
    "$payload/NyRoLauncher.apk" "$project/launcher/system" "$stage" "$build_tools" \
    "$project/.tools/nyroos/aosp-test-key"

stop_guest
mkdir -p -- "$target"
for file in "$prior"/*; do
    name=${file##*/}
    if [[ $name == system.img || $name == package.xml ]]; then continue; fi
    ln -sfn -- "../../dev_phone-nyroos/x86_64/$name" "$target/$name"
done
if [[ -f "$target/system.img" ]]; then
    ln -- "$target/system.img" "$stage/previous-launcher.img"
fi
swapped=true
mv -T -- "$stage/system.img" "$target/system.img"
sed "s|^image\.sysdir\.1=.*|image.sysdir.1=$target_relative/|" "$config" > "$stage/launcher-config.ini"
mv -T -- "$stage/launcher-config.ini" "$config"

printf '\nBoot normal do dev_phone.\n'
setsid --fork /bin/bash --noprofile --norc ./scripts/start_emulator.sh -no-window \
    </dev/null > "$project/work/nyro_launcher_emulator_start.log" 2>&1 7>&- 8>&-
deadline=$((SECONDS + 150))
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

path=$("${guest[@]}" shell pm path "$package" | tr -d '\r')
expected_path=/system/priv-app/NyRoLauncher/NyRoLauncher.apk
[[ $path == "package:$expected_path" ]]
printf '%s\n' "$path"
flags=$("${guest[@]}" shell dumpsys package "$package")
grep -E 'privateFlags=\[.*PRIVILEGED' <<< "$flags"
grep -F 'android.permission.DUMP: granted=true' <<< "$flags"
grep -F 'android.permission.PACKAGE_USAGE_STATS: granted=true' <<< "$flags"
for remote in "$expected_path" /system/etc/permissions/privapp-permissions-nyrolauncher.xml \
    /system/etc/sysconfig/nyrolauncher-home.xml; do
    local_file="$project/launcher/system/${remote##*/}"
    [[ $remote != "$expected_path" ]] || local_file="$payload/NyRoLauncher.apk"
    expected=$(sha256sum "$local_file" | awk '{print $1}')
    installed=$("${guest[@]}" shell sha256sum "$remote" | awk '{print $1}')
    [[ $expected == "$installed" ]]
    printf '%s  %s\n' "$installed" "$remote"
done

quickstep_path=/system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk
expected=$(sha256sum "$stage/quickstep.apk" | awk '{print $1}')
installed=$("${guest[@]}" shell sha256sum "$quickstep_path" | awk '{print $1}')
[[ $expected == "$installed" ]]
printf '%s  %s\n' "$installed" "$quickstep_path"

printf '\nValidando HOME após o boot, antes de abrir qualquer atividade via ADB.\n'
homes=$("${guest[@]}" shell cmd package query-activities --brief --user 0 \
    --query-flags 65536 -a android.intent.action.MAIN -c android.intent.category.HOME)
printf '%s\n' "$homes"
grep -Fq "$component" <<< "$homes"
if grep -Fq 'com.android.launcher3/.uioverrides.QuickstepLauncher' <<< "$homes"; then
    printf 'Launcher3 ainda consta como HOME.\n' >&2
    exit 1
fi
"${guest[@]}" shell input keyevent KEYCODE_WAKEUP
"${guest[@]}" shell wm dismiss-keyguard
focused=
for attempt in {1..12}; do
    focused=$("${guest[@]}" shell dumpsys activity activities | grep -F 'topResumedActivity=' || true)
    [[ $focused != *"$component"* ]] || break
    sleep .7
done
[[ $focused == *"$component"* ]]
printf '%s\n' "$focused"
role=$("${guest[@]}" shell cmd role get-role-holders --user 0 android.app.role.HOME | tr -d '\r')
if [[ $role != "$package" ]]; then
    "${guest[@]}" shell cmd role add-role-holder --user 0 android.app.role.HOME "$package"
fi
"${guest[@]}" shell cmd role get-role-holders --user 0 android.app.role.HOME | tr -d '\r' | grep -Fx "$package"
# Open a real task before checking that SystemUI can still start the overview.
"${guest[@]}" shell am start -W -a android.settings.SETTINGS
"${guest[@]}" shell input keyevent KEYCODE_APP_SWITCH
sleep 1.5
"${guest[@]}" shell dumpsys activity activities | grep -E 'topResumedActivity=.*com.android.launcher3/.*RecentsActivity'
"${guest[@]}" shell input keyevent KEYCODE_HOME
committed=true

"${guest[@]}" shell mkdir -p /sdcard/NyRoOS
if ! "${guest[@]}" shell test -e /sdcard/NyRoOS/projects.json; then
    "${guest[@]}" push "$project/launcher/assets/projects.json" /sdcard/NyRoOS/projects.json
fi

capture() {
    "${guest[@]}" exec-out screencap -p > "$project/work/nyro_launcher_$1.png"
    python3 -I -B -c 'from PIL import Image; import sys; Image.open(sys.argv[1]).verify()' \
        "$project/work/nyro_launcher_$1.png"
}

printf '\nTestando gestos e capturando as três páginas.\n'
resolution=$("${guest[@]}" shell wm size | awk 'END {print $NF}' | tr -d '\r')
[[ $resolution =~ ^([0-9]+)x([0-9]+)$ ]]
width=${BASH_REMATCH[1]}
height=${BASH_REMATCH[2]}
sleep 1
capture home
"${guest[@]}" shell input swipe "$((width / 6))" "$((height / 2))" "$((width * 4 / 5))" "$((height / 2))" 330
sleep 2
capture hub
"${guest[@]}" shell input swipe "$((width * 4 / 5))" "$((height / 2))" "$((width / 6))" "$((height / 2))" 330
sleep .35
"${guest[@]}" shell input swipe "$((width / 2))" "$((height * 4 / 5))" "$((width / 2))" "$((height / 3))" 330
sleep 1
capture drawer
"${guest[@]}" shell input keyevent KEYCODE_HOME

activity_log="$project/work/nyro_launcher_logcat.txt"
"${guest[@]}" logcat -d -v threadtime -s 'NyRoLauncher:*' > "$activity_log"
cat "$activity_log"
grep -Fq 'Atividade HOME criada' "$activity_log"
grep -Fq 'Página HUB' "$activity_log"
grep -Fq 'Página DRAWER' "$activity_log"
if "${guest[@]}" logcat -b crash -d | grep -E 'Process: (os\.nyro\.launcher|com\.android\.launcher3)([,:[:space:]]|$)'; then
    printf 'O Logcat contém uma falha do NyRoLauncher ou do Quickstep.\n' >&2
    exit 1
fi
printf 'NyRoLauncher ativo. Capturas e logs disponíveis em work/.\n'
