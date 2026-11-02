#!/bin/sh
# Existing candidate entry point validates the dependent Hyundai code22 authentication milestone.
set -eu
exec sh "$(dirname "$0")/test-beta2-code22.sh"
