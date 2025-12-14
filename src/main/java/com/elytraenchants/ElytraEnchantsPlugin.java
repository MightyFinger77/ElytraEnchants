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
        getCommand("elytraenchants").setExecutor(this);
        getCommand("elytraenchants").setTabCompleter(this);
        if (debugMode) {
            getLogger().info("ElytraEnchantsPlugin enabled!");
        }
        
        // Check for updates asynchronously
        if (getConfig().getBoolean("update-checker.enabled", true)) {
            checkForUpdates();
        }
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
        if (messages == null) {
            return "";
        }
        String raw = messages.getString(key, "");
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        loadAllowedEnchantments();
    }
    
    /**
     * Migrate config.yml to add missing options from newer versions
     * Preserves user values while adding new options and comments
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
            
            // Check config version - get default version
            int defaultVersion = defaultConfig.getInt("config_version", 1);
            int currentVersion = currentConfig.getInt("config_version", 0); // 0 means old config without version
            
            // If versions match and config has version field, no migration needed
            if (currentVersion == defaultVersion && currentConfig.contains("config_version")) {
                return; // Config is up to date
            }
            
            // Simple merge approach: Use default config structure/comments, replace values with user's where they exist
            // This preserves all comments and formatting from default, while keeping user's custom values
            // Deprecated keys (in user config but not in default) are automatically removed
            List<String> mergedLines = mergeConfigs(defaultLines, currentConfig, defaultConfig);
            
            // Check for and log deprecated keys that were removed
            java.util.Set<String> deprecatedKeys = findDeprecatedKeys(currentConfig, defaultConfig);
            if (!deprecatedKeys.isEmpty()) {
                getLogger().info("Removed deprecated config keys: " + String.join(", ", deprecatedKeys));
            }
            
            // Update config version
            updateConfigVersion(mergedLines, defaultVersion, defaultLines, "config_version");
            
            // Write merged config
            java.nio.file.Files.write(configFile.toPath(), mergedLines, 
                java.nio.charset.StandardCharsets.UTF_8);
            
            if (debugMode) {
                getLogger().info("Config migration completed - merged with default config, preserving user values and all comments");
            }
            
            // Reload config
            reloadConfig();
        } catch (Exception e) {
            if (debugMode) {
                getLogger().warning("Error during config migration: " + e.getMessage());
                e.printStackTrace();
            }
            // Don't fail plugin startup if migration has issues
        }
    }
    
    /**
     * Merge default config (with comments) with user config (with values)
     * Simple approach: Use default structure/comments, replace values with user's where they exist
     */
    private List<String> mergeConfigs(List<String> defaultLines, YamlConfiguration userConfig, YamlConfiguration defaultConfig) {
        List<String> merged = new java.util.ArrayList<>();
        
        // Track current path for nested keys - store both name and indent level
        java.util.Stack<Pair<String, Integer>> pathStack = new java.util.Stack<>();
        
        for (int i = 0; i < defaultLines.size(); i++) {
            String line = defaultLines.get(i);
            String trimmed = line.trim();
            int currentIndent = line.length() - trimmed.length();
            
            // Always preserve comments and blank lines
            if (trimmed.isEmpty() || line.startsWith("#")) {
                merged.add(line);
                continue;
            }
            
            // Pop sections we've left (based on indent level)
            while (!pathStack.isEmpty() && currentIndent <= pathStack.peek().getValue()) {
                pathStack.pop();
            }
            
            // Check if this is a list item (starts with -)
            if (trimmed.startsWith("-")) {
                // This is a list item - preserve as-is (lists are handled at the parent key level)
                merged.add(line);
                continue;
            }
            
            // Check if this is a key=value line
            if (trimmed.contains(":") && !trimmed.startsWith("#")) {
                int colonIndex = trimmed.indexOf(':');
                String keyPart = trimmed.substring(0, colonIndex).trim();
                String valuePart = trimmed.substring(colonIndex + 1).trim();
                
                // Build full path for nested keys
                StringBuilder fullPathBuilder = new StringBuilder();
                for (Pair<String, Integer> pathEntry : pathStack) {
                    if (fullPathBuilder.length() > 0) {
                        fullPathBuilder.append(".");
                    }
                    fullPathBuilder.append(pathEntry.getKey());
                }
                if (fullPathBuilder.length() > 0) {
                    fullPathBuilder.append(".");
                }
                fullPathBuilder.append(keyPart);
                String fullPath = fullPathBuilder.toString();
                
                // Check if this is a section (value is empty and next line is indented or is a list)
                boolean isSection = valuePart.isEmpty();
                boolean isList = false;
                if (isSection && i + 1 < defaultLines.size()) {
                    // Look ahead to see if next non-comment line is indented or is a list item
                    for (int j = i + 1; j < defaultLines.size() && j < i + 10; j++) {
                        String nextLine = defaultLines.get(j);
                        String nextTrimmed = nextLine.trim();
                        if (nextTrimmed.isEmpty() || nextLine.startsWith("#")) {
                            continue;
                        }
                        int nextIndent = nextLine.length() - nextTrimmed.length();
                        if (nextTrimmed.startsWith("-")) {
                            // This is a list
                            isList = true;
                            isSection = true;
                            break;
                        } else if (nextIndent > currentIndent) {
                            isSection = true;
                            break;
                        } else {
                            // Next line is at same or less indent - not a section
                            break;
                        }
                    }
                }
                
                if (isSection) {
                    // This is a section - check if user has values for it
                    if (userConfig.contains(fullPath)) {
                        Object userValue = userConfig.get(fullPath);
                        
                        // If it's a list, we need to handle it specially
                        if (isList && userValue instanceof java.util.List) {
                            // Add the key line
                            merged.add(line);
                            // Add list items from user config
                            java.util.List<?> userList = (java.util.List<?>) userValue;
                            for (Object item : userList) {
                                String itemStr = formatYamlValue(item);
                                // Remove quotes if they were added (lists often don't need them)
                                if (itemStr.startsWith("\"") && itemStr.endsWith("\"")) {
                                    itemStr = itemStr.substring(1, itemStr.length() - 1);
                                }
                                merged.add(" ".repeat(currentIndent + 2) + "- " + itemStr);
                            }
                            // Skip the default list items and their comments - we've already added user's
                            // Skip until we're out of the list (next line at same or less indent)
                            while (i + 1 < defaultLines.size()) {
                                String nextLine = defaultLines.get(i + 1);
                                String nextTrimmed = nextLine.trim();
                                int nextIndent = nextLine.length() - nextTrimmed.length();
                                
                                // If it's a comment or blank line within the list, skip it
                                if (nextTrimmed.isEmpty() || nextLine.startsWith("#")) {
                                    if (nextIndent > currentIndent) {
                                        i++; // Skip comment/blank within list
                                    } else {
                                        break; // Comment at section level or above - end of list
                                    }
                                } else if (nextTrimmed.startsWith("-") && nextIndent > currentIndent) {
                                    i++; // Skip this list item
                                } else {
                                    break; // End of list
                                }
                            }
                            pathStack.push(new Pair<>(keyPart, currentIndent));
                            continue;
                        } else {
                            // Regular section - add it and push to path stack
                            merged.add(line);
                            pathStack.push(new Pair<>(keyPart, currentIndent));
                        }
                    } else {
                        // User doesn't have this section - use default (with all comments and list items)
                        merged.add(line);
                        pathStack.push(new Pair<>(keyPart, currentIndent));
                    }
                } else {
                    // This is a key=value line
                    // Skip version keys - they're handled separately by updateConfigVersion
                    if (keyPart.equals("config_version") || keyPart.equals("messages_version") || keyPart.equals("gui_version")) {
                        // Use default version line - it will be updated by updateConfigVersion
                        merged.add(line);
                    } else if (userConfig.contains(fullPath)) {
                        // User has this key - use their value but keep default's formatting
                        Object userValue = userConfig.get(fullPath);
                        String userValueStr = formatYamlValue(userValue);
                        
                        // Preserve inline comment if present
                        String inlineComment = "";
                        int commentIndex = valuePart.indexOf('#');
                        if (commentIndex >= 0) {
                            inlineComment = " " + valuePart.substring(commentIndex);
                        }
                        
                        // Replace value while preserving indentation and inline comment
                        merged.add(" ".repeat(currentIndent) + keyPart + ": " + userValueStr + inlineComment);
                    } else {
                        // User doesn't have this key - use default (with default value and comments)
                        merged.add(line);
                    }
                }
            } else {
                // Not a key=value line - preserve as-is
                merged.add(line);
            }
        }
        
        return merged;
    }
    
    /**
     * Find deprecated keys that exist in user config but not in default config
     * These keys will be removed during migration
     */
    private java.util.Set<String> findDeprecatedKeys(YamlConfiguration userConfig, YamlConfiguration defaultConfig) {
        java.util.Set<String> deprecated = new java.util.HashSet<>();
        findDeprecatedKeysRecursive(userConfig, defaultConfig, "", deprecated);
        return deprecated;
    }
    
    /**
     * Recursively find deprecated keys
     */
    private void findDeprecatedKeysRecursive(YamlConfiguration userConfig, YamlConfiguration defaultConfig, 
                                             String basePath, java.util.Set<String> deprecated) {
        for (String key : userConfig.getKeys(false)) {
            String fullPath = basePath.isEmpty() ? key : basePath + "." + key;
            
            // Skip version keys - they're handled separately
            if (key.equals("config_version") || key.equals("messages_version") || key.equals("gui_version")) {
                continue;
            }
            
            if (!defaultConfig.contains(fullPath)) {
                // This key doesn't exist in default config - it's deprecated
                deprecated.add(fullPath);
            } else if (userConfig.isConfigurationSection(key) && defaultConfig.isConfigurationSection(fullPath)) {
                // Both are sections - recursively check nested keys
                findDeprecatedKeysRecursive(
                    userConfig.getConfigurationSection(key),
                    defaultConfig.getConfigurationSection(fullPath),
                    fullPath,
                    deprecated
                );
            }
        }
    }
    
    /**
     * Recursive helper for configuration sections
     */
    private void findDeprecatedKeysRecursive(org.bukkit.configuration.ConfigurationSection userSection,
                                            org.bukkit.configuration.ConfigurationSection defaultSection,
                                            String basePath, java.util.Set<String> deprecated) {
        for (String key : userSection.getKeys(false)) {
            String fullPath = basePath.isEmpty() ? key : basePath + "." + key;
            
            // Skip version keys - they're handled separately
            if (key.equals("config_version") || key.equals("messages_version") || key.equals("gui_version")) {
                continue;
            }
            
            if (!defaultSection.contains(key)) {
                // This key doesn't exist in default config - it's deprecated
                deprecated.add(fullPath);
            } else if (userSection.isConfigurationSection(key) && defaultSection.isConfigurationSection(key)) {
                // Both are sections - recursively check nested keys
                findDeprecatedKeysRecursive(
                    userSection.getConfigurationSection(key),
                    defaultSection.getConfigurationSection(key),
                    fullPath,
                    deprecated
                );
            }
        }
    }
    
    // Simple Pair class for path tracking
    private static class Pair<K, V> {
        private final K key;
        private final V value;
        
        public Pair(K key, V value) {
            this.key = key;
            this.value = value;
        }
        
        public K getKey() { return key; }
        public V getValue() { return value; }
    }
    
    /**
     * Format a YAML value as a string
     */
    private String formatYamlValue(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof String) {
            // Check if it needs quotes
            String str = (String) value;
            if (str.contains(":") || str.contains("#") || str.trim().isEmpty() || 
                str.equalsIgnoreCase("true") || str.equalsIgnoreCase("false") || 
                str.equalsIgnoreCase("null") || str.matches("^-?\\d+$")) {
                return "\"" + str.replace("\"", "\\\"") + "\"";
            }
            return str;
        } else if (value instanceof Boolean) {
            return value.toString();
        } else if (value instanceof Number) {
            return value.toString();
        } else if (value instanceof java.util.List) {
            // Format list
            java.util.List<?> list = (java.util.List<?>) value;
            if (list.isEmpty()) {
                return "[]";
            }
            // For lists, we'll just return the first approach - inline if simple
            if (list.size() == 1 && (list.get(0) instanceof String || list.get(0) instanceof Number)) {
                return "[" + formatYamlValue(list.get(0)) + "]";
            }
            // Multi-line list - return as inline for now, could be improved
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatYamlValue(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        } else {
            return value.toString();
        }
    }
    
    /**
     * Update config version in the merged lines
     */
    private void updateConfigVersion(List<String> lines, int newVersion, List<String> defaultLines, String versionKey) {
        // Look for version line and update it, or add it if missing
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            
            // Check if this is the version line
            if (trimmed.startsWith(versionKey + ":") || trimmed.startsWith(versionKey + " ")) {
                // Update the version value, preserving indentation and any inline comments
                int indent = line.length() - trimmed.length();
                String restOfLine = "";
                int colonIndex = trimmed.indexOf(':');
                if (colonIndex >= 0 && colonIndex + 1 < trimmed.length()) {
                    String afterColon = trimmed.substring(colonIndex + 1).trim();
                    // Check if there's an inline comment
                    int commentIndex = afterColon.indexOf('#');
                    if (commentIndex >= 0) {
                        restOfLine = " #" + afterColon.substring(commentIndex + 1);
                    }
                }
                lines.set(i, " ".repeat(indent) + versionKey + ": " + newVersion + restOfLine);
                found = true;
                break;
            }
        }
        
        // If not found, add it after the header comment (usually line 2-3)
        if (!found) {
            // Extract comment from default config
            String commentLine = "# Config version - do not modify";
            for (int i = 0; i < defaultLines.size(); i++) {
                String line = defaultLines.get(i);
                String trimmed = line.trim();
                // Look for version key in default config
                if (trimmed.startsWith(versionKey + ":") || trimmed.startsWith(versionKey + " ")) {
                    // Check if there's a comment line before it
                    if (i > 0) {
                        String prevLine = defaultLines.get(i - 1);
                        if (prevLine.trim().startsWith("#")) {
                            commentLine = prevLine;
                        }
                    }
                    break;
                }
            }
            
            int insertIndex = 0;
            // Find a good place to insert - after first comment block, before first section
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                // Stop at first non-comment, non-blank line that's not the version key
                if (!trimmed.isEmpty() && !line.startsWith("#") && !trimmed.startsWith(versionKey)) {
                    insertIndex = i;
                    break;
                }
            }
            // Insert version line with comment from default config
            lines.add(insertIndex, commentLine);
            lines.add(insertIndex + 1, versionKey + ": " + newVersion);
            // Add blank line after if needed
            if (insertIndex + 2 < lines.size() && !lines.get(insertIndex + 2).trim().isEmpty()) {
                lines.add(insertIndex + 2, "");
            }
        }
    }
    
    /**
     * Migrate a specific config file (messages.yml, etc.)
     * Uses the same simple merge approach as config.yml
     */
    private void migrateConfigFile(String filename) {
        try {
            File configFile = new File(getDataFolder(), filename);
            
            // Save default config if it doesn't exist
            if (!configFile.exists()) {
                saveResource(filename, false);
            }
            
            // Load default config from jar
            InputStream defaultConfigTextStream = getResource(filename);
            if (defaultConfigTextStream == null) {
                return; // No default file in jar, skip
            }
            
            // Read default config as text to preserve formatting
            List<String> defaultLines = new java.util.ArrayList<>();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new InputStreamReader(defaultConfigTextStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    defaultLines.add(line);
                }
            }
            
            // Load as YAML to get user values
            InputStream defaultConfigYamlStream = getResource(filename);
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaultConfigYamlStream));
            YamlConfiguration currentConfig = YamlConfiguration.loadConfiguration(configFile);
            
            // Check config version - get default version (use filename + "_version" as key)
            String versionKey = filename.replace(".yml", "_version");
            int defaultVersion = defaultConfig.getInt(versionKey, 1);
            int currentVersion = currentConfig.getInt(versionKey, 0); // 0 means old config without version
            
            // If versions match and config has version field, no migration needed
            if (currentVersion == defaultVersion && currentConfig.contains(versionKey)) {
                return; // Config is up to date
            }
            
            // Simple merge: Use default structure/comments, replace values with user's where they exist
            // Deprecated keys (in user config but not in default) are automatically removed
            List<String> mergedLines = mergeConfigs(defaultLines, currentConfig, defaultConfig);
            
            // Check for and log deprecated keys that were removed
            java.util.Set<String> deprecatedKeys = findDeprecatedKeys(currentConfig, defaultConfig);
            if (!deprecatedKeys.isEmpty()) {
                getLogger().info("Removed deprecated keys from " + filename + ": " + String.join(", ", deprecatedKeys));
            }
            
            // Update config version
            updateConfigVersion(mergedLines, defaultVersion, defaultLines, versionKey);
            
            // Write merged config
            java.nio.file.Files.write(configFile.toPath(), mergedLines, 
                java.nio.charset.StandardCharsets.UTF_8);
            
            if (debugMode) {
                getLogger().info("Migrated " + filename + " - merged with default, preserving user values and all comments");
            }
        } catch (Exception e) {
            if (debugMode) {
                getLogger().warning("Error migrating " + filename + ": " + e.getMessage());
            }
            // Don't fail plugin startup if migration has issues
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
     * Handles dev versions (e.g., "1.1.4-Dev2a") by comparing base version numbers
     * Dev builds will be prompted to update to release versions of the same base version
     */
    private boolean isNewerVersion(String latest, String current) {
        if (latest == null || current == null) {
            if (debugMode) {
                getLogger().info("[DEBUG] Version comparison failed - null values: latest=" + latest + ", current=" + current);
            }
            return false;
        }
        
        // Store original strings to check for dev/release status
        String originalLatest = latest.trim();
        String originalCurrent = current.trim();
        
        // Clean version strings - remove common prefixes like "Alpha", "Beta", "v", etc.
        String cleanLatest = originalLatest.replaceAll("^(v|version|alpha|beta|release)\\s*", "").trim();
        String cleanCurrent = originalCurrent.replaceAll("^(v|version|alpha|beta|release)\\s*", "").trim();
        
        // Also handle case variations
        cleanLatest = cleanLatest.replaceAll("^(Alpha|Beta|Release|V|Version)\\s*", "").trim();
        cleanCurrent = cleanCurrent.replaceAll("^(Alpha|Beta|Release|V|Version)\\s*", "").trim();
        
        // Check if versions have dev/build suffixes before removing them
        boolean latestIsDev = cleanLatest.matches(".*[-_](?i)(dev|snapshot|alpha|beta|rc|build|pre)[\\d\\w]*$");
        boolean currentIsDev = cleanCurrent.matches(".*[-_](?i)(dev|snapshot|alpha|beta|rc|build|pre)[\\d\\w]*$");
        
        // Remove dev/build suffixes for base version comparison
        String baseLatest = cleanLatest.replaceAll("[-_](?i)(dev|snapshot|alpha|beta|rc|build|pre)[\\d\\w]*$", "").trim();
        String baseCurrent = cleanCurrent.replaceAll("[-_](?i)(dev|snapshot|alpha|beta|rc|build|pre)[\\d\\w]*$", "").trim();
        
        if (debugMode) {
            getLogger().info("[DEBUG] Comparing versions - Latest: '" + originalLatest + "' -> base: '" + baseLatest + "' (dev: " + latestIsDev + "), Current: '" + originalCurrent + "' -> base: '" + baseCurrent + "' (dev: " + currentIsDev + ")");
        }
        
        // Simple version comparison - handles basic semantic versioning (1.0.2 vs 1.0.3)
        try {
            String[] latestParts = baseLatest.split("\\.");
            String[] currentParts = baseCurrent.split("\\.");
            
            int maxLength = Math.max(latestParts.length, currentParts.length);
            
            for (int i = 0; i < maxLength; i++) {
                // Extract numeric part from each version segment (handles "4-Dev2a" -> "4")
                String latestPartStr = i < latestParts.length ? latestParts[i].replaceAll("[^0-9].*$", "") : "0";
                String currentPartStr = i < currentParts.length ? currentParts[i].replaceAll("[^0-9].*$", "") : "0";
                
                int latestPart = latestPartStr.isEmpty() ? 0 : Integer.parseInt(latestPartStr);
                int currentPart = currentPartStr.isEmpty() ? 0 : Integer.parseInt(currentPartStr);
                
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
                    return false; // Current version is newer, don't prompt for update
                }
            }
            
            // Base versions are equal - check if we should update from dev to release
            if (baseLatest.equals(baseCurrent)) {
                // If latest is a release version and current is a dev version (same base), prompt for update
                if (!latestIsDev && currentIsDev) {
                    if (debugMode) {
                        getLogger().info("[DEBUG] Base versions equal - latest is release, current is dev, prompting for update");
                    }
                    return true; // Dev build should update to release version
                } else {
                    if (debugMode) {
                        getLogger().info("[DEBUG] Base versions equal - no update needed");
                    }
                    return false; // Both are same type (both dev or both release), or current is release
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
            boolean result = !baseLatest.equals(baseCurrent);
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
        if (!command.getName().equalsIgnoreCase("elytraenchants")) {
            return false;
        }
        
        if (!sender.hasPermission("elytraenchants.use")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }
        
        if (args.length == 0) {
            sender.sendMessage(msg("usage"));
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        // Handle reload subcommand
        if (subCommand.equals("reload")) {
            if (!sender.hasPermission("elytraenchants.reload")) {
                sender.sendMessage(msg("reload-no-permission"));
                return true;
            }
            reloadConfig();
            loadMessages();
            String reloadMsg = msg("reload-success");
            if (reloadMsg == null || reloadMsg.isEmpty()) {
                sender.sendMessage(ChatColor.GREEN + "ElytraEnchants configuration reloaded!");
            } else {
                sender.sendMessage(reloadMsg);
            }
            return true;
        }
        
        // Handle enchant subcommand
        if (subCommand.equals("enchant")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(msg("not-a-player"));
                return true;
            }
            if (!sender.hasPermission("elytraenchants.enchant")) {
                sender.sendMessage(msg("no-permission-enchant"));
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(msg("usage"));
                return true;
            }
            ItemStack chest = player.getInventory().getChestplate();
            if (chest == null || chest.getType() != Material.ELYTRA) {
                sender.sendMessage(msg("not-wearing-elytra"));
                return true;
            }
            String enchName = args[1].toUpperCase();
            Enchantment ench = Enchantment.getByName(enchName);
            if (ench == null || !allowedEnchantments.contains(ench)) {
                if (debugMode) {
                    getLogger().info("Player " + player.getName() + " tried to use enchantment: " + enchName + " (ench: " + ench + ", allowed: " + allowedEnchantments.contains(ench) + ")");
                }
                sender.sendMessage(msg("enchant-not-allowed"));
                return true;
            }
            String permNode = "elytraenchants.enchant." + enchName.toLowerCase();
            if (!sender.hasPermission(permNode) && !sender.hasPermission("elytraenchants.enchant.*")) {
                sender.sendMessage(msg("no-permission-enchant"));
                return true;
            }
            int level;
            try {
                level = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(msg("invalid-level"));
                return true;
            }
            chest.addUnsafeEnchantment(ench, level);
            sender.sendMessage(msg("success").replace("%enchant%", ench.getKey().getKey()).replace("%level%", String.valueOf(level)));
            return true;
        }
        
        // Unknown subcommand
        sender.sendMessage(msg("usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("elytraenchants")) {
            return List.of();
        }
        
        if (!sender.hasPermission("elytraenchants.use")) {
            return List.of();
        }
        
        if (args.length == 1) {
            // Tab complete subcommands
            List<String> subcommands = new ArrayList<>();
            if (sender.hasPermission("elytraenchants.reload")) {
                subcommands.add("reload");
            }
            if (sender.hasPermission("elytraenchants.enchant")) {
                subcommands.add("enchant");
            }
            return subcommands.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        
        if (args.length == 2 && args[0].equalsIgnoreCase("enchant")) {
            // Tab complete enchantments
            if (!sender.hasPermission("elytraenchants.enchant")) {
                return List.of();
            }
            if (debugMode) {
                getLogger().info("Tab completion - allowed enchantments: " + allowedEnchantments.stream()
                        .map(e -> e.getKey().getKey() + " (name: " + e.getName() + ")")
                        .collect(Collectors.joining(", ")));
            }
            return allowedEnchantments.stream()
                    .map(e -> e.getKey().getKey().toLowerCase())
                    .filter(e -> e.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        
        if (args.length == 3 && args[0].equalsIgnoreCase("enchant")) {
            // Tab complete enchantment levels
            if (!sender.hasPermission("elytraenchants.enchant")) {
                return List.of();
            }
            return List.of("1", "2", "3", "4", "5").stream()
                    .filter(l -> l.startsWith(args[2]))
                    .collect(Collectors.toList());
        }
        
        return List.of();
    }
} 