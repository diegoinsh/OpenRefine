package com.google.refine.extension.records.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.extension.records.db.model.SchemaProfile;

/**
 * 连接池管理器
 * 
 * 使用 HikariCP 管理数据库连接池
 * 支持多个数据库的连接池管理
 */
public class ConnectionPoolManager {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionPoolManager.class);
    
    private static final int DEFAULT_POOL_SIZE = 10;
    private static final int DEFAULT_MAX_POOL_SIZE = 20;
    private static final long DEFAULT_CONNECTION_TIMEOUT = 30000; // 30 秒
    private static final long DEFAULT_IDLE_TIMEOUT = 600000; // 10 分钟
    private static final long DEFAULT_MAX_LIFETIME = 1800000; // 30 分钟
    
    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();
    private final int poolSize;
    private final int maxPoolSize;
    private final long connectionTimeout;
    private final long idleTimeout;
    private final long maxLifetime;
    
    /**
     * 构造函数 (使用默认配置)
     */
    public ConnectionPoolManager() {
        this(DEFAULT_POOL_SIZE, DEFAULT_MAX_POOL_SIZE, DEFAULT_CONNECTION_TIMEOUT, 
             DEFAULT_IDLE_TIMEOUT, DEFAULT_MAX_LIFETIME);
    }
    
    /**
     * 构造函数
     */
    public ConnectionPoolManager(int poolSize, int maxPoolSize, long connectionTimeout,
                                 long idleTimeout, long maxLifetime) {
        this.poolSize = poolSize;
        this.maxPoolSize = maxPoolSize;
        this.connectionTimeout = connectionTimeout;
        this.idleTimeout = idleTimeout;
        this.maxLifetime = maxLifetime;
    }
    
    /**
     * 生成数据源键
     */
    private String generateKey(SchemaProfile profile) {
        return String.format("%s://%s:%d/%s", 
            profile.getDialect(), 
            profile.getHost(), 
            profile.getPort(), 
            profile.getDatabase());
    }
    
    /**
     * 获取连接
     * 
     * @param profile Schema 配置
     * @return 数据库连接
     * @throws SQLException 如果获取连接失败
     */
    public Connection getConnection(SchemaProfile profile) throws SQLException {
        String key = generateKey(profile);
        
        HikariDataSource dataSource = dataSources.computeIfAbsent(key, k -> {
            logger.info("Creating connection pool for {}", k);
            return createDataSource(profile);
        });
        
        try {
            Connection conn = dataSource.getConnection();
            logger.debug("Got connection from pool for {}", key);
            return conn;
        } catch (SQLException e) {
            logger.error("Failed to get connection from pool for {}", key, e);
            throw e;
        }
    }
    
    /**
     * 创建数据源
     */
    private HikariDataSource createDataSource(SchemaProfile profile) {
        HikariConfig config = new HikariConfig();
        
        // 设置 JDBC URL
        String url = buildConnectionUrl(profile);
        config.setJdbcUrl(url);
        
        // 设置用户名和密码
        config.setUsername(profile.getUsername());
        config.setPassword(profile.getPassword());
        
        // 设置连接池参数
        config.setMinimumIdle(poolSize);
        config.setMaximumPoolSize(maxPoolSize);
        config.setConnectionTimeout(connectionTimeout);
        config.setIdleTimeout(idleTimeout);
        config.setMaxLifetime(maxLifetime);
        
        // 设置连接测试
        config.setConnectionTestQuery("SELECT 1");
        
        // 设置自动提交
        config.setAutoCommit(true);
        
        // 设置数据源名称
        config.setPoolName("RecordsDB-" + profile.getDatabase());
        
        // 设置驱动类
        String driverClass = getDriverClass(profile.getDialect());
        if (driverClass != null) {
            config.setDriverClassName(driverClass);
        }
        
        logger.info("Creating HikariCP data source with config: poolSize={}, maxPoolSize={}", 
            poolSize, maxPoolSize);
        
        return new HikariDataSource(config);
    }
    
    /**
     * 构建连接 URL
     */
    private String buildConnectionUrl(SchemaProfile profile) {
        String dialect = profile.getDialect().toLowerCase();
        String host = profile.getHost();
        int port = profile.getPort();
        String database = profile.getDatabase();
        
        switch (dialect) {
            case "mysql":
                return String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
                    host, port, database);
            case "postgresql":
                return String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
            case "mariadb":
                return String.format("jdbc:mariadb://%s:%d/%s", host, port, database);
            case "sqlite":
                return String.format("jdbc:sqlite:%s", database);
            default:
                throw new IllegalArgumentException("Unsupported dialect: " + dialect);
        }
    }
    
    /**
     * 获取驱动类名
     */
    private String getDriverClass(String dialect) {
        String lowerDialect = dialect.toLowerCase();
        
        switch (lowerDialect) {
            case "mysql":
                return "com.mysql.cj.jdbc.Driver";
            case "postgresql":
                return "org.postgresql.Driver";
            case "mariadb":
                return "org.mariadb.jdbc.Driver";
            case "sqlite":
                return "org.sqlite.JDBC";
            default:
                return null;
        }
    }
    
    /**
     * 关闭特定数据库的连接池
     */
    public void closePool(SchemaProfile profile) {
        String key = generateKey(profile);
        HikariDataSource dataSource = dataSources.remove(key);
        
        if (dataSource != null) {
            dataSource.close();
            logger.info("Closed connection pool for {}", key);
        }
    }
    
    /**
     * 关闭所有连接池
     */
    public void closeAllPools() {
        dataSources.forEach((key, dataSource) -> {
            try {
                dataSource.close();
                logger.info("Closed connection pool for {}", key);
            } catch (Exception e) {
                logger.warn("Error closing connection pool for {}", key, e);
            }
        });
        dataSources.clear();
    }
    
    /**
     * 获取连接池统计信息
     */
    public PoolStats getStats(SchemaProfile profile) {
        String key = generateKey(profile);
        HikariDataSource dataSource = dataSources.get(key);
        
        if (dataSource == null) {
            return null;
        }
        
        return new PoolStats(
            dataSource.getHikariPoolMXBean().getActiveConnections(),
            dataSource.getHikariPoolMXBean().getIdleConnections(),
            dataSource.getHikariPoolMXBean().getTotalConnections(),
            dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection()
        );
    }
    
    /**
     * 连接池统计信息
     */
    public static class PoolStats {
        public final int activeConnections;
        public final int idleConnections;
        public final int totalConnections;
        public final int threadsAwaitingConnection;
        
        public PoolStats(int activeConnections, int idleConnections, int totalConnections,
                        int threadsAwaitingConnection) {
            this.activeConnections = activeConnections;
            this.idleConnections = idleConnections;
            this.totalConnections = totalConnections;
            this.threadsAwaitingConnection = threadsAwaitingConnection;
        }
        
        @Override
        public String toString() {
            return String.format("PoolStats{active=%d, idle=%d, total=%d, waiting=%d}",
                activeConnections, idleConnections, totalConnections, threadsAwaitingConnection);
        }
    }
}

