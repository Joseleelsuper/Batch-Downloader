#!/bin/sh
set -eu

# CSPU de noviembre de 2026: binarios oficiales verificados mientras se actualizan las imágenes Docker.
# https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.5.1%2B2
: "${JAVA_HOME:?JAVA_HOME debe apuntar al Java del contenedor}"
if grep -qx 'JAVA_VERSION="25.0.5.1"' "$JAVA_HOME/release"; then
    exit 0
fi
[ "$JAVA_HOME" = /opt/java/openjdk ] || {
    echo 'Instala Temurin 25.0.5.1 antes de aplicar la mitigación.' >&2
    exit 1
}

kind=jre
[ ! -x "$JAVA_HOME/bin/javac" ] || kind=jdk
case "$(uname -m):$kind" in
    x86_64:jdk) arch=x64; checksum=760f91b22c3efc9bd1b64ff950598231be82928cc807e30691a0e7a2995a62c0 ;;
    aarch64:jdk) arch=aarch64; checksum=c37879e0d08eeb1baa69fcbb7758cd455b7b1c0ceab1e2a136ccb1713c530984 ;;
    x86_64:jre) arch=x64; checksum=2edef9e4f81ec5ee426d023336d4cfbd7b4644e99884e7a73a6a867b70b17acf ;;
    aarch64:jre) arch=aarch64; checksum=c6161dcfb365024fbfbcda265343d562d6f821c78dd8e7aeaeaf294893f6ff8b ;;
    *) echo 'Arquitectura Java no soportada.' >&2; exit 1 ;;
esac

staging=$(mktemp -d)
trap 'rm -rf "$staging"' EXIT HUP INT TERM
curl --fail --location --retry 3 --connect-timeout 15 --max-time 300 \
    "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5.1%2B2/OpenJDK25U-${kind}_${arch}_linux_hotspot_25.0.5.1_2.tar.gz" \
    --output "$staging/java.tar.gz"
printf '%s  %s\n' "$checksum" "$staging/java.tar.gz" | sha256sum --check -
mkdir "$staging/java"
tar -xzf "$staging/java.tar.gz" --strip-components=1 -C "$staging/java"
grep -qx 'JAVA_VERSION="25.0.5.1"' "$staging/java/release"
rm -rf /opt/java/openjdk
mv "$staging/java" /opt/java/openjdk
