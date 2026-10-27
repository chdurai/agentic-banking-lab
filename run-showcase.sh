#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
export SPRING_PROFILES_ACTIVE=showcase
echo "SCRIPTED showcase | synthetic sources | no model network calls | local H2 recovery only"
echo "Open http://localhost:8080/mission-control.html?showcase=1"
exec ./mvnw spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=showcase --server.address=127.0.0.1"
