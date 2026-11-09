#!/bin/sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DISTRIBUTION_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip"
MAVEN_HOME="$BASE_DIR/.mvn/local/apache-maven-3.9.11"

if [ -x "$MAVEN_HOME/bin/mvn" ]; then
    exec "$MAVEN_HOME/bin/mvn" "$@"
fi

if command -v mvn >/dev/null 2>&1; then
    exec mvn "$@"
fi

if ! command -v curl >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1; then
    echo "Maven is not installed. Install Maven or install curl and unzip to use this bootstrap script." >&2
    exit 1
fi

DOWNLOAD_DIR="$BASE_DIR/.mvn/local"
mkdir -p "$DOWNLOAD_DIR"
curl --fail --location --retry 2 --output "$DOWNLOAD_DIR/maven.zip" "$DISTRIBUTION_URL"
unzip -q -o "$DOWNLOAD_DIR/maven.zip" -d "$DOWNLOAD_DIR"
rm -f "$DOWNLOAD_DIR/maven.zip"
exec "$MAVEN_HOME/bin/mvn" "$@"
