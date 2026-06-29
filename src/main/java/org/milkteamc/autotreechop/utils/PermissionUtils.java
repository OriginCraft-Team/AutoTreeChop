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

import org.bukkit.entity.Player;
import org.milkteamc.autotreechop.Config;
import org.milkteamc.autotreechop.PlayerConfig;

public class PermissionUtils {

    private PermissionUtils() {}

    /** Whether the player can still use AutoTreeChop at least once more today. */
    public static boolean canUseMore(Player player, PlayerConfig playerConfig, Config config) {
        int limit = config.resolveLimits(player).usesPerDay();
        return limit < 0 || playerConfig.getDailyUses() < limit;
    }

    /** Whether the player can still break at least one more block today. */
    public static boolean canBreakMoreBlocks(Player player, PlayerConfig playerConfig, Config config) {
        int limit = config.resolveLimits(player).blocksPerDay();
        return limit < 0 || playerConfig.getDailyBlocksBroken() < limit;
    }

    /** Whether breaking {@code count} more blocks would stay within the player's daily limit. */
    public static boolean canBreakBlocks(Player player, PlayerConfig playerConfig, Config config, int count) {
        int limit = config.resolveLimits(player).blocksPerDay();
        return limit < 0 || playerConfig.getDailyBlocksBroken() + count <= limit;
    }
}
