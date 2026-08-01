import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 新旧算法的忠实移植。
 *
 * <p>{@link OldMatcher}/{@link OldFits} 严格复刻<b>当前生产代码</b>（DefaultGUI.tick 的
 * 配方匹配、MachineIO.fits 的 clone 模拟）的数据结构与分配模式；{@link NewMatcher}/
 * {@link NewFits} 复刻<b>优化后</b>的实现（缓存槽位数组、int[] 替代 HashMap、输入签名
 * 缓存、零 clone 的 fits）。两者语义必须等价（由 Benchmark 的正确性断言保证）。</p>
 */
public final class Algorithms {

    private Algorithms() {
    }

    // ===== 配方 =====
    static final class SimRecipe {
        final SimItem[] input;
        final SimItem[] output;
        final int ticks;

        SimRecipe(SimItem[] input, SimItem[] output, int ticks) {
            this.input = input;
            this.output = output;
            this.ticks = ticks;
        }
    }

    /** 模拟生产中 getInputSlots() 每次 new int[]{} 的分配。 */
    private static int[] newInputSlotsThree() {
        return new int[]{11, 13, 15};
    }

    private static int[] newInputSlotsSingle() {
        return new int[]{13};
    }

    // =============================================================
    // 旧：配方匹配（忠实复刻 DefaultGUI.tick 的 idle 分支）
    //   - 每次调用新建 HashMap；每个 recipe clear；
    //   - 内层 getInputSlots() 每次 new int[]（与生产一致）。
    //   返回匹配到的配方下标（-1=无），consume 为各输入位置的消耗量（null=未匹配）。
    // =============================================================
    static int[] oldMatchThree(List<SimRecipe> recipes, SimItem[] inputItems) {
        HashMap<Integer, Integer> found = new HashMap<>();
        for (int ri = 0; ri < recipes.size(); ri++) {
            SimItem[] inputs = recipes.get(ri).input;
            found.clear();
            boolean matched = true;
            for (SimItem in : inputs) {
                if (in == null) {
                    continue;
                }
                int needed = in.getAmount();
                int matchedSlot = -1;
                int[] slots = newInputSlotsThree(); // 每次分配（与生产一致）
                for (int p = 0; p < slots.length; p++) {
                    if (found.containsKey(slots[p])) {
                        continue;
                    }
                    SimItem slotItem = inputItems[p];
                    if (slotItem != null
                            && slotItem.getAmount() >= needed
                            && slotItem.isSimilar(in)) {
                        matchedSlot = slots[p];
                        break;
                    }
                }
                if (matchedSlot < 0) {
                    matched = false;
                    break;
                }
                found.put(matchedSlot, needed);
            }
            if (matched) {
                return consumeFromFound(found, inputItems.length, new int[]{11, 13, 15});
            }
        }
        return null;
    }

    static int[] oldMatchSingle(List<SimRecipe> recipes, SimItem[] inputItems) {
        HashMap<Integer, Integer> found = new HashMap<>();
        for (int ri = 0; ri < recipes.size(); ri++) {
            SimItem[] inputs = recipes.get(ri).input;
            found.clear();
            boolean matched = true;
            for (SimItem in : inputs) {
                if (in == null) {
                    continue;
                }
                int needed = in.getAmount();
                int matchedSlot = -1;
                int[] slots = newInputSlotsSingle();
                for (int p = 0; p < slots.length; p++) {
                    if (found.containsKey(slots[p])) {
                        continue;
                    }
                    SimItem slotItem = inputItems[p];
                    if (slotItem != null
                            && slotItem.getAmount() >= needed
                            && slotItem.isSimilar(in)) {
                        matchedSlot = slots[p];
                        break;
                    }
                }
                if (matchedSlot < 0) {
                    matched = false;
                    break;
                }
                found.put(matchedSlot, needed);
            }
            if (matched) {
                return consumeFromFound(found, inputItems.length, new int[]{13});
            }
        }
        return null;
    }

    /** 把 HashMap<slot,amount> 还原为按位置的下标消耗数组，便于与新版比较。 */
    private static int[] consumeFromFound(HashMap<Integer, Integer> found, int len, int[] slots) {
        int[] consume = new int[len];
        for (int p = 0; p < slots.length; p++) {
            Integer amt = found.get(slots[p]);
            if (amt != null) {
                consume[p] = amt;
            }
        }
        return consume;
    }

    // =============================================================
    // 新：配方匹配（优化后）
    //   - 槽位数组为常量（CACHED_THREE），不在循环中分配；
    //   - int[] consume 替代 HashMap；
    //   - 输入签名缓存：引用相等 + 数量相等才命中（零误命中），未变则跳过全量匹配。
    // =============================================================
    static final class MatchCache {
        SimItem[] snap;
        int[] snapAmt;
        int recipeIndex = -1; // -1 = 上次无匹配
        boolean valid = false;
    }

    private static final int[] CACHED_THREE = new int[]{11, 13, 15};
    private static final int[] CACHED_SINGLE = new int[]{13};

    static int[] newMatchThree(List<SimRecipe> recipes, SimItem[] inputItems, MatchCache cache) {
        return newMatch(recipes, inputItems, CACHED_THREE, cache);
    }

    static int[] newMatchSingle(List<SimRecipe> recipes, SimItem[] inputItems, MatchCache cache) {
        return newMatch(recipes, inputItems, CACHED_SINGLE, cache);
    }

    /**
     * 新算法的“冷启动”成本（输入刚刚变化，必须全量匹配），剥离缓存命中的收益，
     * 用于对比旧算法同等条件下的纯算法开销（int[] + 常量槽位数组 vs HashMap + 每次 new int[]）。
     */
    static int[] newMatchThreeCold(List<SimRecipe> recipes, SimItem[] inputItems) {
        int[] consume = new int[inputItems.length];
        int[] slots = CACHED_THREE;
        for (int ri = 0; ri < recipes.size(); ri++) {
            SimItem[] inputs = recipes.get(ri).input;
            Arrays.fill(consume, 0);
            boolean matched = true;
            for (SimItem in : inputs) {
                if (in == null) {
                    continue;
                }
                int needed = in.getAmount();
                int matchedSlot = -1;
                for (int p = 0; p < slots.length; p++) {
                    if (consume[p] != 0) {
                        continue;
                    }
                    SimItem slotItem = inputItems[p];
                    if (slotItem != null
                            && slotItem.getAmount() >= needed
                            && slotItem.isSimilar(in)) {
                        matchedSlot = p;
                        break;
                    }
                }
                if (matchedSlot < 0) {
                    matched = false;
                    break;
                }
                consume[matchedSlot] = needed;
            }
            if (matched) {
                return consume;
            }
        }
        return null;
    }

    private static int[] newMatch(List<SimRecipe> recipes, SimItem[] inputItems, int[] slots, MatchCache cache) {
        // 1. 签名命中：输入（引用 + 数量）未变 → 复用上次结果
        if (cache.valid && sameInput(cache.snap, cache.snapAmt, inputItems)) {
            if (cache.recipeIndex < 0) {
                return null; // 仍无匹配，跳过
            }
            // 上次匹配到但未消耗（如输出槽满）→ 仅重建该配方的消耗映射（单配方，廉价）
            return deriveConsume(recipes.get(cache.recipeIndex).input, inputItems, slots);
        }

        // 2. 冷启动：全量匹配
        int[] consume = new int[inputItems.length];
        int foundIdx = -1;
        for (int ri = 0; ri < recipes.size(); ri++) {
            SimItem[] inputs = recipes.get(ri).input;
            Arrays.fill(consume, 0);
            boolean matched = true;
            for (SimItem in : inputs) {
                if (in == null) {
                    continue;
                }
                int needed = in.getAmount();
                int matchedSlot = -1;
                for (int p = 0; p < slots.length; p++) {
                    if (consume[p] != 0) {
                        continue;
                    }
                    SimItem slotItem = inputItems[p];
                    if (slotItem != null
                            && slotItem.getAmount() >= needed
                            && slotItem.isSimilar(in)) {
                        matchedSlot = p;
                        break;
                    }
                }
                if (matchedSlot < 0) {
                    matched = false;
                    break;
                }
                consume[matchedSlot] = needed;
            }
            if (matched) {
                foundIdx = ri;
                break;
            }
        }

        // 3. 更新缓存
        cache.snap = Arrays.copyOf(inputItems, inputItems.length);
        cache.snapAmt = new int[inputItems.length];
        for (int i = 0; i < inputItems.length; i++) {
            cache.snapAmt[i] = inputItems[i] == null ? 0 : inputItems[i].getAmount();
        }
        cache.recipeIndex = foundIdx;
        cache.valid = true;

        return foundIdx < 0 ? null : consume;
    }

    // 按值比较（isSimilar + 数量），与优化后生产代码一致：生产中 BlockMenu.getItemInSlot
    // 经 Bukkit 返回，可能每次是新包装对象，故不能靠引用相等。
    private static boolean sameInput(SimItem[] snap, int[] snapAmt, SimItem[] cur) {
        if (snap == null || snap.length != cur.length) {
            return false;
        }
        for (int i = 0; i < cur.length; i++) {
            SimItem c = cur[i];
            SimItem w = snap[i];
            if (c == null) {
                if (w != null) {
                    return false;
                }
            } else {
                if (w == null) {
                    return false;
                }
                if (snapAmt[i] != c.getAmount()) {
                    return false;
                }
                if (!c.isSimilar(w)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int[] deriveConsume(SimItem[] inputs, SimItem[] inputItems, int[] slots) {
        int[] consume = new int[inputItems.length];
        for (SimItem in : inputs) {
            if (in == null) {
                continue;
            }
            int needed = in.getAmount();
            for (int p = 0; p < slots.length; p++) {
                if (consume[p] != 0) {
                    continue;
                }
                SimItem slotItem = inputItems[p];
                if (slotItem != null
                        && slotItem.getAmount() >= needed
                        && slotItem.isSimilar(in)) {
                    consume[p] = needed;
                    break;
                }
            }
        }
        return consume;
    }

    // =============================================================
    // 旧：fits（忠实复刻 MachineIO.fits —— clone 每个槽位再模拟）
    // =============================================================
    static boolean oldFits(SimItem[] slotItems, SimItem[] items) {
        int n = slotItems.length;
        SimItem[] snap = new SimItem[n];
        for (int i = 0; i < n; i++) {
            SimItem c = slotItems[i];
            snap[i] = (c == null) ? null : c.clone();
        }
        for (SimItem add : items) {
            if (add == null) {
                continue;
            }
            int remaining = add.getAmount();
            int max = 64;
            for (int i = 0; i < n && remaining > 0; i++) {
                SimItem s = snap[i];
                if (s != null && s.isSimilar(add) && s.getAmount() < max) {
                    int can = Math.min(remaining, max - s.getAmount());
                    s.setAmount(s.getAmount() + can);
                    remaining -= can;
                }
            }
            for (int i = 0; i < n && remaining > 0; i++) {
                if (snap[i] == null) {
                    int can = Math.min(remaining, max);
                    SimItem neu = add.clone();
                    neu.setAmount(can);
                    snap[i] = neu;
                    remaining -= can;
                }
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    // =============================================================
    // 新：fits（int 金额模拟，零 clone）
    // =============================================================
    static boolean newFits(SimItem[] slotItems, SimItem[] items) {
        int n = slotItems.length;
        int[] amt = new int[n];
        SimItem[] base = new SimItem[n];
        for (int i = 0; i < n; i++) {
            SimItem c = slotItems[i];
            if (c != null) {
                base[i] = c;
                amt[i] = c.getAmount();
            }
        }
        for (SimItem add : items) {
            if (add == null) {
                continue;
            }
            int remaining = add.getAmount();
            int max = 64;
            for (int i = 0; i < n && remaining > 0; i++) {
                if (base[i] != null && amt[i] < max && base[i].isSimilar(add)) {
                    int can = Math.min(remaining, max - amt[i]);
                    amt[i] += can;
                    remaining -= can;
                }
            }
            for (int i = 0; i < n && remaining > 0; i++) {
                if (base[i] == null) {
                    int can = Math.min(remaining, max);
                    base[i] = add;
                    amt[i] = can;
                    remaining -= can;
                }
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    // =============================================================
    // 机器 tick 处理分支：进度条显示构建
    //   旧 = 每 tick 总是克隆基座 + 改写 meta（getProgress/getTimeLeft 的 StringBuilder/translate）
    //        + replaceExistingItem（读槽 + markDirty）；无人查看时也照做（当前生产）。
    //   新 = 先 hasViewer()；无人查看则整段跳过（仅一次布尔判定），有人查看才构建。
    //   能量消耗 / 进度递减与显示无关，不在本段建模（两版完全一致）。
    // =============================================================

    /** 进度条基座耐久（建模 item.getType().getMaxDurability()，固定值即可）。 */
    private static final int PROGRESS_BAR_MAX_DUR = 1561;
    private static final String COLOR_CODES = "0123456789AaBbCcDdEeFfKkLlMmNnOoRr";

    /** 忠实移植 MachineHelper.getDurability（廉价算术）。 */
    static short portGetDurability(int timeLeft, int totalTime) {
        if (PROGRESS_BAR_MAX_DUR == 0) {
            return 0;
        }
        return (short) (PROGRESS_BAR_MAX_DUR * (1 - (double) timeLeft / totalTime));
    }

    /** 忠实移植 MachineHelper.getTimeLeft（字符串拼接 + translateAlternateColorCodes 分配）。 */
    static String portGetTimeLeft(int seconds) {
        String timeleft = "";
        int minutes = (int) (seconds / 60L);
        if (minutes > 0) {
            timeleft = timeleft + minutes + "m ";
        }
        seconds -= minutes * 60;
        timeleft = timeleft + seconds + "s";
        return translateAlternateColorCodes('&', "&7" + timeleft + " left");
    }

    /** 忠实移植 MachineHelper.getProgress（StringBuilder + ":".repeat + translate 分配）。 */
    static String portGetProgress(int time, int total) {
        StringBuilder progress = new StringBuilder();
        float percentage = Math.round(((((total - time) * 100.0F) / total) * 100.0F) / 100.0F);
        if (percentage < 16.0F) progress.append("&4");
        else if (percentage < 32.0F) progress.append("&c");
        else if (percentage < 48.0F) progress.append("&6");
        else if (percentage < 64.0F) progress.append("&e");
        else if (percentage < 80.0F) progress.append("&2");
        else progress = progress.append("&a");
        int rest = 20;
        for (int i = (int) percentage; i >= 5; i = i - 5) {
            progress.append(":");
            rest--;
        }
        progress.append("&7");
        progress.append(":".repeat(Math.max(0, rest)));
        progress.append(" - ").append(percentage).append("%");
        return translateAlternateColorCodes('&', progress.toString());
    }

    /** 忠实移植 ChatColor.translateAlternateColorCodes（char[] 扫描 + 新 String 分配）。 */
    private static String translateAlternateColorCodes(char alt, String input) {
        char[] b = input.toCharArray();
        for (int i = 0; i < b.length - 1; i++) {
            if (b[i] == alt && COLOR_CODES.indexOf(b[i + 1]) > -1) {
                b[i] = '§';
                b[i + 1] = Character.toLowerCase(b[i + 1]);
            }
        }
        return new String(b);
    }

    /**
     * 构建进度条显示并写入菜单槽 31（生产中 menu.replaceExistingItem(31, item)）。
     * 复刻：clone 基座、setDurability、getItemMeta/setDisplayName、new ArrayList(3)+3 add、
     * setLore、setItemMeta、replaceExistingItem（读槽 + markDirty）。
     * 返回构建出的物品供基准 sink（防止 JIT 把字符串分配当作死存储消除）。
     */
    private static SimItem buildProgressBar(SimMenu menu, SimItem base, int timeleft, int total) {
        SimItem item = base.clone();                     // progressBar().clone()
        portGetDurability(timeleft, total);              // item.setDurability(...)
        item.displayName = " ";                          // im.setDisplayName(" ")
        String[] lore = new String[3];                   // new ArrayList<>(3)
        lore[0] = portGetProgress(timeleft, total);
        lore[1] = "";
        lore[2] = portGetTimeLeft(timeleft / 2);
        item.lore = lore;                                // im.setLore(lore); item.setItemMeta(im);
        menu.replaceExistingItem(31, item);              // menu.replaceExistingItem(31, item)
        return item;
    }

    /** 旧：每 tick 总是重建进度条（当前生产）。 */
    static SimItem oldTickDisplay(SimMenu menu, SimItem base, int timeleft, int total) {
        return buildProgressBar(menu, base, timeleft, total);
    }

    /** 新：仅当 hasViewer() 为真才重建并返回该物品；否则跳过整段显示工作，返回 null。 */
    static SimItem newTickDisplay(SimMenu menu, SimItem base, int timeleft, int total) {
        if (menu.hasViewer()) {
            return buildProgressBar(menu, base, timeleft, total);
        }
        return null;
    }

    // =============================================================
    // BlockStorage.check 的查找路径建模
    //   生产 BlockStorage.check(b)：定位区块 → 读方块存储 id（字符串）→ SlimefunItem.getById(id)
    //   （多次 Map 查找 + 字符串解析；本 sim 用两级 HashMap 保守建模，真实开销只高不低）。
    //   新：按方块缓存 SF 物品，命中则一次 get。
    // =============================================================
    static final class BlockStorageSim {
        final HashMap<Long, Integer> blockToId = new HashMap<>();   // 方块键 → sfId
        final HashMap<Integer, Integer> idToItem = new HashMap<>(); // sfId → SF 物品（建模为同一 int）

        void put(long blockKey, int sfId) {
            blockToId.put(blockKey, sfId);
            idToItem.put(sfId, sfId);
        }
    }

    /** 模拟 BlockStorage.check(b)：方块键 → id → SF 物品（两次 HashMap 查找）。 */
    static Integer checkSim(BlockStorageSim bs, long blockKey) {
        Integer id = bs.blockToId.get(blockKey);     // getLocationInfo → id
        if (id == null) {
            return null;
        }
        return bs.idToItem.get(id);                  // SlimefunItem.getById(id)
    }

    /** 新：按方块缓存 SF 物品（仅缓存非 null），命中则一次 get。 */
    static Integer cachedResolve(BlockStorageSim bs, HashMap<Long, Integer> cache, long blockKey) {
        Integer cached = cache.get(blockKey);
        if (cached != null) {
            return cached;
        }
        Integer item = checkSim(bs, blockKey);
        if (item != null) {
            cache.put(blockKey, item);
        }
        return item;
    }

    // =============================================================
    // 机器加工 tick 能量结算（每加工机器每 tick，1.3.0）
    //   生产 DefaultGUI/ThreeInputGUI 的 processing 分支：读电量 → 比较消耗 → 写回。
    //   旧（修正后的等价路径，即 removeCharge(loc, consumption)）：
    //       比较用 getCharge(b.getLocation())：b.getLocation() 分配 #1 + getLocationInfo 查询 #1 + parse #1；
    //       removeCharge 内部再 getCharge(b.getLocation())：分配 #2 + 查询 #2 + parse #2；最后写回。
    //       ⇒ 2 次 Location 分配 + 2 次 BlockStorage 查询 + 2 次 parse + 1 次写回。
    //   新：复用按方块缓存的 Location（稳态零分配）+ 单次 getLocationInfo 查询 +
    //       getCharge(loc, data) [parse #1] + setCharge(loc, data, ...)（其“是否变化”比较用同一 data，
    //       再 parse #2，但不查库）+ 写回。
    //       ⇒ 0 次 Location 分配 + 1 次查询 + 2 次 parse + 1 次写回。
    //   注：原生产 addCharge(loc, -consumption) 在 REF 中会因 Validate.isTrue(charge>0) 抛异常（bug），
    //   不参与性能对比；此处以“修正后等价路径（removeCharge）”作为旧基线，确保新旧做同样的有效工作。
    // =============================================================
    static final class ChargeData {
        String chargeStr; // 建模 Config 中 "energy-charge" 字符串
        ChargeData(int charge) {
            this.chargeStr = Integer.toString(charge);
        }
    }

    static final class EnergyBlockStorageSim {
        // BlockStorage.getLocationInfo 两级结构建模：先按 chunk 定位、再按 block 取 Config（两次 HashMap
        // 查找）。与 BlockStorageSim 的“两级 HashMap 保守建模”一致；真实 getLocationInfo 不轻于单次
        // HashMap.get（含定位区块、字符串 id、Config 对象），故把“少一次 getLocationInfo”视作真实收益。
        final HashMap<Long, HashMap<Long, ChargeData>> chunks = new HashMap<>();
        // getLocationInfo 调用计数：每次调用自增，制造可观测副作用，防止 JIT 对“连续两次同参调用”
        // 做公共子表达式消除（CSE）——生产中 BlockStorage.getLocationInfo 是不透明的库方法，两次调用
        // 不可合并。该计数随 bs（堆对象）逃逸，不会被死代码消除。
        int lookups;
    }

    /** 建模 BlockStorage.getLocationInfo：定位 chunk → 取 block 的 Config（两次 HashMap 查找）+ 调用计数。 */
    private static ChargeData getLocationInfo(EnergyBlockStorageSim bs, long blockKey) {
        bs.lookups++; // 副作用：防止 JIT 把连续的同参调用 CSE 合并
        HashMap<Long, ChargeData> blocks = bs.chunks.get(blockKey >> 8);
        if (blocks == null) {
            return null;
        }
        return blocks.get(blockKey);
    }

    /**
     * 旧（修正后的等价路径，即 removeCharge）：getCharge(b.getLocation()) 做一次 getLocationInfo；
     * removeCharge(b.getLocation(), consumption) 内部再 getCharge 一次（又一次 getLocationInfo）。
     * 故每次结算两次 getLocationInfo（各两次 HashMap 查找）+ 两次 parse + 写回。
     * 返回是否成功扣除（电量不足返回 false，不改写）。
     */
    static boolean oldEnergySettle(EnergyBlockStorageSim bs, long blockKey, int consumption) {
        ChargeData d1 = getLocationInfo(bs, blockKey); // getLocationInfo #1（比较用 getCharge）
        int ch1 = Integer.parseInt(d1.chargeStr); // parse #1
        if (ch1 < consumption) {
            return false;
        }
        ChargeData d2 = getLocationInfo(bs, blockKey); // getLocationInfo #2（removeCharge 内部重复 getCharge）
        int ch2 = Integer.parseInt(d2.chargeStr); // parse #2
        int nv = Math.max(0, ch2 - consumption);
        d2.chargeStr = Integer.toString(nv); // 写回
        return true;
    }

    /**
     * 新：resolveLocation（locationCache 单次廉价命中）+ 单次 getLocationInfo + getCharge(loc,data)
     * + setCharge(loc,data)（其“是否变化”比较复用同一 data，不查库）。
     * 故每次结算一次 getLocationInfo（两次 HashMap 查找）+ 一次 locationCache.get（一次查找）+ 两次 parse + 写回。
     */
    static boolean newEnergySettle(EnergyBlockStorageSim bs, HashMap<Long, Long> locCache, long blockKey, int consumption) {
        locCache.get(blockKey); // 按方块缓存的 Location（稳态命中，建模为单次廉价 ConcurrentHashMap.get）
        ChargeData d = getLocationInfo(bs, blockKey); // getLocationInfo 仅一次（两次 HashMap 查找）
        int ch = Integer.parseInt(d.chargeStr); // parse #1（getCharge(loc, data)）
        if (ch < consumption) {
            return false;
        }
        int nv = ch - consumption; // clamp：ch>=consumption>=0 ⇒ nv>=0
        int cur = Integer.parseInt(d.chargeStr); // parse #2（setCharge 内 “charge != getCharge(l,data)” 比较，复用 data 不查库）
        if (nv != cur) {
            d.chargeStr = Integer.toString(nv); // 写回
        }
        return true;
    }

    // =============================================================
    // SlimefunTag 材质判定（onInteract 每次右键，1.3.0）
    //   生产 PlantsListener.onInteract：原遍历全部 SlimefunTag 逐一 isTagged(material) —— O(标签数)。
    //   新：按 Material 记忆化“是否被任意 tag 标记” —— O(1) 命中。
    //   建模：每个 tag = 一组 material(int)；判定 = 遍历全部 tag 做 contains。
    // =============================================================
    static final class SfTagSim {
        final List<Set<Integer>> tags = new ArrayList<>(); // 每个 tag 一组 material
    }

    /** 旧：遍历全部 tag 逐一 contains。 */
    static boolean oldIsTagged(SfTagSim sim, int material) {
        for (Set<Integer> tag : sim.tags) {
            if (tag.contains(material)) {
                return true;
            }
        }
        return false;
    }

    /** 新：按 material 记忆化（稳态命中）。 */
    static boolean newIsTagged(SfTagSim sim, HashMap<Integer, Boolean> cache, int material) {
        Boolean c = cache.get(material);
        if (c != null) {
            return c;
        }
        boolean r = oldIsTagged(sim, material);
        cache.put(material, r);
        return r;
    }

    // =============================================================
    // 物品解析（harvestPlant / 植物生长，1.3.0）
    //   生产 Berry.getItem()：非 ORE_PLANT 时每次 SlimefunItem.getById(id).getItem() —— Map 查找；
    //        ExoticGarden.getItem(toBush())：SlimefunItem.getById(id)（命中即返回）。
    //   新：Berry 上懒缓存字段（稳态字段读取）。
    //   建模：旧 = HashMap.get(id)（SF 注册表查找）；新 = 已缓存对象的字段读取。
    // =============================================================
    static final class CachedItemHolder {
        Integer item; // 缓存字段（建模 Berry.cachedBushItem / cachedPlantItem）
    }

    /** 旧：getItem(id) = SlimefunItem.getById(id)（HashMap 查找）。 */
    static Integer oldResolveItem(HashMap<String, Integer> sfRegistry, String id) {
        return sfRegistry.get(id);
    }

    // =============================================================
    // FoodListener.onUse 物品识别（每次交互事件，1.3.0）
    //   生产：item = SlimefunItem.getByItem(CustomItemStack.create(hand, 1))。
    //   REF 的 getByItem(ItemStack) 仅读 Material（快速负向 Set 查找）+ PDC 中的 SF id（getItemData）
    //   + getById(id)，并校验 Material 与模板一致；全程不读 amount。故 create(hand,1) 的克隆
    //   纯属多余分配。建模 getByItem：sfMaterials.contains(material) + 读 sfId(PDC) + getById。
    //   旧 = 先 clone(hand) 再 getByItem(clone)；新 = 直接 getByItem(hand)。
    // =============================================================
    static final class GetByItemSim {
        final Set<Integer> sfMaterials = new HashSet<>();           // getSlimefunItemMaterials()
        final HashMap<Integer, Integer> idToItem = new HashMap<>();  // SlimefunItem.getById(id)
    }

    private static Integer getByItemSim(GetByItemSim sim, SimItem item) {
        // 快速负向：material 不在任何 SF 物品模板中 → 直接 null（生产：单次 Set 查找）
        if (!sim.sfMaterials.contains(item.material)) {
            return null;
        }
        // getItemDataService().getItemData(item)：读 PDC 得 SF id（建模为 item.sfId；-1 = 非 SF 物品）
        int id = item.sfId;
        if (id < 0) {
            return null;
        }
        return sim.idToItem.get(id); // SlimefunItem.getById(id)
    }

    /** 旧：先克隆（CustomItemStack.create(hand,1)）再 getByItem。 */
    static Integer oldGetByItem(GetByItemSim sim, SimItem hand) {
        SimItem copy = hand.clone();
        return getByItemSim(sim, copy);
    }

    /** 新：直接 getByItem(hand)，省去克隆（getByItem 不读 amount）。 */
    static Integer newGetByItem(GetByItemSim sim, SimItem hand) {
        return getByItemSim(sim, hand);
    }

    // =============================================================
    // FoodListener.onPlace / onEquip 物品识别（每次放方块 / 护甲点击，1.3.0）
    //   生产：原写法 item = getByItem(hand); if (item instanceof EGPlant && hand.type==PLAYER_HEAD) cancel;
    //   即“先做昂贵的 getByItem，再把最廉价的 type 判断后置”。EGPlant 物品均为 PLAYER_HEAD，
    //   非 PLAYER_HEAD 不可能是 EGPlant，故把 type 判断前置短路即可跳过绝大多数事件的 getByItem。
    //   建模：PLAYER_HEAD_MAT 代理 Material.PLAYER_HEAD；(id&1)!=0 代理 instanceof EGPlant。
    //   旧 = 总是 getByItem；新 = 仅 PLAYER_HEAD 才 getByItem。
    // =============================================================
    static final int PLAYER_HEAD_MAT = 99; // 代理 Material.PLAYER_HEAD

    /** 旧：每次都 getByItem，再判 EGPlant && PLAYER_HEAD。返回“是否取消”。 */
    static boolean oldPlaceEquip(GetByItemSim sim, SimItem hand) {
        Integer item = getByItemSim(sim, hand);
        return item != null && hand.material == PLAYER_HEAD_MAT && (item & 1) != 0;
    }

    /** 新：先按材质短路，仅 PLAYER_HEAD 才 getByItem。 */
    static boolean newPlaceEquip(GetByItemSim sim, SimItem hand) {
        if (hand.material != PLAYER_HEAD_MAT) {
            return false;
        }
        Integer item = getByItemSim(sim, hand);
        return item != null && (item & 1) != 0;
    }
}
