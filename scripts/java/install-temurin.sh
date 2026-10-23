#!/bin/sh
set -eu

# CPU de octubre de 2026: binarios oficiales mientras se actualizan las imágenes Docker.
# https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.5%2B8
: "${JAVA_HOME:?JAVA_HOME debe apuntar al Java del contenedor}"
if grep -qx 'JAVA_VERSION="25.0.5"' "$JAVA_HOME/release"; then
    exit 0
fi
[ "$JAVA_HOME" = /opt/java/openjdk ] || {
    echo 'Instala Temurin 25.0.5 antes de aplicar la mitigación.' >&2
    exit 1
}

kind=jre
[ ! -x "$JAVA_HOME/bin/javac" ] || kind=jdk
case "$(uname -m):$kind" in
    x86_64:jdk) arch=x64; checksum=68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb ;;
    aarch64:jdk) arch=aarch64; checksum=bb5b2ded252f21c6090cf780b0a408ad0d83bbb557e702955643557f13231880 ;;
    x86_64:jre) arch=x64; checksum=6fac98f0c8202efc3135a9e9e42aa61963ec42d1c5cbb40caf231e7b14a24ef6 ;;
    aarch64:jre) arch=aarch64; checksum=963931723891302ebb2687178532c3d793d71e2577cd058061c7efb396140a86 ;;
    *) echo 'Arquitectura Java no soportada.' >&2; exit 1 ;;
esac

staging=$(mktemp -d)
trap 'rm -rf "$staging"' EXIT HUP INT TERM
curl --fail --location --retry 3 --connect-timeout 15 --max-time 300 \
    "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-${kind}_${arch}_linux_hotspot_25.0.5_8.tar.gz" \
    --output "$staging/java.tar.gz"
printf '%s  %s\n' "$checksum" "$staging/java.tar.gz" | sha256sum --check -
mkdir "$staging/java"
tar -xzf "$staging/java.tar.gz" --strip-components=1 -C "$staging/java"
grep -qx 'JAVA_VERSION="25.0.5"' "$staging/java/release"
rm -rf /opt/java/openjdk
mv "$staging/java" /opt/java/openjdk
