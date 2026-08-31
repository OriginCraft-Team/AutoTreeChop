/*
 * Copyright (C) 2026 MilkTeaMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.milkteamc.autotreechop.utils;

import com.cryptomorin.xseries.XMaterial;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.milkteamc.autotreechop.AutoTreeChop;
import org.milkteamc.autotreechop.Config;
import org.milkteamc.autotreechop.MessageKeys;
import org.milkteamc.autotreechop.PlayerConfig;

/**
 * Collects the drops of blocks broken by AutoTreeChop and hands them straight to the player
 * instead of letting them fall on the ground.
 *
 * <p>Drops are gathered with {@link Block#getDrops(ItemStack, org.bukkit.entity.Entity)} so
 * enchantments (Fortune, Silk Touch) and the tool type are respected exactly like a vanilla break.
 *
 * <p>Collection and delivery are deliberately split: {@code collectDrops} is called per block while
 * a batch is running, {@code deliverDrops} once when the batch completes. That keeps inventory
 * mutation out of the per-block loop and means a player sees at most one "inventory full" message
 * per chop instead of one per log.
 */
public final class DropCollectionUtils {

    private static final Map<UUID, Long> lastInventoryFullMessage = new ConcurrentHashMap<>();
    private static final long INVENTORY_FULL_MESSAGE_COOLDOWN_MS = 5000L;

    private DropCollectionUtils() {}

    /**
     * Auto pickup requires the config flag, the permission node and the player's own toggle
     * (persisted per player, flipped with {@code /atc autopickup}), following the same pattern as
     * {@link TreeReplantUtils#isReplantEnabledForPlayer(Player, Config)}.
     */
    public static boolean isAutoPickupEnabledForPlayer(Player player, PlayerConfig playerConfig, Config config) {
        return config.isAutoPickupEnabled()
                && player.hasPermission("autotreechop.autopickup")
                && playerConfig.isAutoPickupEnabled();
    }

    /**
     * Gathers the drops of a block into {@code accumulator}.
     *
     * <p>MUST be called BEFORE the block is removed, otherwise the block is already air and
     * yields nothing. Stacks are merged in place so a 500 log tree produces a handful of full
     * stacks rather than 500 single item stacks.
     */
    public static void collectDrops(Block block, ItemStack tool, Player player, List<ItemStack> accumulator) {
        Collection<ItemStack> drops;

        if (tool == null || XMaterial.matchXMaterial(tool) == XMaterial.AIR) {
            drops = block.getDrops();
        } else {
            drops = block.getDrops(tool, player);
        }

        for (ItemStack drop : drops) {
            if (drop == null || drop.getAmount() <= 0) {
                continue;
            }
            merge(accumulator, drop.clone());
        }
    }

    /**
     * Breaks a block and lets its drops fall on the ground, honouring the tool the player is
     * actually holding.
     *
     * <p>Counterpart to {@link #collectDrops} for players without auto pickup. Both must treat the
     * tool the same way, otherwise the same enchanted axe yields different items depending on a
     * setting that is only supposed to decide where the drops end up, not what they are.
     */
    public static void breakNaturally(Block block, ItemStack tool) {
        if (tool == null || XMaterial.matchXMaterial(tool) == XMaterial.AIR) {
            block.breakNaturally();
        } else {
            block.breakNaturally(tool);
        }
    }

    /**
     * Puts everything collected into the player's inventory. Whatever does not fit is dropped at
     * the player's feet and the player is told once (rate limited, because logs and leaves are
     * delivered in two separate phases).
     *
     * <p>The accumulator is cleared so the same list can be reused across phases.
     */
    public static void deliverDrops(Player player, List<ItemStack> accumulator) {
        if (accumulator.isEmpty()) {
            return;
        }

        World world = player.getWorld();
        Location dropLocation = player.getLocation();

        if (!player.isOnline()) {
            // Player left mid-chop: nothing to add to, so put it all on the ground.
            for (ItemStack stack : accumulator) {
                world.dropItemNaturally(dropLocation, stack);
            }
            accumulator.clear();
            return;
        }

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(accumulator.toArray(new ItemStack[0]));
        accumulator.clear();

        if (leftovers.isEmpty()) {
            return;
        }

        for (ItemStack leftover : leftovers.values()) {
            world.dropItemNaturally(dropLocation, leftover);
        }

        notifyInventoryFull(player);
    }

    private static void merge(List<ItemStack> accumulator, ItemStack drop) {
        int maxStackSize = drop.getMaxStackSize();

        if (maxStackSize <= 0) {
            // Bukkit returns -1 when it cannot determine a stack size; keep the drop as-is.
            accumulator.add(drop);
            return;
        }

        for (ItemStack existing : accumulator) {
            if (existing.getAmount() >= maxStackSize || !existing.isSimilar(drop)) {
                continue;
            }

            int space = maxStackSize - existing.getAmount();
            int moved = Math.min(space, drop.getAmount());
            existing.setAmount(existing.getAmount() + moved);
            drop.setAmount(drop.getAmount() - moved);

            if (drop.getAmount() <= 0) {
                return;
            }
        }

        accumulator.add(drop);
    }

    private static void notifyInventoryFull(Player player) {
        UUID playerUUID = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastInventoryFullMessage.get(playerUUID);

        if (last != null && now - last < INVENTORY_FULL_MESSAGE_COOLDOWN_MS) {
            return;
        }

        lastInventoryFullMessage.put(playerUUID, now);
        AutoTreeChop.sendMessage(player, MessageKeys.INVENTORY_FULL);
    }

    /**
     * Drops the cached "inventory full" timestamp for a player. Called on quit so the map does not
     * grow without bound.
     */
    public static void clearPlayerData(UUID playerUUID) {
        lastInventoryFullMessage.remove(playerUUID);
    }
}
