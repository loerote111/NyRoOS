#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source ./env.sh
source ./scripts/a36_adb.sh

mode=${1-}
[[ $# -le 1 && ( -z $mode || $mode == --ui ) ]] || { printf 'Uso: %s [--ui]\n' "$0" >&2; exit 2; }
select_a36_usb
if [[ ! -r "$state/packages.tsv" ]]; then
    printf '%s\n' 'Nenhuma implantação registrada para este Galaxy.'
    exit 0
fi
exec 8>"$state/deploy.lock"
flock -n 8
while IFS=$'\t' read -r package old; do
    [[ $package =~ ^[A-Za-z0-9_]+([.][A-Za-z0-9_]+)+$ ]]
    case "$old" in
        0) action=default-state ;;
        1) action=enable ;;
        *) printf 'Estado inválido no registro para %s\n' "$package" >&2; exit 1 ;;
    esac
    "${phone[@]}" shell -n pm "$action" --user 0 "$package"
done < "$state/packages.tsv"

if [[ $mode == --ui ]]; then
    while IFS=$'\t' read -r key value; do
        case "$key" in
            home)
                [[ $value =~ ^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$ ]]
                "${phone[@]}" shell -n cmd package set-home-activity --user 0 "$value"
                [[ $(home_component) == "$value" ]]
                ;;
            night)
                [[ $value =~ ^(yes|no|auto|custom)$ ]]
                "${phone[@]}" shell -n cmd uimode night "$value"
                ;;
            window_animation_scale|transition_animation_scale|animator_duration_scale)
                if [[ $value == null ]]; then
                    "${phone[@]}" shell -n settings delete global "$key"
                else
                    [[ $value =~ ^[0-9]+([.][0-9]+)?$ ]]
                    "${phone[@]}" shell -n settings put global "$key" "$value"
                fi
                ;;
        esac
    done < "$state/ui.tsv"
fi
