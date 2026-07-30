package io.github.thebusybiscuit.exoticgarden;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.interfaces.InventoryBlock;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;

public abstract class ThreeInputGUI extends SlimefunItem implements InventoryBlock, EnergyNetComponent {
    public static final Map<Block, MachineRecipe> processing = new HashMap<>();
    public static final Map<Block, Integer> progress = new HashMap<>();
    private static final int[] border = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53};
    private static final int[] inputBorder = new int[]{10, 12, 14, 16};
    private static final int[] centerBorder = new int[]{19, 20, 21, 22, 23, 24, 25};
    private static final int[] outputBorder = new int[]{30, 32, 39, 40, 41};
    private static final int[] subSlotSign = new int[]{28, 29};
    private static final int[] mainSlotSign = new int[]{33, 34};
    protected final List<MachineRecipe> recipes = new ArrayList<>();


    public ThreeInputGUI(ItemGroup category, ItemStack item, String name, RecipeType recipeType, ItemStack[] recipe) {
        super(category, item, name, recipeType, recipe);

        new BlockMenuPreset(name, getInventoryTitle()) {

            public void init() {
                ThreeInputGUI.this.constructMenu(this);
            }


            public void newInstance(BlockMenu menu, Block b) {
            }


            public boolean canOpen(Block b, Player p) {
                // 安全：必须拥有该方块的交互权限（领地/保护）才能打开 GUI。
                // 原实现恒返回 true，任何人都能打开他人机器取走原料/产物，绕过保护。
                return Slimefun.getProtectionManager().hasPermission(p, b.getLocation(), Interaction.INTERACT_BLOCK);
            }


            public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
                if (flow.equals(ItemTransportFlow.INSERT)) {
                    return ThreeInputGUI.this.getInputSlots();
                }
                return ThreeInputGUI.this.getOutputMainSlots();
            }
        };

        addItemHandler(new BlockPlaceHandler(false) {
            @Override
            public void onPlayerPlace(@NotNull BlockPlaceEvent blockPlaceEvent) {

            }
        });
        addItemHandler(new BlockBreakHandler(false, false) {
            @Override
            public void onPlayerBreak(BlockBreakEvent blockBreakEvent, ItemStack itemStack, List<ItemStack> list) {
                var b = blockBreakEvent.getBlock();
                BlockMenu inv = BlockStorage.getInventory(b);
                if (inv != null) {

                    for (int slot : ThreeInputGUI.this.getInputSlots()) {
                        if (inv.getItemInSlot(slot) != null) {
                            b.getWorld().dropItemNaturally(b.getLocation(), inv.getItemInSlot(slot));
                        }
                    }
                    for (int slot : ThreeInputGUI.this.getOutputMainSlots()) {
                        if (inv.getItemInSlot(slot) != null) {
                            b.getWorld().dropItemNaturally(b.getLocation(), inv.getItemInSlot(slot));
                        }
                    }
                    for (int slot : ThreeInputGUI.this.getOutputSubSlots()) {
                        if (inv.getItemInSlot(slot) != null) {
                            b.getWorld().dropItemNaturally(b.getLocation(), inv.getItemInSlot(slot));
                        }
                    }
                }
                ThreeInputGUI.progress.remove(b);
                ThreeInputGUI.processing.remove(b);
            }
        });
        addItemHandler(new BlockTicker() {

            public void tick(Block b, SlimefunItem sf, Config data) {
                ThreeInputGUI.this.tick(b);
            }


            public void uniqueTick() {
            }


            public boolean isSynchronized() {
                return false;
            }
        });
        registerDefaultRecipes();
    }

    public ThreeInputGUI(ItemGroup category, ItemStack item, String name, RecipeType recipeType, ItemStack[] recipe, ItemStack recipeOutput) {
        super(category, new SlimefunItemStack(name, item), recipeType, recipe, recipeOutput);

        new BlockMenuPreset(name, getInventoryTitle()) {

            public void init() {
                ThreeInputGUI.this.constructMenu(this);
            }


            public void newInstance(BlockMenu menu, Block b) {
            }


            public boolean canOpen(Block b, Player p) {
                // 安全：必须拥有该方块的交互权限（领地/保护）才能打开 GUI。
                // 原实现恒返回 true，任何人都能打开他人机器取走原料/产物，绕过保护。
                return Slimefun.getProtectionManager().hasPermission(p, b.getLocation(), Interaction.INTERACT_BLOCK);
            }


            public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
                if (flow.equals(ItemTransportFlow.INSERT)) {
                    return ThreeInputGUI.this.getInputSlots();
                }
                return ThreeInputGUI.this.getOutputMainSlots();
            }
        };

        addItemHandler(new BlockPlaceHandler(false) {
            @Override
            public void onPlayerPlace(@NotNull BlockPlaceEvent blockPlaceEvent) {

            }
        });
        addItemHandler(new BlockBreakHandler(false, false) {
            @Override
            public void onPlayerBreak(BlockBreakEvent blockBreakEvent, ItemStack itemStack, List<ItemStack> list) {
                Block b = blockBreakEvent.getBlock();
                BlockMenu inv = BlockStorage.getInventory(b);
                if (inv != null) {
                    for (int slot : ThreeInputGUI.this.getInputSlots()) {
                        if (inv.getItemInSlot(slot) != null) {
                            b.getWorld().dropItemNaturally(b.getLocation(), inv.getItemInSlot(slot));
                        }
                    }
                    for (int slot : ThreeInputGUI.this.getOutputMainSlots()) {
                        if (inv.getItemInSlot(slot) != null) {
                            b.getWorld().dropItemNaturally(b.getLocation(), inv.getItemInSlot(slot));
                        }
                    }
                    for (int slot : ThreeInputGUI.this.getOutputSubSlots()) {
                        if (inv.getItemInSlot(slot) != null) {
                            b.getWorld().dropItemNaturally(b.getLocation(), inv.getItemInSlot(slot));
                        }
                    }
                }
                ThreeInputGUI.processing.remove(b);
                ThreeInputGUI.progress.remove(b);
            }
        });
        addItemHandler(new BlockTicker() {

            public void tick(Block b, SlimefunItem sf, Config data) {
                ThreeInputGUI.this.tick(b);
            }


            public void uniqueTick() {
            }


            public boolean isSynchronized() {
                return false;
            }
        });
        registerDefaultRecipes();
    }

    public int[] getOutputSlots() {
        // 注意：不可使用 Stream#toList() —— 它返回不可变 List，后续 addAll 会抛
        // UnsupportedOperationException，导致 cargo/机器人提取产物时崩溃。
        int[] sub = getOutputSubSlots();
        int[] main = getOutputMainSlots();
        int[] o = new int[sub.length + main.length];
        System.arraycopy(sub, 0, o, 0, sub.length);
        System.arraycopy(main, 0, o, sub.length, main.length);
        return o;
    }

    private void constructMenu(BlockMenuPreset preset) {
        for (int i : border) {
            preset.addItem(i, CustomItemStack.create(Material.BLACK_STAINED_GLASS_PANE, " "), (player, i6, itemStack, clickAction) -> false);
        }
        for (int i : inputBorder) {
            preset.addItem(i, CustomItemStack.create(Material.WHITE_STAINED_GLASS_PANE, " "), (player, i5, itemStack, clickAction) -> false);
        }
        for (int i : centerBorder) {
            preset.addItem(i, CustomItemStack.create(Material.BROWN_STAINED_GLASS_PANE, " "), (player, i4, itemStack, clickAction) -> false);
        }
        for (int i : outputBorder) {
            preset.addItem(i, CustomItemStack.create(Material.GREEN_STAINED_GLASS_PANE, " "), (player, i3, itemStack, clickAction) -> false);
        }
        for (int i : subSlotSign) {
            preset.addItem(i, CustomItemStack.create(Material.RED_STAINED_GLASS_PANE, "&e副输出槽", "", "&7副输出槽通常会输出机器的副产物", "&7有些副产物极其有用甚至非常珍贵"), (player, i2, itemStack, clickAction) -> false);
        }
        for (int i : mainSlotSign) {
            preset.addItem(i, CustomItemStack.create(Material.RED_STAINED_GLASS_PANE, "&c主输出槽", "", "&7主输出槽输出机器的常规产品"), (player, i1, itemStack, clickAction) -> false);
        }
        preset.addItem(31, CustomItemStack.create(Material.PINK_STAINED_GLASS_PANE, " "), (player, i, itemStack, clickAction) -> false);

        preset.addItem(38, null, new ChestMenu.AdvancedMenuClickHandler() {
            public boolean onClick(Player player, int i, ItemStack item, ClickAction action) {
                return false;
            }


            public boolean onClick(InventoryClickEvent event, Player player, int slot, ItemStack item, ClickAction action) {
                return (item == null || item.getType() == null || item.getType() == Material.AIR);
            }
        });
    }

    public int[] getInputSlots() {
        return new int[]{11, 13, 15};
    }

    public int[] getOutputSubSlots() {
        return new int[]{37, 38};
    }

    public int[] getOutputMainSlots() {
        return new int[]{42, 43};
    }


    public MachineRecipe getProcessing(Block b) {
        return processing.get(b);
    }


    public boolean isProcessing(Block b) {
        return (getProcessing(b) != null);
    }


    public void registerRecipe(MachineRecipe recipe) {
        recipe.setTicks(recipe.getTicks());
        this.recipes.add(recipe);
    }


    public void registerRecipe(int seconds, ItemStack[] input, ItemStack[] output) {
        registerRecipe(new MachineRecipe(seconds, input, output));
    }


    protected boolean fits(Block b, ItemStack[] items) {
        BlockMenu menu = BlockStorage.getInventory(b);
        if (menu == null) {
            return false;
        }
        // 用纯计算校验（MachineIO），不再创建临时 Bukkit Inventory——后者在异步 ticker
        // 线程中调用 Bukkit.createInventory 不安全，且每次 tick 分配大对象有 GC 压力。
        return MachineIO.fits(menu, getOutputMainSlots(), items);
    }


    protected void pushMainItems(Block b, ItemStack[] items) {
        BlockMenu menu = BlockStorage.getInventory(b);
        if (menu == null) {
            return;
        }
        MachineIO.push(menu, getOutputMainSlots(), items);
    }


    protected void pushSubItems(Block b, DefaultSubRecipe recipe) {
        if (recipe == null || recipe.getItem() == null) {
            return;
        }
        BlockMenu menu = BlockStorage.getInventory(b);
        if (menu == null) {
            return;
        }
        ItemStack item = recipe.getItem();
        // 副产物放入副输出槽；fits 检查也必须针对副输出槽（原实现误用主输出槽，导致
        // 副槽满时仍尝试放入而丢失物品）。
        if (willOutput(recipe) && MachineIO.fits(menu, getOutputSubSlots(), new ItemStack[]{item})) {
            MachineIO.push(menu, getOutputSubSlots(), new ItemStack[]{item});
        }
    }

    protected DefaultSubRecipe selectSubItem(List<DefaultSubRecipe> subRecipes) {
        int random = (int) (Math.random() * subRecipes.size());
        return subRecipes.get(random);
    }

    private boolean willOutput(DefaultSubRecipe recipe) {
        Random random = new Random();
        int point = random.nextInt(10000);
        return (point < recipe.getChance());
    }

    protected void tick(Block b) {
        if (isProcessing(b)) {

            int timeleft = progress.get(b);
            if (timeleft > 0) {

                ItemStack item = getProgressBar().clone();
                item.setDurability(MachineHelper.getDurability(item, timeleft, processing.get(b).getTicks()));
                ItemMeta im = item.getItemMeta();
                im.setDisplayName(" ");
                List<String> lore = new ArrayList<>();
                lore.add(MachineHelper.getProgress(timeleft, processing.get(b).getTicks()));
                lore.add("");
                lore.add(MachineHelper.getTimeLeft(timeleft / 2));
                im.setLore(lore);
                item.setItemMeta(im);

                BlockStorage.getInventory(b).replaceExistingItem(31, item);
                if (ChargeableBlock.isChargeable(b)) {
                    if (ChargeableBlock.getCharge(b) < getEnergyConsumption()) {
                        return;
                    }
                    ChargeableBlock.addCharge(b, -getEnergyConsumption());
                    progress.put(b, timeleft - 1);
                } else {
                    progress.put(b, timeleft - 1);
                }

            } else {

                BlockStorage.getInventory(b).replaceExistingItem(31, CustomItemStack.create(Material.BLACK_STAINED_GLASS_PANE, " "));
                pushMainItems(b, processing.get(b).getOutput());
                pushSubItems(b, selectSubItem(getSubRecipes()));
                progress.remove(b);
                processing.remove(b);
            }

        } else {

            BlockMenu menu = BlockStorage.getInventory(b);
            if (menu == null) {
                return;
            }
            MachineRecipe r = null;
            Map<Integer, Integer> found = new HashMap<>();
            for (MachineRecipe recipe : this.recipes) {
                found.clear();
                boolean matched = true;
                for (ItemStack input : recipe.getInput()) {
                    if (input == null) {
                        continue;
                    }
                    int needed = input.getAmount();
                    int matchedSlot = -1;
                    for (int slot : getInputSlots()) {
                        if (found.containsKey(slot)) {
                            // 每个输入槽只匹配一个配方输入，避免重复计数导致刷物品
                            continue;
                        }
                        ItemStack slotItem = menu.getItemInSlot(slot);
                        if (slotItem != null
                                && slotItem.getAmount() >= needed
                                && SlimefunUtils.isItemSimilar(slotItem, input, true)) {
                            matchedSlot = slot;
                            break;
                        }
                    }
                    if (matchedSlot < 0) {
                        matched = false;
                        break;
                    }
                    found.put(matchedSlot, needed);
                }

                if (matched) {
                    r = recipe;
                    break;
                }
            }
            if (r != null) {

                if (!fits(b, r.getOutput())) {
                    return;
                }
                for (Map.Entry<Integer, Integer> entry : found.entrySet()) {
                    menu.consumeItem(entry.getKey(), entry.getValue());
                }
                processing.put(b, r);
                progress.put(b, r.getTicks());
            }
        }
    }

    public abstract String getInventoryTitle();

    public abstract ItemStack getProgressBar();

    public abstract List<DefaultSubRecipe> getSubRecipes();

    public abstract void registerDefaultRecipes();

    public abstract int getEnergyConsumption();

    public abstract int getLevel();

    public abstract String getMachineIdentifier();

    public EnergyNetComponentType getEnergyComponentType() {
        return EnergyNetComponentType.CONSUMER;
    }
}


