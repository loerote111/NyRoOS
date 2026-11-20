#!/usr/bin/env bash
set -euo pipefail

project_dir="$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$project_dir"
source ./env.sh
exec > >(tee -- "$project_dir/work/gapps_test.log") 2>&1

avd_dir="$ANDROID_AVD_HOME/dev_phone.avd"
config="$avd_dir/config.ini"
default_sysdir="$ANDROID_HOME/system-images/android-37.0/default/x86_64"
custom_relative="system-images/android-37.0/dev_phone-gapps/x86_64"
custom_sysdir="$ANDROID_HOME/$custom_relative"
payload="$project_dir/custom/gapps-emu"
qemu_img="$ANDROID_HOME/emulator/qemu-img"
guest=(adb -s emulator-5554)
install_pid=
testing=false
configured=false
committed=false

files=(
    product/priv-app/GmsCore/GmsCore.apk
    product/priv-app/Phonesky/Phonesky.apk
    product/priv-app/Velvet/Velvet.apk
    system_ext/priv-app/GoogleServicesFramework/GoogleServicesFramework.apk
    product/app/Chrome/Chrome.apk
    product/app/TrichromeLibrary/TrichromeLibrary.apk
    product/etc/permissions/privapp-permissions-dev-phone-gapps.xml
    product/etc/sysconfig/dev-phone-play-store.xml
    system_ext/etc/permissions/privapp-permissions-dev-phone-gapps.xml
)
packages=(
    com.android.vending
    com.google.android.googlequicksearchbox
    com.android.chrome
    com.google.android.gms
    com.google.android.gsf
)
for file in "${files[@]}"; do
    [[ -s "$payload/$file" ]] || { printf 'Arquivo ausente: %s\n' "$payload/$file" >&2; exit 1; }
done
for flag in Core=1 GoogleSearch=1 GoogleChrome=1; do
    grep -Fxq -- "$flag" custom/nikgapps.config
done

export XDG_CACHE_HOME="$ANDROID_EMULATOR_HOME/cache"
export XDG_CONFIG_HOME="$ANDROID_EMULATOR_HOME/config"
export ANDROID_TMP="$TMPDIR"
export ANDROID_EMULATOR_DISCOVERY_DIR="$ANDROID_EMULATOR_HOME/running"
export ANDROID_EMU_CRASH_REPORTING_DATABASE="$TMPDIR/emulator-crash.db"

exec 8>"$ANDROID_AVD_HOME/dev_phone.gapps.lock"
flock -n 8
stage=$(mktemp -d -- "$TMPDIR/dev-phone-gapps.XXXXXX")
console_name() {
    timeout 5 "${guest[@]}" emu avd name 2>/dev/null | tr -d '\r' | sed -n '1p'
}
console_ports() {
    ss -H -ltn '( sport = :5554 or sport = :5555 )'
}
stop_guest() {
    if [[ -n $(console_ports) ]]; then
        if [[ $(console_name) != dev_phone ]]; then
            printf 'Outro processo ocupa as portas do dev_phone.\n' >&2
            return 1
        fi
        "${guest[@]}" emu kill
        timeout 50 bash -c 'while [[ -n $(ss -H -ltn "( sport = :5554 or sport = :5555 )") ]]; do sleep .5; done'
    fi
}
wait_boot() {
    local deadline=$((SECONDS + 150))
    local reconnect_after=$((SECONDS + 18))
    local reconnected=false
    while (( SECONDS < deadline )); do
        if timeout 2 "${guest[@]}" wait-for-device &&
            [[ $(timeout 4 "${guest[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true) == 1 ]]; then
            [[ $(console_name) == dev_phone ]]
            return
        fi
        if ! "$reconnected" && (( SECONDS >= reconnect_after )) &&
            [[ $("${guest[@]}" get-state 2>/dev/null || true) == offline ]]; then
            "${guest[@]}" reconnect || true
            reconnected=true
        fi
        sleep 1
    done
    printf 'O Android não terminou o boot.\n' >&2
    return 1
}
verify_packages() {
    local pkg system_packages
    system_packages=$("${guest[@]}" shell pm list packages -s | tr -d '\r')
    for pkg in "${packages[@]}"; do
        grep -Fx "package:$pkg" <<< "$system_packages"
        "${guest[@]}" shell pm path "$pkg"
    done
}

cleanup() {
    local status=$?
    if (( status != 0 )); then
        if [[ -n $install_pid ]] && kill -0 "$install_pid" 2>/dev/null; then
            stop_guest || kill -TERM "$install_pid" 2>/dev/null || true
            wait "$install_pid" 2>/dev/null || true
        fi
        if "$testing" && ! "$committed"; then stop_guest || true; fi
        if "$configured" && ! "$committed"; then
            cp -p -- "$stage/config.ini" "$config"
            if [[ -f "$stage/previous.img" ]]; then
                mv -T -- "$stage/previous.img" "$custom_sysdir/system.img"
            elif [[ -f "$custom_sysdir/system.img" ]]; then
                mv -T -- "$custom_sysdir/system.img" "$stage/failed.img"
            fi
            if [[ -e "$stage/previous-boot-params" || -L "$stage/previous-boot-params" ]]; then
                mv -T -- "$stage/previous-boot-params" "$custom_sysdir/VerifiedBootParams.textproto"
            elif [[ -f "$custom_sysdir/VerifiedBootParams.textproto" ]]; then
                mv -T -- "$custom_sysdir/VerifiedBootParams.textproto" "$stage/failed-boot-params"
            fi
        fi
        if ! "$committed"; then
            for image in system.img.qcow2 vendor.img.qcow2; do
                if [[ -f "$avd_dir/$image" ]]; then mv -- "$avd_dir/$image" "$stage/failed-$image"; fi
                if [[ -f "$stage/old-$image" ]]; then mv -- "$stage/old-$image" "$avd_dir/$image"; fi
            done
        fi
        if "$committed"; then
            printf 'A imagem foi instalada. O teste de abertura retornou código %s.\n' "$status" >&2
        else
            printf 'Instalação interrompida (código %s). Log de execução em work/gapps_emulator_install.log.\n' "$status" >&2
        fi
    fi
    python3 -I -B -c 'import pathlib, shutil, sys; directory=pathlib.Path(sys.argv[1]); assert directory.name.startswith("dev-phone-gapps."); shutil.rmtree(directory)' "$stage"
}
trap cleanup EXIT

printf 'AVD dev_phone, Android 17, serial exclusivo emulator-5554.\n'
printf 'Verificando o estado original e preparando espaço na cópia do sistema.\n'
cp -p -- "$config" "$stage/config.ini"
stop_guest
for image in system.img.qcow2 vendor.img.qcow2; do
    if [[ -f "$avd_dir/$image" ]]; then
        mv -- "$avd_dir/$image" "$stage/old-$image"
    fi
done
cp --reflink=auto --sparse=always -- "$default_sysdir/system.img" "$stage/system.img"
python3 -I -B scripts/gapps_image.py expand "$stage/system.img"

printf '\nIniciando a cópia com -writable-system.\n'
env "HOME=$ANDROID_EMULATOR_HOME/console-home" emulator @dev_phone -port 5554 \
    -sysdir "$default_sysdir" -system "$stage/system.img" -writable-system \
    -accel on -gpu host -no-window -no-audio -no-snapshot -no-metrics \
    -crash-report-mode disabled </dev/null > work/gapps_emulator_install.log 2>&1 8>&- &
install_pid=$!
wait_boot
"${guest[@]}" shell pm list packages -s | tr -d '\r' | LC_ALL=C sort > "$stage/initial-packages.txt"
"${guest[@]}" root
timeout 40 "${guest[@]}" wait-for-device
[[ $("${guest[@]}" shell id -u | tr -d '\r') == 0 ]]
remount=$("${guest[@]}" remount 2>&1)
printf '%s\n' "$remount"
if [[ $remount == *"reboot your device"* ]]; then
    "${guest[@]}" reboot
    wait_boot
    "${guest[@]}" root
    timeout 40 "${guest[@]}" wait-for-device
    "${guest[@]}" remount
fi
"${guest[@]}" shell 'getEnforcing=$(getenforce); echo "SELinux: $getEnforcing"; df -h /mnt/scratch /product /system_ext'
required_bytes=0
for file in "${files[@]}"; do
    required_bytes=$((required_bytes + $(stat -c %s -- "$payload/$file")))
done
free_kib=$("${guest[@]}" shell df -k /mnt/scratch | awk 'NR==2 {print $4}')
if [[ ! $free_kib =~ ^[0-9]+$ ]] || (( free_kib * 1024 < required_bytes + 67108864 )); then
    printf 'Espaço insuficiente no overlay do sistema (%s KiB disponíveis).\n' "$free_kib" >&2
    exit 1
fi

for file in "${files[@]}"; do
    directory="/${file%/*}"
    "${guest[@]}" shell mkdir -p "$directory"
    "${guest[@]}" push "$payload/$file" "/$file"
    "${guest[@]}" shell chown 0:0 "/$file"
    "${guest[@]}" shell chmod 0644 "/$file"
    "${guest[@]}" shell restorecon -F "$directory" "/$file"
done
"${guest[@]}" shell sync
printf '\nReiniciando o Android para registrar os pacotes de sistema.\n'
"${guest[@]}" reboot
wait_boot
verify_packages
"${guest[@]}" shell sync
stop_guest
wait "$install_pid"
install_pid=

printf '\nSalvando a imagem do AVD para sobreviver a uma partida normal.\n'
"$qemu_img" convert -f qcow2 -O raw "$avd_dir/system.img.qcow2" "$stage/mounted.img"
python3 -I -B scripts/gapps_image.py bake "$stage/system.img" "$stage/mounted.img" "$payload" "$stage" "${files[@]}"
sgdisk --verify "$stage/system.img"
for image in system.img.qcow2 vendor.img.qcow2; do
    if [[ -f "$avd_dir/$image" ]]; then mv -- "$avd_dir/$image" "$stage/install-$image"; fi
done
mkdir -p -- "$custom_sysdir"
for file in "$default_sysdir"/*; do
    name="${file##*/}"
    if [[ $name == system.img || $name == package.xml || $name == VerifiedBootParams.textproto ]]; then continue; fi
    ln -sfn -- "../../default/x86_64/$name" "$custom_sysdir/$name"
done
if [[ -f "$custom_sysdir/system.img" ]]; then
    mv -T -- "$custom_sysdir/system.img" "$stage/previous.img"
fi
if [[ -e "$custom_sysdir/VerifiedBootParams.textproto" || -L "$custom_sysdir/VerifiedBootParams.textproto" ]]; then
    mv -T -- "$custom_sysdir/VerifiedBootParams.textproto" "$stage/previous-boot-params"
fi
configured=true
mv -T -- "$stage/system.img" "$custom_sysdir/system.img"
# ADB remount changes vbmeta on this development image. Keep that AVD in the
# same unlocked boot state used by -writable-system, with the disks read-only.
{
    cat "$default_sysdir/VerifiedBootParams.textproto"
    printf 'param: "androidboot.verifiedbootstate=orange"\n'
} > "$custom_sysdir/VerifiedBootParams.textproto"
sed "s|^image\.sysdir\.1=.*|image.sysdir.1=$custom_relative/|" "$stage/config.ini" > "$stage/custom-config.ini"
grep -Fxq "image.sysdir.1=$custom_relative/" "$stage/custom-config.ini"
cat "$stage/custom-config.ini" > "$config"

printf '\nPartida normal, sem -writable-system.\n'
testing=true
setsid --fork /bin/bash --noprofile --norc ./scripts/start_emulator.sh -no-window \
    </dev/null > work/gapps_normal_start.log 2>&1 8>&-
wait_boot
cat work/gapps_normal_start.log
verify_packages
printf '\nVerificando SHA-256 dos arquivos dentro do Android.\n'
for file in "${files[@]}"; do
    host_sha=$(sha256sum "$payload/$file" | awk '{print $1}')
    guest_sha=$("${guest[@]}" shell sha256sum "/$file" | awk '{print $1}')
    [[ $host_sha == "$guest_sha" ]] || { printf 'SHA-256 divergente: /%s\n' "$file" >&2; exit 1; }
    printf '%s  /%s\n' "$guest_sha" "$file"
done
"${guest[@]}" shell 'printf "Verified Boot: %s\n" "$(getprop ro.boot.verifiedbootstate)"; getenforce; grep -E " /product | /system_ext " /proc/mounts'
"${guest[@]}" shell pm list packages -s | tr -d '\r' | LC_ALL=C sort > "$stage/final-packages.txt"
printf '\nPacotes de sistema adicionados em relação à base.\n'
added=$(LC_ALL=C comm -13 "$stage/initial-packages.txt" "$stage/final-packages.txt")
printf '%s\n' "$added"
while IFS= read -r package; do
    case "$package" in
        package:com.android.vending|package:com.google.android.googlequicksearchbox|package:com.android.chrome|package:com.google.android.gms|package:com.google.android.gsf|package:com.google.android.trichromelibrary) ;;
        *) printf 'Pacote fora da seleção: %s\n' "$package" >&2; exit 1 ;;
    esac
done <<< "$added"
committed=true
"${guest[@]}" shell cmd deviceidle whitelist | grep -F ',com.android.vending,'
"${guest[@]}" shell input keyevent KEYCODE_WAKEUP
"${guest[@]}" shell wm dismiss-keyguard
printf '\nPacotes visíveis e teste de abertura.\n'
for pkg in "${packages[@]:0:3}"; do
    component=$("${guest[@]}" shell cmd package resolve-activity --brief --user 0 \
        -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$pkg" |
        tr -d '\r' | tail -n 1)
    if [[ $pkg == com.google.android.googlequicksearchbox && $component == android/* ]]; then
        # The Google app also advertises a separate Voice Search launcher.
        component="$pkg/.SearchActivity"
    fi
    [[ $component == "$pkg/"* ]]
    log=$("${guest[@]}" shell am start -W -n "$component")
    printf '\n%s\n%s\n' "$pkg" "$log"
    grep -Eq '^Status: ok' <<< "$log"
    sleep 2
    foreground=$("${guest[@]}" shell dumpsys activity activities |
        awk '/topResumedActivity|mResumedActivity/ {print}')
    printf '%s\n' "$foreground"
    grep -Fq -- "$pkg/" <<< "$foreground"
done
"${guest[@]}" logcat -b crash -d -v epoch > "$stage/crashes.txt"
if grep -Eq 'Process: (com\.android\.vending|com\.google\.android\.googlequicksearchbox|com\.android\.chrome|com\.google\.android\.gms)(,|:|\.)' "$stage/crashes.txt"; then
    printf '\nRegistro de falhas do Android nesta partida.\n'
    cat "$stage/crashes.txt"
    exit 1
fi
"${guest[@]}" shell input keyevent KEYCODE_HOME
sleep 1
"${guest[@]}" shell input swipe 540 2140 540 740 330
sleep 1
"${guest[@]}" exec-out screencap -p > work/gapps_test.png
file work/gapps_test.png
printf 'Verificação concluída: sistema e três apps abertos após partida normal.\n'
