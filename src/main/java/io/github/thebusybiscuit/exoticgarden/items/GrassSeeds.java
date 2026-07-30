package io.github.thebusybiscuit.exoticgarden.items;

import io.github.thebusybiscuit.exoticgarden.ExoticGarden;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.items.SimpleSlimefunItem;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.ItemUtils;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import org.bukkit.Effect;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;

import javax.annotation.ParametersAreNonnullByDefault;

public class GrassSeeds extends SimpleSlimefunItem<ItemUseHandler> {

    @ParametersAreNonnullByDefault
    public GrassSeeds(ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(itemGroup, item, recipeType, recipe);
    }

    @Override
    public ItemUseHandler getItemHandler() {
        return e -> {
            if (e.getClickedBlock().isEmpty()) {
                return;
            }
            Block b = e.getClickedBlock().get();
            if (b.getType() != Material.DIRT) {
                return;
            }
            // 领地保护：禁止在他人领地内改写方块（客户端输入不可信，不可放任任意改写）。
            if (!Slimefun.getProtectionManager().hasPermission(e.getPlayer(), b.getLocation(), Interaction.PLACE_BLOCK)) {
                return;
            }

            if (e.getPlayer().getGameMode() != GameMode.CREATIVE) {
                ItemUtils.consumeItem(e.getItem(), false);
            }

            b.setType(Material.GRASS_BLOCK);

            if (b.getRelative(BlockFace.UP).getType() == Material.AIR) {
                b.getRelative(BlockFace.UP).setType(Material.SHORT_GRASS);
            }

            b.getWorld().playEffect(b.getLocation(), Effect.STEP_SOUND, Material.SHORT_GRASS);
        };
    }

}
