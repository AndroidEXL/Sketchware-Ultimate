#!/bin/bash
# pkg - Termux-style package manager wrapper for the Ubuntu Terminal app.
# Wraps apt with short, script-friendly commands (pkg install foo).
set -u

usage() {
    echo 'usage: pkg command [arguments]'
    echo
    echo 'commands:'
    echo '  install <pkgs>      install packages'
    echo '  remove <pkgs>       remove packages'
    echo '  purge <pkgs>        remove packages + config'
    echo '  reinstall <pkgs>    reinstall packages'
    echo '  update              refresh the package lists'
    echo '  upgrade             upgrade installed packages'
    echo '  full-upgrade        upgrade (may remove packages)'
    echo '  autoremove          remove unneeded packages'
    echo '  search <term>       search packages'
    echo '  show <pkg>          show package details'
    echo '  files <pkg>         list files of an installed package'
    echo '  list-all            list available packages'
    echo '  list-installed      list installed packages'
    echo '  clean               clear the package cache'
    echo '  autoclean           clear obsolete cache'
}

if [ $# -lt 1 ]; then
    usage
    exit 0
fi

cmd="$1"; shift
need_pkg() {
    if [ $# -eq 0 ]; then
        echo "pkg: no package specified (try: pkg $cmd nano)" >&2
        exit 1
    fi
}

# keeps the app's completion-bar package list fresh after any refresh
refresh_names() {
    apt-cache pkgnames 2>/dev/null | sort > /var/lib/dapi-pkgs.txt
    echo "pkg: completion list refreshed ($(wc -l < /var/lib/dapi-pkgs.txt 2>/dev/null) names)"
}

case "$cmd" in
    help|-h|--help)
        usage ;;
    install)
        need_pkg "$@"
        apt install -y "$@" ;;
    remove|uninstall)
        need_pkg "$@"
        apt remove -y "$@" ;;
    purge)
        need_pkg "$@"
        apt purge -y "$@" ;;
    reinstall)
        need_pkg "$@"
        apt install -y --reinstall "$@" ;;
    update)
        apt update
        refresh_names ;;
    upgrade)
        apt upgrade -y
        refresh_names ;;
    full-upgrade)
        apt full-upgrade -y
        refresh_names ;;
    autoremove)
        apt autoremove -y ;;
    search)
        if [ $# -eq 0 ]; then echo 'usage: pkg search <term>' >&2; exit 1; fi
        apt search "$@" ;;
    show)
        if [ $# -eq 0 ]; then echo 'usage: pkg show <package>' >&2; exit 1; fi
        apt show "$@" ;;
    files)
        if [ $# -eq 0 ]; then echo 'usage: pkg files <package>' >&2; exit 1; fi
        dpkg -L "$@" ;;
    list-all)
        apt list "$@" ;;
    list-installed)
        apt list --installed "$@" ;;
    clean)
        apt clean ;;
    autoclean)
        apt autoclean ;;
    *)
        echo "pkg: unknown command '$cmd'" >&2
        usage >&2
        exit 1 ;;
esac
