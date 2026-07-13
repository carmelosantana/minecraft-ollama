package org.xpfarm.ollama.commands;

import org.xpfarm.ollama.OllamaPlugin;
import org.xpfarm.ollama.api.models.ChatMessage;
import org.xpfarm.ollama.chat.ChatSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Command handler for Ollama plugin commands
 * 
 * @author Carmelo Santana
 */
public class OllamaCommand implements CommandExecutor, TabCompleter {
    
    private final OllamaPlugin plugin;
    private final List<String> subcommands = Arrays.asList(
        "help", "version", "say", "code", "run", "chat", "status", "reload", "test", "debug", "prompt"
    );
    
    public OllamaCommand(OllamaPlugin plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }
        
        String subcommand = args[0].toLowerCase();
        
        switch (subcommand) {
            case "help":
                showHelp(sender);
                break;
            case "version":
                showVersion(sender);
                break;
            case "say":
                handleSay(sender, args);
                break;
            case "code":
                handleCode(sender, args);
                break;
            case "run":
                handleRun(sender, args);
                break;
            case "chat":
                handleChat(sender, args);
                break;
            case "status":
                handleStatus(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "test":
                handleTest(sender);
                break;
            case "debug":
                handleDebug(sender, args);
                break;
            case "prompt":
                handlePrompt(sender, args);
                break;
            default:
                sender.sendMessage(Component.text("Unknown subcommand: " + subcommand, NamedTextColor.RED));
                showHelp(sender);
        }
        
        return true;
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return subcommands.stream()
                    .filter(sub -> sub.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            return Arrays.asList("on", "off").stream()
                    .filter(option -> option.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        
        if (args.length == 2 && args[0].equalsIgnoreCase("prompt")) {
            return Arrays.stream(plugin.getPromptManager().getAvailablePrompts())
                    .filter(action -> action.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        
        return new ArrayList<>();
    }
    
    private void showHelp(CommandSender sender) {
        sender.sendMessage(Component.text("=== Ollama Plugin Commands ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/ollama help", NamedTextColor.YELLOW)
                .append(Component.text(" - Show this help message", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/ollama version", NamedTextColor.YELLOW)
                .append(Component.text(" - Show plugin version", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/ollama say <prompt>", NamedTextColor.YELLOW)
                .append(Component.text(" - Generate text", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/ollama code <prompt>", NamedTextColor.YELLOW)
                .append(Component.text(" - Generate code", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/ollama run <prompt>", NamedTextColor.YELLOW)
                .append(Component.text(" - Generate and execute command", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/ollama chat", NamedTextColor.YELLOW)
                .append(Component.text(" - Start interactive chat", NamedTextColor.GRAY)));
        
        if (sender.hasPermission("ollama.admin")) {
            sender.sendMessage(Component.text("Admin Commands:", NamedTextColor.AQUA));
            sender.sendMessage(Component.text("/ollama status", NamedTextColor.YELLOW)
                    .append(Component.text(" - Show plugin status", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/ollama reload", NamedTextColor.YELLOW)
                    .append(Component.text(" - Reload configuration", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/ollama test", NamedTextColor.YELLOW)
                    .append(Component.text(" - Test API connection", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/ollama debug <on|off>", NamedTextColor.YELLOW)
                    .append(Component.text(" - Toggle debug mode", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/ollama prompt <action>", NamedTextColor.YELLOW)
                    .append(Component.text(" - View system prompt for action", NamedTextColor.GRAY)));
        }
    }
    
    private void showVersion(CommandSender sender) {
        sender.sendMessage(Component.text("Ollama Plugin", NamedTextColor.GOLD)
                .append(Component.text(" v" + plugin.getDescription().getVersion(), NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("Model: ", NamedTextColor.AQUA)
                .append(Component.text(plugin.getOllamaAPI().getDefaultModel(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Endpoint: ", NamedTextColor.AQUA)
                .append(Component.text(plugin.getOllamaAPI().getEndpoint(), NamedTextColor.WHITE)));
    }
    
    private void handleSay(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ollama.generate")) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return;
        }
        
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ollama say <prompt>", NamedTextColor.RED));
            return;
        }
        
        String prompt = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Player player = sender instanceof Player ? (Player) sender : null;
        
        sender.sendMessage(Component.text("Generating response...", NamedTextColor.YELLOW));
        
        plugin.getOllamaAPI().generateWithSystemPrompt("say", prompt, player, response -> {
            if (response.hasError()) {
                sender.sendMessage(Component.text("Error: " + response.getError(), NamedTextColor.RED));
            } else {
                sender.sendMessage(Component.text("Response:", NamedTextColor.AQUA));
                sender.sendMessage(Component.text(response.getResponse(), NamedTextColor.WHITE));
                
                if (plugin.isDebugEnabled()) {
                    sender.sendMessage(Component.text("(Generated in " + 
                            String.format("%.2f", response.getTokensPerSecond()) + " tokens/sec)", 
                            NamedTextColor.GRAY));
                }
            }
        });
    }
    
    private void handleCode(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ollama.code")) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return;
        }
        
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ollama code <prompt>", NamedTextColor.RED));
            return;
        }
        
        String prompt = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Player player = sender instanceof Player ? (Player) sender : null;
        
        sender.sendMessage(Component.text("Generating code...", NamedTextColor.YELLOW));
        
        plugin.getOllamaAPI().generateWithSystemPrompt("code", prompt, player, response -> {
            if (response.hasError()) {
                sender.sendMessage(Component.text("Error: " + response.getError(), NamedTextColor.RED));
            } else {
                sender.sendMessage(Component.text("Generated Code:", NamedTextColor.AQUA));
                sender.sendMessage(Component.text(response.getResponse(), NamedTextColor.WHITE));
            }
        });
    }
    
    private void handleRun(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ollama.run")) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return;
        }
        
        if (!plugin.getConfig().getBoolean("commands.enable_execution", true)) {
            sender.sendMessage(Component.text("Command execution is disabled.", NamedTextColor.RED));
            return;
        }
        
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ollama run <prompt>", NamedTextColor.RED));
            return;
        }
        
        String prompt = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Player player = sender instanceof Player ? (Player) sender : null;
        
        sender.sendMessage(Component.text("Generating command...", NamedTextColor.YELLOW));
        
        plugin.getOllamaAPI().generateWithSystemPrompt("run", prompt, player, response -> {
            if (response.hasError()) {
                sender.sendMessage(Component.text("Error: " + response.getError(), NamedTextColor.RED));
            } else {
                String command = response.getResponse().trim();
                
                // Safety check
                if (isCommandBlocked(command)) {
                    sender.sendMessage(Component.text("Generated command is blocked for safety: " + command, NamedTextColor.RED));
                    return;
                }
                
                sender.sendMessage(Component.text("Generated command: ", NamedTextColor.AQUA)
                        .append(Component.text("/" + command, NamedTextColor.YELLOW)));
                
                if (plugin.getConfig().getBoolean("commands.require_confirmation", true)) {
                    sender.sendMessage(Component.text("Execute this command? Type 'yes' to confirm or 'no' to cancel.", NamedTextColor.YELLOW));
                    // TODO: Implement confirmation system
                } else {
                    executeCommand(sender, command);
                }
            }
        });
    }
    
    private void handleChat(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ollama.chat")) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return;
        }
        
        if (!(sender instanceof Player)) {
            sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
            return;
        }
        
        Player player = (Player) sender;
        
        if (args.length == 1) {
            // Start or continue chat session
            plugin.getChatManager().getOrCreateSession(player);
            sender.sendMessage(Component.text("=== Chat Session Started ===", NamedTextColor.GOLD));
            sender.sendMessage(Component.text("Type your message in chat or use /ollama chat <message>", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Type 'exit' to end the session", NamedTextColor.GRAY));
        } else {
            // Send message to chat
            String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            ChatSession session = plugin.getChatManager().getOrCreateSession(player);
            
            session.addMessage(ChatMessage.user(message));
            sender.sendMessage(Component.text("You: " + message, NamedTextColor.AQUA));
            sender.sendMessage(Component.text("Generating response...", NamedTextColor.YELLOW));
            
            plugin.getOllamaAPI().chatWithSystemPrompt(session.getMessages(), player, response -> {
                if (response.hasError()) {
                    sender.sendMessage(Component.text("Error: " + response.getError(), NamedTextColor.RED));
                } else {
                    String content = response.getContent();
                    session.addMessage(ChatMessage.assistant(content));
                    sender.sendMessage(Component.text("Assistant: " + content, NamedTextColor.WHITE));
                }
            });
        }
    }
    
    private void handleStatus(CommandSender sender) {
        if (!sender.hasPermission("ollama.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return;
        }
        
        sender.sendMessage(Component.text("=== Ollama Plugin Status ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Version: ", NamedTextColor.AQUA)
                .append(Component.text(plugin.getDescription().getVersion(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Model: ", NamedTextColor.AQUA)
                .append(Component.text(plugin.getOllamaAPI().getDefaultModel(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Endpoint: ", NamedTextColor.AQUA)
                .append(Component.text(plugin.getOllamaAPI().getEndpoint(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Debug Mode: ", NamedTextColor.AQUA)
                .append(Component.text(plugin.isDebugEnabled() ? "Enabled" : "Disabled", 
                        plugin.isDebugEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        
        // Test API connection
        sender.sendMessage(Component.text("Testing API connection...", NamedTextColor.YELLOW));
        plugin.getOllamaAPI().testConnection((success, message) -> {
            if (success) {
                sender.sendMessage(Component.text("✅ API Status: " + message, NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("❌ API Status: " + message, NamedTextColor.RED));
            }
        });
    }
    
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("ollama.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return;
        }
        
        plugin.reloadPluginConfig();
        sender.sendMessage(Component.text("Plugin configuration reloaded successfully!", NamedTextColor.GREEN));
    }
    
    private void handleTest(CommandSender sender) {
        if (!sender.hasPermission("ollama.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return;
        }
        
        sender.sendMessage(Component.text("Sending test request to Ollama...", NamedTextColor.YELLOW));
        
        plugin.getOllamaAPI().generate("Hello, this is a test message from the Ollama plugin!", 
                sender instanceof Player ? (Player) sender : null, response -> {
            if (response.hasError()) {
                sender.sendMessage(Component.text("❌ Test failed: " + response.getError(), NamedTextColor.RED));
            } else {
                sender.sendMessage(Component.text("✅ Test successful!", NamedTextColor.GREEN));
                sender.sendMessage(Component.text("Response: " + response.getResponse(), NamedTextColor.WHITE));
                sender.sendMessage(Component.text("Performance: " + 
                        String.format("%.2f tokens/sec", response.getTokensPerSecond()), NamedTextColor.GRAY));
            }
        });
    }
    
    private void handleDebug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ollama.debug")) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return;
        }
        
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ollama debug <on|off>", NamedTextColor.RED));
            return;
        }
        
        String mode = args[1].toLowerCase();
        if (mode.equals("on") || mode.equals("true")) {
            plugin.getConfig().set("debug.enabled", true);
            plugin.saveConfig();
            sender.sendMessage(Component.text("Debug mode enabled", NamedTextColor.GREEN));
        } else if (mode.equals("off") || mode.equals("false")) {
            plugin.getConfig().set("debug.enabled", false);
            plugin.saveConfig();
            sender.sendMessage(Component.text("Debug mode disabled", NamedTextColor.RED));
        } else {
            sender.sendMessage(Component.text("Usage: /ollama debug <on|off>", NamedTextColor.RED));
        }
    }
    
    private void handlePrompt(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ollama.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return;
        }
        
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /ollama prompt <action>", NamedTextColor.RED));
            sender.sendMessage(Component.text("Available actions: ", NamedTextColor.AQUA)
                    .append(Component.text(String.join(", ", plugin.getPromptManager().getAvailablePrompts()), 
                            NamedTextColor.WHITE)));
            return;
        }
        
        String action = args[1].toLowerCase();
        
        if (!plugin.getPromptManager().hasPrompt(action)) {
            sender.sendMessage(Component.text("Unknown action: " + action, NamedTextColor.RED));
            sender.sendMessage(Component.text("Available actions: ", NamedTextColor.AQUA)
                    .append(Component.text(String.join(", ", plugin.getPromptManager().getAvailablePrompts()), 
                            NamedTextColor.WHITE)));
            return;
        }
        
        String rawPrompt = plugin.getPromptManager().getRawPromptTemplate(action);
        if (rawPrompt == null) {
            sender.sendMessage(Component.text("Failed to load prompt template for: " + action, NamedTextColor.RED));
            return;
        }
        
        sender.sendMessage(Component.text("=== System Prompt for '" + action + "' ===", NamedTextColor.GOLD));
        
        // Split the prompt into lines and send each line
        String[] lines = rawPrompt.split("\n");
        for (String line : lines) {
            sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
        }
        
        sender.sendMessage(Component.text("=== End of Prompt ===", NamedTextColor.GOLD));
        
        // Show context variables that will be filled
        sender.sendMessage(Component.text("Context Variables:", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("• <INSERT_LOGS_HERE> - Recent player activity logs", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("• <PLAYER_NAME> - Name of the requesting player", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("• <ONLINE>/<MAX_PLAYERS> - Current/max players", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("• <PLAYER_ACTIONS_OR_STATE> - Player's recent actions", NamedTextColor.YELLOW));
    }
    
    private boolean isCommandBlocked(String command) {
        List<String> blockedCommands = plugin.getConfig().getStringList("commands.blocked_commands");
        String commandName = command.split(" ")[0].toLowerCase();
        return blockedCommands.contains(commandName);
    }
    
    private void executeCommand(CommandSender sender, String command) {
        plugin.getServer().dispatchCommand(sender, command);
        sender.sendMessage(Component.text("Command executed: /" + command, NamedTextColor.GREEN));
    }
}
