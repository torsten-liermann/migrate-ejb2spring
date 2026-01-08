package com.github.ejb.stateless;

import javax.ejb.Startup;
import javax.ejb.Stateless;

/**
 * EJB with @Startup annotation.
 * @Startup should be removed (Spring Boot beans are available after startup by default).
 */
@Stateless
@Startup
public class StartupService {

    public void initialize() {
        System.out.println("StartupService initialized");
    }
}
