#!/usr/bin/env bash
set -euo pipefail
action=${1:?action}
package=${2:?package}
case "$action" in query|ensure|remove) [[ $package =~ ^[a-zA-Z0-9][a-zA-Z0-9+._:-]*$ ]] || exit 2;; esac
case "$action" in
  query) rpm -q --qf '%{VERSION}-%{RELEASE}\n' -- "$package";;
  identify) rpm -qp --qf '%{NAME}\n' -- "$package";;
  version) rpm -qp --qf '%{VERSION}-%{RELEASE}\n' -- "$package";;
  architecture) rpm -qp --qf '%{ARCH}\n' -- "$package";;
  install) zypper --non-interactive install --allow-unsigned-rpm --no-recommends "$package";;
  ensure) zypper --non-interactive install --no-recommends "$package";;
  downgrade) zypper --non-interactive install --oldpackage --allow-unsigned-rpm --no-recommends "$package";;
  remove) zypper --non-interactive remove "$package";;
  *) exit 2;;
esac
