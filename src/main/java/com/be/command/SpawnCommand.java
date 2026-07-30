package com.be.command;

import io.github.thebusybiscuit.exoticgarden.ExoticGarden;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

public class SpawnCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("This Command can only executed by a player, sorry!");
            return true;
        }
        if (args.length != 0) {
            p.sendMessage(color(ExoticGarden.getInstance().getConfig().getString("messages.cmd-spawn-usage"), "&c用法: /spawn"));
            return true;
        }
        if (!(sender.isOp() || sender.hasPermission("spawn.admin") || sender.hasPermission("spawn.spawn"))) {
            p.sendMessage("§cYou do not have permission to execute this command!");
            return true;
        }

        ExoticGarden plugin = ExoticGarden.getInstance();
        // 不再每次执行都 reloadConfig；内存 config 在 setspawn 后已是最新。
        String worldName = plugin.getConfig().getString("spawn.world");
        // 未设置出生点时 getString 返回 null；Bukkit.getWorld(null) 会直接抛 IllegalArgumentException
        // (name cannot be null) 而非返回 null，故必须先判空再查。
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        // world 可能为 null（未设置 spawn / 世界未加载），原实现构造 null-world Location 后 teleport 会 NPE。
        if (world == null) {
            p.sendMessage(color(plugin.getConfig().getString("messages.error-nospawnpoint"), "&c无出生点设置"));
            return true;
        }
        double x = plugin.getConfig().getDouble("spawn.x");
        double y = plugin.getConfig().getDouble("spawn.y");
        double z = plugin.getConfig().getDouble("spawn.z");
        float yaw = (float) plugin.getConfig().getDouble("spawn.yaw");
        float pitch = (float) plugin.getConfig().getDouble("spawn.pitch");

        Location loc = new Location(world, x, y, z, yaw, pitch);
        // 显式 COMMAND cause，便于领地/反作弊按 cause 白名单处理（原用默认 UNKNOWN）。
        p.teleport(loc, PlayerTeleportEvent.TeleportCause.COMMAND);

        if (plugin.getConfig().getBoolean("settings.tpmessage-enable", true)) {
            p.sendMessage(color(plugin.getConfig().getString("messages.tpmessage"), "&a你已被传送至出生点"));
        }
        return true;
    }

    /** 对可能为 null 的配置字符串做颜色转换，null 时使用 fallback，避免 NPE。 */
    private static String color(String raw, String fallback) {
        return ChatColor.translateAlternateColorCodes('&', raw != null ? raw : fallback);
    }
}
