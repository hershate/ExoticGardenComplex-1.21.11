import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

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

        // ---------- 正确性：新旧 match / fits 必须完全等价 ----------
        System.out.println("--- 正确性等价性断言 ---");
        int failMatch = correctnessMatch(threeRecipes, 200_000, new Random(123));
        int failSingle = correctnessMatchSingle(singleRecipes, 100_000, new Random(321));
        int failFits = correctnessFits(200_000, new Random(999));
        System.out.println("  3输入 match 等价: " + (failMatch == 0 ? "PASS" : ("FAIL(" + failMatch + ")")));
        System.out.println("  1输入 match 等价: " + (failSingle == 0 ? "PASS" : ("FAIL(" + failSingle + ")")));
        System.out.println("  fits 等价       : " + (failFits == 0 ? "PASS" : ("FAIL(" + failFits + ")")));
        System.out.println();

        System.out.println("(checksum=" + blackHole + ")");
        if (failMatch != 0 || failSingle != 0 || failFits != 0) {
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
}
