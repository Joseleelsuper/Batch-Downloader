#!/usr/bin/env bash
set -euo pipefail
action=${1:?action}
package=${2:?package}
case "$action" in query|ensure|remove) [[ $package =~ ^[a-zA-Z0-9][a-zA-Z0-9+._:-]*$ ]] || exit 2;; *) :;; esac
case "$action" in
  query) dpkg-query -W -f='${Status}\t${Version}\n' -- "$package" | awk '$1=="install" && $3=="installed" {print $4; ok=1} END {if(!ok)exit 1}';;
  identify) dpkg-deb -f "$package" Package;;
  version) dpkg-deb -f "$package" Version;;
  architecture) dpkg-deb -f "$package" Architecture;;
  install) apt-get -y --no-remove install "$package";;
  downgrade) apt-get -y --no-remove --allow-downgrades install "$package";;
  ensure) apt-get -y --no-remove install "$package";;
  remove) apt-get -y remove -- "$package";;
  *) exit 2;;
esac
