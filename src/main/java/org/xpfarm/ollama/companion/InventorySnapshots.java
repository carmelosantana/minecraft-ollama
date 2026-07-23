package org.xpfarm.ollama.companion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** The single place that reads a live {@link Player} into an {@link InventorySnapshot}. Main thread only. */
public final class InventorySnapshots {

    private InventorySnapshots() {}

    public static InventorySnapshot of(Player player) {
        PlayerInventory inv = player.getInventory();

        List<String> hotbar = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            hotbar.add(nameOf(inv.getItem(i)));
        }
        List<String> storage = new ArrayList<>();
        for (int i = 9; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && !s.getType().isAir()) {
                storage.add(s.getType().name());
            }
        }
        Map<String, String> armor = new LinkedHashMap<>();
        armor.put("head", nameOf(inv.getHelmet()));
        armor.put("chest", nameOf(inv.getChestplate()));
        armor.put("legs", nameOf(inv.getLeggings()));
        armor.put("feet", nameOf(inv.getBoots()));

        boolean carriesShield = inv.contains(Material.SHIELD)
                || inv.getItemInOffHand().getType() == Material.SHIELD;
        boolean shieldEquipped = inv.getItemInOffHand().getType() == Material.SHIELD;

        boolean hasFood = false;
        boolean hasTorches = false;
        int lowestToolPct = 100;
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) {
                continue;
            }
            if (s.getType().isEdible()) {
                hasFood = true;
            }
            if (s.getType() == Material.TORCH || s.getType() == Material.SOUL_TORCH) {
                hasTorches = true;
            }
            lowestToolPct = Math.min(lowestToolPct, durabilityPct(s));
        }

        return new InventorySnapshot(hotbar, storage, armor,
                nameOf(inv.getItemInMainHand()), nameOf(inv.getItemInOffHand()),
                carriesShield, shieldEquipped, hasFood, lowestToolPct, hasTorches,
                (int) Math.round(player.getHealth()), player.getFoodLevel(),
                player.getWorld().getBlockAt(player.getLocation()).getBiome().toString(),
                player.getWorld().getName(), player.getWorld().getTime());
    }

    private static String nameOf(ItemStack stack) {
        return stack == null || stack.getType().isAir() ? "AIR" : stack.getType().name();
    }

    /** Durability as a percentage; 100 for items that do not take damage. */
    private static int durabilityPct(ItemStack stack) {
        if (!(stack.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg) || !dmg.hasDamage()) {
            return 100;
        }
        short max = stack.getType().getMaxDurability();
        if (max <= 0) {
            return 100;
        }
        return (int) Math.round(100.0 * (max - dmg.getDamage()) / max);
    }
}
