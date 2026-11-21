#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
tool="$PWD/.tools/shizuku"
mkdir -p "$tool"
exec 9>"$tool/download.lock"
flock 9
download() {
    local url="$1" hash="$2" file="$tool/${1##*/}"
    if [[ ! -s $file ]] || ! printf '%s  %s\n' "$hash" "$file" | sha256sum -c --status; then
        wget --https-only -q -O "$file.part" "$url"
        printf '%s  %s\n' "$hash" "$file.part" | sha256sum -c --status
        mv -f -- "$file.part" "$file"
    fi
}

sdk_version=13.1.5
sdk_base=https://repo.maven.apache.org/maven2/dev/rikka/shizuku
archives=(
  api:4def9bde498ef8626614c2fc5db9af4749c86f16f6c33e3f5658d35e70bab59b
  provider:b0f18cd9812464ec171c53cac93a819fe411718a3965c311f01eb4de265381b3
  shared:4659642c9339be0a26e9c65bb8648f7ad6d8f4a465f557993ccbc78802381635
  aidl:33fe7191cdd69fcb66d649264f3b0c47acb2f3d6343afc05b98dbbff6f221963
)
for artifact in "${archives[@]}"; do
    name=${artifact%%:*}
    archive="$name-$sdk_version.aar"
    download "$sdk_base/$name/$sdk_version/$archive" "${artifact#*:}"
    unzip -p "$tool/$archive" classes.jar > "$tool/$name.jar.part"
    mv -f -- "$tool/$name.jar.part" "$tool/$name.jar"
done
if [[ ${1-} == --sdk-only ]]; then exit 0; fi
(( $# == 0 ))
apk=shizuku-v13.6.0.r1086.2650830c-release.apk
download "https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/$apk" \
    6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f
printf '%s\n' "$tool/$apk"
