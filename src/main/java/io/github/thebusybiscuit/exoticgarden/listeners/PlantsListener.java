package io.github.thebusybiscuit.exoticgarden.listeners;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import io.github.thebusybiscuit.exoticgarden.Berry;
import io.github.thebusybiscuit.exoticgarden.ExoticGarden;
import io.github.thebusybiscuit.exoticgarden.ExoticItems;
import io.github.thebusybiscuit.exoticgarden.PlantType;
import io.github.thebusybiscuit.exoticgarden.Tree;
import io.github.thebusybiscuit.exoticgarden.items.BonemealableItem;
import io.github.thebusybiscuit.exoticgarden.schematics.Schematic;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.dough.config.Config;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import io.github.thebusybiscuit.slimefun4.libraries.dough.skins.PlayerHead;
import io.github.thebusybiscuit.slimefun4.libraries.dough.skins.PlayerSkin;
import io.github.thebusybiscuit.slimefun4.libraries.paperlib.PaperLib;
import io.github.thebusybiscuit.slimefun4.utils.tags.SlimefunTag;
import me.mrCookieSlime.Slimefun.api.BlockStorage;

public class PlantsListener implements Listener {

    private static final Map<String, SlimefunTag> nameLookup = new HashMap<>();
    private static final SlimefunTag[] valuesCache = SlimefunTag.values();
    // “某 Material 是否被任意 SlimefunTag 标记”的结果缓存。SlimefunTag 在 Slimefun 加载后固定不变，
    // 故每个 Material 的判定结果恒定，可永久缓存，将 onInteract 中“遍历全部 tag 逐一 isTagged”
    // 的 O(标签数) 降为 O(1) 命中。ConcurrentHashMap 保证事件线程并发安全。
    private static final Map<Material, Boolean> sfTaggedCache = new ConcurrentHashMap<>();
    private final Config cfg;
    private final ExoticGarden plugin;
    private final BlockFace[] faces = {BlockFace.NORTH, BlockFace.NORTH_EAST, BlockFace.EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH, BlockFace.SOUTH_WEST, BlockFace.WEST, BlockFace.NORTH_WEST};
    // 配置派生值缓存：cfg 为 enable 时的内存快照（本附属无 reload 机制），缓存避免每事件
    // 重复 getStringList/getInt（getStringList 每次新建 List）。
    private final Set<String> worldBlacklist;
    private final int chanceBush;
    private final int chanceTree;
    private final boolean autoGenerate;

    public PlantsListener(ExoticGarden plugin) {
        this.plugin = plugin;
        cfg = plugin.getCfg();
        this.worldBlacklist = new HashSet<>(cfg.getStringList("world-blacklist"));
        this.chanceBush = cfg.getInt("chances.BUSH");
        this.chanceTree = cfg.getInt("chances.TREE");
        this.autoGenerate = cfg.getOrSetDefault("options.auto-generate-plants", true);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onWateringCanWater(PlayerInteractEvent e) {
        if (!ExoticGarden.instance.isFluffyEnabled()) {
            return;
        }

        Block b = e.getClickedBlock();
        ItemStack item = e.getPlayer().getInventory().getItemInMainHand();

        // 空手不处理
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return;
        }

        final ItemMeta itemMeta = item.getItemMeta();
        if (itemMeta == null) {
            return;
        }

        final Optional<String> id = Slimefun.getItemDataService().getItemData(itemMeta);

        if (b != null && id.isPresent() && id.get().equals("WATERING_CAN") && e.getHand() == EquipmentSlot.HAND) {
            waterStructure(b.getLocation(), e, item);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onGrow(StructureGrowEvent e) {
        if (PaperLib.isPaper()) {
            if (PaperLib.isChunkGenerated(e.getLocation())) {
                growStructure(e);
            } else {
                // getChunkAtAsync 的回调可能在异步线程执行；growStructure 内含大量
                // Bukkit API（setType/setBlockData/PlayerHead.setSkin/BlockStorage），
                // 必须在主线程执行，否则异步改世界会崩或不安全。
                PaperLib.getChunkAtAsync(e.getLocation()).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> growStructure(e)));
            }
        } else {
            if (!e.getLocation().getChunk().isLoaded()) {
                e.getLocation().getChunk().load();
            }
            growStructure(e);
        }
    }

    @EventHandler
    public void onGenerate(ChunkPopulateEvent e) {
        if (!autoGenerate) {
            return;
        }

        final World world = e.getWorld();

        if (!Slimefun.getWorldSettingsService().isWorldEnabled(world)) {
            return;
        }

        if (!worldBlacklist.contains(world.getName())) {
            Random random = ThreadLocalRandom.current();

            final int worldLimit = getWorldBorder(world);

            if (random.nextInt(100) < chanceBush) {
                List<Berry> berries = ExoticGarden.getBerries();
                if (berries.isEmpty()) {
                    return;
                }
                Berry berry = berries.get(random.nextInt(berries.size()));
                if (berry.getType().equals(PlantType.ORE_PLANT)) return;

                int chunkX = e.getChunk().getX();
                int chunkZ = e.getChunk().getZ();

                // Middle of chunk between 3-13 (to avoid loading neighbouring chunks)
                int x = chunkX * 16 + random.nextInt(10) + 3;
                int z = chunkZ * 16 + random.nextInt(10) + 3;

                if ((x < worldLimit && x > -worldLimit) && (z < worldLimit && z > -worldLimit)) {
                    if (PaperLib.isPaper()) {
                        if (PaperLib.isChunkGenerated(world, chunkX, chunkZ)) {
                            growBush(e, x, z, berry, random, true);
                        } else {
                            // 异步加载 chunk 后，回主线程执行（growBush 内含 setType 等 Bukkit API）。
                            PaperLib.getChunkAtAsync(world, chunkX, chunkZ).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> growBush(e, x, z, berry, random, true)));
                        }
                    } else {
                        growBush(e, x, z, berry, random, false);
                    }
                }
            } else if (random.nextInt(100) < chanceTree) {
                List<Tree> trees = ExoticGarden.getTrees();
                if (trees.isEmpty()) {
                    return;
                }
                Tree tree = trees.get(random.nextInt(trees.size()));

                int chunkX = e.getChunk().getX();
                int chunkZ = e.getChunk().getZ();

                // Tree size defaults (width/length)
                int tw = 7;
                int tl = 7;

                // Get the sizes of the tree being placed
                // Value is padded +2 blocks to avoid loading neighbouring chunks for block updates
                try {
                    tw = tree.getSchematic().getWidth() + 2;
                    tl = tree.getSchematic().getLength() + 2;
                } catch (IOException ex) {
                    ex.printStackTrace();
                }

                // 限制在 chunk 内：nextInt 的 bound 必须 >= 1，否则 schematic 过大时会抛 IllegalArgumentException。
                int safeW = Math.max(1, Math.min(tw, 15));
                int safeL = Math.max(1, Math.min(tl, 15));

                // Ensure schematic fits inside the chunk
                int x = chunkX * 16 + random.nextInt(16 - safeW) + (int) Math.floor((double) safeW / 2);
                int z = chunkZ * 16 + random.nextInt(16 - safeL) + (int) Math.floor((double) safeL / 2);

                if ((x < worldLimit && x > -worldLimit) && (z < worldLimit && z > -worldLimit)) {
                    if (PaperLib.isPaper()) {
                        if (PaperLib.isChunkGenerated(world, chunkX, chunkZ)) {
                            pasteTree(e, x, z, tree);
                        } else {
                            // 异步加载 chunk 后，回主线程执行（pasteTree/Schematic.paste 含大量 Bukkit API）。
                            PaperLib.getChunkAtAsync(world, chunkX, chunkZ).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> pasteTree(e, x, z, tree)));
                        }
                    } else {
                        plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> pasteTree(e, x, z, tree));
                    }
                }
            }
        }
    }

    private int getWorldBorder(World world) {
        return (int) world.getWorldBorder().getSize();
    }

    @EventHandler
    public void onFastGenerate(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        ItemStack hand = event.getItem();
        if (hand == null || hand.getType().isAir()) {
            return;
        }
        SlimefunItem sfitem = SlimefunItem.getByItem(hand);
        if (sfitem == null || !sfitem.getId().equals(ExoticItems.GoldKeLa.getItemId()) || sfitem.isDisabledIn(block.getWorld())) {
            return;
        }

        applyGoldKela(event, block, hand);
    }

    private boolean applyGoldKela(PlayerInteractEvent event, Block block, ItemStack hand) {
        if (!(BlockStorage.check(block.getLocation()) instanceof BonemealableItem bi)) {
            return false;
        }

        if (bi.isDisabledIn(block.getWorld()) || bi.isBonemealDisabled()) {
            return false;
        }

        var e = new StructureGrowEvent(block.getLocation(), TreeType.TREE, true, event.getPlayer(), List.of());
        if (growStructure0(e)) {
            e.setCancelled(true);
            hand.setAmount(hand.getAmount() - 1);
            return true;
        }

        if (block.getBlockData() instanceof Ageable ageable) {
            ageable.setAge(ageable.getMaximumAge());
            block.setBlockData(ageable);
            e.setCancelled(true);
            hand.setAmount(hand.getAmount() - 1);
            return true;
        }

        return false;
    }

    private void growStructure(StructureGrowEvent e) {
        growStructure0(e);
    }

    private boolean growStructure0(StructureGrowEvent e) {
        SlimefunItem item = BlockStorage.check(e.getLocation());

        if (item != null) {
            e.setCancelled(true);
            Tree tree = plugin.getTreeBySapling(item.getId());
            if (tree != null) {
                BlockStorage.clearBlockInfo(e.getLocation());
                Schematic.pasteSchematic(e.getLocation(), tree, false);
                return true;
            }

            Berry berry = plugin.getBerryByBush(item.getId());
            if (berry != null) {
                switch (berry.getType()) {
                    case BUSH -> e.getLocation().getBlock().setType(Material.OAK_LEAVES, false);
                    case ORE_PLANT, DOUBLE_PLANT -> {
                        Block blockAbove = e.getLocation().getBlock().getRelative(BlockFace.UP);
                        item = BlockStorage.check(blockAbove.getLocation());
                        if (item != null) return false;
                        if (!Tag.SAPLINGS.isTagged(blockAbove.getType()) && !Tag.LEAVES.isTagged(blockAbove.getType())) {
                            switch (blockAbove.getType()) {
                                case AIR, CAVE_AIR, SNOW:
                                    break;
                                default:
                                    return false;
                            }
                        }
                        BlockStorage.store(blockAbove, berry.getItem());
                        e.getLocation().getBlock().setType(Material.OAK_LEAVES, false);
                        blockAbove.setType(Material.PLAYER_HEAD, false);
                        Rotatable rotatable = (Rotatable) blockAbove.getBlockData();
                        rotatable.setRotation(faces[ThreadLocalRandom.current().nextInt(faces.length)]);
                        blockAbove.setBlockData(rotatable, false);
                        PlayerHead.setSkin(blockAbove, PlayerSkin.fromHashCode(berry.getTexture()), true);
                    }
                    default -> {
                        e.getLocation().getBlock().setType(Material.PLAYER_HEAD, false);
                        Rotatable s = (Rotatable) e.getLocation().getBlock().getBlockData();
                        s.setRotation(faces[ThreadLocalRandom.current().nextInt(faces.length)]);
                        e.getLocation().getBlock().setBlockData(s);
                        PlayerHead.setSkin(e.getLocation().getBlock(), PlayerSkin.fromHashCode(berry.getTexture()), true);
                    }
                }

                BlockStorage.clearBlockInfo(e.getLocation());
                BlockStorage.store(e.getLocation().getBlock(), berry.getItem());
                e.getWorld().playEffect(e.getLocation(), Effect.STEP_SOUND, Material.OAK_LEAVES);
            }

            return true;
        }

        return false;
    }

    private void pasteTree(ChunkPopulateEvent e, int x, int z, Tree tree) {
        for (int y = e.getWorld().getHighestBlockYAt(x, z) + 2; y > 30; y--) {
            Block current = e.getWorld().getBlockAt(x, y, z);
            if (current.getType() != Material.WATER && current.getType() != Material.SEAGRASS && current.getType() != Material.TALL_SEAGRASS && !current.getType().isSolid() && !(current.getBlockData() instanceof Waterlogged && ((Waterlogged) current.getBlockData()).isWaterlogged()) && tree.isSoil(current.getRelative(0, -1, 0).getType()) && isFlat(current)) {
                Schematic.pasteSchematic(e.getWorld(), x, y, z, tree, false);
                break;
            }
        }
    }

    private void growBush(ChunkPopulateEvent e, int x, int z, Berry berry, Random random, boolean isPaper) {
        for (int y = e.getWorld().getHighestBlockYAt(x, z) + 2; y > 30; y--) {
            Block current = e.getWorld().getBlockAt(x, y, z);
            if (current.getType() != Material.WATER && !current.getType().isSolid() && berry.isSoil(current.getRelative(BlockFace.DOWN).getType())) {
                BlockStorage.store(current, berry.getItem());
                switch (berry.getType()) {
                    case BUSH:
                        if (isPaper) {
                            current.setType(Material.OAK_LEAVES, false);
                        } else {
                            plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> current.setType(Material.OAK_LEAVES));
                        }
                        break;
                    case FRUIT, ORE_PLANT, DOUBLE_PLANT:
                        if (isPaper) {
                            current.setType(Material.PLAYER_HEAD, false);
                            Rotatable s = (Rotatable) current.getBlockData();
                            s.setRotation(faces[random.nextInt(faces.length)]);
                            current.setBlockData(s, false);
                            PlayerHead.setSkin(current, PlayerSkin.fromHashCode(berry.getTexture()), true);
                        } else {
                            plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                                current.setType(Material.PLAYER_HEAD, false);
                                Rotatable s = (Rotatable) current.getBlockData();
                                s.setRotation(faces[random.nextInt(faces.length)]);
                                current.setBlockData(s, false);
                                PlayerHead.setSkin(current, PlayerSkin.fromHashCode(berry.getTexture()), true);
                            });
                        }
                        break;
                    default:
                        break;
                }
                break;
            }
        }
    }

    private boolean isFlat(Block current) {
        for (int i = -2; i < 2; i++) {
            for (int j = -2; j < 2; j++) {
                for (int k = 0; k < 6; k++) {
                    Block block = current.getRelative(i, k, j);
                    if (block.getType().isSolid()
                            || Tag.LEAVES.isTagged(block.getType())
                            || !current.getRelative(i, -1, j).getType().isSolid()) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarvest(BlockBreakEvent e) {
        if (Slimefun.getProtectionManager().hasPermission(e.getPlayer(), e.getBlock().getLocation(), Interaction.BREAK_BLOCK)) {
            if (e.getBlock().getType() == Material.PLAYER_HEAD || Tag.LEAVES.isTagged(e.getBlock().getType())) {
                dropFruitFromTree(e.getBlock());
            }

            if (e.getBlock().getType() == Material.SHORT_GRASS) {
                if (!ExoticGarden.getGrassDrops().isEmpty() && e.getPlayer().getGameMode() != GameMode.CREATIVE) {
                    Random random = ThreadLocalRandom.current();

                    if (random.nextInt(100) < 6) {
                        ItemStack[] items = ExoticGarden.getInstance().getGrassDropsArray();
                        if (items != null && items.length > 0) {
                            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), items[random.nextInt(items.length)]);
                        }
                    }
                    if (random.nextInt(100) < 3) {
                        ItemStack grassSeeds = ExoticGarden.getGrassDrops().get("GRASS_SEEDS");
                        if (grassSeeds != null) {
                            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), grassSeeds);
                        }
                    }
                    
                    if (random.nextInt(100) < 2) {
                        ItemStack mysticSeed = ExoticGarden.getGrassDrops().get("MYSTIC_SEED");
                        if (mysticSeed != null) {
                            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), mysticSeed);
                        }
                    }
                }
            } else {
                ItemStack item = ExoticGarden.harvestPlant(e.getBlock());

                if (item != null) {
                    e.setCancelled(true);
                    e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), item);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) {
        if (!Slimefun.getWorldSettingsService().isWorldEnabled(e.getBlock().getWorld())) {
            return;
        }

        var item = BlockStorage.check(e.getBlock().getLocation());

        if (item != null && plugin.getBerry(item.getId()) != null) {
            e.setCancelled(true);
            return;
        }

        dropFruitFromTree(e.getBlock());
        ItemStack drop = BlockStorage.retrieve(e.getBlock());

        if (drop != null) {
            e.setCancelled(true);
            e.getBlock().setType(Material.AIR, false);
            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), drop);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent e) {
        if (!Slimefun.getWorldSettingsService().isWorldEnabled(e.getBlock().getWorld())) {
            return;
        }

        String id = BlockStorage.checkID(e.getBlock());

        if (id != null && plugin.getBerry(id) != null) {
            e.setCancelled(true);
            return;
        }


        dropFruitFromTree(e.getBlock());
        ItemStack item = BlockStorage.retrieve(e.getBlock());

        if (item != null) {
            e.setCancelled(true);
            e.getBlock().setType(Material.AIR);
            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), item);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getPlayer().isSneaking()) return;
        Block clickedBlock = e.getClickedBlock();
        if (clickedBlock == null) return;

        Material mainHand = e.getPlayer().getInventory().getItemInMainHand().getType();
        Material offHand = e.getPlayer().getInventory().getItemInOffHand().getType();

        // 手持受 SlimefunTag 约束的方块（如受重力影响方块）时不触发采摘，交还原版行为。
        // 原“遍历全部 tag 逐一 isTagged”是每次右键的 O(标签数) 开销；改为按 Material 记忆化后 O(1) 命中。
        if (isSlimefunTaggedMaterial(mainHand) || isSlimefunTaggedMaterial(offHand)) {
            return;
        }

        if (Slimefun.getProtectionManager().hasPermission(e.getPlayer(), clickedBlock.getLocation(), Interaction.BREAK_BLOCK)) {
            ItemStack item = ExoticGarden.harvestPlant(clickedBlock);

            if (item != null) {
                clickedBlock.getWorld().playEffect(clickedBlock.getLocation(), Effect.STEP_SOUND, Material.OAK_LEAVES);
                clickedBlock.getWorld().dropItemNaturally(clickedBlock.getLocation(), item);
            } else {
                // The block wasn't a plant, we try harvesting a fruit instead
                ExoticGarden.getInstance().harvestFruit(clickedBlock);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeAll(getAffectedBlocks(e.blockList()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeAll(getAffectedBlocks(e.blockList()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBonemealPlant(BlockFertilizeEvent e) {
        Block b = e.getBlock();
        if (b.getType() == Material.OAK_SAPLING) {
            SlimefunItem item = BlockStorage.check(b.getLocation());

            if (item instanceof BonemealableItem && ((BonemealableItem) item).isBonemealDisabled()) {
                e.setCancelled(true);
                b.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, b.getLocation().clone().add(0.5, 0, 0.5), 4);
                b.getWorld().playSound(b.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1);
            }
        }
    }

    private Set<Block> getAffectedBlocks(List<Block> blockList) {
        Set<Block> blocksToRemove = new HashSet<>();

        for (Block block : blockList) {
            ItemStack item = ExoticGarden.harvestPlant(block);

            if (item != null) {
                blocksToRemove.add(block);
                block.getWorld().dropItemNaturally(block.getLocation(), item);
            }
        }

        return blocksToRemove;
    }

    /**
     * 判断某 Material 是否被任意 {@link SlimefunTag} 标记（按 Material 记忆化）。
     *
     * <p>SlimefunTag 在 Slimefun 加载后不可变，故每个 Material 的结果恒定。首次查询遍历全部标签，
     * 之后命中 {@link #sfTaggedCache}。Material 枚举有界（约千项），缓存规模天然有上限。</p>
     */
    private static boolean isSlimefunTaggedMaterial(Material material) {
        Boolean cached = sfTaggedCache.get(material);
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        for (SlimefunTag tag : valuesCache) {
            if (tag.isTagged(material)) {
                result = true;
                break;
            }
        }
        sfTaggedCache.put(material, result);
        return result;
    }

    private void dropFruitFromTree(Block block) {
        for (int x = -1; x < 2; x++) {
            for (int y = -1; y < 2; y++) {
                for (int z = -1; z < 2; z++) {
                    // inspect a cube at the reference
                    Block fruit = block.getRelative(x, y, z);
                    if (fruit.isEmpty()) continue;
                    // 仅 PLAYER_HEAD 可能是树果（树果在 Schematic/growBush 中均以 PLAYER_HEAD 放置），
                    // 过滤可跳过 ~26/27 个非头方块的 BlockStorage.check（大幅降低砍树时的查询开销）。
                    if (fruit.getType() != Material.PLAYER_HEAD) continue;

                    Location loc = fruit.getLocation();
                    SlimefunItem check = BlockStorage.check(loc);
                    if (check == null) continue;
                    if (ExoticGarden.getInstance().isTreeFruit(check.getId())) {
                        BlockStorage.clearBlockInfo(loc);
                        ItemStack fruits = check.getItem();
                        fruit.getWorld().playEffect(loc, Effect.STEP_SOUND, Material.OAK_LEAVES);
                        fruit.getWorld().dropItemNaturally(loc, fruits);
                        fruit.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private void waterStructure(Location l, PlayerInteractEvent e, ItemStack wateringCan) {
        SlimefunItem item = BlockStorage.check(l.getBlock());

        if (item != null) {
            final double random = ThreadLocalRandom.current().nextDouble();
            Tree tree = plugin.getTreeBySapling(item.getId());
            if (tree != null) {
                l.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, l.add(0.5D, 0.5D, 0.5D), 15, 0.2F, 0.2F, 0.2F);
                if (cfg.getDouble("watering-can.chance") >= random) {
                    BlockStorage.clearBlockInfo(l.getBlock());
                    Schematic.pasteSchematic(l, tree, false);
                    return;
                }
            }

            Berry berry = plugin.getBerryByBush(item.getId());
            if (berry != null) {
                l.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, l.add(0.5D, 0.5D, 0.5D), 15, 0.2F, 0.2F, 0.2F);
                if (cfg.getDouble("watering-can.chance") >= random) {
                    switch (berry.getType()) {
                        case BUSH:
                            l.getBlock().setType(Material.OAK_LEAVES, false);
                            break;
                        case ORE_PLANT:
                        case DOUBLE_PLANT:
                            Block blockAbove = l.getBlock().getRelative(BlockFace.UP);
                            item = BlockStorage.check(blockAbove);
                            if (item != null) return;

                            if (!Tag.SAPLINGS.isTagged(blockAbove.getType()) && !Tag.LEAVES.isTagged(blockAbove.getType())) {
                                switch (blockAbove.getType()) {
                                    case AIR:
                                    case CAVE_AIR:
                                    case SNOW:
                                        break;
                                    default:
                                        return;
                                }
                            }

                            BlockStorage.store(blockAbove, berry.getItem());
                            l.getBlock().setType(Material.OAK_LEAVES, false);
                            blockAbove.setType(Material.PLAYER_HEAD, false);
                            Rotatable rotatable = (Rotatable) blockAbove.getBlockData();
                            rotatable.setRotation(faces[ThreadLocalRandom.current().nextInt(faces.length)]);
                            blockAbove.setBlockData(rotatable);

                            PlayerHead.setSkin(blockAbove, PlayerSkin.fromHashCode(berry.getTexture()), false);
                            break;
                        default:
                            l.getBlock().setType(Material.PLAYER_HEAD, false);
                            Rotatable s = (Rotatable) l.getBlock().getBlockData();
                            s.setRotation(faces[ThreadLocalRandom.current().nextInt(faces.length)]);
                            l.getBlock().setBlockData(s);

                            PlayerHead.setSkin(l.getBlock(), PlayerSkin.fromHashCode(berry.getTexture()), false);
                            break;
                    }

                    BlockStorage.deleteLocationInfoUnsafely(l, false);
                    BlockStorage.store(l.getBlock(), berry.getItem());
                    l.getWorld().playEffect(l, Effect.STEP_SOUND, Material.OAK_LEAVES);
                }
            }
        }
    }
}
