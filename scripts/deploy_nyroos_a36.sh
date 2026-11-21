#!/usr/bin/env bash
set -Eeuo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source ./env.sh
source ./scripts/a36_adb.sh
mode=${1-}
[[ $# -le 1 && ( -z $mode || $mode == --built ) ]] || { printf 'Uso: %s [--built]\n' "$0" >&2; exit 2; }

mkdir -p work/a36-live
select_a36_usb
mkdir -m 700 -p "$state"
exec 8>"$state/deploy.lock"
flock -n 8
exec > >(tee -a work/a36_nyroos_deploy.log) 2>&1
trap 'printf "Implantação interrompida na linha %s. O estado salvo pode ser restaurado.\n" "$LINENO" >&2' ERR

if [[ $mode != --built ]]; then
    scripts/build_and_install_launcher.sh --build-only
    scripts/build_and_install_nyrocore.sh --build-only
fi
signer="$ANDROID_HOME/build-tools/37.0.0/apksigner"
launcher=custom/priv-app/NyRoLauncher/NyRoLauncher.apk
core=custom/priv-app/NyRoCore/NyRoCore.apk
shizuku=$(scripts/setup_shizuku.sh)
for apk in "$launcher" "$core" "$shizuku"; do "$signer" verify "$apk"; done

old_home=$(home_component)
old_mode=$("${phone[@]}" shell cmd uimode night | tr -d '\r' | awk -F': ' '/^Night mode:/ {print $2}')
[[ $old_mode =~ ^(yes|no|auto|custom)$ ]]
scales=(window_animation_scale transition_animation_scale animator_duration_scale)
optional_samsung=(
    com.samsung.android.bixby.agent
    com.samsung.android.app.spage
    com.samsung.android.arzone
    com.samsung.android.voc
    com.samsung.android.game.gamehome
)
factory_extras=(com.spotify.music com.netflix.mediaclient com.facebook.katana com.microsoft.skydrive)
critical=(
    com.sec.android.app.camera com.samsung.android.dialer com.android.settings
    com.samsung.android.biometrics.app.setting com.google.android.webview
    com.android.vending com.google.android.gms com.google.android.googlequicksearchbox
    com.android.chrome com.sec.android.app.launcher
)
before=$(enabled_a36)
factory=$("${phone[@]}" shell pm list packages -s --user 0 | tr -d '\r')
warranty_before=$("${phone[@]}" shell getprop ro.boot.warranty_bit | tr -d '\r')
lock_before=$("${phone[@]}" shell getprop ro.boot.flash.locked | tr -d '\r')
printf 'Galaxy SM-A366E. Usuário 0. Bootloader bloqueado=%s, Knox Warranty Bit=%s.\n' "$lock_before" "$warranty_before"

if [[ ! -s "$state/ui.tsv" ]]; then
    {
        printf 'home\t%s\nnight\t%s\n' "$old_home" "$old_mode"
        for key in "${scales[@]}"; do
            value=$("${phone[@]}" shell settings get global "$key" | tr -d '\r')
            [[ $value =~ ^(null|[0-9]+([.][0-9]+)?)$ ]]
            printf '%s\t%s\n' "$key" "$value"
        done
    } > "$state/ui.tmp"
    mv -f "$state/ui.tmp" "$state/ui.tsv"
fi
touch "$state/packages.tsv"

printf '\nInstalando os APKs assinados como apps do usuário.\n'
for apk in "$launcher" "$core" "$shizuku"; do "${phone[@]}" install -r "$apk"; done

if ! "${phone[@]}" shell 'ps -A -o USER,NAME' | tr -d '\r' | awk '$1 == "shell" && $2 == "shizuku_server" {f=1} END {exit !f}'; then
    "${phone[@]}" shell 'path=$(pm path moe.shizuku.privileged.api | sed -n "1s/^package://p"); [ -n "$path" ] && "$(dirname "$path")/lib/arm64/libshizuku.so"'
fi
"${phone[@]}" shell 'ps -A -o USER,NAME' | tr -d '\r' | awk '$1 == "shell" && $2 == "shizuku_server" {f=1; print} END {exit !f}'

home=os.nyro.launcher/.HomeActivity
if [[ $old_home != "$home" ]]; then
    "${phone[@]}" shell cmd package set-home-activity --user 0 "$home"
fi
[[ $(home_component) == "$home" ]]
printf 'Atividade HOME %s\n' "$home"

printf '\nDebloat reversível do usuário 0.\n'
for package in "${optional_samsung[@]}" "${factory_extras[@]}"; do
    if ! grep -Fxq "package:$package" <<< "$before"; then
        printf 'Já desativado ou ausente %s\n' "$package"
        continue
    fi
    case " ${factory_extras[*]} " in
        *" $package "*)
            if ! grep -Fxq "package:$package" <<< "$factory"; then
                printf 'App de usuário preservado %s\n' "$package"
                continue
            fi
            ;;
    esac
    previous=$("${phone[@]}" shell dumpsys package "$package" | tr -d '\r' |
        sed -nE 's/^.*User 0:.* installed=true .* enabled=([01]) .*$/\1/p' | awk 'NR == 1')
    [[ $previous =~ ^[01]$ ]]
    if ! awk -F '\t' -v pkg="$package" '$1 == pkg {found=1} END {exit !found}' "$state/packages.tsv"; then
        printf '%s\t%s\n' "$package" "$previous" >> "$state/packages.tsv"
    fi
    "${phone[@]}" shell pm disable-user --user 0 "$package" | tr -d '\r' | grep -F "new state: disabled-user"
done

if [[ $old_mode != yes ]]; then "${phone[@]}" shell cmd uimode night yes; fi
"${phone[@]}" shell cmd uimode night | tr -d '\r' | grep -Fx 'Night mode: yes'
for key in "${scales[@]}"; do
    if [[ $("${phone[@]}" shell settings get global "$key" | tr -d '\r') != 0.5 ]]; then
        "${phone[@]}" shell settings put global "$key" 0.5
    fi
    [[ $("${phone[@]}" shell settings get global "$key" | tr -d '\r') == 0.5 ]]
    printf '%s=0.5\n' "$key"
done

after=$(enabled_a36)
for package in "${critical[@]}"; do
    if grep -Fxq "package:$package" <<< "$before"; then
        grep -Fxq "package:$package" <<< "$after"
        printf 'App preservado %s\n' "$package"
    fi
done
[[ $("${phone[@]}" shell getprop ro.boot.flash.locked | tr -d '\r') == "$lock_before" ]]
[[ $("${phone[@]}" shell getprop ro.boot.warranty_bit | tr -d '\r') == "$warranty_before" ]]

# Only the active home wallpaper is changed, using Android's WallpaperManager.
wallpaper_start=$("${phone[@]}" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
"${phone[@]}" shell input keyevent KEYCODE_WAKEUP
"${phone[@]}" shell cmd statusbar collapse
"${phone[@]}" shell am start -W -n os.nyro.launcher/.WallpaperActivity
for attempt in {1..20}; do
    wallpaper_log=$("${phone[@]}" shell "logcat -d -T '$wallpaper_start' -s NyRoWallpaper:I '*:S'" | tr -d '\r')
    if grep -Fq 'Aplicado ao HOME.' <<< "$wallpaper_log"; then break; fi
    sleep .35
done
if ! grep -F 'Aplicado ao HOME.' <<< "$wallpaper_log"; then
    printf '%s\n' 'Aplicação do papel de parede ficou pendente.'
    printf '%s\n' "$wallpaper_log"
fi

"${phone[@]}" shell input keyevent KEYCODE_HOME
for attempt in {1..15}; do
    focus=$("${phone[@]}" shell dumpsys window | tr -d '\r' | awk '/mCurrentFocus=/ && /os[.]nyro[.]launcher/ { print }')
    [[ -z $focus ]] || break
    sleep .35
done
if [[ -n $focus ]]; then
    stage=$(mktemp work/a36-live/home.XXXXXX)
    "${phone[@]}" exec-out screencap -p > "$stage"
    python3 -B - "$stage" <<'PY'
import sys
from PIL import Image
with Image.open(sys.argv[1]) as image:
    image.verify()
PY
    mv -f -- "$stage" work/a36_nyroos_live.png
    printf 'Captura HOME validada em %s/work/a36_nyroos_live.png\n' "$PWD"
else
    printf '%s\n' 'Captura pendente. Desbloqueie o Galaxy e volte para a tela inicial.'
fi

if "${phone[@]}" shell pm path com.samsung.android.themedesigner | grep -q '^package:'; then
    printf 'Theme Park presente no Galaxy.\n'
else
    printf '%s\n' 'Theme Park disponível pelo Galaxy Store: https://galaxystore.samsung.com/detail/com.samsung.android.themedesigner'
fi
printf '%s\n' 'Para validar o Shizuku no NyRoCore, abra Orquestrador e toque em Conectar ao Shizuku.'
