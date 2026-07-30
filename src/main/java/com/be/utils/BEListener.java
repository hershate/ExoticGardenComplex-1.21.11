package com.be.utils;

import io.github.thebusybiscuit.exoticgarden.ExoticGarden;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public class BEListener implements Listener {

    private static final BEListener INSTANCE = new BEListener();

    public static BEListener getInstance() {
        return INSTANCE;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        ExoticGarden plugin = ExoticGarden.getInstance();
        // 进服传送受配置开关控制（原实现忽略 settings.spawn-on-join，对所有人强制传送）。
        if (!plugin.getConfig().getBoolean("settings.spawn-on-join", false)) {
            return;
        }
        // 不再每次进服都 reloadConfig（磁盘 IO + 丢弃运行时改动）；内存 config 在 setspawn 后已是最新。
        World world = Bukkit.getWorld(plugin.getConfig().getString("spawn.world"));
        // getWorld 在世界未加载/被卸载/名字拼错时返回 null，构造 null-world Location 传送会 NPE。
        if (world == null) {
            return;
        }
        double x = plugin.getConfig().getDouble("spawn.x");
        double y = plugin.getConfig().getDouble("spawn.y");
        double z = plugin.getConfig().getDouble("spawn.z");
        float yaw = (float) plugin.getConfig().getDouble("spawn.yaw");
        float pitch = (float) plugin.getConfig().getDouble("spawn.pitch");

        Location loc = new Location(world, x, y, z, yaw, pitch);
        // 延迟 1 tick 传送；调度时再校验玩家在线，避免玩家在 join 与下一 tick 间断线导致异常。
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                event.getPlayer().teleport(loc, PlayerTeleportEvent.TeleportCause.COMMAND);
            }
        });
    }

    // 原 onPerLogin 无条件调用 PlayerLoginEvent.allow()，会强制覆盖服务端自带的
    // 封禁 / 白名单 / 满员检测结果（KICK_BANNED / KICK_WHITELIST / KICK_FULL），构成
    // 安全绕过。该方法除一条控制台日志外没有任何正当用途，整体移除。
}
