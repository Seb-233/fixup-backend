#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
exec ./mvnw clean verify "$@"
