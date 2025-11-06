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
import org.bukkit.event.player.PlayerJoinEvent;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.stream.Collectors;

public class ElytraEnchantsPlugin extends JavaPlugin implements Listener, TabExecutor {

    private final Set<Enchantment> allowedEnchantments = new HashSet<>();
    private final Set<UUID> processingThorns = new HashSet<>();
    private YamlConfiguration messages;
    private boolean debugMode;
    
    // Spigot resource ID for update checking
    private static final int SPIGOT_RESOURCE_ID = 126943;
    
    // Store update info for new players
    private String latestVersion = null;
    private boolean updateAvailable = false;

    @Override
    public void onEnable() {
        // Create data folder if it doesn't exist
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        
        // Load configuration FIRST
        loadConfig();
        
        // Migrate other config files
        migrateConfigFile("messages.yml");
        
        loadAllowedEnchantments();
        loadMessages();
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("elytraenchant").setExecutor(this);
        getCommand("elytraenchant").setTabCompleter(this);
        getCommand("elytraenchants").setExecutor(this);
        if (debugMode) {
            getLogger().info("ElytraEnchantsPlugin enabled!");
        }
        
        // Check for updates asynchronously
        checkForUpdates();
    }
    
    private void loadConfig() {
        try {
            // Save default config if it doesn't exist
            saveDefaultConfig();
            
            // Get the config from the parent class
            FileConfiguration config = super.getConfig();
            
            if (config == null) {
                getLogger().severe("Failed to load config.yml - config is still null!");
            } else {
                // Migrate config to add any missing new options
                migrateConfig();
                if (debugMode) {
                    getLogger().info("Config loaded successfully");
                }
            }
        } catch (Exception e) {
            getLogger().severe("Error loading config: " + e.getMessage());
            if (debugMode) {
                e.printStackTrace();
            }
        }
    }

    private void loadAllowedEnchantments() {
        allowedEnchantments.clear();
        FileConfiguration config = getConfig();
        debugMode = config.getBoolean("debug", false);
        if (config.isConfigurationSection("enchantments")) {
            for (String key : config.getConfigurationSection("enchantments").getKeys(false)) {
                if (config.getBoolean("enchantments." + key, true)) {
                    Enchantment ench = Enchantment.getByName(key);
                    if (ench != null) {
                        allowedEnchantments.add(ench);
                        if (debugMode) {
                            getLogger().info("Loaded allowed enchantment: " + key + " -> " + ench.getKey().getKey() + " (name: " + ench.getName() + ")");
                        }
                    } else {
                        if (debugMode) {
                            getLogger().warning("Could not find enchantment: " + key + " - trying alternative names...");
                        }
                        // Try alternative names for common enchantments
                        if (key.equals("UNBREAKING")) {
                            ench = Enchantment.DURABILITY;
                            if (ench != null) {
                                allowedEnchantments.add(ench);
                                if (debugMode) {
                                    getLogger().info("Loaded UNBREAKING as DURABILITY: " + ench.getKey().getKey());
                                }
                            }
                        } else if (key.equals("MENDING")) {
                            ench = Enchantment.MENDING;
                            if (ench != null) {
                                allowedEnchantments.add(ench);
                                if (debugMode) {
                                    getLogger().info("Loaded MENDING: " + ench.getKey().getKey());
                                }
                            }
                        }
                    }
                }
            }
        }
        if (debugMode) {
            getLogger().info("Total allowed enchantments: " + allowedEnchantments.size());
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
    
    /**
     * Migrate config file to add missing options from newer versions
     * This ensures existing config files get new options added without losing custom settings
     * Works generically for all config sections, not just specific ones
     * Preserves formatting and comments by extracting sections as raw text from default config
     */
    private void migrateConfig() {
        try {
            // Get the actual config file
            File configFile = new File(getDataFolder(), "config.yml");
            
            if (!configFile.exists()) {
                return; // No migration needed if file doesn't exist
            }
            
            // Load the default config from the jar resource (get it twice - once for text, once for YAML)
            InputStream defaultConfigTextStream = getResource("config.yml");
            if (defaultConfigTextStream == null) {
                if (debugMode) {
                    getLogger().warning("Could not load default config.yml from jar for migration");
                }
                return;
            }
            
            // Read default config as text to preserve formatting
            List<String> currentLines = new java.util.ArrayList<>(java.nio.file.Files.readAllLines(configFile.toPath()));
            List<String> defaultLines = new java.util.ArrayList<>();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new InputStreamReader(defaultConfigTextStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    defaultLines.add(line);
                }
            }
            
            // Also load as YAML to check what's missing (get resource again - getResource returns new stream)
            InputStream defaultConfigYamlStream = getResource("config.yml");
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaultConfigYamlStream));
            YamlConfiguration currentConfig = YamlConfiguration.loadConfiguration(configFile);
            
            // Check what sections are missing
            boolean needsSave = false;
            List<String> sectionsToAdd = new java.util.ArrayList<>();
            Map<String, List<String>> missingNestedKeys = new java.util.HashMap<>();
            
            for (String key : defaultConfig.getKeys(false)) {
                if (!currentConfig.contains(key)) {
                    sectionsToAdd.add(key);
                    needsSave = true;
                } else if (defaultConfig.isConfigurationSection(key)) {
                    // Check nested keys and collect them for comment-preserving insertion
                    List<String> missingKeys = new java.util.ArrayList<>();
                    collectMissingKeys(defaultConfig.getConfigurationSection(key), currentConfig, key, "", missingKeys);
                    if (!missingKeys.isEmpty()) {
                        missingNestedKeys.put(key, missingKeys);
                        needsSave = true;
                    }
                }
            }
            
            // If we have entire sections missing, extract them from default config with full formatting
            if (!sectionsToAdd.isEmpty()) {
                StringBuilder newContent = new StringBuilder();
                
                for (String section : sectionsToAdd) {
                    // Find the section header comment and extract entire section
                    boolean foundSection = false;
                    boolean inSection = false;
                    int baseIndent = -1;
                    
                    for (int i = 0; i < defaultLines.size(); i++) {
                        String line = defaultLines.get(i);
                        String trimmed = line.trim();
                        
                        // Look for section header (comment before section key)
                        if (!foundSection && trimmed.startsWith("#") && 
                            (trimmed.toLowerCase().contains(section.toLowerCase()) || 
                             (i + 1 < defaultLines.size() && defaultLines.get(i + 1).trim().equals(section + ":")))) {
                            foundSection = true;
                            // Add blank line before section if needed
                            if (newContent.length() > 0 && !newContent.toString().endsWith("\n\n")) {
                                newContent.append("\n");
                            }
                            newContent.append(line).append("\n");
                            continue;
                        }
                        
                        // Found the section key
                        if (foundSection && !inSection && trimmed.equals(section + ":")) {
                            inSection = true;
                            baseIndent = line.length() - trimmed.length();
                            newContent.append(line).append("\n");
                            continue;
                        }
                        
                        // Inside section - add all lines until next top-level key
                        if (inSection) {
                            if (trimmed.isEmpty()) {
                                // Blank line - preserve it
                                newContent.append("\n");
                            } else if (line.startsWith("#")) {
                                // Comment - preserve it
                                newContent.append(line).append("\n");
                            } else {
                                int indent = line.length() - trimmed.length();
                                if (indent > baseIndent) {
                                    // Still in section - add line
                                    newContent.append(line).append("\n");
                                } else if (indent == baseIndent && trimmed.endsWith(":") && !trimmed.equals(section + ":")) {
                                    // Next top-level section starting - stop
                                    break;
                                } else if (indent < baseIndent || (indent == 0 && !trimmed.startsWith("#"))) {
                                    // Back to root level or next section - stop
                                    break;
                                } else {
                                    newContent.append(line).append("\n");
                                }
                            }
                        }
                    }
                }
                
                // Append new sections to current config with proper spacing
                if (newContent.length() > 0) {
                    // Ensure blank line before new content
                    if (!currentLines.isEmpty()) {
                        String lastLine = currentLines.get(currentLines.size() - 1);
                        if (!lastLine.trim().isEmpty()) {
                            currentLines.add("");
                        }
                    }
                    
                    // Add the new section content
                    String contentToAdd = newContent.toString().trim();
                    for (String line : contentToAdd.split("\n", -1)) {
                        currentLines.add(line);
                    }
                    
                    // Write updated config
                    java.nio.file.Files.write(configFile.toPath(), currentLines, 
                        java.nio.charset.StandardCharsets.UTF_8);
                    if (debugMode) {
                        getLogger().info("Config migration completed - new sections added with formatting preserved");
                    }
                }
            }
            
            // For nested keys within existing sections, extract with comments from default config
            if (!missingNestedKeys.isEmpty() && sectionsToAdd.isEmpty()) {
                // Find each section in current config and insert missing keys with comments
                for (Map.Entry<String, List<String>> entry : missingNestedKeys.entrySet()) {
                    String sectionName = entry.getKey();
                    List<String> missingKeys = entry.getValue();
                    
                    // Find the section in current config
                    int sectionStartIndex = -1;
                    int sectionIndent = -1;
                    for (int i = 0; i < currentLines.size(); i++) {
                        String line = currentLines.get(i);
                        String trimmed = line.trim();
                        if (trimmed.equals(sectionName + ":")) {
                            sectionStartIndex = i;
                            sectionIndent = line.length() - trimmed.length();
                            break;
                        }
                    }
                    
                    if (sectionStartIndex >= 0) {
                        // Find where to insert (after last key in section or at end of section)
                        int insertIndex = sectionStartIndex + 1;
                        for (int i = sectionStartIndex + 1; i < currentLines.size(); i++) {
                            String line = currentLines.get(i);
                            String trimmed = line.trim();
                            if (trimmed.isEmpty() || line.startsWith("#")) {
                                insertIndex = i + 1;
                                continue;
                            }
                            int indent = line.length() - trimmed.length();
                            if (indent <= sectionIndent && !trimmed.startsWith("#")) {
                                // End of section or next section
                                break;
                            }
                            insertIndex = i + 1;
                        }
                        
                        // Extract missing keys with comments from default config
                        List<String> keysToInsert = new java.util.ArrayList<>();
                        for (String missingKey : missingKeys) {
                            // Extract key with comments from default config
                            List<String> keyLines = extractKeyWithComments(defaultLines, sectionName, missingKey);
                            if (!keyLines.isEmpty()) {
                                keysToInsert.addAll(keyLines);
                            }
                        }
                        
                        // Insert the keys
                        if (!keysToInsert.isEmpty()) {
                            // Ensure blank line before if needed
                            if (insertIndex > 0 && insertIndex < currentLines.size()) {
                                String prevLine = currentLines.get(insertIndex - 1);
                                if (!prevLine.trim().isEmpty() && !prevLine.trim().equals(sectionName + ":")) {
                                    keysToInsert.add(0, "");
                                }
                            }
                            
                            currentLines.addAll(insertIndex, keysToInsert);
                            needsSave = true;
                        }
                    }
                }
                
                if (needsSave) {
                    // Write updated config
                    java.nio.file.Files.write(configFile.toPath(), currentLines, 
                        java.nio.charset.StandardCharsets.UTF_8);
                    if (debugMode) {
                        getLogger().info("Config migration completed - new options added with comments preserved");
                    }
                }
            }
            
            // Fallback: For any remaining missing keys, use YAML API (will lose formatting)
            if (needsSave && sectionsToAdd.isEmpty() && missingNestedKeys.isEmpty()) {
                currentConfig.save(configFile);
                if (debugMode) {
                    getLogger().info("Config migration completed - new options added to config.yml");
                }
            }
            
            // Reload the config so the new values are available
            if (needsSave) {
                reloadConfig();
            }
        } catch (Exception e) {
            if (debugMode) {
                getLogger().warning("Error during config migration: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Migrate a specific config file (messages.yml, etc.)
     * Uses the same migration logic as config.yml
     */
    private void migrateConfigFile(String filename) {
        try {
            File configFile = new File(getDataFolder(), filename);
            
            if (!configFile.exists()) {
                return; // No migration needed if file doesn't exist
            }
            
            // Load default config from jar
            InputStream defaultConfigTextStream = getResource(filename);
            if (defaultConfigTextStream == null) {
                return; // No default file in jar, skip
            }
            
            // Read both as text to preserve formatting
            List<String> currentLines = new java.util.ArrayList<>(java.nio.file.Files.readAllLines(configFile.toPath()));
            List<String> defaultLines = new java.util.ArrayList<>();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new InputStreamReader(defaultConfigTextStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    defaultLines.add(line);
                }
            }
            
            // Load as YAML to check what's missing
            InputStream defaultConfigYamlStream = getResource(filename);
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaultConfigYamlStream));
            YamlConfiguration currentConfig = YamlConfiguration.loadConfiguration(configFile);
            
            // Check what sections/keys are missing
            boolean needsSave = false;
            List<String> sectionsToAdd = new java.util.ArrayList<>();
            
            for (String key : defaultConfig.getKeys(false)) {
                if (!currentConfig.contains(key)) {
                    sectionsToAdd.add(key);
                    needsSave = true;
                } else if (defaultConfig.isConfigurationSection(key)) {
                    if (addMissingKeys(defaultConfig.getConfigurationSection(key), currentConfig, key)) {
                        needsSave = true;
                    }
                }
            }
            
            // Extract and append missing sections with formatting
            if (!sectionsToAdd.isEmpty()) {
                StringBuilder newContent = new StringBuilder();
                
                for (String section : sectionsToAdd) {
                    boolean foundSection = false;
                    boolean inSection = false;
                    int baseIndent = -1;
                    
                    for (int i = 0; i < defaultLines.size(); i++) {
                        String line = defaultLines.get(i);
                        String trimmed = line.trim();
                        
                        // Find section header comment
                        if (!foundSection && trimmed.startsWith("#") && 
                            (trimmed.toLowerCase().contains(section.toLowerCase()) || 
                             (i + 1 < defaultLines.size() && defaultLines.get(i + 1).trim().equals(section + ":")))) {
                            foundSection = true;
                            if (newContent.length() > 0 && !newContent.toString().endsWith("\n\n")) {
                                newContent.append("\n");
                            }
                            newContent.append(line).append("\n");
                            continue;
                        }
                        
                        // Found section key
                        if (foundSection && !inSection && trimmed.equals(section + ":")) {
                            inSection = true;
                            baseIndent = line.length() - trimmed.length();
                            newContent.append(line).append("\n");
                            continue;
                        }
                        
                        // Inside section - preserve all formatting
                        if (inSection) {
                            if (trimmed.isEmpty()) {
                                newContent.append("\n");
                            } else if (line.startsWith("#")) {
                                newContent.append(line).append("\n");
                            } else {
                                int indent = line.length() - trimmed.length();
                                if (indent > baseIndent) {
                                    newContent.append(line).append("\n");
                                } else if (indent == baseIndent && trimmed.endsWith(":") && !trimmed.equals(section + ":")) {
                                    break;
                                } else if (indent < baseIndent || (indent == 0 && !trimmed.startsWith("#"))) {
                                    break;
                                } else {
                                    newContent.append(line).append("\n");
                                }
                            }
                        }
                    }
                }
                
                // Append to current config
                if (newContent.length() > 0) {
                    if (!currentLines.isEmpty()) {
                        String lastLine = currentLines.get(currentLines.size() - 1);
                        if (!lastLine.trim().isEmpty()) {
                            currentLines.add("");
                        }
                    }
                    
                    String contentToAdd = newContent.toString().trim();
                    for (String line : contentToAdd.split("\n", -1)) {
                        currentLines.add(line);
                    }
                    
                    java.nio.file.Files.write(configFile.toPath(), currentLines, 
                        java.nio.charset.StandardCharsets.UTF_8);
                    
                    if (debugMode) {
                        getLogger().info("Migrated " + filename + " - new sections added with formatting preserved");
                    }
                }
            }
            
            // For nested keys, use YAML API
            if (needsSave && sectionsToAdd.isEmpty()) {
                currentConfig.save(configFile);
                if (debugMode) {
                    getLogger().info("Migrated " + filename + " - new options added");
                }
            }
            
        } catch (Exception e) {
            if (debugMode) {
                getLogger().warning("Error migrating " + filename + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * Recursively adds missing keys from default config to current config
     */
    private boolean addMissingKeys(org.bukkit.configuration.ConfigurationSection defaultSection, 
                                   YamlConfiguration currentConfig, String basePath) {
        boolean needsSave = false;
        
        // Iterate through keys at this level only
        for (String key : defaultSection.getKeys(false)) {
            String fullPath = basePath.isEmpty() ? key : basePath + "." + key;
            
            // Skip if the key already exists
            if (currentConfig.contains(fullPath)) {
                continue;
            }
            
            // Check if this is a section or a value
            if (defaultSection.isConfigurationSection(key)) {
                // Recursively check section
                boolean sectionNeedsSave = addMissingKeys(
                    defaultSection.getConfigurationSection(key),
                    currentConfig,
                    fullPath
                );
                needsSave = needsSave || sectionNeedsSave;
            } else {
                // This is a value - add it
                Object defaultValue = defaultSection.get(key);
                currentConfig.set(fullPath, defaultValue);
                
                if (debugMode) {
                    getLogger().info("Adding missing config option: " + fullPath + " = " + defaultValue);
                }
                needsSave = true;
            }
        }
        
        return needsSave;
    }
    
    /**
     * Collect missing keys from a section without modifying the config
     */
    private void collectMissingKeys(org.bukkit.configuration.ConfigurationSection defaultSection,
                                   YamlConfiguration currentConfig, String basePath, String keyPrefix,
                                   List<String> missingKeys) {
        for (String key : defaultSection.getKeys(false)) {
            String fullPath = basePath.isEmpty() ? key : basePath + "." + key;
            String displayPath = keyPrefix.isEmpty() ? key : keyPrefix + "." + key;
            
            // Skip if the key already exists
            if (currentConfig.contains(fullPath)) {
                // If it's a section, check nested keys
                if (defaultSection.isConfigurationSection(key)) {
                    collectMissingKeys(
                        defaultSection.getConfigurationSection(key),
                        currentConfig,
                        fullPath,
                        displayPath,
                        missingKeys
                    );
                }
                continue;
            }
            
            // This is a missing key - add it to the list
            missingKeys.add(displayPath);
            
            // If it's a section, also check nested keys
            if (defaultSection.isConfigurationSection(key)) {
                collectMissingKeys(
                    defaultSection.getConfigurationSection(key),
                    currentConfig,
                    fullPath,
                    displayPath,
                    missingKeys
                );
            }
        }
    }
    
    /**
     * Extract a key with its comments from default config lines
     */
    private List<String> extractKeyWithComments(List<String> defaultLines, String sectionName, String keyPath) {
        List<String> result = new java.util.ArrayList<>();
        
        // Split key path (e.g., "PROTECTION_ENVIRONMENTAL" or "nested.key")
        String[] keyParts = keyPath.split("\\.");
        String targetKey = keyParts[keyParts.length - 1]; // Last part is the actual key
        
        // Find the section in default config
        boolean inSection = false;
        int sectionIndent = -1;
        
        for (int i = 0; i < defaultLines.size(); i++) {
            String line = defaultLines.get(i);
            String trimmed = line.trim();
            
            // Find section start
            if (!inSection && trimmed.equals(sectionName + ":")) {
                inSection = true;
                sectionIndent = line.length() - trimmed.length();
                continue;
            }
            
            if (inSection) {
                // Check if we've left the section
                if (!trimmed.isEmpty() && !line.startsWith("#")) {
                    int indent = line.length() - trimmed.length();
                    if (indent <= sectionIndent && !trimmed.startsWith("#")) {
                        break; // Left the section
                    }
                }
                
                // Look for the target key
                if (trimmed.startsWith(targetKey + ":") || trimmed.startsWith(targetKey + " ")) {
                    // Found the key - collect preceding comments
                    int startIndex = i;
                    // Go backwards to find all comments before this key
                    while (startIndex > 0) {
                        String prevLine = defaultLines.get(startIndex - 1);
                        String prevTrimmed = prevLine.trim();
                        if (prevTrimmed.isEmpty()) {
                            // Blank line - include it if it's between comments
                            if (startIndex > 1 && defaultLines.get(startIndex - 2).trim().startsWith("#")) {
                                startIndex--;
                                continue;
                            }
                            break;
                        } else if (prevLine.startsWith("#")) {
                            // Comment line - include it
                            startIndex--;
                        } else {
                            // Not a comment - stop
                            break;
                        }
                    }
                    
                    // Extract from startIndex to i (including the key line)
                    for (int j = startIndex; j <= i; j++) {
                        result.add(defaultLines.get(j));
                    }
                    
                    // Also include the next line if it's a continuation
                    if (i + 1 < defaultLines.size()) {
                        String nextLine = defaultLines.get(i + 1);
                        String nextTrimmed = nextLine.trim();
                        int nextIndent = nextLine.length() - nextTrimmed.length();
                        if (nextIndent > sectionIndent && !nextTrimmed.startsWith("#") && !nextTrimmed.isEmpty()) {
                            result.add(nextLine);
                        }
                    }
                    
                    break;
                }
            }
        }
        
        return result;
    }

    @Override
    public void onDisable() {
        if (debugMode) {
            getLogger().info("ElytraEnchantsPlugin disabled!");
        }
    }

    /**
     * Check for plugin updates using SpigotMC API
     */
    private void checkForUpdates() {
        checkForUpdates(null);
    }
    
    /**
     * Check for plugin updates using SpigotMC API with player feedback
     */
    private void checkForUpdates(org.bukkit.entity.Player player) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String url = "https://api.spigotmc.org/legacy/update.php?resource=" + SPIGOT_RESOURCE_ID;
                java.net.URLConnection connection = java.net.URI.create(url).toURL().openConnection();
                connection.setRequestProperty("User-Agent", "ElytraEnchants-UpdateChecker");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                
                String latestVersion;
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(connection.getInputStream()))) {
                    latestVersion = reader.readLine();
                }
                
                String currentVersion = getDescription().getVersion();
                
                if (debugMode) {
                    getLogger().info("[DEBUG] Update check - API returned: '" + latestVersion + "', Current: '" + currentVersion + "'");
                }
                
                if (isNewerVersion(latestVersion, currentVersion)) {
                    // Store update info for new players
                    this.latestVersion = latestVersion;
                    this.updateAvailable = true;
                    
                    getServer().getScheduler().runTask(this, () -> {
                        String updateUrl = "https://www.spigotmc.org/resources/" + SPIGOT_RESOURCE_ID;
                        
                        if (debugMode) {
                            getLogger().info("§a[ElytraEnchants] Update available: " + latestVersion);
                            getLogger().info("§a[ElytraEnchants] Current version: " + currentVersion);
                            getLogger().info("§a[ElytraEnchants] Download: " + updateUrl);
                        }
                        
                        // Send update message to the player who requested the check
                        if (player != null) {
                            player.sendMessage(msg("update-available").replace("%latest%", latestVersion).replace("%current%", currentVersion));
                            player.sendMessage(msg("update-download").replace("%url%", updateUrl));
                        }
                        
                        // Send update message to all online OP'd players with a delay to show after MOTD
                        getServer().getScheduler().runTaskLater(this, () -> {
                            for (org.bukkit.entity.Player onlinePlayer : getServer().getOnlinePlayers()) {
                                if (onlinePlayer.isOp() && (player == null || !onlinePlayer.equals(player))) {
                                    onlinePlayer.sendMessage(msg("update-available").replace("%latest%", latestVersion).replace("%current%", currentVersion));
                                    onlinePlayer.sendMessage(msg("update-download").replace("%url%", updateUrl));
                                }
                            }
                        }, 100L); // 5 seconds delay (100 ticks = 5 seconds)
                    });
                } else {
                    // Plugin is up to date
                    if (debugMode) {
                        getLogger().info("[DEBUG] Plugin is up to date (version " + currentVersion + ")");
                    }
                    
                    // Send "up to date" message to the player who requested the check
                    if (player != null) {
                        getServer().getScheduler().runTask(this, () -> {
                            player.sendMessage(msg("update-up-to-date").replace("%version%", currentVersion));
                        });
                    }
                }
            } catch (Exception e) {
                if (debugMode) {
                    getLogger().warning("Could not check for updates: " + e.getMessage());
                }
                
                // Send error message to the player who requested the check
                if (player != null) {
                    getServer().getScheduler().runTask(this, () -> {
                        player.sendMessage(msg("update-error").replace("%error%", e.getMessage()));
                    });
                }
            }
        });
    }
    
    /**
     * Compare version strings to check if latest is newer
     */
    private boolean isNewerVersion(String latest, String current) {
        if (latest == null || current == null) {
            if (debugMode) {
                getLogger().info("[DEBUG] Version comparison failed - null values: latest=" + latest + ", current=" + current);
            }
            return false;
        }
        
        // Clean version strings - remove common prefixes like "Alpha", "Beta", "v", etc.
        String cleanLatest = latest.trim().replaceAll("^(v|version|alpha|beta|release)\\s*", "").trim();
        String cleanCurrent = current.trim().replaceAll("^(v|version|alpha|beta|release)\\s*", "").trim();
        
        // Also handle case variations
        cleanLatest = cleanLatest.replaceAll("^(Alpha|Beta|Release|V|Version)\\s*", "").trim();
        cleanCurrent = cleanCurrent.replaceAll("^(Alpha|Beta|Release|V|Version)\\s*", "").trim();
        
        if (debugMode) {
            getLogger().info("[DEBUG] Comparing versions - Latest: '" + latest + "' -> '" + cleanLatest + "', Current: '" + current + "' -> '" + cleanCurrent + "'");
        }
        
        // Simple version comparison - handles basic semantic versioning (1.0.2 vs 1.0.3)
        try {
            String[] latestParts = cleanLatest.split("\\.");
            String[] currentParts = cleanCurrent.split("\\.");
            
            int maxLength = Math.max(latestParts.length, currentParts.length);
            
            for (int i = 0; i < maxLength; i++) {
                int latestPart = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                
                if (debugMode) {
                    getLogger().info("[DEBUG] Comparing part " + i + ": " + latestPart + " vs " + currentPart);
                }
                
                if (latestPart > currentPart) {
                    if (debugMode) {
                        getLogger().info("[DEBUG] Latest version is newer at position " + i);
                    }
                    return true;
                } else if (latestPart < currentPart) {
                    if (debugMode) {
                        getLogger().info("[DEBUG] Current version is newer at position " + i);
                    }
                    return false;
                }
            }
            
            if (debugMode) {
                getLogger().info("[DEBUG] Versions are equal");
            }
            return false; // Versions are equal
        } catch (NumberFormatException e) {
            if (debugMode) {
                getLogger().warning("[DEBUG] Version parsing failed, using string comparison: " + e.getMessage());
            }
            // If version parsing fails, do simple string comparison
            boolean result = !cleanLatest.equals(cleanCurrent);
            if (debugMode) {
                getLogger().info("[DEBUG] String comparison result: " + result);
            }
            return result;
        }
    }
    
    /**
     * Handle player join event - notify about available updates
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Notify OP'd players about available updates with a delay to show after MOTD
        if (updateAvailable && event.getPlayer().isOp()) {
            String currentVersion = getDescription().getVersion();
            String updateUrl = "https://www.spigotmc.org/resources/" + SPIGOT_RESOURCE_ID;
            getServer().getScheduler().runTaskLater(this, () -> {
                event.getPlayer().sendMessage(msg("update-available").replace("%latest%", latestVersion).replace("%current%", currentVersion));
                event.getPlayer().sendMessage(msg("update-download").replace("%url%", updateUrl));
            }, 100L); // 5 seconds delay (100 ticks = 5 seconds)
        }
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
            if (debugMode) {
                getLogger().info("Applying book enchantments to elytra. Book contains: " + bookMeta.getStoredEnchants().keySet());
            }
            for (Map.Entry<Enchantment, Integer> entry : bookMeta.getStoredEnchants().entrySet()) {
                Enchantment ench = entry.getKey();
                int level = entry.getValue();
                if (allowedEnchantments.contains(ench)) {
                    result.addUnsafeEnchantment(ench, level);
                    changed = true;
                    if (debugMode) {
                        getLogger().info("Applied enchantment: " + ench.getName() + " level " + level);
                    }
                } else {
                    if (debugMode) {
                        getLogger().info("Skipped enchantment (not allowed): " + ench.getName());
                    }
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
            UUID attackerId = attacker.getUniqueId();
            if (processingThorns.contains(attackerId)) return;
            
            double chance = 0.15 + 0.15 * thorns;
            if (Math.random() < chance) {
                processingThorns.add(attackerId);
                try {
                    attacker.damage(1.0, player);
                } finally {
                    processingThorns.remove(attackerId);
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Handle reload command
        if (command.getName().equalsIgnoreCase("elytraenchants")) {
            if (!sender.hasPermission("elytraenchants.reload")) {
                sender.sendMessage(msg("reload-no-permission"));
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                loadMessages();
                sender.sendMessage(msg("reload-success"));
                return true;
            }
            sender.sendMessage(msg("reload-usage"));
            return true;
        }
        
        // Handle elytraenchant command
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
            if (debugMode) {
                getLogger().info("Player " + player.getName() + " tried to use enchantment: " + enchName + " (ench: " + ench + ", allowed: " + allowedEnchantments.contains(ench) + ")");
            }
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
                if (debugMode) {
                    getLogger().info("Tab completion - allowed enchantments: " + allowedEnchantments.stream()
                            .map(e -> e.getKey().getKey() + " (name: " + e.getName() + ")")
                            .collect(Collectors.joining(", ")));
                }
                return allowedEnchantments.stream()
                        .map(e -> e.getKey().getKey().toLowerCase())
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