#!/bin/sh
# XenoBrex — Linux 安裝腳本。
#
# 兩種用法，腳本自己分辨：
#   1. 在 clone 下來的原始碼裡執行：先編譯打包（需要 JDK 21），再安裝。
#   2. 在解開的 XenoBrex-*-linux-x64.tar.gz 裡執行：直接安裝裡面打包好的程式。
#
# 裝到 ~/.local，不需要 root。換位置：XB_PREFIX=/別的地方 ./install.sh
# 移除：./install.sh --uninstall（設定、金鑰、定義檔不會動）
# 安裝清單：$app_dir/.install-manifest；解除安裝只清清單裡的東西。
set -eu

force=0
action=install

usage() {
    cat <<'EOF'
XenoBrex installer

Usage:
  ./install.sh [--force]
  ./install.sh --uninstall
  XB_PREFIX=/path ./install.sh

Run from a source checkout (builds the Linux app first, needs JDK 21)
or from an extracted XenoBrex-*-linux-x64.tar.gz.

Options:
  --force      replace files that this installation does not own
  --uninstall  remove the installation (keeps settings, keys, definitions)
  -h, --help   show this help

Default prefix: $HOME/.local
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --force) force=1 ;;
        --uninstall) action=uninstall ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument: $1 (see --help)" >&2; exit 2 ;;
    esac
    shift
done

here=$(cd -P "$(dirname "$0")" && pwd)
prefix=${XB_PREFIX:-$HOME/.local}
# 相對路徑以執行時的工作目錄為基準，變成絕對路徑。
case $prefix in
    /*) ;;
    *) prefix=$(pwd -P)/$prefix ;;
esac
# Linux 的 coreutils realpath 同時整理 .、.. 與現有的目錄連結。
case $prefix in
    *"$(printf '\t')"*|*'
'*) echo "Install prefix must not contain tabs or newlines" >&2; exit 1 ;;
esac
prefix=$(realpath -m -- "$prefix")
case $prefix in
    /) echo "Refusing to install into /" >&2; exit 1 ;;
esac

# 選單項目的 Exec= 只做得了簡單引號；這些字元放進路徑會變成壞掉的捷徑，直接拒絕。
if [ "$action" = install ]; then
    case $prefix in
        *[[:cntrl:]]*|*'"'*|*'\'*|*'$'*|*'`'*|*'%'*)
            echo "Install prefix must not contain control characters, double quotes, backslashes, dollar signs, backticks or percent signs" >&2
            exit 1 ;;
    esac
fi

app_dir=$prefix/lib/xenobrex
bin_link=$prefix/bin/xenobrex
cli_link=$prefix/bin/xbrex
desktop=$prefix/share/applications/xenobrex.desktop
icon=$prefix/share/icons/hicolor/256x256/apps/xenobrex.png
manifest=$app_dir/.install-manifest

echo "Install prefix: $prefix"

refresh_menus() {
    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$prefix/share/applications" >/dev/null 2>&1 || true
    fi
}

hash_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$1" | sed 's/^.*= //'
    else
        cksum "$1" | cut -d' ' -f1
    fi
}

# 不穿過既有的目錄符號連結。
parents_are_real() {
    _parent=$(dirname "$1")
    while [ "$_parent" != / ]; do
        [ ! -L "$_parent" ] || return 1
        _parent=$(dirname "$_parent")
    done
}

symlink_into_install() {
    [ -L "$1" ] || return 1
    _resolved=$(realpath -m -- "$1") || return 1
    case $_resolved in
        "$app_dir"|"$app_dir"/*) return 0 ;;
    esac
    return 1
}

manifest_valid() {
    [ -f "$manifest" ] && [ ! -L "$manifest" ] && [ ! -L "$app_dir" ] || return 1
    parents_are_real "$manifest" || return 1
    [ "$(head -n 1 "$manifest")" = '# XenoBrex install manifest v2' ]
}

manifest_line() {
    manifest_valid || return 1
    XB_MANIFEST_PATH=$1 awk -F "$(printf '\t')" 'BEGIN { p=ENVIRON["XB_MANIFEST_PATH"] } $2 == p { print; exit }' "$manifest"
}

path_owned() {
    _owned_path=$1
    parents_are_real "$_owned_path" || return 1
    if [ -L "$_owned_path" ]; then
        symlink_into_install "$_owned_path"
        return $?
    fi
    [ -f "$_owned_path" ] || return 1
    _record=$(manifest_line "$_owned_path" || true)
    [ "$(printf '%s\n' "$_record" | cut -f1)" = file ] || return 1
    [ "$(hash_file "$_owned_path")" = "$(printf '%s\n' "$_record" | cut -f3)" ]
}

app_dir_owned() {
    manifest_valid && [ -d "$app_dir" ] || return 1
    # 未列出的檔案也不能在升級時被刪掉。
    find "$app_dir" -print | while IFS= read -r installed; do
        [ "$installed" != "$manifest" ] || continue
        if [ -d "$installed" ] && [ ! -L "$installed" ]; then
            _record=$(manifest_line "$installed" || true)
            [ "$(printf '%s\n' "$_record" | cut -f1)" = dir ] || exit 1
        else
            path_owned "$installed" || exit 1
        fi
    done
}

remove_target() {
    if [ -L "$1" ] || [ -f "$1" ]; then
        rm -f "$1"
    else
        rm -rf "$1"
    fi
}

check_target() {
    _target=$1
    if ! parents_are_real "$_target"; then
        echo "Refusing a symbolic-link parent directory: $_target" >&2
        blocked=1
    elif [ -e "$_target" ] || [ -L "$_target" ]; then
        if [ "$force" != 1 ] && ! path_owned "$_target"; then
            echo "Refusing to overwrite $_target: not created by this installation (use --force)." >&2
            blocked=1
        fi
    fi
}

write_manifest() {
    manifest_tmp=$(mktemp "$app_dir/.manifest.XXXXXX")
    trap 'rm -f "$manifest_tmp"' 0 HUP INT TERM
    {
        printf '%s\n' '# XenoBrex install manifest v2'
        # 子目錄先列，移除時才能用 rmdir 留住未列出的檔案。
        find "$app_dir" -depth -print | while IFS= read -r created; do
            [ "$created" != "$manifest_tmp" ] && [ "$created" != "$manifest" ] || continue
            if [ -L "$created" ]; then
                printf 'link\t%s\n' "$created"
            elif [ -f "$created" ]; then
                printf 'file\t%s\t%s\n' "$created" "$(hash_file "$created")"
            elif [ -d "$created" ]; then
                printf 'dir\t%s\n' "$created"
            fi
        done
        printf 'link\t%s\n' "$bin_link"
        if [ "$cli_installed" = 1 ]; then printf 'link\t%s\n' "$cli_link"; fi
        printf 'file\t%s\t%s\n' "$desktop" "$(hash_file "$desktop")"
        if [ "$icon_installed" = 1 ]; then printf 'file\t%s\t%s\n' "$icon" "$(hash_file "$icon")"; fi
        printf 'manifest\t%s\n' "$manifest"
    } > "$manifest_tmp"
    mv -f "$manifest_tmp" "$manifest"
    trap - 0 HUP INT TERM
}

uninstall() {
    if ! manifest_valid; then
        echo "No valid install manifest; no files were removed."
        return
    fi
    while IFS= read -r line; do
        case $line in ''|'#'*) continue ;; esac
        type=$(printf '%s\n' "$line" | cut -f1)
        path=$(printf '%s\n' "$line" | cut -f2)
        stored=$(printf '%s\n' "$line" | cut -f3)
        # 只接受本次安裝的路徑，不信任清單裡的走位或其他位置。
        [ "$(realpath -ms -- "$path")" = "$path" ] || continue
        case $path in
            "$app_dir"|"$app_dir"/*|"$bin_link"|"$cli_link"|"$desktop"|"$icon") ;;
            *) continue ;;
        esac
        parents_are_real "$path" || continue
        case $type in
            file)
                if [ -f "$path" ] && [ ! -L "$path" ]; then
                    if [ "$(hash_file "$path")" = "$stored" ]; then rm -f "$path"
                    else echo "Keeping modified file: $path" >&2; fi
                fi ;;
            link)
                if symlink_into_install "$path"; then rm -f "$path"; fi ;;
            dir)
                case $path in "$app_dir"|"$app_dir"/*) rmdir "$path" 2>/dev/null || true ;; esac ;;
        esac
    done < "$manifest"
    _self=$(manifest_line "$manifest" || true)
    if [ "$(printf '%s\n' "$_self" | cut -f1)" = manifest ]; then rm -f "$manifest"; fi
    rmdir "$app_dir" 2>/dev/null || true
    refresh_menus
    echo "Removed listed XenoBrex files. Settings, keys, definitions and unlisted files were left in place."
}

if [ "$action" = uninstall ]; then
    uninstall
    exit 0
fi

if [ -x "$here/XenoBrex/bin/XenoBrex" ]; then
    # 解開的壓縮檔
    src=$here/XenoBrex
elif [ -f "$here/gradlew" ]; then
    # 原始碼：先打包
    echo "Building the Linux app from source (the first run downloads build tools and takes a few minutes)..."
    (cd "$here" && ./gradlew --console=plain :xb-app:createDistributable)
    src=$here/xb-app/build/compose/binaries/main/app/XenoBrex
    [ -x "$src/bin/XenoBrex" ] || { echo "Build did not produce $src/bin/XenoBrex" >&2; exit 1; }
else
    echo "Nothing to install: run this script from the source tree or an extracted archive." >&2
    exit 1
fi

cli_installed=0
[ -x "$src/bin/xbrex" ] && cli_installed=1
icon_installed=0
[ -f "$src/lib/XenoBrex.png" ] && icon_installed=1

blocked=0
if ! parents_are_real "$app_dir"; then
    echo "Refusing a symbolic-link parent directory: $app_dir" >&2
    blocked=1
fi
if [ -L "$app_dir" ]; then
    app_state=foreign
elif [ -d "$app_dir" ]; then
    if app_dir_owned; then
        app_state=owned
    else
        app_state=foreign
    fi
elif [ -e "$app_dir" ]; then
    app_state=foreign
else
    app_state=absent
fi
if [ "$app_state" = foreign ] && [ "$force" != 1 ]; then
    echo "Refusing to replace $app_dir: not created by this installation (use --force)." >&2
    blocked=1
fi

check_target "$bin_link"
if [ "$cli_installed" = 1 ]; then
    check_target "$cli_link"
fi
check_target "$desktop"
if [ "$icon_installed" = 1 ]; then
    check_target "$icon"
fi

if [ "$blocked" = 1 ]; then
    echo "Installation cancelled; nothing was changed." >&2
    exit 1
fi

if [ "$app_state" != absent ]; then
    remove_target "$app_dir"
fi

mkdir -p "$app_dir" "$prefix/bin" "$(dirname "$desktop")" "$(dirname "$icon")"
cp -R "$src/." "$app_dir/"

if [ -e "$bin_link" ] || [ -L "$bin_link" ]; then
    remove_target "$bin_link"
fi
ln -sf "$app_dir/bin/XenoBrex" "$bin_link"

if [ "$cli_installed" = 1 ]; then
    if [ -e "$cli_link" ] || [ -L "$cli_link" ]; then
        remove_target "$cli_link"
    fi
    ln -sf "$app_dir/bin/xbrex" "$cli_link"
elif [ -L "$cli_link" ] && symlink_into_install "$cli_link"; then
    rm -f "$cli_link"
fi

if [ "$icon_installed" = 1 ]; then
    if [ -e "$icon" ] || [ -L "$icon" ]; then
        remove_target "$icon"
    fi
    cp "$src/lib/XenoBrex.png" "$icon"
elif [ -L "$icon" ] && symlink_into_install "$icon"; then
    rm -f "$icon"
fi

if [ -e "$desktop" ] || [ -L "$desktop" ]; then
    remove_target "$desktop"
fi
cat > "$desktop" <<EOF
[Desktop Entry]
Type=Application
Name=XenoBrex
Comment=aRacer ECU tune file editor
Comment[zh_TW]=aRacer ECU 調校檔編輯器與即時調校
Exec="$app_dir/bin/XenoBrex" %f
Icon=xenobrex
Terminal=false
Categories=Utility;
StartupWMClass=xb-app-MainKt
EOF

write_manifest
refresh_menus

echo "XenoBrex installed:"
echo "  app       $app_dir"
echo "  command   $bin_link"
if [ "$cli_installed" = 1 ]; then
    echo "  cli       $cli_link (xbrex help; AI Agent guide: xbrex skill)"
fi
echo "  menu      $desktop"
echo "  manifest  $manifest"
case ":$PATH:" in
    *":$prefix/bin:"*) ;;
    *) echo "Note: $prefix/bin is not in PATH. Open XenoBrex from the menu or run $bin_link." ;;
esac
