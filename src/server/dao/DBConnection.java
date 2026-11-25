package server.dao;

import java.sql.*;

public class DBConnection {
    // Database connection parameters
    private static final String HOST = System.getenv().getOrDefault("DB_HOST", "localhost");
    private static final String PORT = System.getenv().getOrDefault("DB_PORT", "3306");
    private static final String DATABASE = System.getenv().getOrDefault("DB_NAME", "socialchatapp");

    private static final String URL =
            "jdbc:mysql://" + HOST + ":" + PORT + "/" + DATABASE
          + "?useSSL=false"
          + "&allowPublicKeyRetrieval=true"
          + "&serverTimezone=UTC";

    // MySQL credentials (can also be configured via environment variables DB_USER and DB_PASS)
    private static final String USER = System.getenv().getOrDefault("DB_USER", "root");
    private static final String PASS = System.getenv().getOrDefault("DB_PASS", "root");

    public static Connection get() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL driver not found", e);
        }
        Connection conn = DriverManager.getConnection(URL, USER, PASS);
        conn.setAutoCommit(true);
        return conn;
    }

}
