#!/bin/sh
# Existing candidate entry point validates the dependent code13 milestone.
set -eu
exec sh "$(dirname "$0")/test-beta2-code13.sh"
