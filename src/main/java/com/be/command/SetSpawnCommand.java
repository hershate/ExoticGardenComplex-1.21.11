package com.be.command;

import io.github.thebusybiscuit.exoticgarden.ExoticGarden;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class SetSpawnCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("This Command can only executed by a player, sorry!");
            return true;
        }
        if (args.length != 0) {
            sender.sendMessage(color(ExoticGarden.getInstance().getConfig().getString("messages.cmd-setspawn-usage"), "&c用法: /setspawn"));
            return true;
        }
        if (!(sender.isOp() || sender.hasPermission("spawn.admin"))) {
            p.sendMessage("§cYou do not have permission to execute this command!");
            return true;
        }

        ExoticGarden plugin = ExoticGarden.getInstance();
        // 写入内存 config 后 saveConfig 落盘；不再先 reloadConfig（多余且会丢弃运行时改动）。
        plugin.getConfig().set("spawn.world", p.getWorld().getName());
        plugin.getConfig().set("spawn.x", p.getLocation().getX());
        plugin.getConfig().set("spawn.y", p.getLocation().getY());
        plugin.getConfig().set("spawn.z", p.getLocation().getZ());
        plugin.getConfig().set("spawn.yaw", p.getLocation().getYaw());
        plugin.getConfig().set("spawn.pitch", p.getLocation().getPitch());
        plugin.saveConfig();
        p.sendMessage(color(plugin.getConfig().getString("messages.success-setspawn"), "&a成功设置出生点"));
        return true;
    }

    /** 对可能为 null 的配置字符串做颜色转换，null 时使用 fallback，避免 NPE。 */
    private static String color(String raw, String fallback) {
        return ChatColor.translateAlternateColorCodes('&', raw != null ? raw : fallback);
    }
}
