# 性能优化要点（1.0.0 → 1.1.0 → 1.2.0 → 1.3.0）

> 分支：`feature/perf-optimization`（1.1.0/1.2.0）/ `feature/perf-optimization-2`（1.3.0，从 `master` 拉）
> 红线：安全性 / 稳定性 / 兼容性不降；不引入新漏洞；玩家可见行为不变。
> 量化：见 [report/perf/2026-07-31-perf-optimization.md](report/perf/2026-07-31-perf-optimization.md)、
>       [report/perf/2026-07-31-machine-tick-viewer-gating.md](report/perf/2026-07-31-machine-tick-viewer-gating.md)、
>       [report/perf/2026-08-01-perf-optimization-2.md](report/perf/2026-08-01-perf-optimization-2.md)
>       与 `benchmark/`（`bash benchmark/run.sh` 可复现）。

1.0.0 三轮加固后运行时开销上升。本次针对本插件**自身算法层**热点优化，不触碰 Slimefun/Paper 内部。
因 Bukkit/Slimefun 无法脱离服务器运行，采用**忠实复刻**的离线基准（`SimItem` 模型 + 新旧算法
移植 + 20 万级等价性断言），量化前后差异。

---

## 一、机器配方匹配（DefaultGUI / ThreeInputGUI.tick）

1. **输入签名缓存**：输入（引用 + 数量）未变时跳过全量配方匹配。稳态 idle 机器（常态）从
   “每 tick 全扫所有配方”降为“O(1) 缓存命中”。引用 + 数量精确比对，任何变化都判“已变”，
   **零误命中**；命中后 `deriveConsume` 重建消耗。方块销毁/菜单缺失时清理缓存防泄漏。
2. **缓存槽位常量**：tick 内部用 `static final int[]`，不再每次 `new int[]{}`（公开
   `getInputSlots`/`getOutputMainSlots`/`getOutputSubSlots` 仍返回新数组，对外行为不变）。
3. **复用 BlockMenu**：fits/push 直接用已取的 menu，不再重复 `BlockStorage.getInventory`。
4. **单次解析 SF 物品**：原 `ChargeableBlock.isChargeable/getCharge/addCharge` 各查一次
   `BlockStorage.check`（共 3 次），合并为 1 次 `instanceof EnergyNetComponent` 判定。
5. **`int[]` 替代 `HashMap`**：`found` 映射改为并行 int 数组，消除每 tick 分配。
6. **ThreadLocalRandom**：`willOutput`/`selectSubItem` 原 `new Random()`/`Math.random()`。
7. **progressBar/subRecipes 懒缓存**：SeedAnalyzer 原每次完成重建 35 元素副产物列表。

基准（3 输入/35 配方）：不匹配稳态 195.4→25.7 ns（**7.59×**）、命中稳态 392.1→42.8 ns（**9.16×**）、
空输入 268.6→12.8 ns（**20.98×**）。

> 本质：idle 机器不再每 tick 重复匹配。冷启动单次匹配新旧工作量相同（0.75× 是测量噪声 +
> 缓存写入开销），收益全在稳态跳过。
>
> **签名按值比较**：`BlockMenu.getItemInSlot` 经 Bukkit 返回，Craftbukkit 每次可能返回新的
> `CraftItemStack` 包装对象（引用不等），故签名用 `isSimilar`+数量**按值**判定（而非引用相等），
> 否则缓存永不命中。仅需输入槽数量次 isSimilar（1~3 次）。

## 二、MachineIO.fits

int 金额数组模拟，**零 `ItemStack.clone`**（旧实现每输出槽 clone 一次；输出槽满、机器持续
重试时是分配热点）。基准 76.8→28.0 ns（**2.74×**）。

## 三、采集 / 监听器

1. **berry/tree O(1) 索引**：`ExoticGarden` 新增 `berriesById`/`berriesByBush`/`treesBySapling`
   （键小写，兼容原 `equalsIgnoreCase`），`rebuildPlantIndex()` 在 BE 注册后构建。
   - `harvestPlant`、`growStructure0`/`onDecay`/`onBlockBurn`/`waterStructure` 的线性循环全改索引。
   - 基准 berry 查表（~60 项）151.9→2.5 ns（**60×**）。
2. **dropFruitFromTree 头过滤**：树果仅以 PLAYER_HEAD 放置，加 `fruit.getType()==PLAYER_HEAD`，
   跳过 ~26/27 个非头方块的 `BlockStorage.check`（砍树高频路径）。
3. **配置缓存**：`world-blacklist`/`chances.BUSH`/`chances.TREE`/`auto-generate-plants` 在
   PlantsListener 构造期缓存（cfg 为内存快照、本附属无 reload，缓存等价；避免每事件
   `getStringList` 新建 List）。
4. **草掉落数组缓存**：`onHarvest` 用 `getGrassDropsArray()`，不再每次 `values().toArray`。

## 四、正确性

- 离线基准三项等价性断言全 PASS（3 输入 match / 1 输入 match / fits，各 20 万级随机）。
- `mvn clean package -DskipTests` → BUILD SUCCESS；静态测试 9 维度 50/50。
- 公开 API 签名与返回语义不变；仅内部实现替换。

## 五、未改动 / 已知

- `ConcurrentHashMap`（processing/progress/idleMatchCache）保留：Slimefun 按区块并行 tick 必需。
- 未做实机 TPS 回归：建议真实 Paper 1.21.1+ 服务器负载观察（多机器 + 大面积农场）。
- `PlantsListener.nameLookup` 为遗留空 Map（无读取），不影响性能，未在本次处理。

---

## 六、1.2.0 增量：机器 tick 进度条按观看者门控（2026-07-31）

> 详见 [report/perf/2026-07-31-machine-tick-viewer-gating.md](report/perf/2026-07-31-machine-tick-viewer-gating.md)。

1.1.0 优化了 idle 分支后，**processing 分支每 tick 的进度条显示更新**成为最大残留热点。本次：

1. **进度条按 `hasViewer()` 门控（主）**：加工中、无人查看 GUI 时（常态），跳过整段
   `clone + meta + getProgress/getTimeLeft(StringBuilder/translate) + replaceExistingItem`。
   能量消耗与进度递减保持在门控外无条件执行（游戏逻辑）。对打开 GUI 的观看者显示完全不变。
   基准：无人查看 ~225→~2 ns（**约 75–118×**，多次运行区间）。
2. **已解析 SF 物品按方块缓存（次）**：`BlockStorage.check` 每加工机器每 tick 调一次；
   机器方块 SF 物品生命周期内不变，新增 `sfItemCache`（`ConcurrentHashMap<Block, SlimefunItem>`），
   清理与 `processing`/`progress`/`idleMatchCache` 同生命周期。基准：6.9→4.4 ns（**1.58×**，保守；
   真实 `BlockStorage.check` 更重，收益更大）。
3. **顺手修复** ThreeInputGUI 第二构造器 break handler 漏清 `idleMatchCache`（内存泄漏）。

基准扩展：新增 [SimMenu](../benchmark/src/SimMenu.java) 模型 + `oldTickDisplay`/`newTickDisplay` +
`BlockStorageSim`/`cachedResolve`；正确性断言由 3 项增至 **5 项**（新增“进度条门控等价”“SFItem 缓存等价”）。

## 七、1.3.0 增量：事件路径 + 机器能量路径（2026-08-01）

> 详见 [report/perf/2026-08-01-perf-optimization-2.md](report/perf/2026-08-01-perf-optimization-2.md)。

针对 1.2.1 之后仍未优化的事件驱动高频路径与机器能量路径：

1. **SlimefunTag 材质判定（onInteract 每次右键）**：原遍历全部 `SlimefunTag.values()` 逐一 `isTagged(material)`
   （O(标签数)），改为按 `Material` 记忆化（`sfTaggedCache`，`ConcurrentHashMap<Material,Boolean>`）。
   `SlimefunTag` 加载后不可变，结果恒定可永久缓存。基准 **84.3→5.4 ns（约 15.6×）**。
2. **物品解析（Berry.getItem / getBushItem）**：`Berry.getItem()` 非 ORE_PLANT 时每次 `SlimefunItem.getById(id).getItem()`
   （被采集、植物生长高频调用），`harvestPlant` 每次又 `getItem(toBush())`；改为 `Berry` 上的懒缓存字段
   `cachedPlantItem`/`cachedBushItem`。基准（getItem：注册表查找 vs 缓存字段）**10.9→3.7 ns（约 2.9×）**。
3. **getByItem 物品识别（FoodListener.onUse 每次交互）**：`getByItem(CustomItemStack.create(hand,1))` 改为直接
   `getByItem(hand)`——REF 的 `getByItem` 只读 Material + PDC(SF id)，不读 amount，故克隆多余。基准（先 clone vs 直接）
   **31.2→13.5 ns（约 2.3×）**。
4. **机器能量消耗路径（DefaultGUI/ThreeInputGUI）—— 兼稳定性修复**：
   - **修复 Bug**：原 `component.addCharge(b.getLocation(), -getEnergyConsumption())` 在 REF（4.9.5）的 `addCharge`
     中因 `Validate.isTrue(charge>0)` 抛 `IllegalArgumentException`（负数）——所有可充能机器每加工 tick 必抛
     异常且不扣能量。改为 `getCharge(loc,data)` + `setCharge(loc,data,charge-consumption)` 单次读路径，
     机器现在真正按 `getEnergyConsumption()` 消耗电力（设计意图）。
   - **性能**：原等价 `removeCharge` 内部重复 `getCharge`（又一次 `BlockStorage.getLocationInfo`）；改用 `Config`
     重载复用同一次查询，少一次 BlockStorage 查询。新增 `locationCache` 按方块缓存 `Location`，零稳态分配。
   - 离线基准仅保留**正确性等价断言**（单次读+`setCharge` 与 `removeCharge` 完全等价）；计时因 sim 的
     `getLocationInfo` 可被 JIT 公共子表达式消除（CSE）而无法忠实量化（生产中为不透明库方法、不可合并），
     生产收益为“每加工机器每 tick 少一次 BlockStorage 查询 + 零 Location 分配”，属定性收益。

离线等价性断言由 5 项增至 **9 项**（新增 能量结算 / SlimefunTag / getItem 缓存 / getByItem 克隆）。

## 提交结构（细粒度 commit）

1.1.0：

1. `perf(benchmark)`: 新增算法层离线基准 + result.txt 基线
2. `perf(machine)`: MachineIO.fits 零 clone
3. `perf(machine)`: DefaultGUI/ThreeInputGUI tick 优化
4. `perf(harvest)`: ExoticGarden berry/tree 索引 + 草掉落数组缓存
5. `perf(listener)`: PlantsListener 索引化 + 头过滤 + 配置缓存
6. `docs/test/chore`: 报告、版本 1.1.0、benchmark 纳入 test.sh

1.2.0：

1. `perf(benchmark)`: 新增 SimMenu + tick 显示构建 / SFItem 解析的旧新对照与正确性断言
2. `perf(machine)`: DefaultGUI/ThreeInputGUI 进度条按 hasViewer 门控 + SFItem 按方块缓存
3. `fix(machine)`: ThreeInputGUI 第二构造器 break handler 补清 idleMatchCache/sfItemCache（修泄漏）
4. `docs/test/chore`: 报告、版本 1.2.0、test.sh 产物名、note/benchmark 文档更新

1.3.0：

1. `fix(stability)`: 修复机器 addCharge(-x) 能量消耗 Bug（改 getCharge(loc,data)+setCharge 单次读）+ locationCache
2. `perf(listener)`: PlantsListener.onInteract SlimefunTag 按 Material 记忆化
3. `perf(items)`: Berry.getItem/getBushItem 懒缓存（harvestPlant 改用 getBushItem）
4. `perf(benchmark)`: 新增 SlimefunTag / getItem / 能量结算对照与三项正确性断言
5. `chore(release)`: 1.2.1 → 1.3.0（pom + test.sh 产物名）
6. `docs(note)`: 1.3.0 发布说明 + 性能报告 + perf/hardening/testing/README 更新
7. `perf(food)`: FoodListener.getByItem 去除多余克隆（getByItem 不读 amount）
8. `perf(benchmark)`: 新增 getByItem 对照与正确性断言（等价性 8→9 项）
9. `docs(note)`: 补充 getByItem 优化 + 等价性断言 8→9 项
