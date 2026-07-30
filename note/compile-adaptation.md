# ExoticGardenComplex 编译适配分析（依赖粘液科技 REF / 4.9.5）

> 建立日期：2026-07-30
> 目的：使附属编译（及运行时）所依赖的"插件"仅为粘液科技，来源为本仓库 `REF/Slimefun4.1`（等价于远程 `hershate/Slimefun4.1`）。

---

## 1. 依赖来源确认（结论）

- `REF/Slimefun4.1` 与远程仓库 `https://github.com/hershate/Slimefun4.1`（`experimental` 分支）**完全同源**：
  - pom 坐标一致：`com.github.slimefun:Slimefun:4.9.5`（`<name>SlimeFun4.1</name>`）
  - 源码包结构一致，二者都**不含** `com.xzavier0722` 存储包
  - 二者都通过 `maven-shade-plugin` 引入 dough 并 relocate 到 `io.github.thebusybiscuit.slimefun4.libraries.dough`
- 故"REF 本地源码"与"hershate 仓库"二选一等价，**推荐用 REF 本地源码**（离线、可控、用户已指定）。
- 目标 Minecraft：1.21.1 ~ 1.21.11；`paper-api 1.21.1` provided；Java 16。

> ⚠️ REF 是只读参考，**不得修改**。所有适配改动只发生在附属自身代码（`src/`、`pom.xml`、`plugin.yml`）。

---

## 2. 附属原依赖（pom.xml 现状）

| 依赖 | 坐标 | scope | 性质 | 目标 |
|---|---|---|---|---|
| Slimefun | `com.github.SlimefunGuguProject:Slimefun4:b39097e015` | provided | **插件（汉化版）** | 换成 REF（4.9.5） |
| GuizhanLibPlugin | `net.guizhanss:GuizhanLibPlugin:1.3.4` | provided | **插件** | **移除** |
| FluffyMachines | `com.github.NCBPFluffyBear:FluffyMachines:79408746ca` | provided | **插件** | **移除** |
| spigot-api | `org.spigotmc:spigot-api:1.19.2` | provided | 服务端 API | 升级为 paper-api 1.21.1（与 REF 一致） |
| jsr305 | `com.google.code.findbugs:jsr305:3.0.2` | provided | 库 | 保留 |
| bstats-bukkit | `org.bstats:bstats-bukkit:2.2.1` | compile | 库 | 保留 |
| annotations | `org.jetbrains:annotations:24.0.0` | compile | 库 | 保留 |

> 关键差异：附属原依赖的 **SlimefunGuguProject 汉化版**集成了 xzavier0722 存储系统；而 REF（官方 `experimental` 分支 4.9.5）**不含**该存储系统。这是不兼容的根源。

---

## 3. 关键 API 冲突：xzavier0722 存储系统（必须改附属源码）

附属使用的 xzavier 存储类，在 REF 中**不存在**，必须替换为 REF 官方 `me.mrCookieSlime.Slimefun.api.BlockStorage` API：

| 附属当前调用（xzavier） | REF 官方等价（待动手时精确核签） | 出现位置 |
|---|---|---|
| `StorageCacheUtils.getSfItem(loc)` | `BlockStorage.check(loc)` | ExoticGarden、PlantsListener、ChargeableBlock |
| `StorageCacheUtils.hasBlock(loc)` | `BlockStorage.hasBlockInfo(loc)` | 多处 |
| `StorageCacheUtils.getData(loc, key)` | `BlockStorage.getLocationInfo(loc, key)` | ChargeableBlock |
| `StorageCacheUtils.setData(loc, key, val)` | `BlockStorage.addBlockInfo(loc, key, val)` | ChargeableBlock |
| `StorageCacheUtils.getBlock(loc)` → `SlimefunBlockData` | 需重构（REF 无 SlimefunBlockData） | ChargeableBlock |
| `Slimefun.getDatabaseManager().getBlockDataController().removeBlock(loc)` | `BlockStorage.clearBlockInfo(loc)` | ExoticGarden、PlantsListener、Schematic、ChargeableBlock |
| `BlockDataController`（Schematic.paste 异步） | 需重构为同步 `BlockStorage.store` 或 REF 对应机制 | Schematic |

涉及文件：
- `ChargeableBlock.java`（充能方块读写 energy-charge/energy-capacity，改动较大）
- `ExoticGarden.java`（harvestPlant/harvestFruit、removeBlock）
- `listeners/PlantsListener.java`（多处 removeBlock）
- `schematics/Schematic.java`（pasteSchematic 用 BlockDataController）

REF 官方 BlockStorage 已确认存在的公开方法：`store` / `retrieve` / `check` / `getLocationInfo` / `addBlockInfo` / `hasBlockInfo` / `clearBlockInfo` 等。

---

## 4. GuizhanLibPlugin 依赖（需移除）

- 源码引用仅 1 处：`ExoticGarden.java:78`
  `import net.guizhanss.guizhanlibplugin.updater.GuizhanUpdater;`
  → `onEnable` 中 `GuizhanUpdater.start(...)`（自动更新）
- `onEnable` 启动时强校验 `isPluginEnabled("GuizhanLibPlugin")`，缺失则禁用自身
- `plugin.yml`：`depend: [Slimefun, GuizhanLibPlugin]`
- `pom.xml`：`net.guizhanss:GuizhanLibPlugin:1.3.4` provided
- 移除后：自动更新功能消失（影响小）；需删除 import / 调用 / 启动校验 / plugin.yml depend / pom 依赖

---

## 5. FluffyMachines 依赖（需移除）

- **硬 API 引用**：`listeners/PlantsListener.java:61`
  `import io.ncbpfluffybear.fluffymachines.utils.FluffyItems;`
  → `FluffyItems.WATERING_CAN.getItemId()`（浇水壶识别，约 line 99）
- `ExoticGarden.java`：仅字符串判断 `isPluginEnabled("FluffyMachines")` + `fluffy` 标志（不引用 API，无编译依赖）
- `plugin.yml`：`softdepend: [..., FluffyMachines]`
- `pom.xml`：`com.github.NCBPFluffyBear:FluffyMachines` provided
- 移除后需决策：①彻底移除浇水催熟功能；或 ②用物品 displayName/lore 字符串判断保留（无 FluffyMachines 时失效，但不破坏编译）

---

## 6. 编译依赖接入方案（候选）

- **方案 A（推荐）**：`cd REF/Slimefun4.1 && mvn clean install -DskipTests` 装入本地 `~/.m2`，附属 pom 以 `com.github.slimefun:Slimefun:4.9.5` provided 引用。标准、可复现。
- 方案 B：附属 pom 用 `system` scope 指向 REF 编译产物 jar（不推荐，移植性差）。
- 方案 C：JitPack 拉 `com.github.hershate:Slimefun4.1`（与 REF 等价，但依赖外部构建）。

> 方案 A 前置：需能编译 REF（其依赖 dough `cb22e71335`、paper-api 1.21.1 等均来自 jitpack / papermc / maven-central，需网络可达）。

---

## 7. 改动清单（待确认后执行）

1. `pom.xml`：替换 Slimefun 依赖为 REF 坐标；移除 GuizhanLibPlugin、FluffyMachines；spigot-api→paper-api 1.21.1。
2. `plugin.yml`：`depend` 仅留 `Slimefun`；`softdepend` 去掉 `FluffyMachines`。
3. `ExoticGarden.java`：移除 GuizhanUpdater；移除 GuizhanLibPlugin 启动校验；移除 fluffy 相关；替换 xzavier removeBlock。
4. `listeners/PlantsListener.java`：移除 FluffyItems 引用（浇水功能按决策处理）；替换 xzavier removeBlock。
5. `ChargeableBlock.java`：重构 xzavier 存储读写为官方 BlockStorage（改动最大）。
6. `schematics/Schematic.java`：重构 BlockDataController 异步 paste 为 REF 机制。
7. 全量 `mvn clean package` 验证编译通过。
