package com.github.ejb.stateless;

import javax.ejb.LocalBean;
import javax.ejb.Stateless;

/**
 * Stateless EJB with @LocalBean (no-interface view).
 * @LocalBean should be removed during migration.
 */
@Stateless
@LocalBean
public class LocalBeanService {

    public String process(String input) {
        return "Processed: " + input;
    }
}
