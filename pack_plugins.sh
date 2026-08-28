#!/bin/bash
# ============================================================
# BiliLite 插件打包脚本
# 用法: ./pack_plugins.sh
# 把 plugins_examples/ 下每个插件目录打包成 zip(不含目录前缀),
# 产物输出到 plugins_examples/dist/。
# ============================================================
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/plugins_examples"
DIST="$SRC/dist"
mkdir -p "$DIST"

echo "打包插件到 $DIST ..."

# 用 zip(若有),否则退回 jar(JDK 自带)
pack_one() {
    local dir="$1" out="$2"
    if command -v zip >/dev/null 2>&1; then
        (cd "$dir" && zip -q -r "$out" .)
    else
        (cd "$dir" && jar cMf "$out" .)
    fi
}

for dir in "$SRC"/*/; do
    # 跳过 dist
    name=$(basename "$dir")
    if [ "$name" = "dist" ]; then continue; fi
    out="$DIST/$name.zip"
    rm -f "$out"
    pack_one "$dir" "$out"
    echo "  OK $name.zip"
done

echo "完成。插件 zip 列表:"
ls -la "$DIST"/*.zip 2>/dev/null || true