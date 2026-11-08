#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p .secrets
umask 077
if test -e .secrets/device.key; then echo 'Device key already present; refusing overwrite' >&2; exit 1; fi
head -c 32 /dev/urandom > .secrets/device.key
chmod 600 .secrets/device.key
echo 'Device HMAC secret created in .secrets/device.key (32 bytes). Keep private.'
echo 'Provision the SAME key into the future Android app via an authenticated onboarding flow.'
