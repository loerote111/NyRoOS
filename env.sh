#!/usr/bin/env bash
# Load this file with Bash to use the project's portable tools.
if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    printf 'Use source env.sh para carregar o ambiente no Bash atual.\n' >&2
    exit 1
fi

_android_tools_dir="$(CDPATH= cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")/.tools" && pwd -P)" || return 1

export JAVA_HOME="$_android_tools_dir/jdk-21"
export ANDROID_HOME="$_android_tools_dir/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_AVD_HOME="$_android_tools_dir/avd"
export ANDROID_USER_HOME="$_android_tools_dir/android-user"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export TMPDIR="$_android_tools_dir/tmp"

# Use a separate local ADB server for this workspace.
export ANDROID_ADB_SERVER_PORT=5038
export ADB_SERVER_SOCKET="tcp:localhost:$ANDROID_ADB_SERVER_PORT"

_android_tools_path="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$_android_tools_dir/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$_android_tools_dir/apktool:$_android_tools_dir/7zip"
case "${PATH-}" in
    "$_android_tools_path"|"$_android_tools_path":*) ;;
    *) export PATH="$_android_tools_path${PATH:+:$PATH}" ;;
esac

unset _android_tools_dir _android_tools_path
