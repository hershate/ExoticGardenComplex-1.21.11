# 性能优化对比报告（1.2.1 → 1.3.0）

> 日期：2026-08-01
> 分支：`feature/perf-optimization-2`
> 红线：不降低安全性 / 稳定性 / 兼容性；不引入新漏洞；玩家可见行为不变。
> 判据：`mvn clean package -DskipTests` → BUILD SUCCESS；静态测试 10 维度 51/51；离线基准 10 项等价性断言全 PASS。

## 一、背景

1.1.0/1.2.0 已优化了机器 idle 分支（输入签名缓存）、`MachineIO.fits`（零 clone）、机器 processing 分支
进度条按观看者门控、SF 物品按方块缓存、采集 berry/tree O(1) 索引等热点。本轮针对 **1.2.1 之后仍未优化的
事件驱动高频路径与机器能量路径** 做优化，并在过程中发现并修复了一个 **机器能量消耗的稳定性 Bug**。

## 二、改动清单

### 1. 机器能量消耗路径（DefaultGUI / ThreeInputGUI）—— 兼稳定性修复 + 性能优化

**问题（Bug）**：processing 分支的能量扣除写的是
```java
component.addCharge(b.getLocation(), -getEnergyConsumption());
```
而 REF（`com.github.slimefun:Slimefun:4.9.5`）的 `EnergyNetComponent.addCharge` 开头有
```java
Validate.isTrue(charge > 0, "You can only add a positive charge!");
```
`-getEnergyConsumption()`（如 -16/-24/-32/-50）为负，**每次加工 tick 必抛 `IllegalArgumentException`**
（被 Slimefun ticker 吞为事件报错，能量也未被扣除）。即：所有可充能机器（种子分析机 / 酒曲培养机 /
电力酿造机 I/II/III，`getCapacity()` 均为 256~1024）在有足量电力时**每 tick 抛异常、且不消耗能量**。

**修复**：改为“读电量 → 比较 → 写回”的单次读路径，使用 REF 的 `Config` 重载避免重复查库：
```java
int consumption = getEnergyConsumption();
Location loc = resolveLocation(b);                      // 按方块缓存，零稳态分配
Config data = BlockStorage.getLocationInfo(loc);
if (data == null) return;                               // 存储瞬态缺失，跳过本 tick
int charge = component.getCharge(loc, data);
if (charge < consumption) return;                       // 电力不足，保持进度
component.setCharge(loc, data, charge - consumption);   // 真正扣除能量
```
- 语义修正：机器现在真正消耗 `getEnergyConsumption()` / tick（设计意图），不再抛异常。
- 性能：原路径（等价的 `removeCharge`）内部会**再 `getCharge` 一次**（又一次 `BlockStorage.getLocationInfo`）；
  改用 `getCharge(loc,data)` + `setCharge(loc,data,…)` 复用同一次 `getLocationInfo` 的 `data`，
  **少一次 `BlockStorage.getLocationInfo` 查询**（opaque 库方法，定位区块→取 Config，非单次 HashMap.get）。
- 性能：`b.getLocation()` 原每 tick 调用 2 次、各分配一个 `Location`；新增 `locationCache`
  （`ConcurrentHashMap<Block, Location>`）按方块缓存，**稳态零分配**。随方块销毁 / 菜单失效清理
  （与 `sfItemCache` 同生命周期）。

> 关于基准：本项的“少一次 `BlockStorage.getLocationInfo`”在离线 sim 中无法忠实量化——sim 的
> `getLocationInfo` 是可内联纯方法，JIT 会对旧路径“连续两次同参调用”做公共子表达式消除（CSE），
> 把第二次合并；而生产中 `BlockStorage.getLocationInfo` 是不透明库方法，不可合并。故基准仅保留
> **正确性等价性断言**（`correctnessEnergy`：单次读+`setCharge` 与 `removeCharge` 在任意“初始电量×消耗”
> 组合下扣除结果完全一致），不输出会误导的计时行。生产收益为“每加工机器每 tick 少一次 BlockStorage
> 查询 + 零 Location 分配”，属定性收益。

### 2. SlimefunTag 材质判定（PlantsListener.onInteract，每次右键）—— 性能优化

**问题**：`onInteract`（玩家右键方块，极高频）每次都遍历**全部** `SlimefunTag.values()` 逐一
`isTagged(material)`，判断手持物是否受 tag 约束（受约束则交还原版行为、不采摘）：
```java
for (SlimefunTag tag : valuesCache) {
    if (tag.isTagged(mainHand) || tag.isTagged(offHand)) return;
}
```
为 O(标签数) 每次右键。

**优化**：`SlimefunTag` 在 Slimefun 加载后不可变，故每个 `Material` 的“是否被任意 tag 标记”结果恒定。
新增 `sfTaggedCache`（`ConcurrentHashMap<Material, Boolean>`）按 `Material` 记忆化：
```java
if (isSlimefunTaggedMaterial(mainHand) || isSlimefunTaggedMaterial(offHand)) return;
```
首次查询遍历全部标签，之后 O(1) 命中。`Material` 枚举有界（~千项），缓存规模天然有上限；
`ConcurrentHashMap` 保证事件线程并发安全。

基准：**84.3 → 5.4 ns（约 15.6×）**。

### 3. 物品解析（Berry.getItem / getBushItem，采集 + 植物生长）—— 性能优化

**问题**：`Berry.getItem()` 非 ORE_PLANT 时每次都 `SlimefunItem.getById(id).getItem()`（Map 查找），
被 `harvestPlant`（采集）、`growStructure0`/`waterStructure`/`growBush`（植物生长）高频调用；
`harvestPlant` 还每次 `ExoticGarden.getItem(berry.toBush())`（`getById` + `Material.getMaterial` 兜底）。

**优化**：在 `Berry` 上加懒缓存字段 `cachedPlantItem` / `cachedBushItem`。SF 物品注册后恒定，缓存其引用即可
（与原返回同一引用，语义不变；未注册 null 不缓存，下次再查）。`ExoticGarden.harvestPlant` 改用
`berry.getBushItem()`。

基准（getItem：SF 注册表查找 vs 缓存字段）：**10.9 → 3.7 ns（约 2.9×）**。

### 4. getByItem 物品识别（FoodListener.onUse，每次交互）—— 性能优化

**问题**：`FoodListener.onUse`（玩家交互事件，极高频；饥饿时触发）每次都先
`CustomItemStack.create(handItem, 1)`（克隆物品 + amount 置 1）再 `SlimefunItem.getByItem(...)`。

**优化**：REF 的 `SlimefunItem.getByItem(ItemStack)` 只读 `Material`（快速负向 Set 查找）+ `PersistentDataContainer`
中的 SF id（`getItemData`）+ `getById(id)`，并校验 Material 与模板一致——**全程不读 amount**。故 `create(hand,1)`
的克隆纯属性能浪费。改为直接 `getByItem(handItem)`（主手 / 副手两处），删除多余的 `CustomItemStack` import。

基准（getByItem：先 clone vs 直接）：**22.9 → 10.0 ns（约 2.3×）**。

### 5. getByItem 预过滤（FoodListener.onPlace / onEquip，每次放方块 / 护甲点击）—— 性能优化

**问题**：`onPlace`（每次 `BlockPlaceEvent`）与 `onEquip`（每次护甲 `InventoryClickEvent`）都写
```java
SlimefunItem item = SlimefunItem.getByItem(hand);
if (item instanceof EGPlant && hand.getType() == Material.PLAYER_HEAD) e.setCancelled(true);
```
即“先做较昂贵的 `getByItem`（材质集合查找 + PDC 读取），再把最廉价的 `getType()` 判断后置”。
EGPlant 物品均经 `getSkull` 自定义纹理，**全是 `PLAYER_HEAD`**；非 PLAYER_HEAD 不可能是 EGPlant。

**优化**：把 `getType() != PLAYER_HEAD` 前置短路，仅 PLAYER_HEAD 物品才 `getByItem`：
```java
if (hand == null || hand.getType() != Material.PLAYER_HEAD) return;
SlimefunItem item = SlimefunItem.getByItem(hand);
if (item instanceof EGPlant) e.setCancelled(true);
```
语义与原 `instanceof EGPlant && type==PLAYER_HEAD` 完全等价（AND 条件重排，廉价项前置）。绝大多数放方块 /
护甲点击手持物都不是 PLAYER_HEAD，可直接跳过 `getByItem`。

基准（onPlace/onEquip：总是 getByItem vs PLAYER_HEAD 短路，~5% 命中流）：**3.5 → 1.4 ns（约 2.5×）**。

## 三、方法学

延续 1.1.0/1.2.0 的**忠实复刻**策略：以 `SimItem` 等最小模型把“优化前算法”（`Algorithms.old*`）与
“优化后算法”（`Algorithms.new*`）实现成可对照两套代码，相同输入下计时 + 大规模随机序列断言两者输出
**完全一致**。新增五个对照（能量结算 / SlimefunTag / getItem / getByItem / onPlace-onEquip 预过滤）与五项正确性断言。代码见 [benchmark/](../../../benchmark/)，
由 `bash benchmark/run.sh [save]` 复现。

### 局限（必须如实说明）

- `SimItem.isSimilar` / 字符串构建是真实 `SlimefunUtils.isItemSimilar` / `CraftItemStack` 的**近似**（真实绝对耗时只高不低），
  故**绝对 ns 偏保守**，但**新旧相对比值可迁移**——因为新旧对照在循环结构、数据结构、分配模式、调用次数上完全对应。
- 能量结算项因前述 JIT CSE 无法在 sim 中量化计时，仅保留正确性断言（见 §二.1）。

## 四、基准结果

环境：JDK 21.0.12，16 核。数值为 ns/op（越低越好），倍数 = 旧/新。完整结果见
[benchmark/result.txt](../../../benchmark/result.txt)。

### 本轮新增（1.3.0）

| 维度 | 优化前 | 优化后 | 加速 |
|---|---|---|---|
| SlimefunTag 材质判定（每次右键，遍历 tag → 记忆化） | 84.3 | 5.4 | **15.63×** |
| getItem（getById Map 查找 → Berry 缓存字段） | 10.9 | 3.7 | **2.92×** |
| getByItem（先 clone(hand,1) → 直接 hand） | 22.9 | 10.0 | **2.29×** |
| onPlace/onEquip（总是 getByItem → PLAYER_HEAD 短路） | 3.5 | 1.4 | **2.54×** |
| 能量结算（少一次 BlockStorage 查询 + 零 Location 分配） | — | — | 定性（sim 受 JIT CSE 限制，见 §二.1）；正确性等价 PASS |

### 与既有优化（未改动，回归确认未退化）

| 维度 | 优化前 | 优化后 | 加速 |
|---|---|---|---|
| 配方匹配·命中稳态（3 输入/35 配方） | 521.7 | 44.6 | 11.69× |
| 配方匹配·空输入·稳态 | 275.1 | 12.9 | 21.26× |
| fits（4 槽，clone→int） | 77.4 | 27.2 | 2.85× |
| berry 查表（~60 项，线性→HashMap） | 152.7 | 5.5 | 27.73× |
| subRecipes（35 项，重建→缓存） | 124.9 | 26.8 | 4.65× |
| 进度条显示·无人查看（每 tick 重建→门控跳过） | 230.2 | 3.0 | 75.63× |
| SFItem 解析（check→按方块缓存） | 6.6 | 4.1 | 1.60× |

## 五、正确性保障

离线基准 10 项等价性断言全部 **PASS**（[Benchmark.main](../../../benchmark/src/Benchmark.java) 任一失败即
`System.exit(1)`，被 [test/test.sh](../../../test/test.sh) 维度 10 纳入）：

| 断言 | 含义 |
|---|---|
| 3输入 match / 1输入 match / fits | 1.1.0 配方匹配与容量校验未退化 |
| 进度条门控 | 有人查看逐字段一致；无人查看不构建 |
| SFItem 缓存 | 按方块缓存与直接 `check` 完全一致 |
| **能量结算**（1.3.0 新增） | 单次读 + `setCharge` 与 `removeCharge` 在任意“初始电量×消耗”下扣除结果完全一致（含“电量不足不扣”） |
| **SlimefunTag**（1.3.0 新增） | 记忆化判定与逐 tag 判定对任意 material 返回相同布尔 |
| **getItem 缓存**（1.3.0 新增） | Berry 缓存字段值与 `getById` 注册表查找完全一致 |
| **getByItem 克隆**（1.3.0 新增） | `getByItem` 不读 amount，故 `clone(hand,1)` 与直接 `hand` 结果完全一致（含非 SF / 快速负向路径） |
| **onPlace/onEquip 预过滤**（1.3.0 新增） | “取消放置/装备”结果在“总是 getByItem”与“PLAYER_HEAD 短路”下完全一致（AND 条件重排） |

- 编译：`mvn clean package -DskipTests` → BUILD SUCCESS，产物 `ExoticGardenComplex-1.21.11-1.3.0.jar`。
- 静态测试：10 维度 **51/51**（迁移完整性、REF 兼容性、jar 结构、源码完整性、基准正确性均未被破坏）。
- 行为兼容：公开 API（`getInputSlots`/`getOutputSlots`/`fits`/`pushMainItems`/`getSubRecipes`/
  `getProgressBar`、`Berry.getItem()`、配置格式、物品/配方 id 等）签名与返回语义不变；SlimefunTag 记忆化
  与 getItem 缓存均为内部实现替换。

## 六、红线复核

| 红线 | 结论 |
|---|---|
| 安全性 | 无新增外部输入路径；`canOpen` 权限、配方数量校验、`GrassSeeds` 领地保护等不变。 |
| 稳定性 | **修复机器能量消耗 Bug**（原 `addCharge(-x)` 每 tick 抛 `IllegalArgumentException` 且不扣能量；现真正扣除）。`locationCache` null 安全并随方块销毁清理；`data==null` 跳过本 tick 不抛。SlimefunTag / getItem 缓存键不可变，无失效风险。 |
| 兼容性 | 公开 API、配置格式、物品/配方 id 不变；能量修正使机器按设计消耗电力（设计意图），非行为回退。 |

## 七、未覆盖 / 建议

- 本报告为**算法层**量化，**未含实机 TPS 回归**。建议真实 Paper 1.21.1+ 服务器（REF 编译的 Slimefun
  + 本附属 jar）放置多台加工机器、大面积农场、多玩家高频右键做负载观察。
- 能量 Bug 此前未被实机发现，因项目一直仅做源码层编译 + 静态测试（无实机回归）。**强烈建议**重点验证
  电力酿造机 / 种子分析机 / 酒曲培养机在通电后是否按 `getEnergyConsumption()` 正常耗电并推进进度。
- 机器 tick 速率由 Slimefun 全局 ticker 决定；本优化降低的是“每次 tick 的附属侧 CPU 开销”。
