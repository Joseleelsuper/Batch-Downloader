#!/usr/bin/env bash
set -Eeuo pipefail

readonly COOLIFY_INSTALL_URL="https://cdn.coollabs.io/coolify/install.sh"
readonly IPV4_RULES="/etc/iptables/rules.v4"
readonly IPV6_RULES="/etc/iptables/rules.v6"

if (( EUID != 0 )); then
  echo "Ejecuta este script como root (sudo)." >&2
  exit 1
fi

admin_user="${SUDO_USER:-}"
if [[ -z "${admin_user}" || "${admin_user}" == "root" ]]; then
  echo "Ejecuta el bootstrap desde una sesión no-root con sudo para poder verificar el acceso alternativo." >&2
  exit 1
fi
admin_home="$(getent passwd "${admin_user}" | cut -d: -f6)"
if [[ -z "${admin_home}" || ! -s "${admin_home}/.ssh/authorized_keys" ]]; then
  echo "${admin_user} no tiene una clave SSH autorizada; se cancela antes de endurecer sshd." >&2
  exit 1
fi
if ! id -nG "${admin_user}" | tr ' ' '\n' | grep -qx sudo; then
  echo "${admin_user} no pertenece al grupo sudo; se cancela para evitar perder la administración." >&2
  exit 1
fi

source /etc/os-release
if [[ "${ID:-}" != "ubuntu" ]]; then
  echo "Este bootstrap solo admite Ubuntu; se detectó ${ID:-desconocido}." >&2
  exit 1
fi
if [[ "$(uname -m)" != "aarch64" ]]; then
  echo "Se esperaba ARM64 (aarch64)." >&2
  exit 1
fi
if (( $(nproc) < 2 )); then
  echo "Coolify necesita al menos 2 CPU." >&2
  exit 1
fi
if (( $(awk '/MemTotal/ {print $2}' /proc/meminfo) < 2 * 1024 * 1024 )); then
  echo "Coolify necesita al menos 2 GiB de RAM." >&2
  exit 1
fi
if (( $(df --output=avail -k / | tail -n 1) < 20 * 1024 * 1024 )); then
  echo "Se requieren al menos 20 GiB libres antes de instalar." >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
timedatectl set-timezone Europe/Madrid
apt-get update
apt-get install -y --no-install-recommends \
  ca-certificates curl iptables-persistent netfilter-persistent unattended-upgrades
systemctl enable --now unattended-upgrades.service

if ! swapon --show=NAME --noheadings | grep -qx '/swapfile'; then
  if [[ ! -f /swapfile ]]; then
    fallocate -l 2G /swapfile
    chmod 0600 /swapfile
    mkswap /swapfile
  fi
  swapon /swapfile
fi
if ! grep -Eq '^/swapfile[[:space:]]+none[[:space:]]+swap[[:space:]]' /etc/fstab; then
  printf '%s\n' '/swapfile none swap sw 0 0' >> /etc/fstab
fi

ssh_config_tmp="$(mktemp)"
rules_tmp="$(mktemp)"
rules_next=""
ipv6_tmp="$(mktemp)"
installer_tmp=""
override_tmp=""
cleanup() {
  rm -f "${ssh_config_tmp}" "${rules_tmp}" "${rules_next}" \
    "${ipv6_tmp}" "${installer_tmp}" "${override_tmp}"
}
trap cleanup EXIT

cat > "${ssh_config_tmp}" <<'SSH_CONFIG'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
X11Forwarding no
MaxAuthTries 4
SSH_CONFIG
install -m 0644 "${ssh_config_tmp}" /etc/ssh/sshd_config.d/20-batch-downloader-hardening.conf
sshd -t
systemctl reload ssh.service

cp "${IPV4_RULES}" "${rules_tmp}"
rules_next="$(mktemp)"
awk '
  /^-A INPUT / && /--dport 22([[:space:]]|$)/ && /-j ACCEPT([[:space:]]|$)/ { next }
  { print }
' "${rules_tmp}" > "${rules_next}"
mv "${rules_next}" "${rules_tmp}"
rules_next=""
public_ssh_rule="-A INPUT -p tcp -m tcp --dport 22 -m conntrack --ctstate NEW -j ACCEPT"
if ! grep -Fqx -- "${public_ssh_rule}" "${rules_tmp}"; then
  rules_next="$(mktemp)"
  awk -v rule="${public_ssh_rule}" '
    !inserted && /^-A INPUT .* -j (REJECT|DROP)/ { print rule; inserted=1 }
    !inserted && /^COMMIT$/ { print rule; inserted=1 }
    { print }
    END { if (!inserted) exit 2 }
  ' "${rules_tmp}" > "${rules_next}"
  mv "${rules_next}" "${rules_tmp}"
  rules_next=""
fi
for port in 80 443; do
  if grep -Eq -- "^-A INPUT .*--dport ${port} .* -j ACCEPT$" "${rules_tmp}"; then
    continue
  fi
  rules_next="$(mktemp)"
  awk -v rule="-A INPUT -p tcp -m tcp --dport ${port} -m conntrack --ctstate NEW -j ACCEPT" '
    !inserted && /^-A INPUT .* -j (REJECT|DROP)/ { print rule; inserted=1 }
    !inserted && /^COMMIT$/ { print rule; inserted=1 }
    { print }
    END { if (!inserted) exit 2 }
  ' "${rules_tmp}" > "${rules_next}"
  mv "${rules_next}" "${rules_tmp}"
  rules_next=""
done
iptables-restore --test < "${rules_tmp}"
if ! cmp -s "${rules_tmp}" "${IPV4_RULES}"; then
  cp -a "${IPV4_RULES}" "${IPV4_RULES}.before-batch-downloader"
  install -m 0644 "${rules_tmp}" "${IPV4_RULES}"
fi
mapfile -t existing_ssh_rules < <(
  iptables -L INPUT --line-numbers -n -v \
    | awk '$1 ~ /^[0-9]+$/ && $4 == "ACCEPT" && $0 ~ /dpt:22/ { print $1 }' \
    | sort -rn
)
for rule_number in "${existing_ssh_rules[@]}"; do
  iptables -D INPUT "${rule_number}"
done
iptables -I INPUT 1 -p tcp --dport 22 \
  -m conntrack --ctstate NEW -j ACCEPT
for port in 80 443; do
  if ! iptables -C INPUT -p tcp --dport "${port}" -m conntrack --ctstate NEW -j ACCEPT 2>/dev/null; then
    iptables -I INPUT 1 -p tcp --dport "${port}" -m conntrack --ctstate NEW -j ACCEPT
  fi
done

cat > "${ipv6_tmp}" <<'IPV6_RULES'
# Batch Downloader: only the three administrative/web entry points are public.
*filter
:INPUT DROP [0:0]
:FORWARD ACCEPT [0:0]
:OUTPUT ACCEPT [0:0]
-A INPUT -i lo -j ACCEPT
-A INPUT -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
-A INPUT -p ipv6-icmp -j ACCEPT
-A INPUT -p tcp -m tcp --dport 22 -m conntrack --ctstate NEW -j ACCEPT
-A INPUT -p tcp -m tcp --dport 80 -m conntrack --ctstate NEW -j ACCEPT
-A INPUT -p tcp -m tcp --dport 443 -m conntrack --ctstate NEW -j ACCEPT
COMMIT
IPV6_RULES
ip6tables-restore --test < "${ipv6_tmp}"
if ! cmp -s "${ipv6_tmp}" "${IPV6_RULES}"; then
  cp -a "${IPV6_RULES}" "${IPV6_RULES}.before-batch-downloader"
  install -m 0644 "${ipv6_tmp}" "${IPV6_RULES}"
  ip6tables-restore < "${IPV6_RULES}"
fi

systemctl disable --now rpcbind.service rpcbind.socket 2>/dev/null || true

if [[ ! -s /data/coolify/source/.env ]]; then
  installer_tmp="$(mktemp)"
  curl --fail --show-error --silent --location \
    --proto '=https' --tlsv1.2 "${COOLIFY_INSTALL_URL}" --output "${installer_tmp}"
  bash "${installer_tmp}"
fi

override_tmp="$(mktemp)"
cat > "${override_tmp}" <<'COOLIFY_OVERRIDE'
services:
  coolify:
    ports: !override
      - "127.0.0.1:8000:8080"
  soketi:
    ports: !override
      - "127.0.0.1:6001:6001"
      - "127.0.0.1:6002:6002"
COOLIFY_OVERRIDE

readonly COOLIFY_OVERRIDE="/data/coolify/source/docker-compose.custom.yml"
if [[ -e "${COOLIFY_OVERRIDE}" ]] && ! cmp -s "${override_tmp}" "${COOLIFY_OVERRIDE}"; then
  echo "${COOLIFY_OVERRIDE} ya existe con cambios ajenos; no se sobrescribe." >&2
  exit 1
fi
if [[ ! -e "${COOLIFY_OVERRIDE}" ]]; then
  install -m 0600 "${override_tmp}" "${COOLIFY_OVERRIDE}"
  /data/coolify/source/upgrade.sh
fi

docker ps --format '{{.Names}}\t{{.Status}}'
echo "Bootstrap completado. Solo TCP 22/80/443 queda accesible públicamente; SSH admite únicamente claves."
