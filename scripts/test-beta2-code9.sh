#!/bin/sh
# Existing candidate entry point validates the dependent Hyundai code21 milestone.
set -eu
exec sh "$(dirname "$0")/test-beta2-code21.sh"
