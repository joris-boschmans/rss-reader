#!/bin/sh
# Quiet RSS Reader launcher.
set -e
DIR=$(cd "$(dirname "$0")" && pwd)

find_java() {
  if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    echo "$JAVA_HOME/bin/java"; return
  fi
  for d in "$HOME/AppData/Local/Programs/IntelliJ IDEA"* \
           "$HOME/Applications/IntelliJ IDEA"*".app/Contents" \
           "/Applications/IntelliJ IDEA"*".app/Contents" \
           "/opt/jetbrains"/*; do
    if [ -x "$d/jbr/bin/java" ]; then echo "$d/jbr/bin/java"; return; fi
    if [ -x "$d/jbr/Contents/Home/bin/java" ]; then echo "$d/jbr/Contents/Home/bin/java"; return; fi
  done
  command -v java 2>/dev/null || true
}

find_mvn() {
  command -v mvn 2>/dev/null && return
  for d in "$HOME/AppData/Local/Programs/IntelliJ IDEA"* \
           "/Applications/IntelliJ IDEA"*".app/Contents" \
           "/opt/jetbrains"/*; do
    if [ -x "$d/plugins/maven-plugin/lib/maven3/bin/mvn" ]; then
      echo "$d/plugins/maven-plugin/lib/maven3/bin/mvn"; return
    fi
  done
}

JAVA_BIN=$(find_java)
if [ -z "$JAVA_BIN" ]; then
  echo "Could not find Java."
  echo "Set JAVA_HOME to a JDK 21 or newer, then run this again."
  exit 1
fi

JAR="$DIR/target/rss-reader.jar"
if [ ! -f "$JAR" ]; then
  MVN_BIN=$(find_mvn)
  if [ -z "$MVN_BIN" ]; then
    echo "Could not find Maven, and target/rss-reader.jar has not been built yet."
    echo "Install Maven (or open this folder in IntelliJ) and run: mvn -q package"
    exit 1
  fi
  echo "Building (first run only)..."
  JAVA_HOME=$(dirname "$(dirname "$JAVA_BIN")")
  export JAVA_HOME
  "$MVN_BIN" -q -DskipTests -f "$DIR/pom.xml" package
fi

exec "$JAVA_BIN" -jar "$JAR" "$@"
