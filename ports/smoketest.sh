#!/usr/bin/env bash
# Development-only in-game check for a NeoForge or Forge port: ports/smoketest.sh <loader> <version>
# Copies a flat test world into the port's run/saves, starts the client with the smoke test built in,
# and prints the [ChessSmoke] lines. Screenshots: ports/<loader>/<version>/run/screenshots/smoke_*.png
set -u
here="$(cd "$(dirname "$0")" && pwd)"
port="$here/$1/$2"
world_src="$here/testworld"
if [ ! -d "$world_src" ]; then
	# The flat world the Fabric client game test leaves behind (run "gradlew runClientGameTest" in the root first).
	cp -r "$here/../build/run/clientGameTest/saves/New World" "$world_src" && rm -f "$world_src/session.lock"
fi
mkdir -p "$port/run/saves"
rm -rf "$port/run/saves/ChessSmoke" "$port/run/screenshots"/smoke_*.png
cp -r "$world_src" "$port/run/saves/ChessSmoke"
mkdir -p "$port/run/config"
# Skip the first-launch accessibility screen and tutorial hints, which would block --quickPlaySingleplayer.
opts="$port/run/options.txt"
touch "$opts"
sed -i '/^onboardAccessibility:/d;/^tutorialStep:/d;/^skipMultiplayerWarning:/d;/^joinedFirstServer:/d' "$opts"
printf 'onboardAccessibility:false
tutorialStep:none
skipMultiplayerWarning:true
joinedFirstServer:true
' >> "$opts"
cd "$port" && timeout 1200 ./gradlew.bat runClient -Pchess_smoketest=true --console=plain > "$port/smoketest.log" 2>&1
grep -a "\[ChessSmoke\]" "$port/smoketest.log" | sed 's/.*\[ChessSmoke\]/[ChessSmoke]/'
grep -a -q "SMOKE TEST PASSED" "$port/smoketest.log"
