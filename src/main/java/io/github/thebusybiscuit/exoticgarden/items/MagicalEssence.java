package io.github.thebusybiscuit.exoticgarden.items;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import org.bukkit.inventory.ItemStack;

import javax.annotation.ParametersAreNonnullByDefault;

public class MagicalEssence extends SlimefunItem {

    @ParametersAreNonnullByDefault
    public MagicalEssence(ItemGroup itemGroup, SlimefunItemStack item) {
        // 精华由 MagicalEssence 机器产出，不应通过增强台合成。原 8→1 自指配方会让玩家
        // 误操作损失 7 个精华（无合理合成意图）。改为 1→1 占位（无损失、无收益）。
        super(itemGroup, item, RecipeType.ENHANCED_CRAFTING_TABLE, new ItemStack[]{null, null, null, null, item.item(), null, null, null, null});
    }

    @Override
    public boolean useVanillaBlockBreaking() {
        return true;
    }

}
