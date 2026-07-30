#!/usr/bin/env bash
# =============================================================================
# ExoticGardenComplex 算法层基准测试运行器
# 纯 Java、无 Bukkit 依赖。用 JDK 21 编译 benchmark/src 下源码并运行 Benchmark。
#
# 用法：
#   bash benchmark/run.sh            # 编译 + 运行，结果打印到 stdout
#   bash benchmark/run.sh save       # 同上，并把结果保存到 benchmark/result.txt
# =============================================================================
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# 定位 JDK 21
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JH="$JAVA_HOME"
elif [ -x "/c/Program Files/Java/latest/jdk-21/bin/javac" ]; then
    JH="/c/Program Files/Java/latest/jdk-21"
else
    echo "✗ 未找到 JDK（需 JDK 21）。请设置 JAVA_HOME。"; exit 1
fi
JAVAC="$JH/bin/javac"
JAVA="$JH/bin/java"

SRC="benchmark/src"
OUT="benchmark/out"
mkdir -p "$OUT"

echo "--- 编译基准源码 ---"
if ! "$JAVAC" -d "$OUT" "$SRC"/*.java 2>&1; then
    echo "✗ 编译失败"; exit 1
fi
echo "编译完成。"
echo

echo "--- 运行基准 ---"
# 直接以 UTF-8 输出，避免经 bash 变量中转破坏多字节字符。
JAVA_OPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
if [ "${1:-}" = "save" ]; then
    {
        echo "# ExoticGardenComplex 基准结果（UTF-8）"
        echo "# 主机 processors: $(nproc 2>/dev/null || echo n/a)"
        echo
        "$JAVA" $JAVA_OPTS -cp "$OUT" Benchmark
        RC=$?
    } > benchmark/result.txt 2>&1
    cat benchmark/result.txt
    echo
    echo "已保存到 benchmark/result.txt"
else
    "$JAVA" $JAVA_OPTS -cp "$OUT" Benchmark
    RC=$?
fi

exit $RC
