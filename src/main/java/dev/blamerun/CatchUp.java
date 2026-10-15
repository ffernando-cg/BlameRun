package dev.blamerun;

import static dev.blamerun.Messages.p;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;

/**
 * /catchup: brings a late joiner next to the admin and gives them gear of a comparable tier,
 * so they don't start from scratch in the middle of an attempt.
 */
public final class CatchUp {

    public enum Mode {
        ADMIN,
        AVERAGE;

        public static Mode parse(String raw, Mode fallback) {
            if (raw == null) {
                return fallback;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /** Each gear piece and its material ladder, worst to best. */
    private enum Piece {
        HELMET(EquipmentSlot.HEAD, true),
        CHESTPLATE(EquipmentSlot.CHEST, true),
        LEGGINGS(EquipmentSlot.LEGS, true),
        BOOTS(EquipmentSlot.FEET, true),
        SWORD(null, false),
        PICKAXE(null, false),
        AXE(null, false),
        SHOVEL(null, false);

        private static final String[] ARMOR = {"LEATHER", "GOLDEN", "COPPER", "CHAINMAIL", "IRON", "DIAMOND", "NETHERITE"};
        private static final String[] TOOLS = {"WOODEN", "GOLDEN", "STONE", "COPPER", "IRON", "DIAMOND", "NETHERITE"};

        final EquipmentSlot slot;
        final boolean armor;

        Piece(EquipmentSlot slot, boolean armor) {
            this.slot = slot;
            this.armor = armor;
        }

        String[] tiers() {
            return armor ? ARMOR : TOOLS;
        }

        /** 0 = not this piece; 1..n = material tier. */
        int tierOf(Material m) {
            String name = m.name();
            String suffix = "_" + name();
            if (!name.endsWith(suffix)) {
                return 0;
            }
            String prefix = name.substring(0, name.length() - suffix.length());
            String[] tiers = tiers();
            for (int i = 0; i < tiers.length; i++) {
                if (tiers[i].equals(prefix)) {
                    return i + 1;
                }
            }
            return 0;
        }

        /** Material for a tier; if it doesn't exist in this version, steps down until one does. */
        Material material(int tier) {
            for (int t = Math.min(tier, tiers().length); t >= 1; t--) {
                Material m = Material.matchMaterial(tiers()[t - 1] + "_" + name());
                if (m != null) {
                    return m;
                }
            }
            return null;
        }
    }

    /** Reference loadout: tier per piece, plus food and experience. */
    private record Reference(Map<Piece, Integer> tiers, Map<Piece, ItemStack> exactItems,
                             List<ItemStack> food, int foodCount, int level) {
    }

    private final BlameRun plugin;

    public CatchUp(BlameRun plugin) {
        this.plugin = plugin;
    }

    public Mode defaultMode() {
        return Mode.parse(plugin.getConfig().getString("catchup.default-mode"), Mode.ADMIN);
    }

    public void apply(Player admin, Player target, Mode mode) {
        Messages msg = plugin.messages();
        if (admin.equals(target)) {
            admin.sendMessage(msg.prefixed("catchup.self"));
            return;
        }

        Reference ref;
        if (mode == Mode.ADMIN) {
            ref = fromPlayer(admin);
        } else {
            List<Player> others = new ArrayList<>();
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (!pl.equals(target) && pl.getGameMode() == GameMode.SURVIVAL) {
                    others.add(pl);
                }
            }
            if (others.isEmpty()) {
                others.add(admin);
            }
            ref = average(others);
        }

        target.teleport(admin.getLocation());
        boolean revived = false;
        if (target.getGameMode() != GameMode.SURVIVAL) {
            target.setGameMode(GameMode.SURVIVAL);
            revived = true;
        }
        AttributeInstance maxHealth = target.getAttribute(Attribute.MAX_HEALTH);
        target.setHealth(maxHealth != null ? maxHealth.getValue() : 20.0);
        target.setFoodLevel(20);
        target.setSaturation(10f);
        target.setFireTicks(0);

        List<String> given = new ArrayList<>();
        PlayerInventory inv = target.getInventory();
        for (Piece piece : Piece.values()) {
            ItemStack item = itemFor(piece, ref);
            if (item == null) {
                continue;
            }
            if (piece.armor) {
                // Equipped directly; anything equal or better they already wear is kept.
                ItemStack current = inv.getItem(piece.slot);
                if (piece.tierOf(current.getType()) >= piece.tierOf(item.getType())) {
                    continue;
                }
                if (!current.getType().isAir()) {
                    give(target, current);
                }
                inv.setItem(piece.slot, item);
                given.add(pretty(item.getType()));
                continue;
            }
            if (bestTierIn(inv, piece) >= piece.tierOf(item.getType())) {
                continue;
            }
            give(target, item);
            given.add(pretty(item.getType()));
        }

        int foodGiven = 0;
        for (ItemStack food : ref.food()) {
            give(target, food.clone());
            foodGiven += food.getAmount();
        }
        int minimum = plugin.getConfig().getInt("catchup.min-food", 16);
        if (foodGiven < minimum) {
            give(target, new ItemStack(Material.COOKED_BEEF, minimum - foodGiven));
            foodGiven = minimum;
        }

        for (String raw : plugin.getConfig().getStringList("catchup.extras")) {
            ItemStack extra = parseExtra(raw);
            if (extra != null) {
                give(target, extra);
            }
        }

        if (plugin.getConfig().getBoolean("catchup.give-experience", true) && target.getLevel() < ref.level()) {
            target.setLevel(ref.level());
        }

        plugin.runs().addParticipant(target.getUniqueId());
        plugin.deaths().touch(target);

        String gear = given.isEmpty() ? msg.raw("catchup.nothing-new") : String.join(", ", given);
        String modeLabel = mode == Mode.ADMIN
                ? msg.raw("catchup.mode-admin").replace("<admin>", admin.getName())
                : msg.raw("catchup.mode-average");
        Component done = msg.prefixed("catchup.done", p("player", target.getName()), p("mode", modeLabel),
                p("gear", gear), p("food", foodGiven), p("level", target.getLevel()));
        admin.sendMessage(revived ? done.append(msg.get("catchup.revived")) : done);
        target.sendMessage(msg.prefixed("catchup.welcome", p("admin", admin.getName())));
        Bukkit.getServer().sendMessage(msg.prefixed("catchup.announce",
                p("player", target.getName()), p("attempt", plugin.runs().attempt())));
        RunManager.playAll("item.armor.equip_netherite", 1f);
    }

    // ------------------------------------------------------------------ reference

    private Reference fromPlayer(Player player) {
        Map<Piece, Integer> tiers = new EnumMap<>(Piece.class);
        Map<Piece, ItemStack> exact = new EnumMap<>(Piece.class);
        List<ItemStack> food = new ArrayList<>();
        int foodCount = 0;
        boolean copyEnchants = plugin.getConfig().getBoolean("catchup.copy-enchantments", false);

        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (item.getType().isEdible()) {
                food.add(item.clone());
                foodCount += item.getAmount();
                continue;
            }
            for (Piece piece : Piece.values()) {
                int tier = piece.tierOf(item.getType());
                if (tier > tiers.getOrDefault(piece, 0)) {
                    tiers.put(piece, tier);
                    if (copyEnchants) {
                        exact.put(piece, repaired(item));
                    }
                }
            }
        }
        // Cap food at two stacks so it isn't an excessive gift.
        List<ItemStack> capped = new ArrayList<>();
        int budget = 128;
        for (ItemStack f : food) {
            if (budget <= 0) {
                break;
            }
            int amount = Math.min(f.getAmount(), budget);
            ItemStack copy = f.clone();
            copy.setAmount(amount);
            capped.add(copy);
            budget -= amount;
        }
        return new Reference(tiers, exact, capped, Math.min(foodCount, 128), player.getLevel());
    }

    private Reference average(List<Player> players) {
        Map<Piece, Integer> sums = new EnumMap<>(Piece.class);
        int foodSum = 0;
        int levelSum = 0;
        for (Player pl : players) {
            Reference r = fromPlayer(pl);
            for (Piece piece : Piece.values()) {
                sums.merge(piece, r.tiers().getOrDefault(piece, 0), Integer::sum);
            }
            foodSum += r.foodCount();
            levelSum += r.level();
        }
        int n = players.size();
        Map<Piece, Integer> avg = new EnumMap<>(Piece.class);
        sums.forEach((piece, sum) -> avg.put(piece, Math.round(sum / (float) n)));
        int foodAvg = Math.round(foodSum / (float) n);
        List<ItemStack> food = new ArrayList<>();
        if (foodAvg > 0) {
            food.add(new ItemStack(Material.COOKED_BEEF, Math.min(foodAvg, 64)));
            if (foodAvg > 64) {
                food.add(new ItemStack(Material.COOKED_BEEF, Math.min(foodAvg - 64, 64)));
            }
        }
        return new Reference(avg, new HashMap<>(), food, foodAvg, Math.round(levelSum / (float) n));
    }

    // ------------------------------------------------------------------ helpers

    private static ItemStack itemFor(Piece piece, Reference ref) {
        ItemStack exact = ref.exactItems().get(piece);
        if (exact != null) {
            return exact.clone();
        }
        int tier = ref.tiers().getOrDefault(piece, 0);
        if (tier <= 0) {
            return null;
        }
        Material m = piece.material(tier);
        return m == null ? null : new ItemStack(m);
    }

    private static ItemStack repaired(ItemStack item) {
        ItemStack copy = item.clone();
        copy.setAmount(1);
        if (copy.getItemMeta() instanceof Damageable damageable) {
            damageable.setDamage(0);
            copy.setItemMeta(damageable);
        }
        return copy;
    }

    private static int bestTierIn(PlayerInventory inv, Piece piece) {
        int best = 0;
        for (ItemStack item : inv.getContents()) {
            if (item != null) {
                best = Math.max(best, piece.tierOf(item.getType()));
            }
        }
        return best;
    }

    /** Adds to the inventory; whatever doesn't fit drops at the player's feet. */
    private static void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    private ItemStack parseExtra(String raw) {
        String[] parts = raw.split(":");
        Material m = Material.matchMaterial(parts[0].trim());
        if (m == null || !m.isItem()) {
            plugin.getLogger().warning("catchup.extras: unknown material '" + raw + "'");
            return null;
        }
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignored) {
                // keep 1
            }
        }
        return new ItemStack(m, Math.max(1, Math.min(amount, m.getMaxStackSize())));
    }

    private static String pretty(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
