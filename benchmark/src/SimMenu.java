import java.util.concurrent.atomic.AtomicInteger;

/**
 * 机器菜单的离线模型，忠实复刻生产中 {@code BlockMenu}/{@code DirtyChestMenu} 在
 * {@code DefaultGUI.tick} 处理分支里被用到的开销点。
 *
 * <p>生产侧每 tick（加工中、有人查看时）会执行：
 * <ul>
 *   <li>{@code menu.replaceExistingItem(31, item)} —— 内部 {@code getItemInSlot}（setup + 读槽）
 *       + {@code preset.onItemStackChange}（事件）+ {@code super.replaceExistingItem}（写槽）
 *       + {@code markDirty}（{@code changes.incrementAndGet()}）；</li>
 *   <li>{@code menu.hasViewer()} —— {@code toInventory()}（返回内部 inv 字段，可能为 null）
 *       + {@code inv.getViewers().isEmpty()}。</li>
 * </ul>
 * 本模型把这些开销点以同等结构复刻出来，使“无人查看时跳过进度条重建”的收益可测、可复现。
 * 绝对耗时是真实 CraftBukkit 的近似，但新旧相对比值与生产对应。</p>
 */
public final class SimMenu {

    /** 槽位内容（54 槽，足够覆盖输入/输出/显示槽）。 */
    final SimItem[] slots = new SimItem[54];

    /** 对应 DirtyChestMenu.changes：每次 replaceExistingItem 自增（markDirty）。 */
    final AtomicInteger changes = new AtomicInteger(1);

    /** 是否有观看者（模拟 inv != null && !getViewers().isEmpty()）。 */
    boolean viewer;

    /** 对应 DirtyChestMenu.toInventory()：返回内部 inv（这里以 viewer 字段直接建模观看者判定）。 */
    boolean hasViewer() {
        return viewer;
    }

    /** 对应 ChestMenu.getItemInSlot：setup() 后读槽。setup 在生产中按需建 inv（瞬态，已摊销）。 */
    SimItem getItemInSlot(int slot) {
        return slots[slot];
    }

    /**
     * 对应 DirtyChestMenu.replaceExistingItem(slot, item)（event=true）：
     * 读旧值 → onItemStackChange（事件，建模为原样返回）→ 写槽 → markDirty（自增 changes）。
     */
    void replaceExistingItem(int slot, SimItem item) {
        SimItem previous = slots[slot];
        // preset.onItemStackChange(this, slot, previous, item) —— 建模为直接采用 item
        SimItem resolved = item;
        slots[slot] = resolved;
        changes.incrementAndGet(); // markDirty
    }
}
