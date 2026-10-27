#!/bin/sh
# 컴파일 (EC2 · Linux · macOS · Git Bash). 결과: build/classes
cd "$(dirname "$0")/.." || exit 1
mkdir -p build/classes
javac -encoding UTF-8 -d build/classes src/*.java && echo "build OK -> build/classes"
