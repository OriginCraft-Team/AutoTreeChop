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
 
package org.milkteamc.autotreechop.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.Plugin;

public class DatabaseManager {

    private final Plugin plugin;
    private final HikariDataSource dataSource;

    public DatabaseManager(
            Plugin plugin,
            boolean useMysql,
            String hostname,
            int port,
            String database,
            String username,
            String password) {
        this.plugin = plugin;
        this.dataSource = initializeDataSource(useMysql, hostname, port, database, username, password);
        createTable();
    }

    private HikariDataSource initializeDataSource(
            boolean useMysql, String hostname, int port, String database, String username, String password) {
        HikariConfig config = new HikariConfig();

        if (useMysql) {
            config.setJdbcUrl("jdbc:mysql://" + hostname + ":" + port + "/" + database);
            config.setUsername(username);
            config.setPassword(password);
        } else {
            config.setJdbcUrl("jdbc:sqlite:plugins/AutoTreeChop/player_data.db");
        }

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        return new HikariDataSource(config);
    }

    private void createTable() {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "CREATE TABLE IF NOT EXISTS player_data (" + "uuid VARCHAR(36) PRIMARY KEY,"
                                + "autoTreeChopEnabled BOOLEAN,"
                                + "autoPickupEnabled BOOLEAN,"
                                + "dailyUses INT,"
                                + "dailyBlocksBroken INT,"
                                + "lastUseDate VARCHAR(10))")) {
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Error creating database table: " + e.getMessage());
        }

        addMissingColumns();
    }

    /**
     * Adds columns introduced after the first release to tables created by an older version.
     *
     * <p>{@code CREATE TABLE IF NOT EXISTS} leaves an existing table untouched, so a server that
     * has been running since before auto pickup became a per-player setting would keep a table
     * without the {@code autoPickupEnabled} column and every read/write of it would fail. The
     * column list is read from JDBC metadata, which both SQLite and MySQL support, instead of
     * relying on dialect-specific "ADD COLUMN IF NOT EXISTS" syntax.
     */
    private void addMissingColumns() {
        try (Connection conn = dataSource.getConnection()) {
            if (hasColumn(conn, "autoPickupEnabled")) {
                return;
            }

            try (PreparedStatement stmt =
                    conn.prepareStatement("ALTER TABLE player_data ADD COLUMN autoPickupEnabled BOOLEAN")) {
                stmt.executeUpdate();
            }

            // Existing players keep auto pickup on; the global config flag and the
            // autotreechop.autopickup permission still decide whether it does anything.
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE player_data SET autoPickupEnabled = ? WHERE autoPickupEnabled IS NULL")) {
                stmt.setBoolean(1, true);
                stmt.executeUpdate();
            }

            plugin.getLogger().info("Added autoPickupEnabled column to player_data.");
        } catch (SQLException e) {
            plugin.getLogger().warning("Error migrating database table: " + e.getMessage());
        }
    }

    private boolean hasColumn(Connection conn, String columnName) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, "player_data", null)) {
            while (rs.next()) {
                if (columnName.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    public CompletableFuture<PlayerData> loadPlayerDataAsync(
            UUID playerUUID, boolean defaultTreeChop, boolean defaultAutoPickup) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement stmt = conn.prepareStatement("SELECT * FROM player_data WHERE uuid = ?")) {

                stmt.setString(1, playerUUID.toString());
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    boolean autoPickupEnabled = rs.getBoolean("autoPickupEnabled");
                    if (rs.wasNull()) {
                        autoPickupEnabled = defaultAutoPickup;
                    }

                    return new PlayerData(
                            playerUUID,
                            rs.getBoolean("autoTreeChopEnabled"),
                            autoPickupEnabled,
                            rs.getInt("dailyUses"),
                            rs.getInt("dailyBlocksBroken"),
                            LocalDate.parse(rs.getString("lastUseDate")));
                } else {
                    PlayerData data =
                            new PlayerData(playerUUID, defaultTreeChop, defaultAutoPickup, 0, 0, LocalDate.now());
                    insertPlayerData(data);
                    return data;
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Error loading player data: " + e.getMessage());
                return new PlayerData(playerUUID, defaultTreeChop, defaultAutoPickup, 0, 0, LocalDate.now());
            }
        });
    }

    public void savePlayerDataSync(PlayerData data) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE player_data SET autoTreeChopEnabled = ?, autoPickupEnabled = ?, dailyUses = ?, "
                                + "dailyBlocksBroken = ?, lastUseDate = ? WHERE uuid = ?")) {

            stmt.setBoolean(1, data.isAutoTreeChopEnabled());
            stmt.setBoolean(2, data.isAutoPickupEnabled());
            stmt.setInt(3, data.getDailyUses());
            stmt.setInt(4, data.getDailyBlocksBroken());
            stmt.setString(5, data.getLastUseDate().toString());
            stmt.setString(6, data.getPlayerUUID().toString());

            int rows = stmt.executeUpdate();
            if (rows == 0) {
                insertPlayerData(data);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error saving player data: " + e.getMessage());
        }
    }

    public CompletableFuture<Void> savePlayerDataBatchAsync(Map<UUID, PlayerData> dataMap) {
        return CompletableFuture.runAsync(() -> {
            if (dataMap.isEmpty()) return;

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);

                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE player_data SET autoTreeChopEnabled = ?, autoPickupEnabled = ?, dailyUses = ?, "
                                + "dailyBlocksBroken = ?, lastUseDate = ? WHERE uuid = ?")) {

                    for (PlayerData data : dataMap.values()) {
                        stmt.setBoolean(1, data.isAutoTreeChopEnabled());
                        stmt.setBoolean(2, data.isAutoPickupEnabled());
                        stmt.setInt(3, data.getDailyUses());
                        stmt.setInt(4, data.getDailyBlocksBroken());
                        stmt.setString(5, data.getLastUseDate().toString());
                        stmt.setString(6, data.getPlayerUUID().toString());
                        stmt.addBatch();
                    }

                    stmt.executeBatch();
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Error batch saving player data: " + e.getMessage());
            }
        });
    }

    private void insertPlayerData(PlayerData data) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO player_data (uuid, autoTreeChopEnabled, autoPickupEnabled, dailyUses, "
                                + "dailyBlocksBroken, lastUseDate) VALUES (?, ?, ?, ?, ?, ?)")) {

            stmt.setString(1, data.getPlayerUUID().toString());
            stmt.setBoolean(2, data.isAutoTreeChopEnabled());
            stmt.setBoolean(3, data.isAutoPickupEnabled());
            stmt.setInt(4, data.getDailyUses());
            stmt.setInt(5, data.getDailyBlocksBroken());
            stmt.setString(6, data.getLastUseDate().toString());
            stmt.executeUpdate();
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public static class PlayerData {
        private final UUID playerUUID;
        private boolean autoTreeChopEnabled;
        private boolean autoPickupEnabled;
        private int dailyUses;
        private int dailyBlocksBroken;
        private LocalDate lastUseDate;

        public PlayerData(
                UUID playerUUID,
                boolean autoTreeChopEnabled,
                boolean autoPickupEnabled,
                int dailyUses,
                int dailyBlocksBroken,
                LocalDate lastUseDate) {
            this.playerUUID = playerUUID;
            this.autoTreeChopEnabled = autoTreeChopEnabled;
            this.autoPickupEnabled = autoPickupEnabled;
            this.dailyUses = dailyUses;
            this.dailyBlocksBroken = dailyBlocksBroken;
            this.lastUseDate = lastUseDate;
        }

        public UUID getPlayerUUID() {
            return playerUUID;
        }

        public boolean isAutoTreeChopEnabled() {
            return autoTreeChopEnabled;
        }

        public void setAutoTreeChopEnabled(boolean enabled) {
            this.autoTreeChopEnabled = enabled;
        }

        public boolean isAutoPickupEnabled() {
            return autoPickupEnabled;
        }

        public void setAutoPickupEnabled(boolean enabled) {
            this.autoPickupEnabled = enabled;
        }

        public int getDailyUses() {
            return dailyUses;
        }

        public void setDailyUses(int uses) {
            this.dailyUses = uses;
        }

        public void incrementDailyUses() {
            this.dailyUses++;
        }

        public int getDailyBlocksBroken() {
            return dailyBlocksBroken;
        }

        public void setDailyBlocksBroken(int blocks) {
            this.dailyBlocksBroken = blocks;
        }

        public void incrementDailyBlocksBroken() {
            this.dailyBlocksBroken++;
        }

        public LocalDate getLastUseDate() {
            return lastUseDate;
        }

        public void setLastUseDate(LocalDate date) {
            this.lastUseDate = date;
        }
    }
}
