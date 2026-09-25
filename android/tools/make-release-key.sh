#!/usr/bin/env bash
# Create IraAlgo's release signing key, once, and print the four GitHub
# secrets the Android workflow reads. With them set, every APK CI builds is
# signed with THIS key, so a new build installs over the old one and keeps
# your data (vault, PIN, strategies). Without them each build is signed with a
# throwaway debug key and must be uninstalled first.
#
#   bash android/tools/make-release-key.sh            # writes ./iraalgo-release.jks
#
# KEEP the .jks file and the password somewhere safe and OFFLINE (a password
# manager, an encrypted USB stick). Lose them and you can never update the
# installed app again - only uninstall (which erases its data) and reinstall.
# Never commit the .jks to the repository.
set -euo pipefail

OUT="${1:-iraalgo-release.jks}"
ALIAS="iraalgo"
command -v keytool >/dev/null || { echo "keytool not found: install a JDK (e.g. Temurin 17) first"; exit 1; }
[ -e "$OUT" ] && { echo "$OUT already exists; refusing to overwrite a signing key"; exit 1; }

PASS=$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')
keytool -genkeypair -v -keystore "$OUT" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=IraAlgo, O=Personal, C=IN" >/dev/null 2>&1
chmod 600 "$OUT"

B64=$(base64 < "$OUT" | tr -d '\n')
cat <<MSG

Created $OUT (RSA 4096, valid ~27 years).

Add these as repository secrets on GitHub:
  Settings -> Secrets and variables -> Actions -> New repository secret

  OL_KEYSTORE_B64       (the long line below)
  OL_KEYSTORE_PASSWORD  $PASS
  OL_KEY_ALIAS          $ALIAS
  OL_KEY_PASSWORD       $PASS

---- OL_KEYSTORE_B64 ----
$B64
-------------------------

Then re-run the "Android app" workflow. The first APK signed with this key will
not install over one signed with the debug key: uninstall once, install, and
from then on every build updates in place.

Store $OUT and the password offline, then clear this terminal's scrollback.
MSG
