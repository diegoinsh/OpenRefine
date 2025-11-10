package com.google.refine.extension.records.db;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 字段字典缓存
 * 
 * 使用 LRU 缓存策略缓存数据库字段信息
 * 支持多线程访问和缓存失效
 */
public class FieldDictionaryCache {
    private static final Logger logger = LoggerFactory.getLogger(FieldDictionaryCache.class);
    
    private static final int DEFAULT_CACHE_SIZE = 100;
    private static final long DEFAULT_TTL = 3600000; // 1 小时
    
    private final int maxSize;
    private final long ttl;
    private final Map<String, CacheEntry> cache;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    /**
     * 缓存条目
     */
    private static class CacheEntry {
        List<String> fieldNames;
        Map<String, String> fieldTypes;
        long createdAt;
        long lastAccessedAt;
        int accessCount;
        
        CacheEntry(List<String> fieldNames, Map<String, String> fieldTypes) {
            this.fieldNames = fieldNames;
            this.fieldTypes = fieldTypes;
            this.createdAt = System.currentTimeMillis();
            this.lastAccessedAt = createdAt;
            this.accessCount = 0;
        }
        
        boolean isExpired(long ttl) {
            return System.currentTimeMillis() - createdAt > ttl;
        }
    }
    
    /**
     * 构造函数
     */
    public FieldDictionaryCache() {
        this(DEFAULT_CACHE_SIZE, DEFAULT_TTL);
    }
    
    /**
     * 构造函数
     * 
     * @param maxSize 最大缓存条目数
     * @param ttl 缓存过期时间 (毫秒)
     */
    public FieldDictionaryCache(int maxSize, long ttl) {
        this.maxSize = maxSize;
        this.ttl = ttl;
        this.cache = new LinkedHashMap<String, CacheEntry>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry eldest) {
                return size() > FieldDictionaryCache.this.maxSize;
            }
        };
    }
    
    /**
     * 生成缓存键
     */
    private String generateKey(String database, String table) {
        return database + ":" + table;
    }
    
    /**
     * 获取缓存的字段信息
     * 
     * @param database 数据库名
     * @param table 表名
     * @return 字段名列表，如果缓存不存在或已过期则返回 null
     */
    public List<String> getFieldNames(String database, String table) {
        String key = generateKey(database, table);
        
        lock.readLock().lock();
        try {
            CacheEntry entry = cache.get(key);
            
            if (entry != null && !entry.isExpired(ttl)) {
                entry.lastAccessedAt = System.currentTimeMillis();
                entry.accessCount++;
                logger.debug("Cache hit for {}: {}", key, entry.fieldNames);
                return entry.fieldNames;
            }
            
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 获取缓存的字段类型信息
     * 
     * @param database 数据库名
     * @param table 表名
     * @return 字段类型映射，如果缓存不存在或已过期则返回 null
     */
    public Map<String, String> getFieldTypes(String database, String table) {
        String key = generateKey(database, table);
        
        lock.readLock().lock();
        try {
            CacheEntry entry = cache.get(key);
            
            if (entry != null && !entry.isExpired(ttl)) {
                entry.lastAccessedAt = System.currentTimeMillis();
                entry.accessCount++;
                return entry.fieldTypes;
            }
            
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 缓存字段信息
     * 
     * @param database 数据库名
     * @param table 表名
     * @param fieldNames 字段名列表
     * @param fieldTypes 字段类型映射
     */
    public void put(String database, String table, List<String> fieldNames, Map<String, String> fieldTypes) {
        String key = generateKey(database, table);
        
        lock.writeLock().lock();
        try {
            CacheEntry entry = new CacheEntry(fieldNames, fieldTypes);
            cache.put(key, entry);
            logger.debug("Cached field info for {}: {} fields", key, fieldNames.size());
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 清除特定的缓存条目
     * 
     * @param database 数据库名
     * @param table 表名
     */
    public void invalidate(String database, String table) {
        String key = generateKey(database, table);
        
        lock.writeLock().lock();
        try {
            cache.remove(key);
            logger.debug("Invalidated cache for {}", key);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 清除所有缓存
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            cache.clear();
            logger.info("Cleared all cache entries");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats() {
        lock.readLock().lock();
        try {
            int totalEntries = cache.size();
            long totalAccess = cache.values().stream()
                .mapToLong(e -> e.accessCount)
                .sum();
            
            return new CacheStats(totalEntries, totalAccess);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 缓存统计信息
     */
    public static class CacheStats {
        public final int totalEntries;
        public final long totalAccess;
        
        public CacheStats(int totalEntries, long totalAccess) {
            this.totalEntries = totalEntries;
            this.totalAccess = totalAccess;
        }
        
        public double getHitRate() {
            return totalAccess > 0 ? (double) totalAccess / totalEntries : 0;
        }
        
        @Override
        public String toString() {
            return String.format("CacheStats{entries=%d, totalAccess=%d, hitRate=%.2f}", 
                totalEntries, totalAccess, getHitRate());
        }
    }
}

