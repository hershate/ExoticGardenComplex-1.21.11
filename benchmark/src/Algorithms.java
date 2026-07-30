import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

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
}
