#!/bin/sh
# The existing candidate entry point now validates the dependent code12 milestone.
set -eu
exec sh "$(dirname "$0")/test-beta2-code12.sh"
