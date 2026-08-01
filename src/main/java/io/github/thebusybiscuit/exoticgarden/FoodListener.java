package io.github.thebusybiscuit.exoticgarden;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class FoodListener implements Listener {
    final ExoticGarden plugin;

    public FoodListener(ExoticGarden plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(final PlayerInteractEvent e) {
        SlimefunItem item;
        if (e.getPlayer().getFoodLevel() >= 20)
            return;
        EquipmentSlot hand = e.getHand();
        if (hand == null) {
            return;
        }

        switch (hand) {
            case HAND:
                // getByItem 仅读取 Material + PersistentDataContainer(SF id)，与 amount 无关，
                // 故无需 CustomItemStack.create(hand, 1) 复制（原每次交互克隆一次物品，纯属多余分配）。
                item = SlimefunItem.getByItem(e.getPlayer().getInventory().getItemInMainHand());
                if (item instanceof EGPlant && (
                        (EGPlant) item).isEdible()) {
                    ((EGPlant) item).restoreHunger(e.getPlayer());
                    e.getPlayer().getWorld().playSound(e.getPlayer().getEyeLocation(), Sound.ENTITY_GENERIC_EAT, 1.0F, 1.0F);
                    // 同步扣除：原 0-tick 延迟在快速连点时会产生“已恢复饥饿但物品尚未扣除”的窗口。
                    ItemStack handItem = e.getPlayer().getInventory().getItemInMainHand();
                    if (handItem != null && !handItem.getType().isAir()) {
                        handItem.setAmount(handItem.getAmount() - 1);
                        e.getPlayer().getInventory().setItemInMainHand(handItem);
                    }
                }
                break;


            case OFF_HAND:
                item = SlimefunItem.getByItem(e.getPlayer().getInventory().getItemInOffHand());
                if (item instanceof EGPlant && (
                        (EGPlant) item).isEdible()) {
                    ((EGPlant) item).restoreHunger(e.getPlayer());
                    e.getPlayer().getWorld().playSound(e.getPlayer().getEyeLocation(), Sound.ENTITY_GENERIC_EAT, 1.0F, 1.0F);
                    ItemStack offItem = e.getPlayer().getInventory().getItemInOffHand();
                    if (offItem != null && !offItem.getType().isAir()) {
                        offItem.setAmount(offItem.getAmount() - 1);
                        e.getPlayer().getInventory().setItemInOffHand(offItem);
                    }
                }
                break;
        }
    }


    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack hand = e.getItemInHand();
        // EGPlant 物品均为 PLAYER_HEAD（经 getSkull 自定义纹理）；非 PLAYER_HEAD 不可能是 EGPlant，
        // 直接返回，避免对每次放方块都 getByItem（PDC 读取 + 材质集合查找）。原条件
        // “instanceof EGPlant && type==PLAYER_HEAD” 把最廉价的 type 判断后置，等价改为前置短路。
        if (hand == null || hand.getType() != Material.PLAYER_HEAD) {
            return;
        }
        SlimefunItem item = SlimefunItem.getByItem(hand);
        if (item instanceof EGPlant) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onEquip(InventoryClickEvent e) {
        if (e.getSlotType() != InventoryType.SlotType.ARMOR)
            return;
        ItemStack cursor = e.getCursor();
        // 同 onPlace：仅 PLAYER_HEAD 才可能是 EGPlant，提前按材质短路，避免每次护甲点击都 getByItem。
        if (cursor == null || cursor.getType() != Material.PLAYER_HEAD) {
            return;
        }
        SlimefunItem item = SlimefunItem.getByItem(cursor);
        if (item instanceof EGPlant) {
            e.setCancelled(true);
        }
    }
}


