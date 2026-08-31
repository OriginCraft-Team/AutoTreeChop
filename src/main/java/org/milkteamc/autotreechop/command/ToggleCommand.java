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
 
package org.milkteamc.autotreechop.command;

import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.milkteamc.autotreechop.AutoTreeChop;
import org.milkteamc.autotreechop.MessageKeys;
import org.milkteamc.autotreechop.PlayerConfig;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;
import revxrsal.commands.bukkit.parameters.EntitySelector;

@Command({"atc", "autotreechop"})
public class ToggleCommand {

    private final AutoTreeChop plugin;

    public ToggleCommand(AutoTreeChop plugin) {
        this.plugin = plugin;
    }

    @CommandPermission("autotreechop.use")
    public void root(BukkitCommandActor actor) {
        performSelfToggle(actor);
    }

    @Subcommand("toggle")
    @CommandPermission("autotreechop.use")
    public void toggle(BukkitCommandActor actor, @Optional Player targetPlayer) {
        if (targetPlayer == null) {
            performSelfToggle(actor);
            return;
        }

        if (!actor.sender().hasPermission("autotreechop.other")) {
            AutoTreeChop.sendMessage(actor.sender(), MessageKeys.NO_PERMISSION);
            return;
        }

        UUID targetUUID = targetPlayer.getUniqueId();
        PlayerConfig playerConfig = plugin.getPlayerConfig(targetUUID);
        boolean autoTreeChopEnabled = !playerConfig.isAutoTreeChopEnabled();
        playerConfig.setAutoTreeChopEnabled(autoTreeChopEnabled);

        if (autoTreeChopEnabled) {
            AutoTreeChop.sendMessage(
                    targetPlayer,
                    MessageKeys.ENABLED_BY_OTHER,
                    Placeholder.parsed("player", actor.sender().getName()));
            AutoTreeChop.sendMessage(
                    actor.sender(),
                    MessageKeys.ENABLED_FOR_OTHER,
                    Placeholder.parsed("player", targetPlayer.getName()));
        } else {
            plugin.getConfirmationManager().clearPlayer(targetUUID);
            AutoTreeChop.sendMessage(
                    targetPlayer,
                    MessageKeys.DISABLED_BY_OTHER,
                    Placeholder.parsed("player", actor.sender().getName()));
            AutoTreeChop.sendMessage(
                    actor.sender(),
                    MessageKeys.DISABLED_FOR_OTHER,
                    Placeholder.parsed("player", targetPlayer.getName()));
        }
    }

    // enable — self (no args)
    @Subcommand("enable")
    @CommandPermission("autotreechop.use")
    public void enable(BukkitCommandActor actor) {
        if (!plugin.getPluginConfig().getCommandToggle()) {
            AutoTreeChop.sendMessage(actor.sender(), MessageKeys.NO_PERMISSION);
            return;
        }
        if (!(actor.sender() instanceof Player player)) {
            AutoTreeChop.sendMessage(actor.sender(), MessageKeys.ONLY_PLAYERS);
            return;
        }
        PlayerConfig playerConfig = plugin.getPlayerConfig(player.getUniqueId());
        if (playerConfig.isAutoTreeChopEnabled()) {
            AutoTreeChop.sendMessage(player, MessageKeys.ALREADY_ENABLED);
            return;
        }
        playerConfig.setAutoTreeChopEnabled(true);
        AutoTreeChop.sendMessage(player, MessageKeys.ENABLED);
    }

    // enable — targets (requires .other)
    @Subcommand("enable")
    @CommandPermission("autotreechop.other")
    public void enable(BukkitCommandActor actor, EntitySelector<Player> targetPlayers) {
        int count = 0;
        String lastName = null;
        for (Player targetPlayer : targetPlayers) {
            PlayerConfig cfg = plugin.getPlayerConfig(targetPlayer.getUniqueId());
            if (cfg.isAutoTreeChopEnabled()) continue; // skip already-enabled silently, or send per-player msg
            cfg.setAutoTreeChopEnabled(true);
            lastName = targetPlayer.getName();
            count++;
            AutoTreeChop.sendMessage(
                    targetPlayer,
                    MessageKeys.ENABLED_BY_OTHER,
                    Placeholder.parsed("player", actor.sender().getName()));
        }
        if (count == 1 && lastName != null) {
            AutoTreeChop.sendMessage(
                    actor.sender(), MessageKeys.ENABLED_FOR_OTHER, Placeholder.parsed("player", lastName));
        } else if (count > 1) {
            AutoTreeChop.sendMessage(
                    actor.sender(), MessageKeys.ENABLED_FOR_OTHER, Placeholder.parsed("player", "everyone"));
        }
    }

    // disable — self
    @Subcommand("disable")
    @CommandPermission("autotreechop.use")
    public void disable(BukkitCommandActor actor) {
        if (!plugin.getPluginConfig().getCommandToggle()) {
            AutoTreeChop.sendMessage(actor.sender(), MessageKeys.NO_PERMISSION);
            return;
        }
        if (!(actor.sender() instanceof Player player)) {
            AutoTreeChop.sendMessage(actor.sender(), MessageKeys.ONLY_PLAYERS);
            return;
        }
        UUID playerUUID = player.getUniqueId();
        PlayerConfig playerConfig = plugin.getPlayerConfig(playerUUID);
        if (!playerConfig.isAutoTreeChopEnabled()) {
            AutoTreeChop.sendMessage(player, MessageKeys.ALREADY_DISABLED);
            return;
        }
        playerConfig.setAutoTreeChopEnabled(false);
        plugin.getConfirmationManager().clearPlayer(playerUUID);
        AutoTreeChop.sendMessage(player, MessageKeys.DISABLED);
    }

    // disable — targets
    @Subcommand("disable")
    @CommandPermission("autotreechop.other")
    public void disable(BukkitCommandActor actor, EntitySelector<Player> targetPlayers) {
        int count = 0;
        String lastName = null;
        for (Player targetPlayer : targetPlayers) {
            UUID targetUUID = targetPlayer.getUniqueId();
            PlayerConfig cfg = plugin.getPlayerConfig(targetUUID);
            if (!cfg.isAutoTreeChopEnabled()) continue;
            cfg.setAutoTreeChopEnabled(false);
            plugin.getConfirmationManager().clearPlayer(targetUUID);
            lastName = targetPlayer.getName();
            count++;
            AutoTreeChop.sendMessage(
                    targetPlayer,
                    MessageKeys.DISABLED_BY_OTHER,
                    Placeholder.parsed("player", actor.sender().getName()));
        }
        if (count == 1 && lastName != null) {
            AutoTreeChop.sendMessage(
                    actor.sender(), MessageKeys.DISABLED_FOR_OTHER, Placeholder.parsed("player", lastName));
        } else if (count > 1) {
            AutoTreeChop.sendMessage(
                    actor.sender(), MessageKeys.DISABLED_FOR_OTHER, Placeholder.parsed("player", "everyone"));
        }
    }

    /**
     * Flips the player's own auto pickup preference. The preference is stored per player and
     * persists across sessions, exactly like the AutoTreeChop toggle above.
     *
     * <p>Gated on {@code autotreechop.use} rather than {@code autotreechop.autopickup} on purpose:
     * a player without the auto pickup permission should be told they may not use the feature,
     * not have the subcommand disappear from tab completion and answer "unknown command".
     * The real permission is checked in the body so the refusal is an explicit message.
     *
     * <p>Toggling is also refused when auto pickup cannot take effect anyway — the server
     * disabled it in config.yml — so a player never ends up with a stored "on" that silently
     * does nothing.
     */
    @Subcommand({"autopickup", "pickup"})
    @CommandPermission("autotreechop.use")
    public void autoPickup(BukkitCommandActor actor) {
        if (!(actor.sender() instanceof Player player)) {
            AutoTreeChop.sendMessage(actor.sender(), MessageKeys.ONLY_PLAYERS);
            return;
        }

        if (!player.hasPermission("autotreechop.autopickup")) {
            AutoTreeChop.sendMessage(player, MessageKeys.NO_PERMISSION);
            return;
        }

        if (!plugin.getPluginConfig().isAutoPickupEnabled()) {
            AutoTreeChop.sendMessage(player, MessageKeys.AUTO_PICKUP_UNAVAILABLE);
            return;
        }

        PlayerConfig playerConfig = plugin.getPlayerConfig(player.getUniqueId());
        boolean enabled = !playerConfig.isAutoPickupEnabled();
        playerConfig.setAutoPickupEnabled(enabled);

        AutoTreeChop.sendMessage(player, enabled ? MessageKeys.AUTO_PICKUP_ENABLED : MessageKeys.AUTO_PICKUP_DISABLED);
    }

    private void performSelfToggle(BukkitCommandActor actor) {
        if (!(actor.sender() instanceof Player player)) {
            AutoTreeChop.sendMessage(actor.sender(), MessageKeys.ONLY_PLAYERS);
            return;
        }

        if (!plugin.getPluginConfig().getCommandToggle()) {
            AutoTreeChop.sendMessage(actor.sender(), MessageKeys.NO_PERMISSION);
            return;
        }

        UUID playerUUID = player.getUniqueId();
        PlayerConfig playerConfig = plugin.getPlayerConfig(playerUUID);
        boolean autoTreeChopEnabled = !playerConfig.isAutoTreeChopEnabled();
        playerConfig.setAutoTreeChopEnabled(autoTreeChopEnabled);

        if (autoTreeChopEnabled) {
            AutoTreeChop.sendMessage(player, MessageKeys.ENABLED);
        } else {
            plugin.getConfirmationManager().clearPlayer(playerUUID);
            AutoTreeChop.sendMessage(player, MessageKeys.DISABLED);
        }
    }
}
