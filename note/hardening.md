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
3. **`SpawnCommand`/`SetSpawnCommand`**：`Bukkit.getWorld` null NPE；`translateAlternateColorCodes` 对 null 配置 NPE；返回 `false` 导致成功后仍打印用法；每次执行 `reloadConfig`。全部修复（`color` fallback + world 判空 + 成功返回 true + 去 reloadConfig + 显式 `COMMAND` 传送 cause）。
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

## 七、待确认 / 已知（未改动，避免破坏现有行为）

- **`MagicalEssence` 8→1 自指配方**：疑似设计（防误合成/占位），改它可能破坏兼容。建议人工确认设计意图。
- **`Crook` 对 1.19+ 新树叶（樱花/红树/杜鹃）不掉树苗**：功能缺失（不崩）。建议补显式 Material 映射表。
- **bstats 匿名数据上报**：被动匿名统计、非 bug，保留。若需完全离线，可移除 `Metrics` 调用 + pom bstats 依赖 + shade relocation。
- **`drunkPlayers` 用玩家名而非 UUID 作 key**：玩家改名会丢数据/残留。涉及 storage.yml 数据格式兼容，未改。
- **`MaterialData`/`PotionData` deprecated 警告**：不影响功能。
- **`Material.SHORT_GRASS` 版本**：经核实，1.21.1 paper-api 即含 `SHORT_GRASS`（项目编译通过即证），1.21.1~1.21.11 运行时均可用，无需版本兼容层。

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
