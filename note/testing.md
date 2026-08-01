# 静态测试要点

> 建立：2026-07-30（2026-07-31 更新：新增第 10 维度“基准正确性”；1.2.0 基准等价性断言由 3 项扩至 5 项；
> 2026-08-01 更新：1.3.0 基准等价性断言由 5 项扩至 9 项）
> 脚本：`test/test.sh`（`bash test/test.sh` 运行）
> 结果：**10 维度 51 项，全部通过 (51/51)**

## 1. 测试脚本

- 位置：`test/test.sh`
- 运行：`bash test/test.sh`
- 自动定位 Maven（PATH 或 `~/apache-maven-3.9.16`）与 JAVA_HOME
- 每次运行都会重新 `mvn clean package` 编译，确保测试针对最新代码
- 退出码：0=全过，1=有失败（可接入 CI）

## 2. 十个测试维度

| # | 维度 | 覆盖要点 |
|---|---|---|
| 1 | 环境 | JDK 21 / Maven / `jar` 工具可用 |
| 2 | REF 完整性 | `.m2` 含 `Slimefun-4.9.5.jar`；内容条数 >1000；抽查 5 个关键类（SlimefunItem / SlimefunItemStack / BlockStorage / CustomItemStack / Juice） |
| 3 | 编译 | `mvn clean package -DskipTests` → BUILD SUCCESS |
| 4 | 产物 | `target/ExoticGarden vUNOFFICIAL.jar` 存在且 >100KB |
| 5 | pom.xml | Slimefun=`com.github.slimefun:Slimefun:4.9.5`；无 GuizhanLib/Fluffy 依赖；用 paper-api；无 spigot-api / SlimefunGuguProject 残留 |
| 6 | plugin.yml | `main` 正确；含 `api-version`；不依赖 GuizhanLibPlugin；不 softdepend FluffyMachines；depend Slimefun |
| 7 | jar 内容结构 | 含主类、`com/be` 包、17 个 `.schematic`、config.yml、storage.yml；**无** GuizhanLib/Fluffy/xzavier 类；bstats 已正确 shade/relocate |
| 8 | 源码迁移完整性 | 无 `new CustomItemStack(`、`Material.GRASS`、`Particle.VILLAGER_*`、`BlockDataController`、`StorageCacheUtils.`/`getBlockDataController` 调用、xzavier/GuizhanLib/Fluffy import |
| 9 | REF 兼容性 | 附属 **48 个** `io.github.thebusybiscuit.slimefun4.*` / `me.mrCookieSlime.*` import **逐一验证存在于 REF jar** |
| 10 | 基准正确性 | 运行 `benchmark/`，校验新旧算法**等价性断言全 PASS**（match / fits / 进度条门控 / SFItem 缓存 / 能量结算 / SlimefunTag / getItem 缓存 / getByItem 克隆，共 9 项；`Benchmark.main` 在任一断言失败时退出码 1） |

## 3. 关键设计：REF 兼容性检查（维度 9）

编译通过仅保证「符号可见」（编译期类路径有），**不保证运行时类实际存在**。为此新增该项：

1. 扫描 `src/main/java` 下所有 `import` 语句；
2. 过滤出 Slimefun 提供的包（`io.github.thebusybiscuit.slimefun4.*`、`me.mrCookieSlime.*`，含 shaded 的 `slimefun4.libraries.dough`）；
3. 把每个 import 的全限定名映射为 jar 内 class 路径（`.` → `/`，加 `.class`）；
4. 在 REF jar 的 `jar tf` 列表里逐一查找（兼容顶层类 `.class`、内部类 `$`、包目录 `/`）；
5. 任一缺失即报「运行时将 NoClassDefFound」。

当前 48 个 import 全部命中，REF 迁移在「类存在性」层面无遗漏。

## 4. 静态测试覆盖的迁移正确性

| 迁移点 | 由哪个维度保障 |
|---|---|
| CustomItemStack 构造 → `create` 工厂（179 处） | 维度 8（无 `new CustomItemStack(`）+ 编译通过 |
| SlimefunItemStack → `.item()`（192+ 处） | 编译通过（类型校验） |
| xzavier 存储 → 官方 BlockStorage | 维度 8（无 `StorageCacheUtils.`/`getBlockDataController`/`BlockDataController`/`com.xzavier0722`）+ 维度 7（jar 无 xzavier 类） |
| 移除 GuizhanLibPlugin（自动更新） | 维度 5/6/7/8（pom、plugin.yml、jar、源码均无） |
| FluffyMachines 浇水壶 → SF ID 字面量 | 维度 8（无 `FluffyItems`/`io.ncbpfluffybear`）+ 维度 7（jar 无 Fluffy 类） |
| paper 1.21 适配（Material.GRASS / Particle 改名） | 维度 8（无 `Material.GRASS` / `Particle.VILLAGER_*`） |
| 依赖仅粘液科技 | 维度 5（pom）+ 维度 6（plugin.yml）+ 维度 7（jar）三重校验 |

## 5. 测试边界（不覆盖）

静态测试覆盖：**编译正确性、产物结构、依赖剥离、API 迁移完整性、REF 类存在性**。

**不覆盖**（需动态/实机测试）：
- 插件随 Slimefun 在 1.21.11 服务器实际加载（onEnable 是否抛异常）
- 各功能运行时行为（植物生长、机器加工、醉酒系统等）
- SlimefunItemStack 委托语义、BlockStorage 同步 `store` 的运行时性能
- 与其它服务端插件的实际交互

这些需在真实 Paper 1.21.1+ 服务器（放入 REF 编译的 Slimefun jar + 附属 jar）上做冒烟测试与功能回归。
