#!/usr/bin/env bash
# =============================================================================
# ExoticGardenComplex 完整静态测试脚本
# 维度: 环境 / REF完整性 / 编译 / 产物 / pom配置 / plugin.yml /
#       jar内容结构 / 源码迁移完整性 / REF兼容性(import类全部存在于REF)
# 适用: Windows Git Bash / Linux / macOS，需 JDK 21 与 Maven
# 用法: bash test/test.sh
# =============================================================================
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# ---------- 工具定位 ----------
if command -v mvn >/dev/null 2>&1; then
    MVN=mvn
elif [ -x "$HOME/apache-maven-3.9.16/bin/mvn" ]; then
    MVN="$HOME/apache-maven-3.9.16/bin/mvn"
else
    echo "✗ 未找到 Maven"; exit 1
fi
if [ -z "${JAVA_HOME:-}" ]; then
    if [ -x "/c/Program Files/Java/latest/jdk-21/bin/java.exe" ]; then
        export JAVA_HOME="/c/Program Files/Java/latest/jdk-21"
    else
        echo "✗ 未设置 JAVA_HOME 且无法定位 JDK"; exit 1
    fi
fi
JAR_TOOL="$JAVA_HOME/bin/jar"
JAVA="$JAVA_HOME/bin/java"
SETTINGS="$HOME/maven-settings.xml"
SARGS=(); [ -f "$SETTINGS" ] && SARGS=(-s "$SETTINGS")

REFJAR="$HOME/.m2/repository/com/github/slimefun/Slimefun/4.9.5/Slimefun-4.9.5.jar"
ADDONJAR="target/ExoticGardenComplex-1.21.11-1.2.0.jar"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

# ---------- 计数 ----------
PASS=0; FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS+1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL+1)); }
sect(){ echo; echo "=== $1 ==="; }

# =====================================================================
sect "1. 环境检查"
# =====================================================================
"$JAVA" -version >/dev/null 2>&1 && ok "JDK: $("$JAVA" -version 2>&1 | head -1)" || { bad "JDK 不可用"; exit 1; }
"$MVN" -version >/dev/null 2>&1 && ok "Maven: $("$MVN" -version 2>&1 | head -1)" || { bad "Maven 不可用"; exit 1; }
[ -x "$JAR_TOOL" ] && ok "jar 工具可用" || bad "jar 工具不可用"

# =====================================================================
sect "2. REF(Slimefun 4.9.5) 安装与完整性"
# =====================================================================
if [ -f "$REFJAR" ]; then
    ok "REF jar 已安装"
else
    bad "REF 未安装。请先: cd REF/Slimefun4.1 && $MVN ${SARGS[*]} clean install -DskipTests"
    exit 1
fi
"$JAR_TOOL" tf "$REFJAR" > "$TMP/ref.list" 2>/dev/null
REF_CLASSES=$(wc -l < "$TMP/ref.list")
[ "$REF_CLASSES" -gt 1000 ] && ok "REF 内容完整 ($REF_CLASSES 条)" || bad "REF 内容异常 ($REF_CLASSES 条)"
# 抽查 REF 必备的关键类（附属迁移后依赖）
for c in \
    io/github/thebusybiscuit/slimefun4/api/items/SlimefunItem \
    io/github/thebusybiscuit/slimefun4/api/items/SlimefunItemStack \
    me/mrCookieSlime/Slimefun/api/BlockStorage \
    io/github/thebusybiscuit/slimefun4/libraries/dough/items/CustomItemStack \
    io/github/thebusybiscuit/slimefun4/implementation/items/food/Juice ; do
    grep -q "^${c}\.class$" "$TMP/ref.list" && ok "REF 含 ${c##*/}" || bad "REF 缺 ${c##*/}"
done

# =====================================================================
sect "3. 编译附属 (mvn clean package -DskipTests)"
# =====================================================================
if "$MVN" "${SARGS[@]}" clean package -DskipTests -B -q >/tmp/eg_build.log 2>&1; then
    ok "编译成功 (BUILD SUCCESS)"
else
    bad "编译失败，日志尾部:"; tail -n 25 /tmp/eg_build.log | sed 's/^/      /'; exit 1
fi

# =====================================================================
sect "4. 产物 jar"
# =====================================================================
if [ -f "$ADDONJAR" ]; then
    SIZE=$(stat -c%s "$ADDONJAR" 2>/dev/null || stat -f%z "$ADDONJAR")
    ok "产物存在: $ADDONJAR ($SIZE 字节)"
else
    bad "产物缺失: $ADDONJAR"; exit 1
fi
[ "$SIZE" -gt 100000 ] && ok "产物大小合理" || bad "产物异常小"

"$JAR_TOOL" tf "$ADDONJAR" > "$TMP/addon.list" 2>/dev/null

# =====================================================================
sect "5. pom.xml 配置"
# =====================================================================
grep -q "<artifactId>Slimefun</artifactId>" pom.xml && grep -q "<groupId>com.github.slimefun</groupId>" pom.xml \
    && ok "pom: Slimefun 依赖为 com.github.slimefun:Slimefun (REF)" || bad "pom: Slimefun 坐标非 REF"
grep -q "<version>4.9.5</version>" pom.xml && ok "pom: Slimefun 版本 4.9.5" || bad "pom: Slimefun 版本非 4.9.5"
grep -qi "guizhanlibplugin" pom.xml && bad "pom: 仍含 GuizhanLibPlugin 依赖" || ok "pom: 无 GuizhanLibPlugin 依赖"
grep -qi "fluffymachines" pom.xml && bad "pom: 仍含 FluffyMachines 依赖" || ok "pom: 无 FluffyMachines 依赖"
grep -q "paper-api" pom.xml && ok "pom: 使用 paper-api" || bad "pom: 未使用 paper-api"
grep -q "spigot-api" pom.xml && bad "pom: 仍残留 spigot-api" || ok "pom: 无 spigot-api 残留"
grep -q "SlimefunGuguProject" pom.xml && bad "pom: 仍残留 SlimefunGuguProject" || ok "pom: 无 SlimefunGuguProject 残留"

# =====================================================================
sect "6. plugin.yml (jar 内)"
# =====================================================================
( cd "$TMP" && "$JAR_TOOL" xf "$ROOT/$ADDONJAR" plugin.yml ) 2>/dev/null
if [ -f "$TMP/plugin.yml" ]; then
    grep -q "^main: io.github.thebusybiscuit.exoticgarden.ExoticGarden" "$TMP/plugin.yml" \
        && ok "main 类正确" || bad "main 类不正确"
    grep -Eq "^api-version:" "$TMP/plugin.yml" && ok "含 api-version" || bad "缺 api-version"
    if grep -q "GuizhanLibPlugin" "$TMP/plugin.yml"; then
        bad "plugin.yml 仍依赖 GuizhanLibPlugin"
    else
        ok "plugin.yml 不依赖 GuizhanLibPlugin"
    fi
    if grep -q "FluffyMachines" "$TMP/plugin.yml"; then
        bad "plugin.yml 仍 softdepend FluffyMachines"
    else
        ok "plugin.yml 不 softdepend FluffyMachines"
    fi
    grep -Eq "^depend:|^  - Slimefun" "$TMP/plugin.yml" && ok "plugin.yml depend Slimefun" || bad "plugin.yml 未 depend Slimefun"
else
    bad "未从 jar 提取到 plugin.yml"
fi

# =====================================================================
sect "7. jar 内容结构"
# =====================================================================
# 主类与附属包
grep -q "io/github/thebusybiscuit/exoticgarden/ExoticGarden.class" "$TMP/addon.list" && ok "含主类 ExoticGarden" || bad "缺主类"
grep -q "com/be/" "$TMP/addon.list" && ok "含 com/be 包(BEPlugin)" || bad "缺 com/be 包"
# 资源
SCHEM=$("$JAR_TOOL" tf "$ADDONJAR" | grep -c '\.schematic$')
[ "$SCHEM" -ge 15 ] && ok "含 $SCHEM 个 schematic 资源" || bad "schematic 资源不足 ($SCHEM)"
grep -q "^config.yml$" "$TMP/addon.list" && ok "含 config.yml" || bad "缺 config.yml"
grep -q "^storage.yml$" "$TMP/addon.list" && ok "含 storage.yml" || bad "缺 storage.yml"
# 已剥离的插件类不应出现在附属 jar
check_no_in_jar() {
    local pkg="$1" name="$2"
    grep -q "$pkg" "$TMP/addon.list" && bad "jar 内仍含 $name ($pkg)" || ok "jar 内无 $name"
}
check_no_in_jar "net/guizhanss/"     "GuizhanLibPlugin"
check_no_in_jar "io/ncbpfluffybear/" "FluffyMachines"
check_no_in_jar "com/xzavier0722/"   "xzavier0722 存储"
# 匿名统计(bstats)已移除：jar 内不应再含 bstats 类（org/bstats 或 relocated 包）
check_no_in_jar "org/bstats/" "bstats"
check_no_in_jar "io/github/thebusybiscuit/exoticgarden/bstats/" "bstats(relocated)"

# =====================================================================
sect "8. 源码迁移完整性"
# =====================================================================
check_no_src() {
    local pat="$1" name="$2" mode="${3:-E}"
    if grep "-$mode" -rq -- "$pat" src/main/java 2>/dev/null; then
        bad "源码残留 $name:"; grep "-$mode" -rln -- "$pat" src/main/java | sed 's/^/      /'
    else
        ok "源码无 $name"
    fi
}
check_no_src "com\.xzavier0722"           "xzavier0722 import"
check_no_src "net\.guizhanss"             "GuizhanLib import"
check_no_src "io\.ncbpfluffybear"         "FluffyMachines import"
check_no_src "StorageCacheUtils\."        "StorageCacheUtils 调用"
check_no_src "GuizhanUpdater"             "GuizhanUpdater"
check_no_src "FluffyItems"                "FluffyItems"
check_no_src "getBlockDataController"     "getBlockDataController 调用"
check_no_src "new CustomItemStack\("      "new CustomItemStack(应已全改为 create)"
check_no_src "Material\.GRASS\b"          "Material.GRASS(应为 SHORT_GRASS)" E
check_no_src "Particle\.VILLAGER_ANGRY"   "Particle.VILLAGER_ANGRY(应为 ANGRY_VILLAGER)"
check_no_src "Particle\.VILLAGER_HAPPY"   "Particle.VILLAGER_HAPPY(应为 HAPPY_VILLAGER)"
check_no_src "BlockDataController"        "BlockDataController(xzavier)"
check_no_src "org.bstats"                 "bstats 引用(匿名统计已移除)"
check_no_src "new Metrics("               "Metrics 实例化(匿名统计已移除)"

# =====================================================================
sect "9. REF 兼容性 (附属 import 的 Slimefun 类须全部存在于 REF)"
# =====================================================================
MISSING=0; TOTAL=0
while IFS= read -r imp; do
    # imp: io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem
    cls_path=$(echo "$imp" | tr '.' '/')
    TOTAL=$((TOTAL+1))
    if grep -q "^${cls_path}\.class$" "$TMP/ref.list" \
        || grep -q "^${cls_path}"'$' "$TMP/ref.list" \
        || grep -q "^${cls_path}/" "$TMP/ref.list"; then
        :
    else
        echo "      缺失: $imp"
        MISSING=$((MISSING+1))
    fi
done < <(grep -rhE "^import " src/main/java \
    | sed -E 's/^import (static )?//;s/;$//' \
    | grep -E "^(io\.github\.thebusybiscuit\.slimefun4|me\.mrCookieSlime)" \
    | grep -vE "\*$" \
    | sort -u)
if [ "$MISSING" -eq 0 ]; then
    ok "REF 兼容: $TOTAL 个 Slimefun/mrCookieSlime import 全部存在于 REF jar"
else
    bad "REF 兼容: $MISSING/$TOTAL import 在 REF jar 中缺失(运行时将 NoClassDefFound)"
fi

# =====================================================================
sect "10. 基准测试正确性（benchmark/）"
# =====================================================================
# 离线基准（benchmark/）的新旧算法等价性断言（match / fits）。Benchmark.main 在任一
# 等价性断言失败时 System.exit(1)，故退出码 0 即代表全部 PASS（语言无关，避免中文 grep）。
if bash benchmark/run.sh > "$TMP/bench.log" 2>&1; then
    ok "基准等价性断言全 PASS（match/fits，优化前后行为一致）"
else
    bad "基准运行失败或等价性断言未通过"
    tail -n 20 "$TMP/bench.log" | sed 's/^/      /'
fi

# =====================================================================
echo
echo "================================================================"
echo "  完整静态测试结果:   通过 $PASS   失败 $FAIL"
echo "================================================================"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
