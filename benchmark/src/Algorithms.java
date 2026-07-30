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

    private static boolean sameInput(SimItem[] snap, int[] snapAmt, SimItem[] cur) {
        if (snap == null || snap.length != cur.length) {
            return false;
        }
        for (int i = 0; i < cur.length; i++) {
            if (snap[i] != cur[i]) {
                return false; // 引用不等 → 视为变化（不会误命中）
            }
            int amt = cur[i] == null ? 0 : cur[i].getAmount();
            if (snapAmt[i] != amt) {
                return false; // 数量变化（原地 setAmount）→ 视为变化
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
}
