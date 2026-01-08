package com.github.ejb.stateless;

import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;

/**
 * Audit service demonstrating transaction attributes.
 */
@Stateless(name = "auditService")
public class AuditService {

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void logAction(String action) {
        // Log to database - always in new transaction
        System.out.println("AUDIT: " + action);
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void logToFile(String message) {
        // Log to file - no transaction needed
        System.out.println("FILE LOG: " + message);
    }

    @TransactionAttribute(TransactionAttributeType.MANDATORY)
    public void logCritical(String message) {
        // Must be called within existing transaction
        System.out.println("CRITICAL: " + message);
    }
}
