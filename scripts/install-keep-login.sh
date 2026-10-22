#!/usr/bin/env bash
# 安装 debug APK 并保留登录态。
#
# 签名一致时直接 `install -r` 覆盖安装（数据天然保留）；签名不一致时 Android 不允许
# 覆盖安装，只能卸载重装——此时先把应用数据 tar 出来，装完再灌回去。
#
# 依赖：adb + 应用为 debuggable（用 run-as 读写私有目录，无需 root）。
#
# 用法：
#   scripts/install-keep-login.sh install [-s <serial>] [-k] <apk>
#   scripts/install-keep-login.sh restore [-s <serial>] <apk> <数据备份.tar>
#   scripts/install-keep-login.sh backup  [-s <serial>] [包名]
#
#   -k  卸载前顺带把设备上的现有 APK 拉一份下来（回退时才有包可装）
set -euo pipefail

ADB_BIN="${ADB:-adb}"
SERIAL=""
SAVE_APK=0
PKG_DEFAULT="dev.frost819.newbv.debug"
BACKUP_DIR="${BACKUP_DIR:-$HOME/newbv-install-backup}"
# 需要保全的私有目录：Room + DataStore(登录凭证) + SharedPreferences
DATA_PATHS="databases files shared_prefs"
TMP_REMOTE="/data/local/tmp/newbv-data.tar"

# Windows/Git Bash 下 adb 会把远端路径改写成 Windows 路径，必须关闭转换
case "${OSTYPE:-}" in
  mingw* | msys* | cygwin*) export MSYS_NO_PATHCONV=1 ;;
esac

ARGS=()

die() {
  echo "错误：$*" >&2
  exit 1
}

usage() {
  sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
}

# 主机路径转成 adb（Windows 原生程序）能识别的形式
to_host() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

A() {
  if [ -n "$SERIAL" ]; then "$ADB_BIN" -s "$SERIAL" "$@"; else "$ADB_BIN" "$@"; fi
}

find_aapt() {
  if [ -n "${AAPT:-}" ]; then
    printf '%s' "$AAPT"
    return
  fi
  local sdk best
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-D:/AndroidSdk}}"
  # 只要 aapt（v1，有 dump badging），不能匹配到 aapt2
  best="$(find "$sdk/build-tools" -maxdepth 2 \( -name aapt -o -name aapt.exe \) 2>/dev/null | sort -V | tail -1)"
  [ -n "$best" ] || die "找不到 aapt，请设置 ANDROID_HOME 或 AAPT"
  printf '%s' "$best"
}

apk_pkg() {
  "$(find_aapt)" dump badging "$(to_host "$1")" 2>/dev/null |
    sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1
}

installed() {
  local out
  out="$(A shell "pm path $1" 2>/dev/null | tr -d '\r')"
  [ -n "$out" ]
}

device_apk_path() {
  A shell "pm path $1" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1
}

current_uid() {
  A shell "pm list packages -U $1" 2>/dev/null | tr -d '\r' |
    sed -n 's/.*uid:\([0-9]*\).*/\1/p' | head -1
}

current_version() {
  A shell "dumpsys package $1" 2>/dev/null | tr -d '\r' |
    sed -n 's/^[[:space:]]*versionName=\([^ ]*\).*/\1/p' | head -1
}

stop() { A shell "am force-stop $1" >/dev/null 2>&1 || true; }

# run-as 打 tar 流到主机；只打包设备上实际存在的目录
backup_data() {
  local pkg="$1" out="$2" listing dirs d
  # 非 tty 下 ls 用空格分隔多项输出，归一化成每行一项
  listing="$(A exec-out "run-as $pkg ls /data/data/$pkg" 2>/dev/null | tr -d '\r' | tr -s ' \t' '\n')" || return 1
  dirs=""
  for d in $DATA_PATHS; do
    if printf '%s\n' "$listing" | grep -qx "$d"; then
      dirs="$dirs $d"
    fi
  done
  [ -n "${dirs# }" ] || {
    echo "  设备上无数据目录"
    return 1
  }
  A exec-out "run-as $pkg tar c -C /data/data/$pkg $dirs" >"$out"
  [ -s "$out" ] || {
    rm -f "$out"
    return 1
  }
  echo "  已备份数据：$out（$(wc -c <"$out") 字节）"
}

# 先拉设备上的现有 APK，否则卸载后就没有回退材料了（签名私钥往往不在本机）
backup_apk() {
  local pkg="$1" ver="$2" dest remote
  remote="$(device_apk_path "$pkg")"
  [ -n "$remote" ] || return 0
  dest="$BACKUP_DIR/$pkg-${ver:-unknown}.apk"
  A pull "$remote" "$(to_host "$dest")" >/dev/null 2>&1 &&
    echo "  已留存现有 APK：$dest"
  return 0
}

# 还原：run-as 写出的文件天然属于新 uid、SELinux 标签也正确，无需 chown/restorecon
restore_data() {
  local pkg="$1" tar="$2" d
  A push "$(to_host "$tar")" "$TMP_REMOTE" >/dev/null
  A shell "chmod 644 $TMP_REMOTE" >/dev/null
  # 清掉安装器建好的空目录，避免与备份内容混叠
  for d in $DATA_PATHS; do
    A shell "run-as $pkg sh -c 'rm -rf /data/data/$pkg/$d'" >/dev/null
  done
  A shell "run-as $pkg sh -c 'cd /data/data/$pkg && tar xf $TMP_REMOTE'"
  A shell "rm -f $TMP_REMOTE" >/dev/null
  echo "  已还原数据（uid $(current_uid "$pkg")）"
}

launch() {
  local pkg="$1" cat
  # resolve-activity 在 TV 包/无匹配时会返回系统 ResolverActivity，不可靠；用 monkey 注入启动
  for cat in android.intent.category.LAUNCHER android.intent.category.LEANBACK_LAUNCHER; do
    if A shell "monkey -p $pkg -c $cat 1" 2>&1 | grep -q 'Events injected'; then
      echo "  已启动（$cat）"
      return 0
    fi
  done
  echo "  启动失败，请手动打开应用"
}

# 校验：备份里的 DataStore 与设备上的逐字节是否一致
verify() {
  local pkg="$1" tar="$2" pb saved live
  pb="$(tar tf "$tar" 2>/dev/null | grep -m1 'datastore/.*\.preferences_pb$')" || true
  if [ -z "${pb:-}" ]; then
    echo "  校验跳过：备份里没有 DataStore 文件"
    return
  fi
  saved="$(tar xO -f "$tar" "$pb" | md5sum | cut -d' ' -f1)"
  live="$(A exec-out "run-as $pkg cat /data/data/$pkg/$pb" | md5sum | cut -d' ' -f1)"
  if [ "$saved" = "$live" ]; then
    echo "  校验通过：$pb 与备份一致（md5 ${saved:0:8}…）"
  else
    echo "  ⚠ 校验不一致：备份 $saved / 现场 $live"
  fi
}

cmd_backup() {
  local pkg="$1" ver
  installed "$pkg" || die "$pkg 未安装"
  mkdir -p "$BACKUP_DIR"
  stop "$pkg"
  ver="$(current_version "$pkg")"
  backup_apk "$pkg" "$ver"
  backup_data "$pkg" "$BACKUP_DIR/$pkg-${ver:-unknown}-$(date +%Y%m%d-%H%M%S).tar" ||
    die "备份失败"
}

# 覆盖安装；签名不一致时退化为 备份 → 卸载 → 安装 → 还原
cmd_install() {
  local apk="$1" pkg ver out tar
  pkg="$(apk_pkg "$apk")"
  [ -n "$pkg" ] || die "读不出包名：$apk"
  mkdir -p "$BACKUP_DIR"
  stop "$pkg"

  if ! installed "$pkg"; then
    echo "$pkg 未安装，直接安装"
    out="$(A install "$(to_host "$apk")" 2>&1 || true)"
    printf '%s' "$out" | grep -q Success || die "安装失败：$out"
    launch "$pkg"
    return
  fi

  ver="$(current_version "$pkg")"
  echo "设备现有：$pkg $ver；目标 APK：$(basename "$apk")"

  # -d 允许 versionCode 降级；签名一致时数据原样保留
  out="$(A install -r -d "$(to_host "$apk")" 2>&1 || true)"
  if printf '%s' "$out" | grep -q Success; then
    echo "  签名一致，已覆盖安装（数据未动）"
    launch "$pkg"
    return
  fi
  printf '%s' "$out" | grep -qiE 'UPDATE_INCOMPATIBLE|signatures do not match' ||
    die "安装失败：$(printf '%s' "$out" | tail -2)"

  echo "  签名不一致，走 备份 → 卸载 → 安装 → 还原"
  if [ "$SAVE_APK" = 1 ]; then backup_apk "$pkg" "$ver"; fi
  tar="$BACKUP_DIR/$pkg-$ver-$(date +%Y%m%d-%H%M%S).tar"
  backup_data "$pkg" "$tar" || die "备份失败，已中止（尚未卸载）"
  A uninstall "$pkg" >/dev/null || die "卸载失败，用 '$0 restore' 之前请勿再启动应用"
  out="$(A install -r -d "$(to_host "$apk")" 2>&1 || true)"
  printf '%s' "$out" | grep -q Success ||
    die "安装失败！用 '$0 restore <旧apk> $tar' 还原"
  restore_data "$pkg" "$tar"
  verify "$pkg" "$tar"
  launch "$pkg"
  echo "完成。回退：$0 restore <上一个版本的 apk> $tar"
}

cmd_restore() {
  local apk="$1" tar="$2" pkg out
  [ -f "$tar" ] || die "备份文件不存在：$tar"
  pkg="$(apk_pkg "$apk")"
  [ -n "$pkg" ] || die "读不出包名：$apk"
  stop "$pkg"
  A uninstall "$pkg" >/dev/null 2>&1 || true
  out="$(A install -r -d "$(to_host "$apk")" 2>&1 || true)"
  printf '%s' "$out" | grep -q Success || die "安装失败：$(printf '%s' "$out" | tail -2)"
  restore_data "$pkg" "$tar"
  verify "$pkg" "$tar"
  launch "$pkg"
}

[ $# -ge 1 ] || {
  usage
  exit 1
}
SUBCMD="$1"
shift
while [ $# -gt 0 ]; do
  case "$1" in
    -s)
      [ $# -ge 2 ] || die "-s 需要参数"
      SERIAL="$2"
      shift 2
      ;;
    -k)
      SAVE_APK=1
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*) die "未知选项：$1" ;;
    *)
      ARGS+=("$1")
      shift
      ;;
  esac
done
while [ $# -gt 0 ]; do
  ARGS+=("$1")
  shift
done

case "$SUBCMD" in
  -h | --help | help)
    usage
    ;;
  backup)
    cmd_backup "${ARGS[0]:-$PKG_DEFAULT}"
    ;;
  install)
    [ ${#ARGS[@]} -ge 1 ] || {
      usage
      exit 1
    }
    cmd_install "${ARGS[0]}"
    ;;
  restore)
    [ ${#ARGS[@]} -ge 2 ] || {
      usage
      exit 1
    }
    cmd_restore "${ARGS[0]}" "${ARGS[1]}"
    ;;
  *)
    usage
    exit 1
    ;;
esac
