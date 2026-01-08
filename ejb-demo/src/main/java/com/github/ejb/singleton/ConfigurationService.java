package com.github.ejb.singleton;

import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import java.util.HashMap;
import java.util.Map;

/**
 * Singleton EJB demonstrating:
 * - @Singleton annotation
 * - @Startup for eager initialization
 * - @Lock for concurrency control
 */
@Singleton
@Startup
public class ConfigurationService {

    private final Map<String, String> config = new HashMap<>();

    @Lock(LockType.WRITE)
    public void setProperty(String key, String value) {
        config.put(key, value);
    }

    @Lock(LockType.READ)
    public String getProperty(String key) {
        return config.get(key);
    }

    @Lock(LockType.READ)
    public Map<String, String> getAllProperties() {
        return new HashMap<>(config);
    }
}
