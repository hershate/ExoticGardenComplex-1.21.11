# ExoticGardenComplex 算法层基准测试

本目录是一个**纯 Java、无 Bukkit/Slimefun 依赖**的离线基准，用于量化本插件自身算法层
（配方匹配、物品容量校验、采集查表、副产物列表）的性能改动。

## 为什么不直接跑真实插件？

生产代码的热点依赖运行中的 Paper 服务端：`ItemStack`/`SlimefunUtils.isItemSimilar`/
`BlockMenu`/`BlockStorage.check` 都无法脱离服务器实例化或执行（paper-api 单 jar 还依赖
adventure 等传递依赖，且核心逻辑需 Slimefun 运行时）。项目本身也**未做实机运行回归**
（见 `note/testing.md`）。

因此这里采用**忠实复刻**策略：用一个最小但“形似”的物品模型（`SimItem`）把“当前生产算法”
（`Algorithms.old*`）与“优化后算法”（`Algorithms.new*`）实现成可对照的两套代码，在相同
输入下计时，并用随机序列断言两者输出**完全一致**，从而：

1. 证明优化不改变行为（正确性）；
2. 量化相对耗时差异（性能）。

> 局限：SimItem 的 `isSimilar` 工作量是真实 `SlimefunUtils.isItemSimilar` 的近似（按 SF id
> 比较 + 少量字段），真实绝对耗时会更高，但**新旧相对比值可迁移**到生产——因为生产改动
> 与本基准的新旧对照在循环结构、数据结构、分配模式、调用次数上完全对应。

## 运行

```bash
bash benchmark/run.sh        # 编译 + 运行，结果打印到 stdout
bash benchmark/run.sh save   # 同上，并把结果存到 benchmark/result.txt（UTF-8）
```

需要 JDK 21（与项目构建一致）。脚本会自动定位 `JAVA_HOME` 或
`/c/Program Files/Java/latest/jdk-21`。

## 源文件

| 文件 | 作用 |
|---|---|
| `src/SimItem.java` | 物品模型：material + sfId + amount + 派生 meta；`isSimilar` 忽略数量（同 Bukkit）、`clone` 复刻分配开销 |
| `src/SimMenu.java` | 机器菜单模型：槽位数组 + `changes` 原子计数器（markDirty）+ `hasViewer` 标志 + `getItemInSlot`/`replaceExistingItem`（1.2.0 新增） |
| `src/Algorithms.java` | 新旧算法移植：`oldMatchThree/Single`、`newMatch*(MatchCache)`、`newMatchThreeCold`、`oldFits`/`newFits`；1.2.0 新增 `oldTickDisplay`/`newTickDisplay`（进度条门控）、`BlockStorageSim`/`checkSim`/`cachedResolve`、`MachineHelper.*` 忠实移植 |
| `src/Benchmark.java` | 入口：贴近 ElectricityBrewing 的配方集（3 输入 35 配方）、计时、5 项正确性断言、各微基准 |
| `run.sh` | 编译 + 运行（含 UTF-8 输出与保存） |

## 测量维度

1. **配方匹配**（idle 机器每 tick 的开销）
   - 旧 = 每次全量扫描（当前生产）
   - 新冷启动 = 输入刚变化时的全量扫描（int[] + 常量槽位数组）
   - 新命中缓存 = 输入未变时跳过匹配（稳态，真实 idle 机器的常态）
2. **fits**（产物能否放入输出槽）：clone 模拟 vs int 金额零 clone
3. **berry 查表**（采集时按 id 找 berry）：线性 `equalsIgnoreCase` vs `HashMap`
4. **getSubRecipes()**（每次完成时调用）：每次重建 vs 缓存
5. **tick 进度条显示构建**（加工机器每 tick，1.2.0）：旧 = 每 tick 总是 clone+meta+StringBuilder+`replaceExistingItem`；
   新·无人查看 = `hasViewer()` 判定后整段跳过（常态）；新·有人查看 = 与旧相同构建（持平）
6. **SF 物品解析**（加工机器每 tick，1.2.0）：`BlockStorage.check`（两级 Map 建模）vs 按方块缓存
7. **正确性**：`old*` 与 `new*` 在大规模随机序列上输出完全一致 —— match（3/1 输入）/ fits / 进度条门控 / SFItem 缓存，共 5 项

最新一次结果见 `result.txt`（由 `run.sh save` 生成）。
