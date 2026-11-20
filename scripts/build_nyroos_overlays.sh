#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source ./env.sh
tools="$ANDROID_HOME/build-tools/37.0.0"
platform="$ANDROID_HOME/platforms/android-37.0/android.jar"
export JDK_JAVA_OPTIONS="-XX:+PerfDisableSharedMem -Djava.io.tmpdir=$TMPDIR"
stage=$(mktemp -d "$PWD/work/nyroos-overlays.XXXXXX")
trap 'rm -r -- "$stage"' EXIT
destination="$PWD/custom/overlays"
mkdir -p -- "$destination"
keystore="$ANDROID_USER_HOME/nyroos-overlay.p12"
if [[ ! -s $keystore ]]; then
    (
        umask 077
        keytool -genkeypair -keystore "$keystore" -storetype PKCS12 -storepass android \
            -alias nyroos -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=NyRoOS Development'
    )
fi

build() {
    local name=$1 package=$2 target=$3 static=$4
    mkdir -p "$stage/res/xml"
    cat > "$stage/AndroidManifest.xml" <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$package">
    <application android:hasCode="false" android:label="NyRoOS" />
    <overlay android:targetPackage="$target" android:resourcesMap="@xml/resources_map"
        android:isStatic="$static" android:priority="90" />
</manifest>
EOF
    {
        printf '<overlay>\n'
        while read -r resource value; do
            printf '    <item target="%s" value="%s" />\n' "$resource" "$value"
        done
        printf '</overlay>\n'
    } > "$stage/res/xml/resources_map.xml"
    "$tools/aapt2" compile --dir "$stage/res" -o "$stage/resources.zip"
    "$tools/aapt2" link -I "$platform" --manifest "$stage/AndroidManifest.xml" \
        --min-sdk-version 30 --target-sdk-version 37 --auto-add-overlay \
        --no-resource-deduping --no-resource-removal -o "$stage/unsigned.apk" "$stage/resources.zip"
    "$tools/zipalign" -f 4 "$stage/unsigned.apk" "$stage/aligned.apk"
    "$tools/apksigner" sign --ks "$keystore" --ks-key-alias nyroos --ks-pass pass:android \
        --v4-signing-enabled false --out "$stage/$name.apk" "$stage/aligned.apk"
    "$tools/apksigner" verify --verbose "$stage/$name.apk"
    mv -T -- "$stage/$name.apk" "$destination/$name.apk"
    printf '%s → %s\n' "$name.apk" "$target"
}

# This APK is immutable so the framework has the dark default before SystemUI starts.
build NyRoOSDefaultsOverlay os.nyro.branding.defaults android true <<'EOF'
integer/config_defaultNightMode 2
dimen/rounded_corner_radius 24dp
EOF

build NyRoOSThemeOverlay os.nyro.branding.theme android false <<'EOF'
color/system_background_dark #11131A
color/system_surface_dark #11131A
color/system_surface_dim_dark #11131A
color/system_surface_container_lowest_dark #0F1017
color/system_surface_container_low_dark #14161F
color/system_surface_container_dark #171922
color/system_surface_container_high_dark #1E202B
color/system_surface_container_highest_dark #29253B
color/system_surface_variant_dark #29253B
color/system_on_surface_dark #EFEDF6
color/system_on_background_dark #EFEDF6
color/system_on_surface_variant_dark #A4A3B6
color/system_primary_dark #B29AFF
color/system_primary_dim_dark #AA92F6
color/system_on_primary_dark #191026
color/system_primary_container_dark #483579
color/system_on_primary_container_dark #EFEDF6
color/system_surface_tint_dark #B29AFF
color/system_outline_variant_dark #30313F
EOF

build NyRoOSSystemUIOverlay os.nyro.branding.systemui com.android.systemui false <<'EOF'
dimen/common_tile_default_active_tile_corner_radius 32dp
dimen/common_tile_default_inactive_tile_corner_radius 38dp
dimen/notification_corner_radius 34dp
dimen/qs_content_horizontal_padding 20dp
dimen/qs_horizontal_margin 20dp
dimen/qs_footer_actions_top_padding 16dp
EOF

build NyRoOSSettingsOverlay os.nyro.branding.settings com.android.settings false <<'EOF'
dimen/homepage_title_margin_bottom 16dp
dimen/homepage_title_margin_horizontal 28dp
dimen/homepage_preference_text_padding_start_expressive 16dp
color/homepage_network_background #433667
color/homepage_network_foreground #E6D9FF
color/homepage_connected_device_background #433667
color/homepage_connected_device_foreground #E6D9FF
color/homepage_apps_background #483579
color/homepage_apps_foreground #EFEDF6
color/homepage_notification_background #352B4C
color/homepage_notification_foreground #DAC9FF
color/homepage_sound_background #352B4C
color/homepage_sound_foreground #DAC9FF
color/homepage_modes_background #352B4C
color/homepage_modes_foreground #DAC9FF
color/homepage_display_background #413556
color/homepage_display_foreground #E6D9FF
color/homepage_wallpaper_background #413556
color/homepage_wallpaper_foreground #E6D9FF
color/homepage_storage_background #413556
color/homepage_storage_foreground #E6D9FF
EOF
