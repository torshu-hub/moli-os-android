#!/bin/sh
# Existing candidate entry point validates the dependent Netatmo code14 milestone.
set -eu
exec sh "$(dirname "$0")/test-beta2-code14.sh"
