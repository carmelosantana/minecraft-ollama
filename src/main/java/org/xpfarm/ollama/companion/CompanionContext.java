package org.xpfarm.ollama.companion;

/** Renders an {@link InventorySnapshot} into a compact context block for the companion's system prompt. */
public final class CompanionContext {

    private CompanionContext() {}

    public static String describe(InventorySnapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append("Player state (this player only):\n");
        sb.append("- Health: ").append(s.health()).append("/20, Hunger: ").append(s.hunger()).append("/20\n");
        sb.append("- Dimension: ").append(s.dimension()).append(", Biome: ").append(s.biome())
                .append(", Time: ").append(s.timeOfDay()).append("\n");
        sb.append("- Main hand: ").append(s.mainHand()).append(", Off hand: ").append(s.offHand()).append("\n");
        sb.append("- Hotbar: ").append(String.join(", ", s.hotbar())).append("\n");
        sb.append("- Armor: ").append(s.armor()).append("\n");
        if (!s.storage().isEmpty()) {
            sb.append("- Inventory: ").append(String.join(", ", s.storage())).append("\n");
        }
        sb.append("- Carries shield: ").append(s.carriesShield())
                .append(" (equipped: ").append(s.shieldEquipped()).append(")\n");
        sb.append("- Has food: ").append(s.hasFood())
                .append(", Has torches: ").append(s.hasTorches())
                .append(", Lowest tool durability: ").append(s.lowestToolDurabilityPct()).append("%\n");
        return sb.toString();
    }
}
