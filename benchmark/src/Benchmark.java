import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * ExoticGardenComplex 算法层性能基准（离线、纯 Java、无 Bukkit 依赖）。
 *
 * <p>方法学：以 {@link SimItem} 为物品模型，把“当前生产算法”（Old*）与“优化后算法”
 * （New*）作为对照，在相同输入下计时；并以随机序列断言两者输出完全一致，证明优化
 * 不改变行为。详见 benchmark/README.md 与 note/report/perf。</p>
 *
 * <p>运行：见 benchmark/run.sh。</p>
 */
public final class Benchmark {

    // ===== 计时工具 =====
    private static long blackHole;

    private static double benchNs(Runnable op, int warmup, int iters) {
        for (int i = 0; i < warmup; i++) {
            op.run();
        }
        long start = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            op.run();
        }
        long elapsed = System.nanoTime() - start;
        return (double) elapsed / iters;
    }

    public static void main(String[] args) {
        System.out.println("=== ExoticGardenComplex 算法层基准 ===");
        System.out.println("JVM: " + System.getProperty("java.version")
                + " | processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println();

        int warmup = 100_000;
        int iters = 500_000;

        // ---------- 配方集（复刻 ElectricityBrewing：3 输入，~35 配方） ----------
        List<Algorithms.SimRecipe> threeRecipes = buildThreeInputRecipes(35);
        List<Algorithms.SimRecipe> singleRecipes = buildSingleInputRecipes(3); // SeedAnalyzer/YeastCulturer 量级

        // ---------- 场景输入 ----------
        // A) 有输入但不匹配任何配方（最坏：全扫）
        SimItem[] threeNoMatch = new SimItem[]{
                SimItem.of(8, 9_999, 1),
                SimItem.of(8, 9_998, 1),
                SimItem.of(8, 9_997, 1)
        };
        // B) 命中第 20 个配方
        SimItem[] threeMatch = slotsForRecipe(threeRecipes, 20);
        // C) 空输入
        SimItem[] threeEmpty = new SimItem[]{null, null, null};

        Algorithms.MatchCache cacheNoMatch = new Algorithms.MatchCache();
        Algorithms.MatchCache cacheMatch = new Algorithms.MatchCache();
        Algorithms.MatchCache cacheEmpty = new Algorithms.MatchCache();
        // 预热缓存到 warm 态
        Algorithms.newMatchThree(threeRecipes, threeNoMatch, cacheNoMatch);
        Algorithms.newMatchThree(threeRecipes, threeMatch, cacheMatch);
        Algorithms.newMatchThree(threeRecipes, threeEmpty, cacheEmpty);

        System.out.println("--- 配方匹配（3 输入 / 35 配方，每次 = 一个机器一个 tick 的 idle 分支）---");
        double oldNoMatch = benchNs(() -> sink(Algorithms.oldMatchThree(threeRecipes, threeNoMatch)), warmup, iters);
        double newColdNoMatch = benchNs(() -> sink(Algorithms.newMatchThreeCold(threeRecipes, threeNoMatch)), warmup, iters);
        double newWarmNoMatch = benchNs(() -> sink(Algorithms.newMatchThree(threeRecipes, threeNoMatch, cacheNoMatch)), warmup, iters);

        double oldMatch = benchNs(() -> sink(Algorithms.oldMatchThree(threeRecipes, threeMatch)), warmup, iters);
        double newWarmMatch = benchNs(() -> sink(Algorithms.newMatchThree(threeRecipes, threeMatch, cacheMatch)), warmup, iters);

        double oldEmpty = benchNs(() -> sink(Algorithms.oldMatchThree(threeRecipes, threeEmpty)), warmup, iters);
        double newWarmEmpty = benchNs(() -> sink(Algorithms.newMatchThree(threeRecipes, threeEmpty, cacheEmpty)), warmup, iters);

        row("不匹配·旧(每tick全扫)", oldNoMatch, oldNoMatch);
        row("不匹配·新冷启动", newColdNoMatch, oldNoMatch);
        row("不匹配·新命中缓存(稳态)", newWarmNoMatch, oldNoMatch);
        row("命中·旧(每tick全扫)", oldMatch, oldMatch);
        row("命中·新命中缓存(稳态)", newWarmMatch, oldMatch);
        row("空输入·旧", oldEmpty, oldEmpty);
        row("空输入·新命中缓存", newWarmEmpty, oldEmpty);
        System.out.println();

        // ---------- 单输入机器（SeedAnalyzer/YeastCulturer 量级） ----------
        SimItem[] singleNoMatch = new SimItem[]{SimItem.of(8, 9_999, 1)};
        Algorithms.MatchCache cacheSingle = new Algorithms.MatchCache();
        Algorithms.newMatchSingle(singleRecipes, singleNoMatch, cacheSingle);
        System.out.println("--- 配方匹配（1 输入 / 3 配方）---");
        double oldSingle = benchNs(() -> sink(Algorithms.oldMatchSingle(singleRecipes, singleNoMatch)), warmup, iters);
        double newSingle = benchNs(() -> sink(Algorithms.newMatchSingle(singleRecipes, singleNoMatch, cacheSingle)), warmup, iters);
        row("不匹配·旧(每tick全扫)", oldSingle, oldSingle);
        row("不匹配·新命中缓存(稳态)", newSingle, oldSingle);
        System.out.println();

        // ---------- fits（输出槽容量校验） ----------
        System.out.println("--- fits（输出槽容量校验，4 槽位）---");
        Random fr = new Random(7);
        SimItem[][] fitsSlots = new SimItem[2000][];
        SimItem[][] fitsItems = new SimItem[2000][];
        for (int i = 0; i < 2000; i++) {
            fitsSlots[i] = randomSlots(fr, 4);
            fitsItems[i] = new SimItem[]{SimItem.of(8, 200 + fr.nextInt(40), 1 + fr.nextInt(64))};
        }
        double oldFits = benchNs(() -> {
            for (int i = 0; i < fitsSlots.length; i++) {
                sink(Algorithms.oldFits(fitsSlots[i], fitsItems[i]));
            }
        }, 200, 20_000) / fitsSlots.length;
        double newFits = benchNs(() -> {
            for (int i = 0; i < fitsSlots.length; i++) {
                sink(Algorithms.newFits(fitsSlots[i], fitsItems[i]));
            }
        }, 200, 20_000) / fitsSlots.length;
        row("fits·旧(clone 模拟)", oldFits, oldFits);
        row("fits·新(int 金额零 clone)", newFits, oldFits);
        System.out.println();

        // ---------- 采集 berry 查表 ----------
        System.out.println("--- 采集：berry 按 id 查表（~60 项）---");
        BenchBerry bb = new BenchBerry(60);
        String[] lookIds = new String[1000];
        Random br = new Random(11);
        for (int i = 0; i < lookIds.length; i++) {
            lookIds[i] = (br.nextInt(10) == 0) ? "MISS_" + i : bb.ids.get(br.nextInt(bb.ids.size()));
        }
        double oldBerry = benchNs(() -> {
            for (String id : lookIds) {
                sink(bb.linear(id));
            }
        }, 200, 20_000) / lookIds.length;
        double newBerry = benchNs(() -> {
            for (String id : lookIds) {
                sink(bb.map(id));
            }
        }, 200, 20_000) / lookIds.length;
        row("berry·旧(线性 equalsIgnoreCase)", oldBerry, oldBerry);
        row("berry·新(HashMap)", newBerry, oldBerry);
        System.out.println();

        // ---------- 副产物列表重建 vs 缓存（SeedAnalyzer 35 项） ----------
        System.out.println("--- 副产物 getSubRecipes()：每次重建 vs 缓存（35 项）---");
        BenchSub bs = new BenchSub(35);
        double oldSub = benchNs(() -> sink(bs.rebuild().hashCode()), warmup, iters);
        double newSub = benchNs(() -> sink(bs.cached().hashCode()), warmup, iters);
        row("subRecipes·旧(每次重建)", oldSub, oldSub);
        row("subRecipes·新(缓存)", newSub, oldSub);
        System.out.println();

        // ---------- tick 处理分支：进度条显示构建（每加工机器每 tick） ----------
        System.out.println("--- tick 处理：进度条显示构建（每机器每 tick）---");
        SimItem barBase = SimItem.of(7, 501, 1); // 进度条基座（IRON_PICKAXE 代理）
        final int TIMELEFT = 53;
        final int TOTAL = 80;
        // 旧：每 tick 总是 clone+meta+StringBuilder+replace（当前生产，无论是否有人看）
        SimMenu menuOld = new SimMenu();
        double oldDisplay = benchNs(() -> sink(Algorithms.oldTickDisplay(menuOld, barBase, TIMELEFT, TOTAL)), warmup, iters);
        // 新·无人查看（常态）：仅一次 hasViewer() 判定后整段跳过
        SimMenu menuNoViewer = new SimMenu();
        double newDisplayIdle = benchNs(() -> sink(Algorithms.newTickDisplay(menuNoViewer, barBase, TIMELEFT, TOTAL)), warmup, iters);
        // 新·有人查看：与旧相同的构建 + 一次 hasViewer 判定（不得退化）
        SimMenu menuViewer = new SimMenu();
        menuViewer.viewer = true;
        double newDisplayViewer = benchNs(() -> sink(Algorithms.newTickDisplay(menuViewer, barBase, TIMELEFT, TOTAL)), warmup, iters);
        row("进度条·旧(每tick重建)", oldDisplay, oldDisplay);
        row("进度条·新(无人查看·常态)", newDisplayIdle, oldDisplay);
        row("进度条·新(有人查看)", newDisplayViewer, oldDisplay);
        System.out.println();

        // ---------- SF 物品解析：BlockStorage.check vs 按方块缓存 ----------
        System.out.println("--- SF 物品解析：BlockStorage.check vs 按方块缓存 ---");
        BenchSfItem bsf = new BenchSfItem(2000);
        double oldCheck = benchNs(() -> {
            for (int i = 0; i < bsf.keys.length; i++) {
                sink(Algorithms.checkSim(bsf.bs, bsf.keys[i]));
            }
        }, 200, 20_000) / bsf.keys.length;
        double newResolve = benchNs(() -> {
            for (int i = 0; i < bsf.keys.length; i++) {
                sink(Algorithms.cachedResolve(bsf.bs, bsf.cache, bsf.keys[i]));
            }
        }, 200, 20_000) / bsf.keys.length;
        row("SFItem·旧(BlockStorage.check)", oldCheck, oldCheck);
        row("SFItem·新(按方块缓存)", newResolve, oldCheck);
        System.out.println();

        // ---------- 机器加工 tick 能量结算（每加工机器每 tick，1.3.0） ----------
        // 说明：仅做“正确性等价性”断言（见末尾 能量结算等价），不输出计时行。
        // 原因：离线 sim 的 getLocationInfo 是可内联的纯查找方法，JIT 会对旧路径“连续两次同参调用”
        // 做公共子表达式消除（CSE），把第二次 getLocationInfo 合并掉——而生产中 BlockStorage.getLocationInfo
        // 是不透明库方法，两次调用不可合并。因此 sim 无法忠实反映“少一次 getLocationInfo”的收益，
        // 计时行会误导（反而显示新路径略慢）。生产收益（少一次 BlockStorage 查询 + 零 Location 分配）
        // 在 note/report/perf 中定性说明。correctnessEnergy 仍验证“单次读 + setCharge”与“removeCharge”等价。

        // ---------- SlimefunTag 材质判定（onInteract 每次右键，1.3.0） ----------
        System.out.println("--- SlimefunTag 材质判定（每次右键：手持物是否受 tag 约束）---");
        BenchSfTag btag = new BenchSfTag(20, 30);
        int[] tagMats = btag.lookupMaterials(1000);
        // 预热记忆化缓存到稳态（生产中玩家手持物集合有界，绝大多数右键命中缓存）
        for (int m : tagMats) {
            Algorithms.newIsTagged(btag.sim, btag.cache, m);
        }
        double oldTag = benchNs(() -> {
            for (int m : tagMats) {
                sink(Algorithms.oldIsTagged(btag.sim, m));
            }
        }, 200, 20_000) / tagMats.length;
        double newTag = benchNs(() -> {
            for (int m : tagMats) {
                sink(Algorithms.newIsTagged(btag.sim, btag.cache, m));
            }
        }, 200, 20_000) / tagMats.length;
        row("SlimefunTag·旧(遍历全部 tag)", oldTag, oldTag);
        row("SlimefunTag·新(按 material 记忆化)", newTag, oldTag);
        System.out.println();

        // ---------- 物品解析（harvestPlant/植物生长，1.3.0） ----------
        System.out.println("--- 物品解析（getItem：SF 注册表查找 vs Berry 缓存字段）---");
        BenchItemResolve bir = new BenchItemResolve(60);
        double oldItem = benchNs(() -> {
            for (int i = 0; i < bir.ids.length; i++) {
                sink(Algorithms.oldResolveItem(bir.sfRegistry, bir.ids[i]));
            }
        }, 200, 20_000) / bir.ids.length;
        double newItem = benchNs(() -> {
            for (int i = 0; i < bir.holders.length; i++) {
                sink(bir.holders[i].item);
            }
        }, 200, 20_000) / bir.holders.length;
        row("getItem·旧(getById Map 查找)", oldItem, oldItem);
        row("getItem·新(缓存字段读取)", newItem, oldItem);
        System.out.println();

        // ---------- 正确性：新旧 match / fits 必须完全等价 ----------
        System.out.println("--- 正确性等价性断言 ---");
        int failMatch = correctnessMatch(threeRecipes, 200_000, new Random(123));
        int failSingle = correctnessMatchSingle(singleRecipes, 100_000, new Random(321));
        int failFits = correctnessFits(200_000, new Random(999));
        int failDisplay = correctnessDisplay(50_000, new Random(7));
        int failResolve = correctnessResolve(bsf, 100_000, new Random(8));
        int failEnergy = correctnessEnergy(100_000, new Random(42));
        int failTag = correctnessTag(btag.sim, 50_000, new Random(17));
        int failItem = correctnessItem(bir, 50_000, new Random(23));
        System.out.println("  3输入 match 等价   : " + (failMatch == 0 ? "PASS" : ("FAIL(" + failMatch + ")")));
        System.out.println("  1输入 match 等价   : " + (failSingle == 0 ? "PASS" : ("FAIL(" + failSingle + ")")));
        System.out.println("  fits 等价         : " + (failFits == 0 ? "PASS" : ("FAIL(" + failFits + ")")));
        System.out.println("  进度条门控等价     : " + (failDisplay == 0 ? "PASS" : ("FAIL(" + failDisplay + ")")));
        System.out.println("  SFItem 缓存等价    : " + (failResolve == 0 ? "PASS" : ("FAIL(" + failResolve + ")")));
        System.out.println("  能量结算等价       : " + (failEnergy == 0 ? "PASS" : ("FAIL(" + failEnergy + ")")));
        System.out.println("  SlimefunTag 等价   : " + (failTag == 0 ? "PASS" : ("FAIL(" + failTag + ")")));
        System.out.println("  getItem 缓存等价   : " + (failItem == 0 ? "PASS" : ("FAIL(" + failItem + ")")));
        System.out.println();

        System.out.println("(checksum=" + blackHole + ")");
        if (failMatch != 0 || failSingle != 0 || failFits != 0 || failDisplay != 0 || failResolve != 0
                || failEnergy != 0 || failTag != 0 || failItem != 0) {
            System.exit(1);
        }
    }

    private static void row(String name, double ns, double base) {
        double speedup = base / ns;
        System.out.printf("  %-32s %10.1f ns/op   (%.2fx)%n", name, ns, speedup);
    }

    private static void sink(Object o) {
        if (o != null) {
            blackHole ^= o.hashCode();
        }
    }

    // ===== 配方集构造 =====
    private static List<Algorithms.SimRecipe> buildThreeInputRecipes(int count) {
        List<Algorithms.SimRecipe> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SimItem a = SimItem.of(7, 100 + i / 12, 1);          // 酒曲（分档）
            SimItem b = SimItem.of(8, 200 + i, 1);
            SimItem c = SimItem.of(8, 300 + i, (i % 3) + 1);
            SimItem out = SimItem.of(9, 500 + i, 1);
            list.add(new Algorithms.SimRecipe(new SimItem[]{a, b, c}, new SimItem[]{out}, 80));
        }
        return list;
    }

    private static List<Algorithms.SimRecipe> buildSingleInputRecipes(int count) {
        List<Algorithms.SimRecipe> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SimItem a = SimItem.of(8, 700 + i, 1);
            list.add(new Algorithms.SimRecipe(new SimItem[]{a}, new SimItem[]{SimItem.of(9, 600 + i, 1)}, 120));
        }
        return list;
    }

    private static SimItem[] slotsForRecipe(List<Algorithms.SimRecipe> recipes, int idx) {
        Algorithms.SimRecipe r = recipes.get(idx);
        SimItem[] s = new SimItem[r.input.length];
        for (int i = 0; i < r.input.length; i++) {
            // 用同 SF 身份的新实例 + 足量，验证“按 id 相似”
            s[i] = SimItem.of(r.input[i].material, r.input[i].sfId, r.input[i].getAmount() + 3);
        }
        return s;
    }

    private static SimItem[] randomSlots(Random r, int n) {
        SimItem[] s = new SimItem[n];
        for (int i = 0; i < n; i++) {
            if (r.nextInt(3) == 0) {
                s[i] = null;
            } else {
                s[i] = SimItem.of(8, 200 + r.nextInt(40), 1 + r.nextInt(64));
            }
        }
        return s;
    }

    // ===== 正确性 =====
    private static int correctnessMatch(List<Algorithms.SimRecipe> recipes, int steps, Random r) {
        int fail = 0;
        SimItem[] slots = new SimItem[]{null, null, null};
        Algorithms.MatchCache cache = new Algorithms.MatchCache();
        for (int s = 0; s < steps; s++) {
            mutate(slots, recipes, r);
            int[] oldC = Algorithms.oldMatchThree(recipes, slots);
            int[] newC = Algorithms.newMatchThree(recipes, slots, cache);
            if (!sameConsume(oldC, newC)) {
                fail++;
            }
        }
        return fail;
    }

    private static int correctnessMatchSingle(List<Algorithms.SimRecipe> recipes, int steps, Random r) {
        int fail = 0;
        SimItem[] slots = new SimItem[]{null};
        Algorithms.MatchCache cache = new Algorithms.MatchCache();
        for (int s = 0; s < steps; s++) {
            if (r.nextInt(2) == 0) {
                slots[0] = null;
            } else {
                Algorithms.SimRecipe rr = recipes.get(r.nextInt(recipes.size()));
                slots[0] = SimItem.of(rr.input[0].material, rr.input[0].sfId, 1 + r.nextInt(5));
            }
            // 偶尔放入不匹配物
            if (r.nextInt(5) == 0) {
                slots[0] = SimItem.of(8, 9_000 + r.nextInt(50), 1);
            }
            int[] oldC = Algorithms.oldMatchSingle(recipes, slots);
            int[] newC = Algorithms.newMatchSingle(recipes, slots, cache);
            if (!sameConsume(oldC, newC)) {
                fail++;
            }
        }
        return fail;
    }

    private static void mutate(SimItem[] slots, List<Algorithms.SimRecipe> recipes, Random r) {
        int pos = r.nextInt(slots.length);
        int action = r.nextInt(4);
        switch (action) {
            case 0 -> slots[pos] = null; // 取出
            case 1 -> { // 放入某配方输入
                Algorithms.SimRecipe rr = recipes.get(r.nextInt(recipes.size()));
                slots[pos] = SimItem.of(rr.input[pos % rr.input.length].material,
                        rr.input[pos % rr.input.length].sfId, 1 + r.nextInt(5));
            }
            case 2 -> { // 不匹配物
                slots[pos] = SimItem.of(8, 9_000 + r.nextInt(50), 1 + r.nextInt(5));
            }
            default -> { // 原地改数量（触发“引用相同但数量变化”路径）
                if (slots[pos] != null) {
                    slots[pos].setAmount(1 + r.nextInt(8));
                }
            }
        }
    }

    private static boolean sameConsume(int[] a, int[] b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return Arrays.equals(a, b);
    }

    private static int correctnessFits(int steps, Random r) {
        int fail = 0;
        for (int s = 0; s < steps; s++) {
            SimItem[] slots = randomSlots(r, 4);
            int k = 1 + r.nextInt(3);
            SimItem[] items = new SimItem[k];
            for (int i = 0; i < k; i++) {
                items[i] = r.nextInt(4) == 0 ? null : SimItem.of(8, 200 + r.nextInt(10), 1 + r.nextInt(80));
            }
            boolean ob = Algorithms.oldFits(slots, items);
            boolean nb = Algorithms.newFits(slots, items);
            if (ob != nb) {
                fail++;
            }
        }
        return fail;
    }

    /**
     * 进度条门控等价性：① 有人查看时，新实现必须构建出与旧实现完全一致的显示物；
     * ② 无人查看时，新实现必须跳过（返回 null）。进度/能量递减不在显示段内（生产代码结构保证），
     * 故与观看者无关——此点在生产代码中由“门控仅包裹显示语句、能量与 progress 放在门控外”保证。
     */
    private static int correctnessDisplay(int steps, Random r) {
        int fail = 0;
        SimItem base = SimItem.of(7, 501, 1);
        SimMenu menuViewer = new SimMenu();
        menuViewer.viewer = true;
        SimMenu menuNoViewer = new SimMenu();
        for (int s = 0; s < steps; s++) {
            int total = 40 + r.nextInt(80);
            int timeleft = 1 + r.nextInt(total);
            SimItem oldItem = Algorithms.oldTickDisplay(menuViewer, base, timeleft, total);
            SimItem newItem = Algorithms.newTickDisplay(menuViewer, base, timeleft, total);
            if (!sameDisplay(oldItem, newItem)) {
                fail++;
            }
            SimItem idleItem = Algorithms.newTickDisplay(menuNoViewer, base, timeleft, total);
            if (idleItem != null) {
                fail++;
            }
        }
        return fail;
    }

    /** SFItem 缓存等价性：按方块缓存解析的结果必须与直接 BlockStorage.check 完全一致（含未注册键均返回 null）。 */
    private static int correctnessResolve(BenchSfItem bsf, int steps, Random r) {
        int fail = 0;
        HashMap<Long, Integer> cache = new HashMap<>();
        for (int s = 0; s < steps; s++) {
            long key = bsf.keys[r.nextInt(bsf.keys.length)];
            Integer direct = Algorithms.checkSim(bsf.bs, key);
            Integer cached = Algorithms.cachedResolve(bsf.bs, cache, key);
            if (!Objects.equals(direct, cached)) {
                fail++;
            }
        }
        Integer directMiss = Algorithms.checkSim(bsf.bs, Long.MIN_VALUE);
        Integer cachedMiss = Algorithms.cachedResolve(bsf.bs, cache, Long.MIN_VALUE);
        if (directMiss != null || cachedMiss != null) {
            fail++;
        }
        return fail;
    }

    /**
     * 能量结算等价性：旧（removeCharge 路径）与新（getCharge(loc,data)+setCharge(loc,data)）在
     * 相同初始电量、相同消耗下，扣除结果（是否成功 + 剩余电量）必须完全一致。消耗与初始电量随机
     * 组合，覆盖“电量不足不扣”与“足量扣除”两分支。
     */
    private static int correctnessEnergy(int steps, Random r) {
        int fail = 0;
        long key = 1L;
        for (int s = 0; s < steps; s++) {
            int consumption = 1 + r.nextInt(60);
            int startCharge = r.nextInt(120); // 横跨 < 与 >= consumption
            Algorithms.EnergyBlockStorageSim bsOld = new Algorithms.EnergyBlockStorageSim();
            Algorithms.EnergyBlockStorageSim bsNew = new Algorithms.EnergyBlockStorageSim();
            HashMap<Long, Long> locCache = new HashMap<>();
            bsOld.chunks.computeIfAbsent(key >> 8, c -> new HashMap<>()).put(key, new Algorithms.ChargeData(startCharge));
            bsNew.chunks.computeIfAbsent(key >> 8, c -> new HashMap<>()).put(key, new Algorithms.ChargeData(startCharge));
            locCache.put(key, key);
            boolean ob = Algorithms.oldEnergySettle(bsOld, key, consumption);
            boolean nb = Algorithms.newEnergySettle(bsNew, locCache, key, consumption);
            if (ob != nb) {
                fail++;
                continue;
            }
            int afterOld = Integer.parseInt(bsOld.chunks.get(key >> 8).get(key).chargeStr);
            int afterNew = Integer.parseInt(bsNew.chunks.get(key >> 8).get(key).chargeStr);
            if (afterOld != afterNew) {
                fail++;
            }
        }
        return fail;
    }

    /** SlimefunTag 等价性：记忆化判定与逐 tag 判定对任意 material 返回相同布尔。 */
    private static int correctnessTag(Algorithms.SfTagSim sim, int steps, Random r) {
        int fail = 0;
        HashMap<Integer, Boolean> cache = new HashMap<>();
        for (int s = 0; s < steps; s++) {
            int m = r.nextInt(2000);
            boolean ob = Algorithms.oldIsTagged(sim, m);
            boolean nb = Algorithms.newIsTagged(sim, cache, m);
            if (ob != nb) {
                fail++;
            }
        }
        return fail;
    }

    /** getItem 缓存等价性：缓存字段值必须与 getById 注册表查找完全一致。 */
    private static int correctnessItem(BenchItemResolve bir, int steps, Random r) {
        int fail = 0;
        for (int s = 0; s < steps; s++) {
            int i = r.nextInt(bir.ids.length);
            Integer ob = Algorithms.oldResolveItem(bir.sfRegistry, bir.ids[i]);
            Integer nb = bir.holders[i].item;
            if (!Objects.equals(ob, nb)) {
                fail++;
            }
        }
        return fail;
    }

    private static boolean sameDisplay(SimItem a, SimItem b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (!Objects.equals(a.displayName, b.displayName)) {
            return false;
        }
        String[] la = a.lore;
        String[] lb = b.lore;
        if (la == null || lb == null) {
            return la == null && lb == null;
        }
        if (la.length != lb.length) {
            return false;
        }
        for (int i = 0; i < la.length; i++) {
            if (!Objects.equals(la[i], lb[i])) {
                return false;
            }
        }
        return true;
    }

    // ===== berry 查表微基准 =====
    static final class BenchBerry {
        final List<String> ids = new ArrayList<>();
        final HashMap<String, Integer> map = new HashMap<>();

        BenchBerry(int n) {
            for (int i = 0; i < n; i++) {
                String id = "BERRY_" + i;
                ids.add(id);
                map.put(id, i);
            }
        }

        Integer linear(String id) {
            for (int i = 0; i < ids.size(); i++) {
                if (ids.get(i).equalsIgnoreCase(id)) {
                    return i;
                }
            }
            return null;
        }

        Integer map(String id) {
            return map.get(id);
        }
    }

    // ===== subRecipes 微基准 =====
    static final class BenchSub {
        final int[] chances;
        final int[] sfIds;
        final List<Integer> cached;

        BenchSub(int n) {
            chances = new int[n];
            sfIds = new int[n];
            for (int i = 0; i < n; i++) {
                chances[i] = 2500 + i;
                sfIds[i] = 100 + i;
            }
            cached = build();
        }

        List<Integer> build() {
            List<Integer> l = new ArrayList<>(chances.length);
            for (int i = 0; i < chances.length; i++) {
                l.add(chances[i] * 31 + sfIds[i]);
            }
            return l;
        }

        List<Integer> rebuild() {
            return build();
        }

        List<Integer> cached() {
            return cached;
        }
    }

    // ===== SFItem 解析微基准 =====
    static final class BenchSfItem {
        final Algorithms.BlockStorageSim bs = new Algorithms.BlockStorageSim();
        final HashMap<Long, Integer> cache = new HashMap<>();
        final long[] keys;

        BenchSfItem(int n) {
            keys = new long[n];
            for (int i = 0; i < n; i++) {
                long key = 1_000_000L + i;
                int sfId = 100 + (i % 40);
                bs.put(key, sfId);
                keys[i] = key;
                // 预热缓存到 warm 态（稳态命中，对应机器方块首次解析后持续命中）
                cache.put(key, sfId);
            }
        }
    }

    // ===== SlimefunTag 微基准（1.3.0）=====
    static final class BenchSfTag {
        final Algorithms.SfTagSim sim = new Algorithms.SfTagSim();
        final HashMap<Integer, Boolean> cache = new HashMap<>();
        final Set<Integer> allTagged = new HashSet<>();

        BenchSfTag(int numTags, int perTag) {
            Random r = new Random(99);
            for (int t = 0; t < numTags; t++) {
                Set<Integer> tag = new HashSet<>();
                for (int j = 0; j < perTag; j++) {
                    int m = r.nextInt(1500);
                    tag.add(m);
                    allTagged.add(m);
                }
                sim.tags.add(tag);
            }
        }

        /** 构造右键手持物材质流：~10% 命中、~90% 未命中（多数右键手持物不受 tag 约束）。 */
        int[] lookupMaterials(int n) {
            Random r = new Random(5);
            int[] out = new int[n];
            Integer[] hits = allTagged.toArray(new Integer[0]);
            for (int i = 0; i < n; i++) {
                out[i] = (r.nextInt(10) == 0 && hits.length > 0) ? hits[r.nextInt(hits.length)] : r.nextInt(2000);
            }
            return out;
        }
    }

    // ===== 物品解析微基准（1.3.0）=====
    static final class BenchItemResolve {
        final HashMap<String, Integer> sfRegistry = new HashMap<>(); // 建模 SlimefunItem 注册表
        final String[] ids;
        final Algorithms.CachedItemHolder[] holders; // 建模 Berry 上的缓存字段

        BenchItemResolve(int n) {
            ids = new String[n];
            holders = new Algorithms.CachedItemHolder[n];
            for (int i = 0; i < n; i++) {
                String id = "BUSH_" + i;
                int val = 5000 + i;
                sfRegistry.put(id, val);
                ids[i] = id;
                holders[i] = new Algorithms.CachedItemHolder();
                holders[i].item = val; // 预填充缓存（稳态：Berry 已解析过）
            }
        }
    }
}
