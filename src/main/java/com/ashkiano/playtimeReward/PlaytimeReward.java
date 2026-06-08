package com.ashkiano.playtimeReward;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class PlaytimeReward extends JavaPlugin {

    private BukkitTask tickTask;
    private int intervalSeconds;
    private String commandTemplate;
    private ExecutorType executorType;
    private String requiredPermission;
    private final Map<UUID, Integer> carryOverSeconds = new HashMap<>();
    private File dataFile;
    private FileConfiguration dataConf;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.loadRuntimeConfig();

        this.dataFile = new File(this.getDataFolder(), "player-times.yml");
        if (!this.dataFile.exists()) {
            try {
                this.getDataFolder().mkdirs();
                this.dataFile.createNewFile();
            } catch (IOException e) {
                this.getLogger().log(Level.SEVERE, "Failed to create player-times.yml", e);
            }
        }
        this.dataConf = YamlConfiguration.loadConfiguration(this.dataFile);
        this.loadAllCarryOvers();

        // Hook PlaceholderAPI
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaytimeRewardExpansion(this).register();
            this.getLogger().info("Hooked into PlaceholderAPI successfully.");
        } else {
            this.getLogger().warning("PlaceholderAPI not found — placeholders will not be available.");
        }

        if (this.getConfig().getBoolean("runOnJoin", false)) {
            this.getServer().getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onJoin(PlayerJoinEvent e) {
                    Bukkit.getScheduler().runTaskLater(PlaytimeReward.this,
                            () -> PlaytimeReward.this.executeForPlayer(e.getPlayer()), 20L);
                }
            }, this);
        }

        this.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onQuit(PlayerQuitEvent e) {
                UUID id = e.getPlayer().getUniqueId();
                int sec = PlaytimeReward.this.carryOverSeconds.getOrDefault(id, 0);
                PlaytimeReward.this.saveCarryOver(id, sec);
            }
        }, this);

        this.startTickTask();
        this.getLogger().info("PlaytimeReward enabled.");
    }

    @Override
    public void onDisable() {
        this.cancelTickTask();
        this.saveAllCarryOvers();
        this.getLogger().info("PlaytimeReward disabled.");
    }

    private void loadRuntimeConfig() {
        int minutes = Math.max(1, this.getConfig().getInt("intervalMinutes", 10));
        this.intervalSeconds = minutes * 60;
        this.commandTemplate = Objects.toString(this.getConfig().getString("command", ""), "").trim();
        this.executorType = ExecutorType.from(this.getConfig().getString("runAs", "CONSOLE"));
        this.requiredPermission = Objects.toString(this.getConfig().getString("requiredPermission", ""), "").trim();
        this.getLogger().log(Level.INFO, "Interval set to {0} minute(s) ({1}s).",
                new Object[]{minutes, this.intervalSeconds});
    }

    private void startTickTask() {
        this.cancelTickTask();
        this.tickTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (this.commandTemplate.isEmpty()) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!this.requiredPermission.isEmpty() && !p.hasPermission(this.requiredPermission)) continue;
                UUID id = p.getUniqueId();
                int now = this.carryOverSeconds.getOrDefault(id, 0) + 1;
                while (now >= this.intervalSeconds && this.intervalSeconds > 0) {
                    this.executeForPlayer(p);
                    now -= this.intervalSeconds;
                }
                this.carryOverSeconds.put(id, now);
            }
        }, 20L, 20L);
    }

    private void cancelTickTask() {
        if (this.tickTask != null) {
            this.tickTask.cancel();
            this.tickTask = null;
        }
    }

    private void executeForPlayer(Player player) {
        String processed = this.commandTemplate
                .replace("%player%", player.getName())
                .replace("{player}", player.getName());
        if (processed.startsWith("/")) processed = processed.substring(1);

        boolean ok;
        if (this.executorType == ExecutorType.PLAYER) {
            ok = player.performCommand(processed);
        } else {
            ConsoleCommandSender console = Bukkit.getConsoleSender();
            ok = Bukkit.dispatchCommand(console, processed);
        }
        if (!ok) {
            this.getLogger().log(Level.WARNING, "Command failed for {0}: /{1}",
                    new Object[]{player.getName(), processed});
        }
    }

    // ── Getters dùng cho PAPI expansion ──────────────────────────────────────

    public int getRemainingSeconds(UUID uuid) {
        int elapsed = this.carryOverSeconds.getOrDefault(uuid, 0);
        return Math.max(0, this.intervalSeconds - elapsed);
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void loadAllCarryOvers() {
        this.carryOverSeconds.clear();
        if (this.dataConf == null) return;
        if (!this.dataConf.isConfigurationSection("players")) return;
        ConfigurationSection section = this.dataConf.getConfigurationSection("players");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                int seconds = Math.max(0, this.dataConf.getInt("players." + key + ".seconds", 0));
                this.carryOverSeconds.put(id, seconds);
            } catch (IllegalArgumentException ex) {
                this.getLogger().warning("Invalid UUID in player-times.yml: " + key);
            }
        }
    }

    private void saveCarryOver(UUID id, int seconds) {
        if (this.dataConf == null) return;
        this.dataConf.set("players." + id + ".seconds", Math.max(0, seconds));
        try {
            this.dataConf.save(this.dataFile);
        } catch (IOException e) {
            this.getLogger().log(Level.SEVERE, "Failed to save carry-over for " + id, e);
        }
    }

    private void saveAllCarryOvers() {
        if (this.dataConf == null) return;
        for (Map.Entry<UUID, Integer> e : this.carryOverSeconds.entrySet()) {
            this.dataConf.set("players." + e.getKey() + ".seconds", Math.max(0, e.getValue()));
        }
        try {
            this.dataConf.save(this.dataFile);
        } catch (IOException e) {
            this.getLogger().log(Level.SEVERE, "Failed to save player-times.yml", e);
        }
    }

    // ── Command ───────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // /rpcreload
        if (command.getName().equalsIgnoreCase("rpcreload")) {
            if (!sender.hasPermission("rpcommand.reload")) {
                sender.sendMessage("§cBạn không có quyền thực hiện lệnh này.");
                return true;
            }
            this.reloadConfig();
            this.loadRuntimeConfig();
            this.startTickTask();
            sender.sendMessage("§aPlaytimeReward đã được reload.");
            return true;
        }

        // /pr
        if (command.getName().equalsIgnoreCase("pr")) {
            if (!sender.hasPermission("playtimereward.admin")) {
                sender.sendMessage("§cBạn không có quyền thực hiện lệnh này.");
                return true;
            }

            if (args.length == 0) {
                sender.sendMessage("§ePlaytimeReward Admin:");
                sender.sendMessage("§7/pr set <player> <giây> §f— Set thời gian đã chờ");
                sender.sendMessage("§7/pr get <player> §f— Xem thời gian còn lại");
                return true;
            }

            String sub = args[0].toLowerCase();

            // /pr get <player>
            if (sub.equals("get")) {
                if (args.length < 2) {
                    sender.sendMessage("§cCách dùng: /pr get <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("§cKhông tìm thấy player §e" + args[1] + "§c online.");
                    return true;
                }
                int remaining = getRemainingSeconds(target.getUniqueId());
                int mm = remaining / 60;
                int ss = remaining % 60;
                sender.sendMessage("§a" + target.getName() + " §7còn §e" + mm + "m " + ss + "s §7đến reward tiếp theo.");
                return true;
            }

            // /pr set <player> <giây>
            if (sub.equals("set")) {
                if (args.length < 3) {
                    sender.sendMessage("§cCách dùng: /pr set <player> <giây>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("§cKhông tìm thấy player §e" + args[1] + "§c online.");
                    return true;
                }
                int seconds;
                try {
                    seconds = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cSố giây không hợp lệ: §e" + args[2]);
                    return true;
                }
                if (seconds < 0 || seconds > intervalSeconds) {
                    sender.sendMessage("§cSố giây phải từ §e0 §cđến §e" + intervalSeconds + "§c (= 1 interval).");
                    return true;
                }
                // carryOver là số giây ĐÃ chờ, nên = interval - remaining
                int elapsed = intervalSeconds - seconds;
                carryOverSeconds.put(target.getUniqueId(), elapsed);
                saveCarryOver(target.getUniqueId(), elapsed);
                int mm = seconds / 60;
                int ss = seconds % 60;
                sender.sendMessage("§aĐã set §e" + target.getName() + " §acòn §e" + mm + "m " + ss + "s §ađến reward.");
                return true;
            }

            sender.sendMessage("§cLệnh không hợp lệ. Dùng §e/pr §cđể xem hướng dẫn.");
            return true;
        }

        return false;
    }

    // ── Enum ──────────────────────────────────────────────────────────────────

    private enum ExecutorType {
        CONSOLE, PLAYER;

        static ExecutorType from(String s) {
            try {
                return valueOf(s.trim().toUpperCase());
            } catch (Exception e) {
                return CONSOLE;
            }
        }
    }
}
