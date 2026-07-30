# 性能优化对比报告（1.0.0 → 1.1.0）

> 日期：2026-07-31
> 分支：`feature/perf-optimization`
> 红线：不降低安全性 / 稳定性 / 兼容性；不引入新漏洞；玩家可见行为不变。
> 判据：`mvn clean package -DskipTests` → BUILD SUCCESS；静态测试全绿；离线基准量化前后差异。

## 一、背景

1.0.0 完成三轮安全/稳定性加固后，运行时开销有所上升（并发容器、异步回主线程调度、
每 tick 重复匹配、每事件重复配置读取等）。本报告量化并优化本插件**自身算法层**的热点，
不触碰 Slimefun/Paper 内部。

## 二、方法学

### 为何用离线基准而非实机

生产热点依赖运行中的 Paper 服务端：`ItemStack` / `SlimefunUtils.isItemSimilar` /
`BlockMenu` / `BlockStorage.check` 均无法脱离服务器实例化或执行（paper-api 单 jar 还依赖
adventure 等传递依赖；项目本身亦未做实机运行回归）。因此采用**忠实复刻**策略：

- 用最小但“形似”的物品模型 `SimItem`（material + sfId + 派生 meta + amount）复刻相关算法；
- `Algorithms.old*` 严格复刻**优化前生产代码**的数据结构与分配模式；
- `Algorithms.new*` 复刻**优化后生产代码**；
- 在相同输入下计时，并以 20 万级随机序列断言两者输出**完全一致**（正确性）。

代码见 `benchmark/`，可由 `bash benchmark/run.sh` 复现。

### 局限（必须如实说明）

- `SimItem.isSimilar` 的工作量是真实 `SlimefunUtils.isItemSimilar` 的**近似**（按 SF id 比较
  + 少量字段），真实绝对耗时会更高；
- 但**新旧相对比值可迁移**到生产——因为生产改动与基准的新旧对照在循环结构、数据结构、
  分配模式、调用次数上完全对应；
- 本报告的“加速倍数”是**算法层**的相对提升，不等同于整服 TPS 提升（后者还需实机回归）。

## 三、优化清单

### 1. 机器配方匹配（DefaultGUI / ThreeInputGUI.tick，每机器每 tick）

| 改动 | 说明 |
|---|---|
| 输入签名缓存 | 输入（引用 + 数量）未变时跳过全量配方匹配；引用精确比对，**零误命中**；命中后 `deriveConsume` 重建消耗。稳态 idle 机器（常态）从“每 tick 全扫”降为“O(1) 缓存命中”。 |
| 缓存槽位常量 | tick 内部用 `static final int[]`，不再每次 `new int[]{}`（公开方法仍返回新数组，行为不变） |
| 复用 BlockMenu | fits/push 直接用已取的 menu，不再重复 `BlockStorage.getInventory` |
| 单次解析 SF 物品 | 原 `ChargeableBlock.isChargeable/getCharge/addCharge` 各查一次 `BlockStorage.check`（3 次），合并为 1 次 |
| `int[]` 替代 `HashMap` | `found` 映射改为并行 int 数组，消除每 tick 分配 |
| ThreadLocalRandom | `willOutput`/`selectSubItem` 原 `new Random()`/`Math.random()` |
| progressBar/subRecipes 懒缓存 | SeedAnalyzer 原每次完成重建 35 元素副产物列表 |

### 2. MachineIO.fits（产物容量校验）

改为 int 金额数组模拟，**零 `ItemStack.clone`**（旧实现对每个输出槽 clone；输出槽满、机器
持续重试的稳态下是分配热点）。

### 3. 采集 / 监听器（事件驱动，高频）

| 改动 | 说明 |
|---|---|
| berry/tree O(1) 索引 | `harvestPlant` 与 `growStructure0`/`onDecay`/`onBlockBurn`/`waterStructure` 的线性 `equalsIgnoreCase` 循环改为 `HashMap` 查找（键小写，兼容原语义） |
| dropFruitFromTree 头过滤 | 树果仅以 PLAYER_HEAD 放置，增加 `fruit.getType()==PLAYER_HEAD` 过滤，跳过 ~26/27 个非头方块的 `BlockStorage.check`（砍树高频） |
| 配置缓存 | `world-blacklist`/`chances.BUSH`/`chances.TREE`/`auto-generate-plants` 在构造期缓存（cfg 为内存快照、本附属无 reload，缓存等价） |
| 草掉落数组缓存 | `onHarvest` 用 `getGrassDropsArray()`，不再每次 `values().toArray` |

## 四、基准结果

环境：JDK 21.0.12，16 核。数值为 ns/op（越低越好），倍数 = 旧/新。

### 配方匹配（3 输入 / 35 配方，ElectricityBrewing 量级）

| 场景 | 优化前 | 优化后 | 加速 |
|---|---|---|---|
| 不匹配·稳态（idle 常态） | 209.6 | 36.9 | **5.68×** |
| 不匹配·冷启动（输入刚变） | 209.6 | 261.9 | 0.80×（见注） |
| 命中·稳态 | 416.9 | 39.4 | **10.58×** |
| 空输入·稳态 | 291.9 | 11.5 | **25.49×** |

> 注：冷启动路径新旧算法工作量相同（都是全量扫描），优化收益**来自稳态跳过重匹配**，
> 而非单次匹配更快。这是该优化的本质：idle 机器不再每 tick 重复劳动。

### 配方匹配（1 输入 / 3 配方，SeedAnalyzer/YeastCulturer 量级）

| 场景 | 优化前 | 优化后 | 加速 |
|---|---|---|---|
| 不匹配·稳态 | 15.0 | 8.6 | 1.75× |

### 其他热点

| 维度 | 优化前 | 优化后 | 加速 |
|---|---|---|---|
| fits（4 槽，clone → int） | 76.6 | 26.5 | **2.89×** |
| berry 查表（~60 项，线性 → HashMap） | 154.3 | 2.5 | **61.07×** |
| subRecipes（35 项，重建 → 缓存） | 130.3 | 27.1 | **4.81×** |

## 五、正确性保障

- 离线基准：`oldMatch` 与 `newMatch` 在 20 万级随机输入序列（含放入/取出/原地改数量/
  不匹配物）上输出**完全一致**；`oldFits` 与 `newFits` 在 20 万级随机（槽位 + 待放入）上
  结果**完全一致**。三项等价性断言全部 **PASS**。
- 编译：`mvn clean package -DskipTests` → BUILD SUCCESS。
- 静态测试：9 维度 **50/50** 通过（迁移完整性、REF 兼容性等未被破坏）。
- 行为兼容：所有优化均为内部实现替换；公开 API（`getInputSlots`/`getOutputSlots`/`fits`/
  `pushMainItems`/`getSubRecipes`/`getProgressBar` 等）签名与返回语义不变；配置缓存因本附属
  无 reload 机制而与原“每次读内存快照”等价。

## 六、未覆盖 / 建议

- 本报告为**算法层**量化，**未含实机 TPS 回归**。建议在真实 Paper 1.21.1+ 服务器
  （REF 编译的 Slimefun + 本附属 jar）放置多台机器、大面积农场做冒烟与负载观察。
- 机器 tick 速率由 Slimefun 全局 ticker 决定；本优化降低的是“每次 tick 的附属侧 CPU 开销”。
- `ConcurrentHashMap`（processing/progress/idleMatchCache）保留——Slimefun 按区块并行 tick
  所必需，未回退为 HashMap（那会重新引入并发数据损坏）。
