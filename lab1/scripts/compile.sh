#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/classes
find src/main/java src/test/java -name '*.java' | sort > build/sources.txt
if [ -n "${JAVA_HOME:-}" ]; then
    "$JAVA_HOME/bin/javac" --release 17 -d build/classes @build/sources.txt
else
    javac --release 17 -d build/classes @build/sources.txt
fi
