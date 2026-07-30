package io.github.thebusybiscuit.exoticgarden.items;

import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.DamageableItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotPlaceable;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.core.handlers.ToolUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.items.SimpleSlimefunItem;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.concurrent.ThreadLocalRandom;

public class Crook extends SimpleSlimefunItem<ToolUseHandler> implements NotPlaceable, DamageableItem {

    private static final int CHANCE = 25;

    /** 1.19+ 新树叶→树苗的显式映射（名称不遵循 XXX_LEAVES→XXX_SAPLING 规则）。 */
    private static final java.util.Map<Material, Material> LEAF_TO_SAPLING = new java.util.EnumMap<>(Material.class);
    static {
        LEAF_TO_SAPLING.put(Material.MANGROVE_LEAVES, Material.MANGROVE_PROPAGULE);
        LEAF_TO_SAPLING.put(Material.CHERRY_LEAVES, Material.CHERRY_SAPLING);
    }

    @ParametersAreNonnullByDefault
    public Crook(ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(itemGroup, item, recipeType, recipe);
        addItemHandler(onRightClick());
    }

    @Nonnull
    private ItemUseHandler onRightClick() {
        return PlayerRightClickEvent::cancel;
    }

    @Override
    public ToolUseHandler getItemHandler() {
        return (e, tool, fortune, drops) -> {
            damageItem(e.getPlayer(), tool);

            if (Tag.LEAVES.isTagged(e.getBlock().getType()) && ThreadLocalRandom.current().nextInt(100) < CHANCE) {
                Material leaf = e.getBlock().getType();
                // 1.19+ 新树叶的树苗名不遵循 "XXX_LEAVES"→"XXX_SAPLING" 规则，需显式映射；
                // 标准树仍走名称规则；找不到返回 null（如杜鹃叶无对应树苗，不掉）。
                Material saplingMaterial = LEAF_TO_SAPLING.get(leaf);
                if (saplingMaterial == null) {
                    saplingMaterial = Material.matchMaterial(leaf.name().replace("LEAVES", "SAPLING"));
                }
                if (saplingMaterial != null) {
                    drops.add(new ItemStack(saplingMaterial));
                }
            }
        };
    }

    @Override
    public boolean isDamageable() {
        return true;
    }

}
