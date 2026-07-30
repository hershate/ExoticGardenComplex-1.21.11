#!/usr/bin/env bash
# =============================================================================
# ExoticGardenComplex 编译与静态测试脚本
# 适用: Windows Git Bash / Linux / macOS，需 JDK 21 与 Maven
# 用法: bash test/test.sh
# =============================================================================
set -u

# ---------- 路径与工具定位 ----------
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if command -v mvn >/dev/null 2>&1; then
    MVN=mvn
elif [ -x "$HOME/apache-maven-3.9.16/bin/mvn" ]; then
    MVN="$HOME/apache-maven-3.9.16/bin/mvn"
else
    echo "✗ 未找到 Maven（mvn 不在 PATH，且 \$HOME/apache-maven-3.9.16 不存在）"
    exit 1
fi

if [ -z "${JAVA_HOME:-}" ]; then
    if [ -x "/c/Program Files/Java/latest/jdk-21/bin/java.exe" ]; then
        export JAVA_HOME="/c/Program Files/Java/latest/jdk-21"
    else
        echo "✗ 未设置 JAVA_HOME 且无法自动定位 JDK"
        exit 1
    fi
fi
JAR_TOOL="$JAVA_HOME/bin/jar"

SETTINGS="$HOME/maven-settings.xml"
SARGS=()
[ -f "$SETTINGS" ] && SARGS=(-s "$SETTINGS")

# ---------- 计数 ----------
PASS=0; FAIL=0
ok()   { echo "  ✓ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ✗ $1"; FAIL=$((FAIL+1)); }
sect() { echo; echo "=== $1 ==="; }

# ---------- 1. 环境 ----------
sect "1. 环境检查"
if "$JAVA_HOME/bin/java" -version >/dev/null 2>&1; then
    ok "JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
else
    bad "JDK 不可用"; exit 1
fi
if "$MVN" -version >/dev/null 2>&1; then
    ok "Maven: $("$MVN" -version 2>&1 | head -1)"
else
    bad "Maven 不可用"; exit 1
fi

# ---------- 2. REF 已安装 ----------
sect "2. REF(Slimefun 4.9.5) 本地仓库"
REFJAR="$HOME/.m2/repository/com/github/slimefun/Slimefun/4.9.5/Slimefun-4.9.5.jar"
if [ -f "$REFJAR" ]; then
    ok "已安装: $REFJAR"
else
    bad "REF 未安装。请先执行: cd REF/Slimefun4.1 && $MVN \"${SARGS[@]}\" clean install -DskipTests"
    echo "  （后续测试依赖 REF，终止）"
    exit 1
fi

# ---------- 3. 编译附属 ----------
sect "3. 编译附属 (mvn clean package -DskipTests)"
if "$MVN" "${SARGS[@]}" clean package -DskipTests -B -q >/tmp/eg_build.log 2>&1; then
    ok "编译成功 (BUILD SUCCESS)"
else
    bad "编译失败，日志尾部:"
    tail -n 25 /tmp/eg_build.log | sed 's/^/      /'
    exit 1
fi

# ---------- 4. 产物 ----------
sect "4. 产物 jar"
JAR="target/ExoticGarden vUNOFFICIAL.jar"
if [ -f "$JAR" ]; then
    SIZE=$(stat -c%s "$JAR" 2>/dev/null || stat -f%z "$JAR")
    ok "产物存在: $JAR ($SIZE 字节)"
else
    bad "产物缺失: $JAR"; exit 1
fi

# ---------- 5. jar 内容静态检查 ----------
sect "5. jar 内容静态检查"
TMP="$(mktemp -d)"
"$JAR_TOOL" tf "$JAR" > "$TMP/jar.list" 2>/dev/null

# 5.1 含主类
if grep -q "io/github/thebusybiscuit/exoticgarden/ExoticGarden.class" "$TMP/jar.list"; then
    ok "含主类 ExoticGarden.class"
else
    bad "缺主类 ExoticGarden.class"
fi

# 5.2 不含已剥离的插件类
check_no_class() {
    local pkg="$1" name="$2"
    if grep -q "$pkg" "$TMP/jar.list"; then
        bad "jar 内仍含 $name 类 ($pkg)"
    else
        ok "jar 内无 $name 类"
    fi
}
check_no_class "net/guizhanss/"        "GuizhanLibPlugin"
check_no_class "io/ncbpfluffybear/"    "FluffyMachines"
check_no_class "com/xzavier0722/"      "xzavier0722 存储"

# 5.3 plugin.yml 声明
"$JAR_TOOL" xf "$JAR" plugin.yml -C > /dev/null 2>&1 || true
# jar xf 默认解到当前目录，改用临时目录
( cd "$TMP" && "$JAR_TOOL" xf "$ROOT/$JAR" plugin.yml ) 2>/dev/null || true
if [ -f "$TMP/plugin.yml" ]; then
    MAIN=$(grep -E "^main:" "$TMP/plugin.yml" | head -1)
    DEPEND=$(grep -A10 "^depend:" "$TMP/plugin.yml" | grep -E "Slimefun|GuizhanLibPlugin" | tr -d ' -')
    SOFT=$(grep -A10 "^softdepend:" "$TMP/plugin.yml" | grep -E "FluffyMachines" || true)
    [ -n "$MAIN" ] && ok "plugin.yml main: ${MAIN#main: }" || bad "plugin.yml 无 main"
    if grep -q "GuizhanLibPlugin" "$TMP/plugin.yml"; then
        bad "plugin.yml 仍 depend/softdepend GuizhanLibPlugin"
    else
        ok "plugin.yml 不再依赖 GuizhanLibPlugin"
    fi
    if grep -q "FluffyMachines" "$TMP/plugin.yml"; then
        bad "plugin.yml 仍 softdepend FluffyMachines"
    else
        ok "plugin.yml 不再 softdepend FluffyMachines"
    fi
    if grep -q "Slimefun" "$TMP/plugin.yml"; then
        ok "plugin.yml 依赖 Slimefun"
    else
        bad "plugin.yml 未依赖 Slimefun"
    fi
else
    bad "未从 jar 提取到 plugin.yml"
fi

# ---------- 6. 源码残留检查 ----------
sect "6. 源码残留禁用引用检查"
check_no_src() {
    local pat="$1" name="$2"
    if grep -rq "$pat" src/main/java 2>/dev/null; then
        bad "源码残留 $name ($pat):"; grep -rln "$pat" src/main/java | sed 's/^/      /'
    else
        ok "源码无 $name 引用"
    fi
}
check_no_src "com.xzavier0722"              "xzavier0722 存储"
check_no_src "net.guizhanss"                "GuizhanLibPlugin"
check_no_src "io.ncbpfluffybear"            "FluffyMachines (API)"
check_no_src "StorageCacheUtils\\."         "StorageCacheUtils 调用"
check_no_src "GuizhanUpdater"               "GuizhanUpdater"
check_no_src "FluffyItems"                  "FluffyItems"
check_no_src "getBlockDataController"       "getBlockDataController"

# ---------- 汇总 ----------
rm -rf "$TMP"
echo
echo "============================================================"
echo "  测试结果:  通过 $PASS   失败 $FAIL"
echo "============================================================"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
