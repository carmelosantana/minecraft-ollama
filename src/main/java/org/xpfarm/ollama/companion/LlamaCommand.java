package org.xpfarm.ollama.companion;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** /llama ask|recipe|dismiss|give. Never executes suggested commands; give is op-gated. */
public final class LlamaCommand implements CommandExecutor, TabCompleter {

    public enum Sub { ASK, RECIPE, DISMISS, GIVE, HELP, UNKNOWN }

    private final Plugin plugin;
    private final CompanionConversation conversation;
    private final CompanionRegistry registry;
    private final CompanionItem item;

    public LlamaCommand(Plugin plugin, CompanionConversation conversation, CompanionRegistry registry,
            CompanionItem item) {
        this.plugin = plugin;
        this.conversation = conversation;
        this.registry = registry;
        this.item = item;
    }

    public static Sub route(String[] args) {
        if (args.length == 0) {
            return Sub.HELP;
        }
        return switch (args[0].toLowerCase()) {
            case "ask" -> Sub.ASK;
            case "recipe" -> Sub.RECIPE;
            case "dismiss" -> Sub.DISMISS;
            case "give" -> Sub.GIVE;
            case "help" -> Sub.HELP;
            default -> Sub.UNKNOWN;
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (route(args)) {
            case ASK -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Only players can talk to a llama.", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(Component.text("Usage: /llama ask <message>", NamedTextColor.YELLOW));
                    return true;
                }
                conversation.ask(player, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            }
            case RECIPE -> sender.sendMessage(Component.text(
                    "Llama Companion recipe:\n W L W   (W=White Wool, L=Lead)\n H G H   (H=Hay Bale, G=Gold Ingot)\n W L W",
                    NamedTextColor.AQUA));
            case DISMISS -> {
                if (!(sender instanceof Player player)) {
                    return true;
                }
                dismiss(player);
            }
            case GIVE -> {
                if (!sender.hasPermission("ollama.llama.give")) {
                    sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                    return true;
                }
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1])
                        : (sender instanceof Player p ? p : null);
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                    return true;
                }
                Map<Integer, ItemStack> leftover = target.getInventory().addItem(item.create(1));
                if (!leftover.isEmpty()) {
                    leftover.values().forEach(drop ->
                            target.getWorld().dropItemNaturally(target.getLocation(), drop));
                }
                sender.sendMessage(Component.text("Gave a companion charm to " + target.getName(), NamedTextColor.GREEN));
            }
            case HELP -> sender.sendMessage(Component.text(
                    "/llama ask <message> | /llama recipe | /llama dismiss | /llama give [player]",
                    NamedTextColor.GOLD));
            case UNKNOWN -> sender.sendMessage(Component.text("Unknown subcommand. Try /llama help", NamedTextColor.RED));
        }
        return true;
    }

    private void dismiss(Player player) {
        UUID id = registry.companionOf(player);
        if (id != null) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof Llama llama) {
                llama.remove();
            }
            registry.unbind(player);
        }
        conversation.forget(player);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item.create(1));
        if (!leftover.isEmpty()) {
            leftover.values().forEach(drop ->
                    player.getWorld().dropItemNaturally(player.getLocation(), drop));
        }
        player.sendMessage(Component.text("Your llama returns to its charm.", NamedTextColor.AQUA));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Stream.of("ask", "recipe", "dismiss", "give")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
