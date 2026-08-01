package io.github.thebusybiscuit.exoticgarden;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.EnumSet;
import java.util.Set;

public class Berry {

    private static final Set<Material> SOILS = EnumSet.of(Material.GRASS_BLOCK, Material.DIRT);

    private final ItemStack item;
    private final String id;
    private final String texture;
    private final PlantType type;

    // 解析后的植物物品 / 灌木物品懒缓存。getItem() 在采集（harvestPlant）与植物生长
    // （growStructure0/waterStructure/growBush）中高频调用，原每次都做 SlimefunItem.getById
    // 的 Map 查找；SF 物品注册后恒定，缓存其引用即可（与原 getItem() 返回同一引用，语义不变）。
    private ItemStack cachedPlantItem;
    private ItemStack cachedBushItem;

    @ParametersAreNonnullByDefault
    public Berry(String id, PlantType type, String texture) {
        this(null, id, type, texture);
    }

    @ParametersAreNonnullByDefault
    public Berry(@Nullable ItemStack item, String id, PlantType type, String texture) {
        this.item = item;
        this.id = id;
        this.texture = texture;
        this.type = type;
    }

    /**
     * Returns the identifier of this Berry.
     *
     * @return the identifier of this Berry
     */
    public String getID() {
        return this.id;
    }

    public ItemStack getItem() {
        ItemStack cached = cachedPlantItem;
        if (cached != null) {
            return cached;
        }
        ItemStack resolved = type == PlantType.ORE_PLANT ? item : SlimefunItem.getById(id).getItem();
        if (resolved != null) {
            cachedPlantItem = resolved;
        }
        return resolved;
    }

    public String getTexture() {
        return this.texture;
    }

    public PlantType getType() {
        return type;
    }

    public String toBush() {
        return type == PlantType.ORE_PLANT ? this.id.replace("_ESSENCE", "_PLANT") : this.id + "_BUSH";
    }

    /**
     * 解析“灌木/植物方块”物品（按 {@link #toBush()} 的 SF id 查找，懒缓存）。
     *
     * <p>harvestPlant 采集后需把方块重新存为灌木；原每次都调用 {@code ExoticGarden.getItem(toBush())}
     * （含 {@code SlimefunItem.getById} + {@code Material.getMaterial} 兜底）。灌木 id 对应的 SF 物品
     * 注册后恒定，缓存其引用避免重复查找。未注册（null）不缓存，下次再查。</p>
     */
    public ItemStack getBushItem() {
        ItemStack cached = cachedBushItem;
        if (cached != null) {
            return cached;
        }
        ItemStack resolved = ExoticGarden.getItem(toBush());
        if (resolved != null) {
            cachedBushItem = resolved;
        }
        return resolved;
    }

    public boolean isSoil(Material type) {
        return SOILS.contains(type);
    }

}
