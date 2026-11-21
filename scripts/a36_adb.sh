#!/usr/bin/env bash
# Shared by the A36 deployment and restoration entry points.
select_a36_usb() {
    local serial device
    local -a candidates=()
    while read -r serial; do
        device=$(timeout 5 adb -s "$serial" shell -n getprop ro.product.model 2>/dev/null | tr -d '\r') || continue
        [[ $device != SM-A366E && $device != SM-A366E/DS ]] || candidates+=("$serial")
    done < <(adb devices -l | awk '$2 == "device" && $1 !~ /^emulator-/ && /[[:space:]]usb:/ { print $1 }')
    if (( ${#candidates[@]} != 1 )); then
        printf '%s\n' 'Conecte um único Galaxy A36 SM-A366E por USB e autorize a depuração na tela do aparelho.' >&2
        return 1
    fi
    serial=${candidates[0]}
    [[ $serial =~ ^[[:alnum:]_-]+$ ]]
    phone=(adb -s "$serial")
    [[ $("${phone[@]}" shell am get-current-user | tr -d '\r[:space:]') == 0 ]] || {
        printf '%s\n' 'Selecione o usuário principal do Galaxy para continuar.' >&2
        return 1
    }
    state="$PWD/work/a36-live/$serial"
}

home_component() {
    "${phone[@]}" shell -n cmd package resolve-activity --brief --user 0 \
        -a android.intent.action.MAIN -c android.intent.category.HOME |
        tr -d '\r' | awk '/^[a-zA-Z0-9_.]+\/[a-zA-Z0-9_.$]+$/ { result=$0 } END { if (result) print result; else exit 1 }'
}

enabled_a36() {
    "${phone[@]}" shell pm list packages -e --user 0 | tr -d '\r' | sort
}
