#!/usr/bin/env bash
set -euo pipefail

project_dir="$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$project_dir"
source ./env.sh

window_args=()
case "${1-}" in
    '') ;;
    -no-window|--no-window) window_args=(-no-window -no-audio) ;;
    -h|--help)
        printf 'Uso: %s [-no-window]\n' "$0"
        exit 0
        ;;
    *)
        printf 'Uso: %s [-no-window]\n' "$0" >&2
        exit 2
        ;;
esac
if (( $# > 1 )); then
    printf 'Uso: %s [-no-window]\n' "$0" >&2
    exit 2
fi

avd_name=dev_phone
console_port=5554
serial="emulator-$console_port"
adb=(adb -s "$serial")
emulator="$ANDROID_HOME/emulator/emulator"
emulator_log="$project_dir/work/dev_phone.emulator.log"
boot_timeout="${EMULATOR_BOOT_TIMEOUT:-300}"
if [[ ! $boot_timeout =~ ^[1-9][0-9]{0,5}$ ]]; then
    printf 'EMULATOR_BOOT_TIMEOUT deve ser um número inteiro positivo de segundos.\n' >&2
    exit 2
fi

export XDG_CACHE_HOME="$ANDROID_EMULATOR_HOME/cache"
export XDG_CONFIG_HOME="$ANDROID_EMULATOR_HOME/config"
export ANDROID_TMP="$TMPDIR"
export ANDROID_EMU_CRASH_REPORTING_DATABASE="$TMPDIR/emulator-crash.db"
export ANDROID_EMULATOR_DISCOVERY_DIR="$ANDROID_EMULATOR_HOME/running"
emulator_profile_home="$ANDROID_EMULATOR_HOME/console-home"
mkdir -p -- "$XDG_CACHE_HOME" "$XDG_CONFIG_HOME" "$ANDROID_EMULATOR_DISCOVERY_DIR"
mkdir -m 700 -p -- "$emulator_profile_home"

exec 9> "$ANDROID_AVD_HOME/$avd_name.start.lock"
if ! flock -n 9; then
    printf 'Já existe uma inicialização de %s em andamento.\n' "$avd_name" >&2
    exit 1
fi
if [[ -n $(ss -H -ltn "( sport = :$console_port or sport = :$((console_port + 1)) )") ]]; then
    printf 'As portas do emulador %s/%s já estão em uso.\n' "$console_port" "$((console_port + 1))" >&2
    exit 1
fi

avd_directory="$ANDROID_AVD_HOME/$avd_name.avd"
avd_descriptor="$ANDROID_AVD_HOME/$avd_name.ini"
if [[ ! -f $avd_descriptor || ! -f $avd_directory/config.ini ]]; then
    printf 'AVD %s ausente em %s\n' "$avd_name" "$ANDROID_AVD_HOME" >&2
    exit 1
fi
# The emulator launcher needs the current absolute path after the workspace moves.
if ! grep -Fxq -- "path=$avd_directory" "$avd_descriptor"; then
    new_descriptor=$(mktemp -- "$avd_descriptor.XXXXXX")
    sed '/^path=/d' "$avd_descriptor" > "$new_descriptor"
    printf 'path=%s\n' "$avd_directory" >> "$new_descriptor"
    mv -T -- "$new_descriptor" "$avd_descriptor"
fi

stop_on_failure() {
    local status=$?
    if (( status != 0 )) && [[ -n ${emulator_pid-} ]]; then
        kill "$emulator_pid" 2>/dev/null || true
        printf 'Consulte o log do emulador em %s\n' "$emulator_log" >&2
    fi
}
trap stop_on_failure EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"${adb[@]}" start-server 9>&-
emulator_args=("@$avd_name" -port "$console_port" -accel on -gpu host -no-snapshot -no-metrics -crash-report-mode disabled "${window_args[@]}")
printf 'Iniciando %s no ADB como %s\n' "$avd_name" "$serial"
printf 'Comando:'
printf ' %q' "$emulator" "${emulator_args[@]}"
printf '\n'
# The emulator stores its console authentication token in its process home.
nohup env "HOME=$emulator_profile_home" "$emulator" "${emulator_args[@]}" </dev/null > "$emulator_log" 2>&1 9>&- &
emulator_pid=$!
printf 'PID %s, log %s\n' "$emulator_pid" "$emulator_log"
printf 'Aguardando adb wait-for-device\n'

deadline=$((SECONDS + boot_timeout))
adb_ready=0
while (( SECONDS < deadline )); do
    if ! kill -0 "$emulator_pid" 2>/dev/null; then
        printf 'O processo do emulador terminou durante o boot.\n' >&2
        exit 1
    fi
    if (( ! adb_ready )) && timeout 2 "${adb[@]}" wait-for-device; then
        adb_ready=1
        printf 'ADB conectado. Aguardando sys.boot_completed.\n'
    fi
    if (( adb_ready )); then
        boot_completed=$(timeout 3 "${adb[@]}" shell getprop sys.boot_completed 2>/dev/null || true)
        if [[ ${boot_completed//$'\r'/} == 1 ]]; then
            printf '%s pronto, sys.boot_completed=1\n' "$serial"
            exit 0
        fi
    fi
    sleep 1
done

printf 'O boot de %s excedeu %s segundos.\n' "$avd_name" "$boot_timeout" >&2
exit 1
