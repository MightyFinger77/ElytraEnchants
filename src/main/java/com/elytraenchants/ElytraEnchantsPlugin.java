package com.elytraenchants;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ElytraEnchantsPlugin extends JavaPlugin implements Listener, TabExecutor {

    private final Set<Enchantment> allowedEnchantments = new HashSet<>();
    private YamlConfiguration messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadAllowedEnchantments();
        loadMessages();
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("elytraenchant").setExecutor(this);
        getCommand("elytraenchant").setTabCompleter(this);
        getLogger().info("ElytraEnchantsPlugin enabled!");
    }

    private void loadAllowedEnchantments() {
        allowedEnchantments.clear();
        FileConfiguration config = getConfig();
        if (config.isConfigurationSection("enchantments")) {
            for (String key : config.getConfigurationSection("enchantments").getKeys(false)) {
                if (config.getBoolean("enchantments." + key, true)) {
                    Enchantment ench = Enchantment.getByName(key);
                    if (ench != null) {
                        allowedEnchantments.add(ench);
                    }
                }
            }
        }
    }

    private void loadMessages() {
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(messagesFile);
    }

    private String msg(String key) {
        String raw = messages.getString(key, "");
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        loadAllowedEnchantments();
    }

    @Override
    public void onDisable() {
        getLogger().info("ElytraEnchantsPlugin disabled!");
    }

    @EventHandler
    public void onEnchantItem(EnchantItemEvent event) {
        ItemStack item = event.getItem();
        if (item.getType() != Material.ELYTRA) return;
        Map<Enchantment, Integer> enchantments = event.getEnchantsToAdd();
        for (Enchantment ench : allowedEnchantments) {
            if (!enchantments.containsKey(ench)) continue;
            item.addUnsafeEnchantment(ench, enchantments.get(ench));
        }
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack first = inv.getItem(0);
        ItemStack second = inv.getItem(1);
        String rename = event.getInventory().getRenameText();
        if (first == null) return;
        // Elytra + Elytra: merge all enchantments, keep highest level
        if (first.getType() == Material.ELYTRA && second != null && second.getType() == Material.ELYTRA) {
            ItemStack result = first.clone();
            boolean changed = false;
            // Merge all enchantments from both elytras
            Map<Enchantment, Integer> all = new java.util.HashMap<>();
            all.putAll(first.getEnchantments());
            for (Map.Entry<Enchantment, Integer> entry : second.getEnchantments().entrySet()) {
                all.merge(entry.getKey(), entry.getValue(), Math::max);
            }
            for (Map.Entry<Enchantment, Integer> entry : all.entrySet()) {
                result.addUnsafeEnchantment(entry.getKey(), entry.getValue());
                changed = true;
            }
            if (rename != null && !rename.isEmpty()) {
                var meta = result.getItemMeta();
                meta.setDisplayName(rename);
                result.setItemMeta(meta);
            }
            if (changed) {
                event.setResult(result);
                inv.setRepairCost(10);
            }
            return;
        }
        // Book to Elytra
        if (first.getType() == Material.ELYTRA && second != null && second.getType() == Material.ENCHANTED_BOOK && second.hasItemMeta()) {
            ItemStack result = first.clone();
            boolean changed = false;
            EnchantmentStorageMeta bookMeta = (EnchantmentStorageMeta) second.getItemMeta();
            for (Map.Entry<Enchantment, Integer> entry : bookMeta.getStoredEnchants().entrySet()) {
                Enchantment ench = entry.getKey();
                int level = entry.getValue();
                if (allowedEnchantments.contains(ench)) {
                    result.addUnsafeEnchantment(ench, level);
                    changed = true;
                }
            }
            if (rename != null && !rename.isEmpty()) {
                var meta = result.getItemMeta();
                meta.setDisplayName(rename);
                result.setItemMeta(meta);
            }
            if (changed) {
                event.setResult(result);
                inv.setRepairCost(5);
            }
        }
        // Chestplate to Elytra
        else if (first.getType() == Material.ELYTRA && second != null && isChestplate(second.getType())) {
            ItemStack result = first.clone();
            boolean changed = false;
            for (Map.Entry<Enchantment, Integer> entry : second.getEnchantments().entrySet()) {
                Enchantment ench = entry.getKey();
                int level = entry.getValue();
                if (allowedEnchantments.contains(ench)) {
                    result.addUnsafeEnchantment(ench, level);
                    changed = true;
                }
            }
            if (rename != null && !rename.isEmpty()) {
                var meta = result.getItemMeta();
                meta.setDisplayName(rename);
                result.setItemMeta(meta);
            }
            if (changed) {
                event.setResult(result);
                inv.setRepairCost(10);
            }
        }
        // Renaming Elytra only
        else if (first.getType() == Material.ELYTRA && (second == null || second.getType() == Material.AIR)) {
            if (rename != null && !rename.isEmpty() && !first.getItemMeta().hasDisplayName()) {
                ItemStack result = first.clone();
                var meta = result.getItemMeta();
                meta.setDisplayName(rename);
                result.setItemMeta(meta);
                event.setResult(result);
                inv.setRepairCost(1);
            }
        }
    }

    private boolean isChestplate(Material mat) {
        return mat == Material.DIAMOND_CHESTPLATE || mat == Material.NETHERITE_CHESTPLATE || mat == Material.IRON_CHESTPLATE || mat == Material.GOLDEN_CHESTPLATE || mat == Material.LEATHER_CHESTPLATE || mat == Material.CHAINMAIL_CHESTPLATE;
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || chest.getType() != Material.ELYTRA) return;

        double reduction = 0.0;

        // General Protection
        int prot = chest.getEnchantmentLevel(Enchantment.PROTECTION_ENVIRONMENTAL);
        if (prot > 0) {
            reduction += 0.04 * prot;
        }

        // Fire Protection
        int fireProt = chest.getEnchantmentLevel(Enchantment.PROTECTION_FIRE);
        if (fireProt > 0 && (event.getCause() == EntityDamageEvent.DamageCause.FIRE
                || event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK
                || event.getCause() == EntityDamageEvent.DamageCause.LAVA)) {
            reduction += 0.08 * fireProt;
        }

        // Blast Protection
        int blastProt = chest.getEnchantmentLevel(Enchantment.PROTECTION_EXPLOSIONS);
        if (blastProt > 0 && (event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)) {
            reduction += 0.08 * blastProt;
        }

        // Projectile Protection
        int projProt = chest.getEnchantmentLevel(Enchantment.PROTECTION_PROJECTILE);
        if (projProt > 0 && event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE) {
            reduction += 0.08 * projProt;
        }

        if (reduction > 0.8) reduction = 0.8; // cap at 80% reduction

        double newDamage = event.getDamage() * (1.0 - reduction);
        event.setDamage(newDamage);
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || chest.getType() != Material.ELYTRA) return;

        int thorns = chest.getEnchantmentLevel(Enchantment.THORNS);
        if (thorns > 0 && event.getDamager() instanceof LivingEntity attacker) {
            double chance = 0.15 + 0.15 * thorns;
            if (Math.random() < chance) {
                attacker.damage(1.0, player);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("not-a-player"));
            return true;
        }
        if (!sender.hasPermission("elytraenchant.use")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(msg("usage"));
            return true;
        }
        ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || chest.getType() != Material.ELYTRA) {
            sender.sendMessage(msg("not-wearing-elytra"));
            return true;
        }
        String enchName = args[0].toUpperCase();
        Enchantment ench = Enchantment.getByName(enchName);
        if (ench == null || !allowedEnchantments.contains(ench)) {
            sender.sendMessage(msg("enchant-not-allowed"));
            return true;
        }
        String permNode = "elytraenchant.enchant." + enchName.toLowerCase();
        if (!sender.hasPermission(permNode) && !sender.hasPermission("elytraenchant.enchant.*")) {
            sender.sendMessage(msg("no-permission-enchant"));
            return true;
        }
        int level;
        try {
            level = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(msg("invalid-level"));
            return true;
        }
        chest.addUnsafeEnchantment(ench, level);
        sender.sendMessage(msg("success").replace("%enchant%", ench.getKey().getKey()).replace("%level%", String.valueOf(level)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("elytraenchant")) {
            if (args.length == 1) {
                return allowedEnchantments.stream()
                        .map(e -> e.getName().toLowerCase())
                        .filter(e -> e.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 2) {
                return List.of("1", "2", "3", "4", "5").stream()
                        .filter(l -> l.startsWith(args[1]))
                        .collect(Collectors.toList());
            }
        }
        return List.of();
    }
} 