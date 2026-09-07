#!/usr/bin/env bash
bd_confirm() {
  local title=$1 message=$2 answer
  if command -v whiptail >/dev/null; then whiptail --title "$title" --yesno "$message" 22 78
  elif command -v dialog >/dev/null; then dialog --title "$title" --yesno "$message" 22 78
  else read -r -p "$message [y/N] " answer; [[ $answer == [yYsS] ]]; fi
}
