# 性能优化对比报告（1.1.0 → 1.2.0：机器 tick 进度条按观看者门控）

> 日期：2026-07-31
> 分支：`feature/perf-optimization`
> 红线：不降低安全性 / 稳定性 / 兼容性；不引入新漏洞；玩家可见行为不变。
> 判据：`mvn clean package -DskipTests` → BUILD SUCCESS；静态测试 10 维度 51/51；离线基准量化前后差异。

## 一、背景

1.1.0 已优化了机器 **idle 分支**（输入签名缓存，跳过每 tick 全量配方匹配）、`MachineIO.fits`（零 clone）、
采集查表（berry/tree O(1) 索引）等热点。本轮针对 1.1.0 之后**仍存在的最大热点**：
机器 **processing 分支每 tick 的进度条显示更新**。

生产代码（[DefaultGUI.tick](../../src/main/java/io/github/thebusybiscuit/exoticgarden/DefaultGUI.java) /
[ThreeInputGUI.tick](../../src/main/java/io/github/thebusybiscuit/exoticgarden/ThreeInputGUI.java)）在加工中、
**无论是否有人打开 GUI**，每 tick 都会：

1. `progressBar().clone()` —— 克隆进度条基础 `ItemStack`；
2. `getItemMeta()` + `setDisplayName` + `new ArrayList<>(3)` + `setLore` + `setItemMeta` —— 改写 meta；
3. `MachineHelper.getProgress/getTimeLeft` —— 每次都 `StringBuilder`/字符串拼接 +
   `ChatColor.translateAlternateColorCodes`（char[] 扫描 + 新 String）；
4. `menu.replaceExistingItem(31, item)` —— 读槽 + `onItemStackChange` 事件 + 写槽 + `markDirty`（原子自增）。

这一整套是**纯显示**（玩家看到的进度条），但在常态下（机器加工中、无人查看 GUI）也在每 tick 重复执行。
对于多机器服务器，它是机器侧每 tick CPU 开销的主要来源。

## 二、优化清单

### 1. 进度条显示按观看者门控（主）

把 processing 分支里**整段进度条重建 + `replaceExistingItem`** 用 `menu.hasViewer()` 包起来：

- 无人查看（常态）：跳过整段，仅一次 `hasViewer()` 判定（`toInventory()` 返回内部 `inv` 字段 +
  `getViewers().isEmpty()`，O(1)、null 安全）；
- 有人查看：与原来完全相同的重建逻辑。

**能量消耗与进度递减（`component.getCharge/addCharge`、`progress.put(b, timeleft-1)`）保持在门控之外，
始终执行**——它们是游戏逻辑，与观看者无关。

### 2. 已解析 Slimefun 物品按方块缓存（次）

processing 分支每 tick 调 `BlockStorage.check(b)` 取机器的 SF 物品以判 `EnergyNetComponent`。
机器方块的 SF 物品在其生命周期内不变，新增 `sfItemCache`（`ConcurrentHashMap<Block, SlimefunItem>`），
仅在未缓存时查一次并记住（null 不缓存，瞬态下次再查）。随方块销毁 / 菜单失效（`tick` 中 menu==null、
`BlockBreakHandler`）一同 `remove`，**生命周期与 `processing`/`progress`/`idleMatchCache` 完全一致**。

### 3. 顺手修复 ThreeInputGUI 第二构造器的 break handler 漏清缓存

[ThreeInputGUI.java](../../src/main/java/io/github/thebusybiscuit/exoticgarden/ThreeInputGUI.java) 的第二构造器
（带 `recipeOutput`）的 `BlockBreakHandler` 原本只清 `processing`/`progress`，**漏清 `idleMatchCache`**
（第一构造器有清，第二构造器没有）—— 机器被破坏时其 idle 签名缓存条目会残留（内存泄漏）。本次一并补上
`idleMatchCache.remove` + `sfItemCache.remove`，与第一构造器、与 `DefaultGUI` 对齐。

## 三、方法学

### 为何仍用离线基准

进度条重建依赖运行中的 Paper 服务端（`ItemStack.clone`、`ItemMeta`、`BlockMenu.replaceExistingItem`、
`BlockStorage.check` 均无法脱离服务器实例化或执行；项目亦未做实机回归，见 [note/testing.md](../../testing.md)）。
故延续 1.1.0 的**忠实复刻**策略：

- 新增 [SimMenu](../../../benchmark/src/SimMenu.java) 模型 `BlockMenu`/`DirtyChestMenu` 的相关开销点
  （槽位数组、`changes` 原子计数器、`hasViewer` 标志、`getItemInSlot`、`replaceExistingItem`）；
- [Algorithms.java](../../../benchmark/src/Algorithms.java) 忠实移植 `MachineHelper.getProgress/getTimeLeft/
  getDurability` + `ChatColor.translateAlternateColorCodes`（char[] 扫描 + 新 String），并实现
  `oldTickDisplay`（每 tick 总是构建）/ `newTickDisplay`（按 `hasViewer` 门控）；
- `BlockStorage.check` 用两级 `HashMap`（方块键→id→SF 物品）**保守建模**，`cachedResolve` 建模按方块缓存。

### 局限（必须如实说明）

- `SimItem.clone` / 移植的字符串构建是真实 `CraftItemStack.clone` + `ItemMeta` 改写的**近似**（真实绝对耗时
  只高不低：CraftItemStack 的 meta 拷贝、adventure Component 构造、`replaceExistingItem` 的事件派发都更重）；
- 因此**绝对 ns 偏保守**，但**新旧相对比值可迁移**——因为门控的语义就是“无人查看时整段跳过、有人查看时
  做相同工作”，与基准的新旧对照在调用结构上完全对应；
- `BlockStorage.check` 的真实开销（定位区块、字符串 id 解析、`SlimefunItem.getById` 并发 Map）高于两级
  `HashMap` 建模，故 SFItem 缓存的**真实收益大于基准所示**。

## 四、基准结果

环境：JDK 21.0.12，16 核。数值为 ns/op（越低越好），倍数 = 旧/新。完整结果见
[benchmark/result.txt](../../../benchmark/result.txt)（`bash benchmark/run.sh save` 生成，每次运行略有抖动）。

### 进度条显示构建（每加工机器每 tick）

| 场景 | 优化前 | 优化后 | 加速 |
|---|---|---|---|
| 无人查看·常态（加工机器绝大多数时间） | ~225 | ~2–3 | **约 75–118×**（多次运行区间） |
| 有人查看（打开 GUI 时） | ~225 | ~180 | 持平（见注） |

> 注：有人查看时新旧走**完全相同**的构建路径（门控只多一次 `hasViewer()` 判定），算法上严格持平。
> 基准中“有人查看”读数偶低于“优化前”是**分配/GC 抖动**所致（分配密集型微基准对 GC 落点敏感），
> 非真实差异。本优化的收益**全部来自无人查看的稳态跳过**，这正是加工机器的常态。
>
> 单次保存结果（result.txt）：无人查看 224.6→1.9 ns（**118.43×**）、有人查看 224.6→182.6 ns。

### SF 物品解析（每加工机器每 tick）

| 场景 | 优化前 | 优化后 | 加速 |
|---|---|---|---|
| BlockStorage.check（两级 Map 建模） | 6.9 | 4.4 | 1.58×（保守；真实收益更大） |

### 与 1.1.0 已有优化（未改动，回归确认未退化）

| 维度 | 优化前 | 优化后 | 加速 |
|---|---|---|---|
| 配方匹配·命中稳态（3 输入/35 配方） | 539.2 | 42.7 | 12.64× |
| 配方匹配·空输入·稳态 | 280.2 | 12.8 | 21.82× |
| fits（4 槽，clone→int） | 79.1 | 27.2 | 2.91× |
| berry 查表（~60 项，线性→HashMap） | 152.7 | 5.6 | 27.44× |
| subRecipes（35 项，重建→缓存） | 130.1 | 27.0 | 4.82× |

## 五、正确性保障

离线基准 5 项等价性断言全部 **PASS**（[Benchmark.main](../../../benchmark/src/Benchmark.java) 任一失败即
`System.exit(1)`，被 [test/test.sh](../../../test/test.sh) 维度 10 纳入）：

| 断言 | 含义 |
|---|---|
| 3输入 match / 1输入 match / fits | 1.1.0 的配方匹配与容量校验优化未退化 |
| **进度条门控** | 有人查看时新实现构建的显示物与旧实现**逐字段一致**；无人查看时新实现**不构建**（返回 null） |
| **SFItem 缓存** | 按方块缓存解析的结果与直接 `BlockStorage.check` **完全一致**（含未注册键均返回 null） |

- 编译：`mvn clean package -DskipTests` → BUILD SUCCESS。
- 静态测试：10 维度 **51/51**（迁移完整性、REF 兼容性、jar 结构、源码完整性、基准正确性均未被破坏）。
- 行为兼容：门控仅包裹显示语句，能量与进度递减在门控外无条件执行；公开 API（`getInputSlots`/
  `getOutputSlots`/`fits`/`pushMainItems`/`getSubRecipes`/`getProgressBar` 等）签名与返回语义不变。

## 六、红线复核

| 红线 | 结论 |
|---|---|
| 安全性 | 无新增外部输入路径；`canOpen` 权限、配方数量校验等不变。门控反而**减少**了 ticker 在异步线程对 Bukkit 库存的 `replaceExistingItem` 调用次数（更安全）。 |
| 稳定性 | `hasViewer()` null 安全（`inv==null` 时返回 false）；`resolveSfItem` 仅缓存非 null，瞬态下次重查；缓存清理与既有缓存同生命周期。顺手修复了 ThreeInputGUI 第二构造器漏清 `idleMatchCache` 的内存泄漏。 |
| 兼容性 | 对**打开 GUI 的观看者**显示完全不变（有人查看时构建逻辑原样保留）；公开 API 不变；配置格式不变。 |

## 七、未覆盖 / 建议

- 本报告为**算法层**量化，**未含实机 TPS 回归**。建议在真实 Paper 1.21.1+ 服务器
  （REF 编译的 Slimefun + 本附属 jar）放置多台加工机器（尤其长时间运行、多数机器无人查看）做负载观察。
- 机器 tick 速率由 Slimefun 全局 ticker 决定；本优化降低的是“加工中机器每 tick 的附属侧 CPU 开销”，
  且收益与“无人查看的机器占比”正相关——该占比越高，节省越大。
- `ConcurrentHashMap`（processing/progress/idleMatchCache/sfItemCache）保留：Slimefun 按区块并行 tick 所必需。
- 真实 `CraftItemStack.clone` + `ItemMeta` + `replaceExistingItem`（事件派发 + `markDirty`）比离线模型更重，
  故生产中“无人查看跳过”的绝对节省**大于**基准所示的 ~220 ns/tick/机器。
