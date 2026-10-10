package me.pintoadmin.velocityServerDialog;

import com.velocitypowered.api.proxy.*;
import org.slf4j.*;

import java.io.*;
import java.nio.file.*;
import java.rmi.*;
import java.sql.*;
import java.util.*;

public class DBManager {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;

    private Connection connection;

    public DBManager(ProxyServer proxy, Logger logger, Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;

        try {
            Path dbPath = dataDir.resolve("auth.db");
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:"+ dbPath.toAbsolutePath());
            createTable();
        } catch (ClassNotFoundException e) {
            logger.error("SQLite JDBC driver is missing from the plugin jar", e);
        } catch (SQLException e) {
            logger.error("Failed to initialize the SQLite database", e);
        }
    }

    private void createTable() throws SQLException {
        // table of uuid key, server, password
        String sql = "CREATE TABLE IF NOT EXISTS saved ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " uuid TEXT NOT NULL,"
                + " server TEXT NOT NULL,"
                + " password TEXT NOT NULL"
                + ");";

        Statement stmt = connection.createStatement();
        stmt.execute(sql);
    }

    public String getSavedPassword(String playerName, String server) throws SQLException {
        Optional<Player> player = proxy.getPlayer(playerName);
        if(player.isEmpty()) return null; // No such player
        return getSavedPassword(player.get().getUniqueId(), server);
    }
    public String getSavedPassword(UUID uuid, String server) throws SQLException {
        String sql = "SELECT password FROM saved WHERE uuid = ? AND server = ?";

        PreparedStatement ps = connection.prepareStatement(sql);
        ps.setString(1, uuid.toString());
        ps.setString(2, server);

        ResultSet rs = ps.executeQuery();

        while (rs.next()) {
            return rs.getString("password");
        }
        return null; // Not found in DB
    }
    public synchronized void addSavedPassword(UUID uuid, String server, String password) throws SQLException {
        String updateSql = "UPDATE saved SET password = ? WHERE uuid = ? AND server = ?";
        try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
            ps.setString(1, password);
            ps.setString(2, uuid.toString());
            ps.setString(3, server);

            if (ps.executeUpdate() > 0) {
                return;
            }
        }

        String insertSql = "INSERT INTO saved(uuid, server, password) VALUES(?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, server);
            ps.setString(3, password);
            ps.executeUpdate();
        }
    }
}
