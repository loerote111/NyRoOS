#!/usr/bin/env bash
set -euo pipefail

cd -- "$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
source ./env.sh

if [[ ${1-} == --help || ${1-} == -h || $# == 0 ]]; then
    printf 'Uso: %s --version X.Y.Z --notes CHANGELOG [--ota OTA.zip] [--replace] [--build-apks] [--raw-assets]\n' "$0"
    exit 0
fi

args=()
build=false
for arg in "$@"; do
    if [[ $arg == --build-apks ]]; then
        build=true
    else
        args+=("$arg")
    fi
done

exec 8>"$ANDROID_USER_HOME/ota-release.lock"
flock -n 8
if "$build"; then
    ./scripts/build_and_install_launcher.sh --build-only
    ./scripts/build_and_install_nyrocore.sh --build-only
fi
python3 -I -B ota/package_release.py "${args[@]}"
