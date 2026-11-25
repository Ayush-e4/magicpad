#!/bin/sh
# Creates a self-signed code-signing certificate in your login keychain, once.
#
# Why: an ad-hoc signed app gets a new identity on every build, so macOS forgets its
# Accessibility permission after each rebuild. Signed with the same certificate every
# time, the permission sticks. The certificate only exists on this Mac; it doesn't make
# the app trusted anywhere else, and it's not an Apple Developer ID.
#
# Usage: mac/make-signing-cert.sh ["Certificate Name"]   (or: make signing-cert)
set -eu

NAME="${1:-MagicPad Local Signing}"
KEYCHAIN="$HOME/Library/Keychains/login.keychain-db"

if security find-identity -p codesigning | grep -q "\"$NAME\""; then
    echo "\"$NAME\" is already in your keychain."
    exit 0
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

cat >"$TMP/cert.cnf" <<EOF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $NAME
[ext]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature
extendedKeyUsage = critical, codeSigning
EOF

openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -config "$TMP/cert.cnf" \
    -keyout "$TMP/key.pem" -out "$TMP/cert.pem" 2>/dev/null
PASS=$(openssl rand -hex 16)
openssl pkcs12 -export -inkey "$TMP/key.pem" -in "$TMP/cert.pem" -name "$NAME" \
    -passout "pass:$PASS" -out "$TMP/identity.p12"
security import "$TMP/identity.p12" -k "$KEYCHAIN" -P "$PASS" -T /usr/bin/codesign >/dev/null

echo "Added \"$NAME\" to your login keychain. 'make app' will sign with it from now on."
echo "If macOS asks whether codesign may use the key, click Always Allow."
