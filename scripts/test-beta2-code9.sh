#!/bin/sh
# Existing candidate entry point validates code24 architecture and preservation from code23.
set -eu
exec sh "$(dirname "$0")/test-beta2-code24.sh"
