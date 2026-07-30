package io.github.thebusybiscuit.exoticgarden;

import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.inventory.ItemStack;

/**
 * 机器物品输入/输出的纯计算工具。
 *
 * <p>取代旧实现中“创建临时 Bukkit Inventory 模拟堆叠”的做法（{@code inject/injectSub}）：
 * 原做法在异步 ticker 线程中调用 {@code Bukkit.createInventory} 不安全（Paper 会抛
 * “Asynchronous inventory creation!”），且每次 tick 分配大对象造成 GC 压力。
 * 本类仅基于 {@link BlockMenu} 槽位的读取 + 克隆做容量计算，线程安全、零额外大对象分配。</p>
 */
final class MachineIO {

    private MachineIO() {
    }

    /**
     * 检查 {@code items} 能否全部放入指定 {@code slots}（考虑同类型堆叠），不修改菜单。
     *
     * @param menu  目标菜单
     * @param slots 允许放入的槽位
     * @param items 待放入物品（null/空气将被忽略）
     * @return 全部可放入返回 true
     */
    static boolean fits(BlockMenu menu, int[] slots, ItemStack[] items) {
        ItemStack[] snapshot = new ItemStack[slots.length];
        for (int i = 0; i < slots.length; i++) {
            ItemStack current = menu.getItemInSlot(slots[i]);
            snapshot[i] = (current == null || current.getType().isAir()) ? null : current.clone();
        }

        for (ItemStack add : items) {
            if (add == null || add.getType().isAir()) {
                continue;
            }
            int remaining = add.getAmount();
            int max = add.getMaxStackSize() > 0 ? add.getMaxStackSize() : 64;

            // 先尝试堆叠到同类型槽
            for (int i = 0; i < snapshot.length && remaining > 0; i++) {
                ItemStack s = snapshot[i];
                if (s != null && s.isSimilar(add) && s.getAmount() < max) {
                    int can = Math.min(remaining, max - s.getAmount());
                    s.setAmount(s.getAmount() + can);
                    remaining -= can;
                }
            }
            // 再放入空槽
            for (int i = 0; i < snapshot.length && remaining > 0; i++) {
                if (snapshot[i] == null) {
                    int can = Math.min(remaining, max);
                    ItemStack neu = add.clone();
                    neu.setAmount(can);
                    snapshot[i] = neu;
                    remaining -= can;
                }
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把 {@code items} 放入指定 {@code slots}（实际修改菜单）。调用前应已通过 {@link #fits} 校验；
     * 若仍有剩余（理论不应发生），该部分将被丢弃而非塞入非目标槽位。
     */
    static void push(BlockMenu menu, int[] slots, ItemStack[] items) {
        for (ItemStack add : items) {
            if (add == null || add.getType().isAir()) {
                continue;
            }
            int remaining = add.getAmount();
            int max = add.getMaxStackSize() > 0 ? add.getMaxStackSize() : 64;

            // 先堆叠到同类型槽
            for (int slot : slots) {
                if (remaining <= 0) {
                    break;
                }
                ItemStack current = menu.getItemInSlot(slot);
                if (current != null && !current.getType().isAir() && current.isSimilar(add) && current.getAmount() < max) {
                    int can = Math.min(remaining, max - current.getAmount());
                    current.setAmount(current.getAmount() + can);
                    remaining -= can;
                    menu.replaceExistingItem(slot, current);
                }
            }
            // 再放入空槽
            for (int slot : slots) {
                if (remaining <= 0) {
                    break;
                }
                ItemStack current = menu.getItemInSlot(slot);
                if (current == null || current.getType().isAir()) {
                    int can = Math.min(remaining, max);
                    ItemStack neu = add.clone();
                    neu.setAmount(can);
                    menu.replaceExistingItem(slot, neu);
                    remaining -= can;
                }
            }
        }
    }
}
