package io.github.thebusybiscuit.exoticgarden;

import io.github.thebusybiscuit.exoticgarden.schematics.Schematic;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;

public class Tree {

    private final String sapling;
    private final String texture;
    private final String fruit;
    private final Collection<Material> soils;

    private Schematic schematic;

    public Tree(String fruit, String texture, Material... soil) {
        this.sapling = fruit + "_SAPLING";
        this.texture = texture;
        this.fruit = fruit;
        this.soils = EnumSet.copyOf(Arrays.asList(soil));
    }

    public Schematic getSchematic() throws IOException {
        if (schematic == null) {
            schematic = Schematic.loadSchematic(new File(ExoticGarden.getInstance().getSchematicsFolder(), fruit + "_TREE.schematic"));
        }
        // loadSchematic 在文件缺失/损坏/格式非法时会吞掉异常并返回 null。
        // 这里把 null 转成 IOException，使调用方 (Schematic.pasteSchematic) 的
        // catch(IOException) 能正常兜底，避免后续 schematic.getBlocks() 触发 NPE。
        if (schematic == null) {
            throw new IOException("Failed to load schematic for tree: " + fruit + "_TREE.schematic (file missing, corrupt, or invalid)");
        }

        return schematic;
    }

    public ItemStack getItem() {
        return SlimefunItem.getById(sapling).getItem();
    }

    public String getTexture() {
        return this.texture;
    }

    public ItemStack getFruit() {
        return SlimefunItem.getById(fruit).getItem();
    }

    public String getFruitID() {
        return fruit;
    }

    public String getSapling() {
        return this.sapling;
    }

    public boolean isSoil(Material material) {
        return soils.contains(material);
    }

}
