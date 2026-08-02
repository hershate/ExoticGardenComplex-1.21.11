# 安全性与稳定性加固（2026-07-30）

> 分支：`feature/security-stability-hardening`（从 `feature/ref-compile-adapt` 拉出，保护"编译通过"检查点）
> 判据：`mvn clean package -DskipTests` → BUILD SUCCESS；`test/test.sh` 9 维度 47 项全部通过。
> 原则：修复一切影响安全与稳定的问题，保持对外 API 兼容；贯彻"客户端 / 外部输入不可信"。
> 范围：源码层加固。实机运行回归仍建议在真实 Paper 1.21.1+ 服务器上观察。

---

## 一、致命崩溃（插件无法加载或必然 NPE）

1. **`VersionedPotionEffectType` 返回 null**：原 `get()` 遍历 `PotionEffectType.values()` + `getName()`，1.20.5+ 后者行为不稳，失败返回 null；下游 `new PotionEffect(null,...)` 在 onEnable 注册酒类时抛 `IllegalArgumentException` 致插件无法加载。改为 `getByName` 逐名解析 + 兜底（不再依赖 1.20.5+ 已移除的 `PotionEffectType.SLOW` 静态常量）。
2. **`DefaultGUI`/`ThreeInputGUI.getOutputSlots()` 必抛 `UnsupportedOperationException`**：`Stream#toList()` 返回不可变 List，`addAll` 崩溃（cargo/机器人提取产物时触发）。改为 `System.arraycopy` 拼数组。
3. **`PlayerAlcohol` 构造器 `createSection("Players")` 清空数据**：每次新玩家加入/首次饮酒调用此构造器，`createSection` 会清空整个 Players 段，抹除所有玩家醉酒数据。改为 `set` 到玩家子路径。
4. **`ExoticCommand` NPE / NumberFormatException**：`Bukkit.getPlayer(name).isOnline()` 对不存在玩家 NPE；`drunkPlayers.get(name)` NPE；`Integer.parseInt` 未捕获。改为 `getPlayerExact` + 判空 + try-catch。
5. **`PlayerListener.move` NPE**：`drunkPlayers.get(name)` 对不在表中的玩家返回 null，直接 `getAlcohol()` NPE（热重载/异常情况触发）。加 null 检查。

## 二、机器逻辑与安全（DefaultGUI / ThreeInputGUI）

1. **`canOpen` 恒返回 true（安全绕过）**：任何人可打开他人机器 GUI 取走原料/产物，绕过领地/保护。改为 `Slimefun.getProtectionManager().hasPermission(..., INTERACT_BLOCK)`。
2. **配方匹配不校验原料数量（刷物品漏洞）**：`isItemSimilar` 不检查数量，槽内 1 个原料可被当作 N 个消耗并产出成品。改为校验 `slotItem.amount >= input.amount`，且每个输入槽只匹配一个配方输入（避免重复计数）。
3. **`Bukkit.createInventory` 在异步 ticker 线程不安全**：旧 `inject/injectSub` 每次 tick 创建临时 Bukkit Inventory 模拟堆叠，Paper 异步线程调用会抛 "Asynchronous inventory creation!"。新增 `MachineIO`（纯计算 fits/push，零 Bukkit 库存分配，线程安全）。
4. **`pushSubItems` fits 用错槽位（物品丢失）**：副产物 fits 检查主输出槽、实际放副输出槽，副槽满时 `addItem` 溢出丢失。改为针对副输出槽 fits/push。
5. **`ThreeInputGUI` 第二构造器 break handler 未判 inventory null**：NPE。补 `inv != null` 判断（与第一构造器一致）。

## 三、植物监听器（PlantsListener）

1. **异步回调调用 Bukkit API（线程不安全）**：`getChunkAtAsync().thenRun(...)` 可能在异步线程执行 `growStructure/growBush/pasteTree`，内含 `setType/setBlockData/PlayerHead.setSkin/BlockStorage` 等主线程 API。改为回调内 `Bukkit.getScheduler().runTask` 调度回主线程。
2. **`onGenerate` `nextInt(16-tw)` 负数**：schematic 过大时 bound<=0 抛 `IllegalArgumentException`。加 `safeW/safeL` 夹取至 [1,15]。
3. **`onInteract` clickedBlock null + nameLookup 死代码**：未判 null NPE；`nameLookup` 永不读取却每次 put。修复 + 清理。
4. **`onFastGenerate` hand null**：`getByItem(null)`。加判空。

## 四、com.be（安全与稳定）

1. **`BEListener.onPerLogin` 无条件 `login.allow()`（严重安全）**：强制覆盖服务端封禁/白名单/满员检测结果（`KICK_BANNED/KICK_WHITELIST/KICK_FULL`），可绕过。该方法除一条控制台日志外无正当用途，整体移除。
2. **`BEListener.onPlayerJoin` 强制传送所有人 + 忽略 `spawn-on-join` 配置**：改为读取 `settings.spawn-on-join` 开关 + `world` null 判空 + 调度时 `isOnline` 校验 + 不再每次 `reloadConfig`。
   > **1.2.1 补**：原 `world == null` 判空无效——默认 config `spawn: {}` 时 `getString("spawn.world")` 返回 null，而 `Bukkit.getWorld(null)` **直接抛 `IllegalArgumentException(name cannot be null)`**，并非返回 null，导致玩家进服即报错。改为先取字符串、判空后再 `getWorld`。
3. **`SpawnCommand`/`SetSpawnCommand`**：`Bukkit.getWorld` null NPE；`translateAlternateColorCodes` 对 null 配置 NPE；返回 `false` 导致成功后仍打印用法；每次执行 `reloadConfig`。全部修复（`color` fallback + world 判空 + 成功返回 true + 去 reloadConfig + 显式 `COMMAND` 传送 cause）。
   > **1.2.1 补**：同上，`SpawnCommand` 的 `Bukkit.getWorld(getString(...))` 在未设置出生点时也会抛 `name cannot be null`（而非返回 null）；已改为先取字符串判空再查。
4. **`RegistryHandler`**：`schematicsFolder` 类加载时依赖 `instance`（`ExceptionInInitializerError` 风险）→ 双检锁懒加载；`saveSchematic` `getResourceAsStream` null NPE（不被 IOException 捕获）→ 判空 + 改回 try-with-resources；`getItem` null 静默改变配方语义 → 记日志。

## 五、schematics / items

1. **`Schematic.loadSchematic` 失败返回 null → paste 必 NPE**：`Tree.getSchematic` 对 null 抛 IOException，让 `pasteSchematic` 的 `catch(IOException)` 兜底（原 catch 捕不到 null）。
2. **schematic 维度与数组长度不校验（AIOOBE）**：恶意/损坏文件可使 `width*length*height != blocks.length`，paste 索引越界。加载时校验一致性 + 拒绝非正维度。
3. **`NBTInputStream` 恶意/损坏文件 OOM/SOE**：byte array/list 长度直接按 `readInt` 分配 + 嵌套递归无深度限制。加 `MAX_DEPTH/MAX_BYTE_ARRAY_LENGTH/MAX_LIST_LENGTH/MAX_STRING_LENGTH/MAX_NAME_LENGTH` 上限。
4. **`Kitchen.locateFurnace` SOUTH 无条件强转 ClassCastException**：多方块被改造、四面非熔炉时崩。改为查找 + `instanceof` + 返回 null，`onInteract` 友好提示。
5. **`Kitchen` dispenser 未校验强转 + 配方数组越界**：校验 `DISPENSER` 类型 + `matchCount` 夹取。
6. **`GrassSeeds` 未做领地保护改写方块**：加 `Slimefun` 的 `PLACE_BLOCK` 权限检查（客户端可恶意对他人领地泥土使用）。

## 六、其余健壮性

1. **`initDataFromYAML` 用相对路径 `"storge.yml"` 写文件**：写到服务器根目录，与其它地方 `getDataFolder()/storge.yml` 不一致，数据分裂。改为绝对路径。
2. **`sendDrunkMessage` 无在线玩家崩溃**：`nextInt(0)`。判空 + `ThreadLocalRandom`。
3. **主类 `saveSchematic` `getResourceAsStream` null NPE**：判空。
4. **`FoodListener` 0-tick 延迟扣除（连点刷饥饿窗口）**：原 `scheduleSyncDelayedTask(0L)` 扣除，当前 tick 物品仍在，快速连点可多次恢复饥饿。改为同步扣除。
5. **BE 草掉落绕过 `grass-drops` 配置过滤**：BE 注册（`onPlantsRegister/onTreesRegister`）晚于 `registerItems` 的首次过滤，其加入的草掉落条目不受配置开关控制。提取 `applyGrassDropsFilter()` 并在 BE 注册后再次调用。

## 七、待确认 / 已知（未改动）

- **`BEPlants` ROSE/REED texture 哈希截断（62/61 位）**：导致头显异常，但缺少正确的 64 位纹理哈希，无法修复，需原作者提供。
- **版本号仍为 `UNOFFICIAL`**：本仓库为非官方分支的有意标识，发布前再定。
- **`MaterialData`/`PotionData` deprecated 警告**：不影响功能。
- **`Material.SHORT_GRASS` 版本**：经核实，1.21.1 paper-api 即含 `SHORT_GRASS`（项目编译通过即证），1.21.1~1.21.11 运行时均可用，无需版本兼容层。

## 八、第二轮加固（按用户决策，2026-07-30）

1. **醉酒数据改用正版验证 UUID**：`drunkPlayers` 以 `player.getUniqueId()` 为 key（online 服务器即 Mojang 正版 UUID），玩家改名不再丢数据/残留；旧"玩家名"格式数据在 join 时自动迁移到 UUID。
2. **移除 bstats 匿名统计**：删除 `Metrics` 调用、pom bstats 依赖与 shade relocation；test.sh 改为校验"无 bstats"。本附属不做联网匿名上报。
3. **MagicalEssence 8→1 破坏配方**：改为 1→1 占位，避免玩家误操作损失 7 个精华。
4. **Crook 新树叶显式映射**：MANGROVE/CHERRY 等不再因名称规则漏掉，补 `LEAF_TO_SAPLING` 映射。
5. **死代码清理**：`CustomFood.restoreHunger`（从未调用且语义不一致）、`ExoticGardenFruit` default 分支 BARREL 死判断。
6. **onDisable 不再置空集合**：避免卸载期间残余事件回调 NPE。
7. **新玩家不即时落盘**：`PlayerAlcohol` 构造器移除磁盘写，退出时统一保存，减少 join IO。

## 九、第三轮深挖：高负载并发与物品流转（2026-07-30）

针对"长时间高负载、多用户高频使用"与"刷物品 / 物品凭空消失"专项排查：

1. **机器 `processing`/`progress` 改 `ConcurrentHashMap`**：Slimefun ticker 按区块并行 tick，不同机器方块会被多个线程同时访问这两个 static 表；原 `HashMap` 并发写入会丢数据 / 损坏结构。
2. **机器完成分支加 `fits` 检查**：加工完成时若输出槽放不下，保持处理状态等下次 tick 重试，避免产物凭空消失（原 `pushMainItems` 直接 push 会丢弃放不下的部分）。
3. **机器 tick 开头统一取 `BlockMenu` 并判 null**：机器被爆炸等非破坏事件移除后，清理残留 `processing`/`progress`，避免 NPE 与长时间运行的内存泄漏。
4. **Kitchen 消耗范围 = 匹配范围（`matchCount`）**：原消耗循环遍历全部 9 格，配方数组 < 9 时会误吞 dispenser 里多余物品。
5. **`harvestPlant`：`getItem(bush)` 为 null 时跳过 `store`**，避免传 null。
6. **`onGenerate`：berry/tree 列表为空时跳过**，避免 `nextInt(0)` 崩溃。
7. **核验 BE 食物配方**：`BEFoodRegistry` 引用的 fruit id 全部已在 `BEPlants`/`BETrees`/主包（PEANUT/ICE_CUBE）注册，不存在 `getItem` 返回 null 导致的"空配方刷物品"；ROSE/REED 因 texture 哈希截断需**保留注册**（否则对应 Juice 配方变空会被空手合成刷物品），头显异常属遗留显示瑕疵。
8. **treeFruits 补全 BE 树**：原 `registerItems` 末尾仅填主包树，BE 树果右键采集失效（`harvestFruit` 判断 `treeFruits`）；BE 注册后重新填充（含 BE），并新增 `isTreeFruit` 供 `dropFruitFromTree` 做 O(1) 判断（原遍历 `getTrees()` 在砍树高频时为 27×trees 次比较）。
9. **GoldKeLa 配方防护**：`FERTILIZER_WHEAT`（Slimefun 原版物品）缺失时跳过注册，避免配方槽变空被空手合成刷物品。

**物品流转结论**：机器输入消耗（数量校验、槽位不重复）、产出（`fits` 检查）、副产物（副槽 `fits`）、采集（`clone`+`drop`）、食用（同步扣除）等路径均已核对，无刷物品 / 无凭空消失。加工中被破坏机器会损失正在加工的材料，与原版 Slimefun 行为一致（非本插件缺陷）。

## 十、第四轮：机器能量消耗 Bug（1.3.0，2026-08-01）

性能优化排查机器 tick 时发现：

1. **`addCharge(-x)` 每 tick 抛异常且不扣能量（严重）**：[DefaultGUI.tick](../src/main/java/io/github/thebusybiscuit/exoticgarden/DefaultGUI.java) /
   [ThreeInputGUI.tick](../src/main/java/io/github/thebusybiscuit/exoticgarden/ThreeInputGUI.java) 的加工分支写的是
   `component.addCharge(b.getLocation(), -getEnergyConsumption())`。而 REF（`Slimefun 4.9.5`）的
   `EnergyNetComponent.addCharge` 开头有 `Validate.isTrue(charge > 0, "You can only add a positive charge!")`——
   传入负数（如 -16/-24/-32/-50）**直接抛 `IllegalArgumentException`**。所有可充能机器（种子分析机
   capacity 1024 / 酒曲培养机 256 / 电力酿造机 I/II/III 256/512/768，`isChargeable()` 均为真）在**有足量电力时
   每加工 tick 必抛异常、且不消耗能量**（被 Slimefun ticker 吞为事件报错）。表现为：日志每 tick 报错、电力不消耗
   （等于免费加工）、进度不推进（`progress.put` 在抛异常之后执行不到）。
   - 根因：从汉化版（含 xzavier0722 存储 + 老 dough）迁移到 REF 时，`ChargeableBlock.addCharge` 原可接受负数（减电），
     而 REF 的 `EnergyNetComponent.addCharge` 校验 `charge > 0`，迁移未同步语义。
   - 修复：改为 `getCharge(loc, data)` + `setCharge(loc, data, charge - consumption)` 的单次读路径（机器现在真正按
     `getEnergyConsumption()` 消耗电力，符合设计意图）。同时附带性能收益（少一次 `BlockStorage.getLocationInfo` 查询 +
     `locationCache` 零稳态 Location 分配），详见 [perf-optimization.md](perf-optimization.md) 七。
   - 此前未被实机发现：项目一直仅做源码层编译 + 静态测试（无实机回归，见 [testing.md](testing.md) 5）。

## 十一、第五轮：受保护领地内破坏草丛掉种子致服务端延时极高（1.3.1，2026-08-02，实机发现）

实机测试发现：在他人受保护领地（如 Residence / 其它“还原式”领地插件）内破坏草丛时，**只要掉出一颗种子，
服务端延时立刻变得极高**（主线程严重卡顿）。经核实：仅在保护区出现（非保护区正常破坏草丛不卡），且报告者**并未**
无限刷（单次破坏即触发）。详见 [release/1.3.1.md](release/1.3.1.md)。

1. **根因一：掉落门禁无法识别 Residence**。[PlantsListener.onHarvest](../src/main/java/io/github/thebusybiscuit/exoticgarden/listeners/PlantsListener.java)
   草种子掉落分支的唯一门禁是 `Slimefun.getProtectionManager().hasPermission(player, loc, BREAK_BLOCK)`。
   而 REF（`Slimefun 4.9.5`）**全源码无 `residence` 引用**——Slimefun 的 `ProtectionManager` 不对接 Residence；
   `ExoticGarden.isResidenceEnabled()`（`onEnable` 仅置布尔标志）**从未被读取**（死代码）。故领地内门禁返回 `true`。
2. **根因二：当帧掉落 + 事件时序**。`BlockBreakEvent` 在方块实际移除**之前**派发；该领地插件以“还原方块”
   （而非取消事件）方式保护，会在事件结束后把草放回。原实现当帧即 `dropItemNaturally`，于是“草被还原、种子已落在该位置”。
3. **卡顿精确机制未在静态分析中确定**：本插件掉落路径**不存在**递归 / 大量分配 / 异常（事件异常被 Bukkit 吞为日志；
   掉落物均为合法 `ItemStack`——无玩家头；全仓无 `ItemSpawn/EntitySpawn/ItemMerge` 回调）。故“种子 + 草还原位置”为何令
   主线程延时极高，推测发生在 Bukkit/Paper/Slimefun/领地插件对“还原位置上的物品实体”的处理侧，超出本附属源码可静态确定范围。
   但触发条件明确：领地内掉落的这颗种子（附带：同一缺陷原理上也允许“破坏→还原→再破坏”无限刷，本次一并堵死）。
4. **修复**：掉落改为 `Bukkit.getScheduler().runTask` 延迟到下一 tick，仅当 `grassBlock.getType().isAir()`
   （草确实被破坏、未被还原）才掉落；还原式保护放回草（非 air）则 `return` 不掉落——种子不再出现在被还原位置，卡顿触发条件消除。
   掉落物一律 `clone()`，避免修改共享掉落池实例。
5. **待实机确认**：本仓库一贯无实机回归。逻辑上“领地内不再掉种子”确定成立（草还原→非 air→return）；服务端延时是否随之恢复
   **需实机复测**。若该领地插件为“延迟还原”（晚于 1 tick 才放回草），下一 tick 方块仍为 air 会误判掉落，需改为更长延迟的二次校验。

## 构建与验证

```bash
# REF 一次性安装
cd REF/Slimefun4.1 && mvn clean install -DskipTests
# 编译附属
cd ../.. && mvn clean package -DskipTests   # → target/ExoticGarden vUNOFFICIAL.jar
# 静态测试
bash test/test.sh   # 9 维度 47 项，47/47 通过
```

## 提交结构（本分支细粒度 commit）

1. `fix(crash)`: 致命崩溃修复（VersionedPotionEffectType/PlayerAlcohol/ExoticCommand/PlayerListener）
2. `fix(machine)`: 机器 GUI 加固 + 新增 MachineIO
3. `fix(schematic,items)`: Schematic/NBT/Kitchen/GrassSeeds
4. `fix(listener)`: PlantsListener 异步回主线程 + 边界
5. `fix(com.be)`: login.allow 移除 + 命令/RegistryHandler 安全
6. `fix(core)`: FoodListener 连点 + initDataFromYAML 路径 + sendDrunkMessage + saveSchematic
7. `docs(note)`: 加固记录
8. `fix(config)`: BE 草掉落条目应用 grass-drops 过滤
9. `feat(data)`: 醉酒数据改用正版 UUID 存储
10. `chore(privacy)`: 移除 bstats 匿名统计
11. `fix(items)`: 配方正确性 / 新树苗映射 / 死代码清理
12. `fix(stability)`: 机器并发安全 + 物品防消失/防吞 + tick 残留清理
13. `fix(stability,perf)`: treeFruits 补全 BE 树 + dropFruitFromTree 优化 + 空防护
14. `fix`: GoldKeLa 配方防护 FERTILIZER_WHEAT 缺失
