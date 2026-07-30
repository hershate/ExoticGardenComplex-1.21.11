package io.github.thebusybiscuit.exoticgarden;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /exotic 命令：管理玩家酒精度。
 *
 * <p>子命令：
 * <ul>
 *   <li>{@code /exotic alo info <玩家>} —— 查看酒精度</li>
 *   <li>{@code /exotic alo add <玩家> <值>} —— 增加/减少酒精度</li>
 *   <li>{@code /exotic alo set <玩家> <值>} —— 设置酒精度</li>
 * </ul>
 *
 * <p>健壮性：所有分支都对“玩家不存在/不在线/不在醉酒表/参数非整数”做了防御，
 * 避免原实现中 {@code Bukkit.getPlayer(name).isOnline()} 与 {@code Integer.parseInt}
 * 直接 NPE / NumberFormatException 的问题。
 */
public class ExoticCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!hasPermission(sender, "exoticgarden.admin")) {
            sender.sendMessage("§c你没有权限这么做!");
            return true;
        }

        // 仅接受 alo 子命令
        if (args.length == 0 || !args[0].equalsIgnoreCase("alo")) {
            sendHelp(sender);
            return true;
        }

        // /exotic alo info <player>
        if (args.length == 3 && args[1].equalsIgnoreCase("info")) {
            PlayerAlcohol pa = getAlcohol(sender, args[2]);
            if (pa != null) {
                sender.sendMessage("§8[§b异域花园§8] §7玩家§e" + args[2] + "§7的酒精度为§e" + pa.getAlcohol());
            }
            return true;
        }

        // /exotic alo add|set <player> <value>
        if (args.length == 4 && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("set"))) {
            PlayerAlcohol pa = getAlcohol(sender, args[2]);
            if (pa == null) {
                return true;
            }
            Integer amount = parseAmount(sender, args[3]);
            if (amount == null) {
                return true;
            }
            if (args[1].equalsIgnoreCase("add")) {
                pa.addAlcohol(amount);
                sender.sendMessage("§8[§b异域花园§8] §7为玩家§e" + args[2] + "§7增加了§e" + amount + "§7酒精度");
            } else {
                pa.setAlcohol(amount);
                sender.sendMessage("§8[§b异域花园§8] §7将玩家§e" + args[2] + "§7的酒精度设置为§e" + amount);
            }
            return true;
        }

        sendHelp(sender);
        return true;
    }

    private PlayerAlcohol getAlcohol(CommandSender sender, String name) {
        // getPlayerExact 对不存在玩家返回 null；原实现用 getPlayer(name).isOnline() 会 NPE。
        Player target = Bukkit.getPlayerExact(name);
        if (target == null || !target.isOnline()) {
            sender.sendMessage("§8[§b异域花园§8] §c指定的玩家不在线！");
            return null;
        }
        PlayerAlcohol pa = ExoticGarden.drunkPlayers.get(target.getUniqueId());
        if (pa == null) {
            sender.sendMessage("§8[§b异域花园§8] §c未找到该玩家的醉酒数据！");
            return null;
        }
        return pa;
    }

    private Integer parseAmount(CommandSender sender, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            sender.sendMessage("§8[§b异域花园§8] §c酒精度必须是一个整数！");
            return null;
        }
    }

    private void sendHelp(CommandSender sender) {
        String[] help = {"        §7--------§8====§e[ §b异域花园 §e]§8====§7--------",
                "§b/exotic alo info <玩家名>            §7查看指定玩家酒精度",
                "§b/exotic alo add <玩家名> <值>        §7增加/减少 酒精度",
                "§b/exotic alo set <玩家名> <值>        §7设定 酒精度"};
        sender.sendMessage(help);
    }

    private boolean hasPermission(CommandSender sender, String perms) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        return player.hasPermission(perms) || player.isOp();
    }
}
