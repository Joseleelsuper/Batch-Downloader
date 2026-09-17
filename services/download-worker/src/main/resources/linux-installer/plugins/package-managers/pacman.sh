#!/usr/bin/env bash
set -euo pipefail
action=${1:?action}
package=${2:?package}
case "$action" in query|ensure|remove) [[ $package =~ ^[a-zA-Z0-9][a-zA-Z0-9+._:-]*$ ]] || exit 2;; *) :;; esac
case "$action" in
  query) pacman -Q -- "$package" | awk '{print $2}';;
  identify) pacman -Qp -- "$package" | awk '{print $1}';;
  version) pacman -Qp -- "$package" | awk '{print $2}';;
  architecture) bsdtar -xOf "$package" .PKGINFO | awk '$1=="arch" {print $3}';;
  install|downgrade) pacman -U --noconfirm -- "$package";;
  ensure) pacman -S --needed --noconfirm -- "$package";;
  remove) pacman -R --noconfirm -- "$package";;
  *) exit 2;;
esac
