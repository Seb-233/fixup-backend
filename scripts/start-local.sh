#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
exec ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev "$@"
