#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tmp-test
kotlinc app/src/main/java/ru/portfolio/watch/DiffCore.kt tests/RunDiffTests.kt -include-runtime -d .tmp-test/diff-tests.jar
java -jar .tmp-test/diff-tests.jar
