#!/bin/sh
set -eu

# CVE-2026-91403: https://ops.java/emudhra (Oracle, 2026-10-22).
# Las huellas identifican las raíces aunque el proveedor cambie sus alias.
: "${JAVA_HOME:?JAVA_HOME debe apuntar al Java que se va a proteger}"
keytool="$JAVA_HOME/bin/keytool"
truststore="$JAVA_HOME/lib/security/cacerts"
emsign='86:A1:EC:BA:08:9C:4A:8D:3B:BE:27:34:C6:12:BA:34:1D:81:3E:04:3C:F9:E8:A8:62:CD:5C:57:A3:6B:BE:6B'
aaa='D7:A7:A0:FB:5D:7E:27:31:D7:71:E9:48:4E:BC:DE:F7:1D:5F:0C:3E:0A:29:48:78:2B:C8:3E:E0:EA:69:9E:F4'

certificates=$("$keytool" -J-Duser.language=en -J-Duser.country=US -list -v -cacerts -storepass changeit)
aliases=$(printf '%s\n' "$certificates" | awk -v emsign="$emsign" -v aaa="$aaa" '
    /^Alias name: / { alias = substr($0, 13) }
    /SHA256:/ { if ($2 == emsign || $2 == aaa) print alias }
')
printf '%s\n' "$aliases" | while IFS= read -r alias; do
    [ -n "$alias" ] || continue
    if [ ! -f "$truststore.before-CVE-2026-91403" ]; then
        cp -L "$truststore" "$truststore.before-CVE-2026-91403"
    fi
    "$keytool" -delete -cacerts -storepass changeit -alias "$alias"
done

certificates=$("$keytool" -J-Duser.language=en -list -v -cacerts -storepass changeit)
case "$certificates" in
    *"$emsign"*|*"$aaa"*)
        echo 'No se han retirado todas las autoridades afectadas por CVE-2026-91403.' >&2
        exit 1
        ;;
esac

if [ "$#" -gt 0 ]; then
    exec "$@"
fi
