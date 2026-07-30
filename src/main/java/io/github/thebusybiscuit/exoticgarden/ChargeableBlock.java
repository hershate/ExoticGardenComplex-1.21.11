package io.github.thebusybiscuit.exoticgarden;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 充能方块工具：基于官方 {@link EnergyNetComponent} 接口读写电量。
 *
 * <p>历史版本通过 xzavier0722 存储系统（StorageCacheUtils / SlimefunBlockData）自行管理
 * energy-charge/energy-capacity。REF（官方 Slimefun 4.9.5）不含该存储系统，故统一委托
 * {@link EnergyNetComponent} 的 default 方法（其内部已使用官方 BlockStorage 读写电量）。
 * 原 Location 重载无外部调用，已移除。</p>
 */
public class ChargeableBlock {

    public static boolean isChargeable(@NotNull Block block) {
        SlimefunItem item = getSfItem(block);
        if (item instanceof EnergyNetComponent component) {
            return component.isChargeable();
        } else {
            return false;
        }
    }

    public static @Nullable SlimefunItem getSfItem(@NotNull Block block) {
        return BlockStorage.check(block);
    }

    public static int getCharge(@NotNull Block block) {
        SlimefunItem item = getSfItem(block);
        if (item instanceof EnergyNetComponent component) {
            return component.getCharge(block.getLocation());
        } else {
            return 0;
        }
    }

    public static void setCharge(@NotNull Block block, int charge) {
        SlimefunItem item = getSfItem(block);
        if (item instanceof EnergyNetComponent component) {
            component.setCharge(block.getLocation(), charge);
        }
    }

    public static void addCharge(@NotNull Block block, int charge) {
        if (charge < 0) {
            removeCharge(block, -charge);
            return;
        }

        SlimefunItem item = getSfItem(block);
        if (item instanceof EnergyNetComponent component) {
            component.addCharge(block.getLocation(), charge);
        }
    }

    public static void removeCharge(@NotNull Block block, int charge) {
        if (charge < 0) {
            addCharge(block, -charge);
            return;
        }

        SlimefunItem item = getSfItem(block);
        if (item instanceof EnergyNetComponent component) {
            component.removeCharge(block.getLocation(), charge);
        }
    }
}
