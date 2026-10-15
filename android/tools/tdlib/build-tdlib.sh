#!/usr/bin/env bash
# TDLib (Telegram's official client library) for the Signal tester's read-only Telegram login, built from source
# (15 Oct 2026, Boss: "no random prebuilt binaries"). Everything is pinned and checked before it is built:
#  - tdlib/td at one commit (the "Update version to 1.8.67" commit), fetched from the official repository and checked
#    against TD_COMMIT;
#  - OpenSSL from the official openssl/openssl release, checked against its published SHA-256;
#  - zlib is the NDK's own (Android ships it as a system library; the NDK is Google's, installed by sdkmanager).
# Output: <out>/arm64-v8a/libtdjsonjava.so (TDLib's JSON interface with its own small Java wrapper, the class
# org.drinkless.tdlib.JsonClient that the app carries), <out>/SHA256SUMS and <out>/VERSION. arm64-v8a only.
#
# Usage: build-tdlib.sh <out dir> [work dir]   (needs ANDROID_NDK_ROOT, cmake >= 3.10, ninja, gperf, perl, make, a host C++)
set -euo pipefail

TD_COMMIT=bc9c263e2bfee06aaab41e82db51a103376030bc   # TDLib 1.8.67 (24 Aug 2026)
OPENSSL_VERSION=3.5.8                                  # an LTS line, supported to 2030
OPENSSL_SHA256=a8f84a39918ec6415ce765d9b429d313ba97b8143169c172e734b9514464f5b2
ABI=arm64-v8a
API=26                                                 # the app's minSdk

OUT=$(mkdir -p "$1" && cd "$1" && pwd)
WORK=$(mkdir -p "${2:-$OUT/../tdlib-work}" && cd "${2:-$OUT/../tdlib-work}" && pwd)
NDK=${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT to the NDK}
JOBS=${JOBS:-$(nproc)}
HOST=linux-x86_64
for t in cmake ninja gperf perl make git curl sha256sum; do command -v "$t" >/dev/null || { echo "missing tool: $t"; exit 1; }; done

echo "== TDLib $TD_COMMIT (official tdlib/td)"
if [ ! -d "$WORK/td/.git" ]; then
  git init -q "$WORK/td"
  git -C "$WORK/td" fetch -q --depth 1 https://github.com/tdlib/td "$TD_COMMIT"
  git -C "$WORK/td" -c advice.detachedHead=false checkout -q FETCH_HEAD
fi
got=$(git -C "$WORK/td" rev-parse HEAD)
[ "$got" = "$TD_COMMIT" ] || { echo "TDLib commit is $got, expected $TD_COMMIT"; exit 1; }

echo "== OpenSSL $OPENSSL_VERSION (official release, SHA-256 checked)"
OSSL="$WORK/openssl-install/$ABI"
if [ ! -f "$OSSL/lib/libcrypto.a" ]; then
  cd "$WORK"
  curl -sSfL -o "openssl-$OPENSSL_VERSION.tar.gz" "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"
  echo "$OPENSSL_SHA256  openssl-$OPENSSL_VERSION.tar.gz" | sha256sum -c -
  rm -rf "openssl-$OPENSSL_VERSION" && tar xzf "openssl-$OPENSSL_VERSION.tar.gz"
  cd "openssl-$OPENSSL_VERSION"
  export ANDROID_NDK_ROOT="$NDK"
  PATH="$NDK/toolchains/llvm/prebuilt/$HOST/bin:$PATH" ./Configure android-arm64 no-shared no-tests no-docs no-apps no-engine \
    no-dso no-ui-console -U__ANDROID_API__ -D__ANDROID_API__=$API -ffunction-sections -fdata-sections
  PATH="$NDK/toolchains/llvm/prebuilt/$HOST/bin:$PATH" make -s -j"$JOBS" build_libs
  mkdir -p "$OSSL/lib"
  cp libcrypto.a libssl.a "$OSSL/lib/"
  cp -r include "$OSSL/"
  cd "$WORK" && rm -rf "openssl-$OPENSSL_VERSION" "openssl-$OPENSSL_VERSION.tar.gz"
fi

echo "== TDLib's generated sources (host build of its code generators)"
cmake -S "$WORK/td/example/android" -B "$WORK/build-native" -DTD_ANDROID_JSON_JAVA=ON -DTD_GENERATE_SOURCE_FILES=ON >/dev/null
cmake --build "$WORK/build-native" -j "$JOBS"

echo "== TDLib for $ABI, API $API (NDK $(sed -n 's/^Pkg.Revision *= *//p' "$NDK/source.properties"))"
cmake -S "$WORK/td/example/android" -B "$WORK/build-$ABI" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API \
  -DANDROID_STL=c++_static -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DOPENSSL_ROOT_DIR="$OSSL" -DTD_ANDROID_JSON_JAVA=ON >/dev/null
cmake --build "$WORK/build-$ABI" --target tdjni -j "$JOBS"

mkdir -p "$OUT/$ABI"
cp "$WORK/build-$ABI/libtdjsonjava.so" "$OUT/$ABI/"
(cd "$OUT" && sha256sum "$ABI/libtdjsonjava.so" > SHA256SUMS)
printf 'tdlib/td %s\nopenssl %s %s\nndk %s\nabi %s api %s\n' "$TD_COMMIT" "$OPENSSL_VERSION" "$OPENSSL_SHA256" \
  "$(sed -n 's/^Pkg.Revision *= *//p' "$NDK/source.properties")" "$ABI" "$API" > "$OUT/VERSION"
echo "== Done: $(du -h "$OUT/$ABI/libtdjsonjava.so" | cut -f1) $(cat "$OUT/SHA256SUMS")"
