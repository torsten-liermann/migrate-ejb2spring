package com.github.ejb.singleton;

import javax.ejb.ConcurrencyManagement;
import javax.ejb.ConcurrencyManagementType;
import javax.ejb.Singleton;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Singleton EJB with Bean-Managed Concurrency.
 * Demonstrates @ConcurrencyManagement.
 */
@Singleton
@ConcurrencyManagement(ConcurrencyManagementType.BEAN)
public class CacheService {

    private final Map<String, Object> cache = new ConcurrentHashMap<>();

    public void put(String key, Object value) {
        cache.put(key, value);
    }

    public Object get(String key) {
        return cache.get(key);
    }

    public void remove(String key) {
        cache.remove(key);
    }

    public void clear() {
        cache.clear();
    }
}
