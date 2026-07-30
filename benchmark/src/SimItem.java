/**
 * 纯 Java 物品模型，用于离线基准测试。
 *
 * <p>生产代码的物品操作（构造/克隆/isSimilar/getAmount/setAmount）依赖运行中的
 * Bukkit/Paper 服务端（{@code ItemStack}、{@code SlimefunUtils.isItemSimilar}、
 * {@code BlockMenu} 均无法脱离服务器实例化或执行）。为量化本插件自身算法层的
 * 性能改动，这里用一个最小但“形似”的物品模型忠实复刻相关算法，使新旧实现的
 * 相对耗时差异可测、可复现。</p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@link #isSimilar} 忽略数量（与 Bukkit 语义一致），比较 material + sfId + meta；</li>
 *   <li>meta 用 4-int 数组模拟真实 NBT/lore 比较的“一定工作量”，使“减少 isSimilar 调用
 *       次数”的收益能在耗时上体现；</li>
 *   <li>{@link #clone()} 复刻 Bukkit 的克隆开销（分配 + 复制 meta）。</li>
 * </ul>
 * </p>
 */
public final class SimItem {

    final int material;   // Material.ordinal() 的代理
    final int sfId;       // Slimefun 物品 id 的代理（-1 = 非 SF 物品）
    final int[] meta;     // 4-int 元数据代理
    int amount;

    SimItem(int material, int sfId, int amount) {
        this.material = material;
        this.sfId = sfId;
        this.amount = amount;
        // meta 由 (material, sfId) 确定性派生 —— 与生产语义一致：SlimefunUtils.isItemSimilar
        // 按 Slimefun 物品 id（PDC）判定相似性，故“同一 SF 物品的任意两个实例”必须相似。
        // 克隆时仍需深拷贝 meta（见 clone()），保留 ItemStack.clone 的分配开销建模。
        this.meta = new int[]{material, sfId, material ^ sfId, sfId * 31 + material};
    }

    static SimItem of(int material, int sfId, int amount) {
        return new SimItem(material, sfId, amount);
    }

    int getAmount() {
        return amount;
    }

    void setAmount(int a) {
        this.amount = a;
    }

    int getMaxStackSize() {
        return 64;
    }

    boolean isSimilar(SimItem other) {
        if (other == null) {
            return false;
        }
        if (this == other) {
            return true;
        }
        // 先比廉价字段
        if (this.material != other.material || this.sfId != other.sfId) {
            return false;
        }
        // 再比 meta（模拟真实 ItemMeta / PersistentData 比较）
        int[] a = this.meta;
        int[] b = other.meta;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public SimItem clone() {
        SimItem c = new SimItem(material, sfId, amount);
        // 深拷贝 meta（模拟 Bukkit ItemStack.clone 复制 ItemMeta）
        System.arraycopy(this.meta, 0, c.meta, 0, this.meta.length);
        return c;
    }
}
