#!/usr/bin/env bash
# 跑 JDBC URL 探针：定位 "Failed to create connection" 的真实根因。
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

export JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.12-tem"
export PATH="$JAVA_HOME/bin:$HOME/.sdkman/candidates/maven/3.9.9/bin:$PATH"

CP_FILE=".run/cp.txt"
mkdir -p .run
mvn -q -B dependency:build-classpath -Dmdep.outputFile="$CP_FILE" || exit 1

java -cp "target/classes:$(cat "$CP_FILE")" tools/JdbcProbe.java
