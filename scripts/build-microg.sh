#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="${MICROG_SOURCE_DIR:-$repo_root/.build/microg}"
output_dir="$repo_root/dist/microg"
source_commit=32bc8954ff872d0e1a05ddfae81561b6dbecef69
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
export MYBOX_SIGNING_STORE="${MYBOX_SIGNING_STORE:-$repo_root/signing/development.keystore}"

if [[ -z "$sdk_root" || ! -f "$sdk_root/build-tools/35.0.0/apksigner" ]]; then
    printf '%s\n' 'ANDROID_HOME must point to an SDK containing build-tools;35.0.0 and platforms;android-35.' >&2
    exit 1
fi
test -f "$MYBOX_SIGNING_STORE"
mkdir -p "$output_dir" "$(dirname "$source_dir")"
if [[ ! -d "$source_dir/.git" ]]; then
    git clone --depth 1 --branch v0.3.17.252432 https://github.com/microg/GmsCore.git "$source_dir"
fi
test "$(git -C "$source_dir" rev-parse HEAD)" = "$source_commit"

patches=("$repo_root"/microg-patches/*.patch)
reverse_patches=()
for patch in "${patches[@]}"; do
    reverse_patches=("$patch" "${reverse_patches[@]}")
done
# Later patches can amend earlier hunks; validate the whole applied series backwards first.
if ! git -C "$source_dir" apply --reverse --check "${reverse_patches[@]}" 2>/dev/null; then
    for patch in "${patches[@]}"; do
        if git -C "$source_dir" apply --check "$patch"; then
            git -C "$source_dir" apply --intent-to-add "$patch"
        else
            git -C "$source_dir" apply --reverse --check "$patch"
        fi
    done
fi
git -C "$source_dir" diff --binary > "$output_dir/source-changes.patch"

python3 "$repo_root/microg-patches/audit_source.py" --self-test
python3 "$repo_root/microg-patches/audit_source.py" --source "$source_dir" > "$output_dir/source-log-audit.txt"

identity_classes="$source_dir/.mybox-identity-test"
mkdir -p "$identity_classes"
javac --release 8 -cp "$sdk_root/platforms/android-35/android.jar" -d "$identity_classes" \
    "$source_dir/play-services-base/core/src/main/java/org/microg/gms/common/MyboxIdentity.java" \
    "$source_dir/play-services-base/core/src/main/java/org/microg/gms/common/NotOkayException.java" \
    "$source_dir/play-services-core/src/main/java/org/microg/gms/auth/loginservice/MyboxAccountResponse.java" \
    "$repo_root/microg-patches/IdentitySelfTest.java" \
    "$repo_root/microg-patches/PrivacySelfTest.java"
java -ea -cp "$identity_classes:$sdk_root/platforms/android-35/android.jar" org.microg.gms.common.IdentitySelfTest
java -ea -cp "$identity_classes:$sdk_root/platforms/android-35/android.jar" org.microg.gms.common.PrivacySelfTest

printf 'sdk.dir=%s\nmodules.hms=false\n' "$sdk_root" > "$source_dir/local.properties"
cat > "$source_dir/play-services-core/user.gradle" <<'GRADLE'
android.signingConfigs.debug {
    storeFile new File(System.getenv('MYBOX_SIGNING_STORE'))
    storePassword 'android'
    keyAlias 'androiddebugkey'
    keyPassword 'android'
}
GRADLE

cd "$source_dir"
chmod +x gradlew
./gradlew --no-daemon --no-configuration-cache --console=plain buildEnvironment > "$output_dir/build-environment.txt"
./gradlew --no-daemon --no-configuration-cache --console=plain :play-services-core:dependencies \
    --configuration mapboxDefaultDebugRuntimeClasspath > "$output_dir/runtime-dependencies.txt"
./gradlew --no-daemon --no-configuration-cache --console=plain --max-workers=2 \
    :play-services-core:assembleMapboxDefaultDebug 2>&1 | tee "$output_dir/gradle-build.log"

mapfile -t apks < <(find play-services-core/build/outputs/apk -name '*.apk' -type f)
if [[ "${#apks[@]}" -ne 1 ]]; then
    printf '%s\n' 'Expected exactly one mapboxDefaultDebug APK.' >&2
    exit 1
fi
cp "${apks[0]}" "$output_dir/microg-nohms.apk"
"$sdk_root/build-tools/35.0.0/apksigner" verify --verbose --print-certs "$output_dir/microg-nohms.apk" > "$output_dir/apk-signature.txt"
"$sdk_root/build-tools/35.0.0/aapt" dump badging "$output_dir/microg-nohms.apk" > "$output_dir/apk-badging.txt"
"$sdk_root/build-tools/35.0.0/aapt" dump xmltree "$output_dir/microg-nohms.apk" AndroidManifest.xml > "$output_dir/apk-manifest.txt"
if grep -Eq 'com\.huawei\.(hms|agconnect|hmf)' "$output_dir/apk-manifest.txt"; then
    printf '%s\n' 'HMS component found in merged manifest.' >&2
    exit 1
fi
python3 "$repo_root/microg-patches/audit_apk.py" --output "$output_dir" \
    --source-commit "$source_commit" --patches "${patches[@]}"
