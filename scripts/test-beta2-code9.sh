#!/bin/sh
# Existing candidate entry point validates the dependent Netatmo code15 corrective milestone.
set -eu
exec sh "$(dirname "$0")/test-beta2-code15.sh"
