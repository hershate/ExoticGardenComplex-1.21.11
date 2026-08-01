package io.github.thebusybiscuit.exoticgarden;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
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

public abstract class DefaultGUI extends SlimefunItem implements InventoryBlock, EnergyNetComponent {
    // 并发安全：Slimefun ticker 按区块并行 tick，不同机器方块会被多个线程同时访问这两个
    // static 表。HashMap 并发写入会丢数据/损坏结构，故使用 ConcurrentHashMap。
    public static final Map<Block, MachineRecipe> processing = new ConcurrentHashMap<>();
    public static final Map<Block, Integer> progress = new ConcurrentHashMap<>();
    private static final int[] border = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53};
    private static final int[] inputBorder = new int[]{10, 11, 12, 14, 15, 16};
    private static final int[] centerBorder = new int[]{19, 20, 21, 22, 23, 24, 25};
    private static final int[] outputBorder = new int[]{30, 32, 39, 40, 41};
    private static final int[] subSlotSign = new int[]{28, 29};
    private static final int[] mainSlotSign = new int[]{33, 34};
    protected final List<MachineRecipe> recipes = new ArrayList<>();

    // tick 内部使用的槽位常量（避免每次 new int[]）。对外公开的 getInputSlots()/
    // getOutputMainSlots()/getOutputSubSlots() 仍返回新数组（cargo/ItemTransport 可能持有
    // 并比对，保持原行为不变）。
    private static final int[] INPUT_SLOTS = new int[]{13};
    private static final int[] MAIN_OUTPUT_SLOTS = new int[]{42, 43};
    private static final int[] SUB_OUTPUT_SLOTS = new int[]{37, 38};

    // idle 机器“输入签名”缓存：输入（引用 + 数量）未变时跳过全量配方匹配。
    // recipes 在注册后固定，方块销毁/tick 发现菜单缺失时清理条目。
    private final Map<Block, IdleMatchCache> idleMatchCache = new ConcurrentHashMap<>();

    // 已解析的 Slimefun 物品按方块缓存：避免每 tick 调用 BlockStorage.check（定位→id→SF 物品）。
    // 机器方块的 SF 物品在其生命周期内不变；随方块销毁 / 菜单失效一同清理（与 idleMatchCache 同生命周期）。
    private final Map<Block, SlimefunItem> sfItemCache = new ConcurrentHashMap<>();

    // 方块 Location 按方块缓存：加工 tick 中能量读取/写回需要 Location（REF 的 EnergyNetComponent
    // 仅有 Location 重载，无 Block 重载）。原每 tick 两次 b.getLocation() 各分配一个 Location 对象；
    // 机器方块不移动，缓存其 Location 可消除稳态分配。随方块销毁 / 菜单失效一同清理。
    private final Map<Block, Location> locationCache = new ConcurrentHashMap<>();

    // 进度条基础物品 / 副产物列表的懒缓存（各子类返回值恒定，缓存零风险）。
    private ItemStack cachedProgressBar;
    private List<DefaultSubRecipe> cachedSubRecipes;

    /** idle 匹配缓存条目：输入槽引用快照 + 数量 + 上次命中的配方（null=上次无匹配）。 */
    private static final class IdleMatchCache {
        final ItemStack[] snapshot;
        final int[] amounts;
        final MachineRecipe recipe;

        IdleMatchCache(ItemStack[] snapshot, int[] amounts, MachineRecipe recipe) {
            this.snapshot = snapshot;
            this.amounts = amounts;
            this.recipe = recipe;
        }
    }


    public DefaultGUI(ItemGroup category, ItemStack item, String name, RecipeType recipeType, ItemStack[] recipe) {
        super(category, item, name, recipeType, recipe);

        new BlockMenuPreset(name, getInventoryTitle()) {

            public void init() {
                DefaultGUI.this.constructMenu(this);
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
                    return DefaultGUI.this.getInputSlots();
                }
                return DefaultGUI.this.getOutputMainSlots();
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

                    for (int slot : DefaultGUI.this.getInputSlots()) {
                        if (inv.getItemInSlot(slot) != null) {
                            b.getWorld().dropItemNaturally(b.getLocation(), inv.getItemInSlot(slot));
                        }
                    }
                    for (int slot : DefaultGUI.this.getOutputMainSlots()) {
                        if (inv.getItemInSlot(slot) != null) {
                            b.getWorld().dropItemNaturally(b.getLocation(), inv.getItemInSlot(slot));
                        }
                    }
                    for (int slot : DefaultGUI.this.getOutputSubSlots()) {
                        if (inv.getItemInSlot(slot) != null) {
                            b.getWorld().dropItemNaturally(b.getLocation(), inv.getItemInSlot(slot));
                        }
                    }
                }
                DefaultGUI.progress.remove(b);
                DefaultGUI.processing.remove(b);
                DefaultGUI.this.idleMatchCache.remove(b);
                DefaultGUI.this.sfItemCache.remove(b);
                DefaultGUI.this.locationCache.remove(b);
            }
        });
        addItemHandler(new BlockTicker() {

            public void tick(Block b, SlimefunItem sf, Config data) {
                DefaultGUI.this.tick(b);
            }


            public void uniqueTick() {
            }


            public boolean isSynchronized() {
                return false;
            }
        });
        registerDefaultRecipes();
    }

    public DefaultGUI(ItemGroup category, ItemStack item, String name, RecipeType recipeType, ItemStack[] recipe, ItemStack recipeOutput) {
        super(category, new SlimefunItemStack(name, item), recipeType, recipe, recipeOutput);

        new BlockMenuPreset(name, getInventoryTitle()) {

            public void init() {
                DefaultGUI.this.constructMenu(this);
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
                    return DefaultGUI.this.getInputSlots();
                }
                return DefaultGUI.this.getOutputMainSlots();
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

                    for (int slot : DefaultGUI.this.getInputSlots()) {
                        if (inv.getItemInSlot(slot) != null) {
                            b.getWorld().dropItemNaturally(b.getLocation(), inv.getItemInSlot(slot));
                        }
                    }
                    for (int slot : DefaultGUI.this.getOutputMainSlots()) {
                        if (inv.getItemInSlot(slot) != null) {
                            b.getWorld().dropItemNaturally(b.getLocation(), inv.getItemInSlot(slot));
                        }
                    }
                    for (int slot : DefaultGUI.this.getOutputSubSlots()) {
                        if (inv.getItemInSlot(slot) != null) {
                            b.getWorld().dropItemNaturally(b.getLocation(), inv.getItemInSlot(slot));
                        }
                    }
                }
                DefaultGUI.progress.remove(b);
                DefaultGUI.processing.remove(b);
                DefaultGUI.this.idleMatchCache.remove(b);
                DefaultGUI.this.sfItemCache.remove(b);
                DefaultGUI.this.locationCache.remove(b);
            }
        });
        addItemHandler(new me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker() {

            public void tick(Block b, SlimefunItem sf, Config data) {
                DefaultGUI.this.tick(b);
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
            preset.addItem(i, CustomItemStack.create(Material.BLACK_STAINED_GLASS_PANE, " "), (player, i1, itemStack, clickAction) -> false);
        }
        for (int i : inputBorder) {
            preset.addItem(i, CustomItemStack.create(Material.WHITE_STAINED_GLASS_PANE, " "), (player, i2, itemStack, clickAction) -> false);
        }
        for (int i : centerBorder) {
            preset.addItem(i, CustomItemStack.create(Material.BROWN_STAINED_GLASS_PANE, " "), (player, i4, itemStack, clickAction) -> false);
        }
        for (int i : outputBorder) {
            preset.addItem(i, CustomItemStack.create(Material.GREEN_STAINED_GLASS_PANE, " "), (player, i3, itemStack, clickAction) -> false);
        }
        for (int i : subSlotSign) {
            preset.addItem(i, CustomItemStack.create(Material.RED_STAINED_GLASS_PANE, "&e副输出槽", "", "&7副输出槽通常会输出机器的副产物", "&7有些副产物极其有用甚至非常珍贵"), (player, i6, itemStack, clickAction) -> false);
        }
        for (int i : mainSlotSign) {
            preset.addItem(i, CustomItemStack.create(Material.RED_STAINED_GLASS_PANE, "&c主输出槽", "", "&7主输出槽输出机器的常规产品"), (player, i5, itemStack, clickAction) -> false);
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
        return new int[]{13};
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
        if (subRecipes == null || subRecipes.isEmpty()) {
            return null;
        }
        return subRecipes.get(ThreadLocalRandom.current().nextInt(subRecipes.size()));
    }

    private boolean willOutput(DefaultSubRecipe recipe) {
        return ThreadLocalRandom.current().nextInt(10000) < recipe.getChance();
    }


    protected void tick(Block b) {
        BlockMenu menu = BlockStorage.getInventory(b);
        if (menu == null) {
            // 方块已不存在（被爆炸/移除等），清理残留状态避免 NPE 与内存泄漏。
            processing.remove(b);
            progress.remove(b);
            idleMatchCache.remove(b);
            sfItemCache.remove(b);
            locationCache.remove(b);
            return;
        }
        if (isProcessing(b)) {

            int timeleft = progress.get(b);
            if (timeleft > 0) {
                MachineRecipe current = processing.get(b);
                if (current == null) {
                    // 与下方完成分支一致：状态不一致（并发破坏等）时清理退出，避免 NPE。
                    progress.remove(b);
                    return;
                }

                // 进度条为纯显示：仅当有人正在查看 GUI 时才重建（clone + meta + replace）。
                // 无人查看的加工机器是常态，跳过可消除每 tick 的 ItemStack 克隆、meta 改写、
                // StringBuilder（getProgress/getTimeLeft）与 replaceExistingItem（含 markDirty）开销。
                // hasViewer() 经 toInventory() 返回内部 inv 字段（可能为 null，判空后安全）+ getViewers().isEmpty()。
                // 能量消耗与进度递减始终执行（游戏逻辑，与观看者无关）。
                if (menu.hasViewer()) {
                    ItemStack item = progressBar().clone();
                    item.setDurability(MachineHelper.getDurability(item, timeleft, current.getTicks()));
                    ItemMeta im = item.getItemMeta();
                    im.setDisplayName(" ");
                    List<String> lore = new ArrayList<>(3);
                    lore.add(MachineHelper.getProgress(timeleft, current.getTicks()));
                    lore.add("");
                    lore.add(MachineHelper.getTimeLeft(timeleft / 2));
                    im.setLore(lore);
                    item.setItemMeta(im);

                    menu.replaceExistingItem(31, item);
                }
                // 单次解析 Slimefun 物品并按方块缓存（原 ChargeableBlock.isChargeable/getCharge/addCharge
                // 各调用一次 BlockStorage.check 共 3 次，此处合并为 1 次且按方块缓存）。
                SlimefunItem sfItem = resolveSfItem(b);
                if (sfItem instanceof EnergyNetComponent component && component.isChargeable()) {
                    // 能量扣除：读→比较→写回。
                    // 修正：原 component.addCharge(loc, -consumption) 在 REF(4.9.5) 的 addCharge 中有
                    //   Validate.isTrue(charge > 0) —— 传入负数会抛 IllegalArgumentException（每 tick 必崩且不扣能量）。
                    // 改用 getCharge(loc,data) + setCharge(loc,data,...) 的 Config 重载：既修正语义（真正扣除能量），
                    // 又复用同一次 BlockStorage.getLocationInfo 查询（setCharge 内部的“是否变化”比较也用这个 data，
                    // 不再触发 removeCharge/addCharge 路径里的二次 getLocationInfo）。Location 用按方块缓存，零稳态分配。
                    int consumption = getEnergyConsumption();
                    Location loc = resolveLocation(b);
                    Config data = BlockStorage.getLocationInfo(loc);
                    if (data == null) {
                        return; // 方块存储瞬态缺失（刚放置未写入等），跳过本 tick 等下次重试，不抛异常。
                    }
                    int charge = component.getCharge(loc, data);
                    if (charge < consumption) {
                        return; // 能量不足，保持进度，等下次 tick 重试
                    }
                    component.setCharge(loc, data, charge - consumption);
                }
                progress.put(b, timeleft - 1);

            } else {
                MachineRecipe recipe = processing.get(b);
                if (recipe == null) {
                    // 状态不一致（无配方却进入完成分支），清理后退出，避免 NPE。
                    progress.remove(b);
                    return;
                }
                // 输出槽放不下时保持处理状态，等下次 tick 重试，避免产物凭空消失。
                if (!MachineIO.fits(menu, MAIN_OUTPUT_SLOTS, recipe.getOutput())) {
                    return;
                }

                menu.replaceExistingItem(31, CustomItemStack.create(Material.BLACK_STAINED_GLASS_PANE, " "));
                MachineIO.push(menu, MAIN_OUTPUT_SLOTS, recipe.getOutput());
                pushSubItems(menu, selectSubItem(subRecipes()));
                progress.remove(b);
                processing.remove(b);
            }

        } else {
            // idle：输入签名（按值 isSimilar + 数量）未变时跳过全量配方匹配 —— 稳态机器的常态。
            MachineRecipe r;
            int[] consume;
            IdleMatchCache cached = idleMatchCache.get(b);
            if (cached != null && inputUnchanged(cached, menu, INPUT_SLOTS)) {
                r = cached.recipe;
                if (r == null) {
                    return; // 上次无匹配，输入未变 → 仍无匹配，跳过
                }
                consume = deriveConsume(r, menu, INPUT_SLOTS);
            } else {
                // 冷路径：全量匹配。consume[p]>0 表示该输入槽已分配给某配方输入（不重复计数，防刷物品）。
                consume = new int[INPUT_SLOTS.length];
                r = null;
                for (MachineRecipe recipe : this.recipes) {
                    Arrays.fill(consume, 0);
                    if (matchRecipe(recipe, menu, INPUT_SLOTS, consume)) {
                        r = recipe;
                        break;
                    }
                }
                idleMatchCache.put(b, snapshotInput(menu, INPUT_SLOTS, r));
            }

            if (r != null) {
                if (!MachineIO.fits(menu, MAIN_OUTPUT_SLOTS, r.getOutput())) {
                    return;
                }
                for (int p = 0; p < INPUT_SLOTS.length; p++) {
                    if (consume[p] > 0) {
                        menu.consumeItem(INPUT_SLOTS[p], consume[p]);
                    }
                }
                processing.put(b, r);
                progress.put(b, r.getTicks());
            }
        }
    }

    /** 进度条基础物品的懒缓存（子类 getProgressBar() 返回值恒定）。 */
    private ItemStack progressBar() {
        ItemStack cached = cachedProgressBar;
        if (cached == null) {
            cached = getProgressBar();
            cachedProgressBar = cached;
        }
        return cached;
    }

    /** 副产物列表的懒缓存（子类 getSubRecipes() 返回值仅依赖 getLevel()，恒定）。 */
    private List<DefaultSubRecipe> subRecipes() {
        List<DefaultSubRecipe> cached = cachedSubRecipes;
        if (cached == null) {
            cached = getSubRecipes();
            cachedSubRecipes = cached;
        }
        return cached;
    }

    /**
     * 解析方块对应的 Slimefun 物品（按方块缓存，仅缓存非 null 结果）。
     *
     * <p>机器方块的 SF 物品在其生命周期内不变，无需每 tick 重复 {@code BlockStorage.check}
     * （定位区块→读取 id→SlimefunItem.getById）。仅在尚未缓存时查一次并记住；
     * null 不缓存（方块刚放下、存储尚未写入的瞬态），下次 tick 再查。随方块销毁 /
     * 菜单失效（{@link #tick} 中 menu==null）一同 {@code remove}，生命周期与 processing/
     * progress/idleMatchCache 完全一致。</p>
     */
    private SlimefunItem resolveSfItem(Block b) {
        SlimefunItem cached = sfItemCache.get(b);
        if (cached != null) {
            return cached;
        }
        SlimefunItem item = BlockStorage.check(b);
        if (item != null) {
            sfItemCache.put(b, item);
        }
        return item;
    }

    /**
     * 解析方块的 {@link Location}（按方块缓存）。
     *
     * <p>机器方块在其生命周期内不移动，能量读写所需的 Location 可缓存复用，避免每 tick
     * {@code b.getLocation()} 分配。Location 仅作为只读键传给 BlockStorage（读取 world + 区块坐标），
     * 不被修改，缓存安全。随方块销毁 / 菜单失效（{@link #tick} 中 menu==null）一同 {@code remove}，
     * 生命周期与 {@link #sfItemCache} 完全一致。</p>
     */
    private Location resolveLocation(Block b) {
        Location cached = locationCache.get(b);
        if (cached != null) {
            return cached;
        }
        Location loc = b.getLocation();
        locationCache.put(b, loc);
        return loc;
    }

    /**
     * 输入签名比对：按值（isSimilar + 数量）判断输入是否变化。
     *
     * <p>不能用引用相等：{@code BlockMenu.getItemInSlot} 经 Bukkit {@code Inventory.getItem}
     * 返回，Craftbukkit 通常每次返回新的 {@code CraftItemStack} 包装对象（引用不等，即使
     * 底层 NMS 物品相同）。按值比较既兼容该行为，也兼容返回稳定引用的实现，且仅需
     * 输入槽数量次 isSimilar（1~3 次），远少于全量配方扫描。</p>
     */
    private boolean inputUnchanged(IdleMatchCache cached, BlockMenu menu, int[] slots) {
        ItemStack[] snap = cached.snapshot;
        if (snap.length != slots.length) {
            return false;
        }
        int[] snapAmt = cached.amounts;
        for (int p = 0; p < slots.length; p++) {
            ItemStack cur = menu.getItemInSlot(slots[p]);
            ItemStack was = snap[p];
            if (cur == null) {
                if (was != null) {
                    return false; // 有物 → 空
                }
            } else {
                if (was == null) {
                    return false; // 空 → 有物
                }
                if (cur.getAmount() != snapAmt[p]) {
                    return false; // 数量变化（含原地 setAmount）
                }
                if (!cur.isSimilar(was)) {
                    return false; // 物品变化（类型 / SF id / meta 不同）
                }
            }
        }
        return true;
    }

    /** 捕获当前输入槽签名 + 命中的配方（null=无匹配），写入缓存。 */
    private IdleMatchCache snapshotInput(BlockMenu menu, int[] slots, MachineRecipe recipe) {
        int n = slots.length;
        ItemStack[] snap = new ItemStack[n];
        int[] amt = new int[n];
        for (int p = 0; p < n; p++) {
            ItemStack it = menu.getItemInSlot(slots[p]);
            snap[p] = it;
            amt[p] = it == null ? 0 : it.getAmount();
        }
        return new IdleMatchCache(snap, amt, recipe);
    }

    /** 在已清零的 consume 上尝试匹配单个配方；成功则 consume[p] 置为该输入需求量。 */
    private boolean matchRecipe(MachineRecipe recipe, BlockMenu menu, int[] slots, int[] consume) {
        for (ItemStack input : recipe.getInput()) {
            if (input == null) {
                continue;
            }
            int needed = input.getAmount();
            int matchedSlot = -1;
            for (int p = 0; p < slots.length; p++) {
                if (consume[p] != 0) {
                    continue; // 每个输入槽只匹配一个配方输入，避免重复计数导致刷物品
                }
                ItemStack slotItem = menu.getItemInSlot(slots[p]);
                if (slotItem != null
                        && slotItem.getAmount() >= needed
                        && SlimefunUtils.isItemSimilar(slotItem, input, true)) {
                    matchedSlot = p;
                    break;
                }
            }
            if (matchedSlot < 0) {
                return false;
            }
            consume[matchedSlot] = needed;
        }
        return true;
    }

    /** 已知 recipe 命中时重建各槽消耗量（单配方扫描，比全量匹配廉价）。 */
    private int[] deriveConsume(MachineRecipe recipe, BlockMenu menu, int[] slots) {
        int[] consume = new int[slots.length];
        matchRecipe(recipe, menu, slots, consume);
        return consume;
    }

    /** 副产物放入副输出槽（menu 版，供 tick 复用已取的菜单）。 */
    private void pushSubItems(BlockMenu menu, DefaultSubRecipe recipe) {
        if (recipe == null || recipe.getItem() == null) {
            return;
        }
        ItemStack item = recipe.getItem();
        // fits 检查针对副输出槽（原实现误用主输出槽，副槽满时仍放入而丢失物品）。
        if (willOutput(recipe) && MachineIO.fits(menu, SUB_OUTPUT_SLOTS, new ItemStack[]{item})) {
            MachineIO.push(menu, SUB_OUTPUT_SLOTS, new ItemStack[]{item});
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


