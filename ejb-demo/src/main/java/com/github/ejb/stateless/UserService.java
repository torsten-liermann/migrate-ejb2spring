package com.github.ejb.stateless;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;

/**
 * Stateless EJB with name attribute and @EJB injection.
 * Demonstrates:
 * - @Stateless with name and description
 * - @EJB injection with beanName
 * - @TransactionAttribute
 */
@Stateless(name = "userService", description = "Service for user management operations")
public class UserService {

    @EJB
    private CalculatorService calculator;

    @EJB(beanName = "auditService")
    private AuditService auditService;

    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public void createUser(String username) {
        // Business logic
        auditService.logAction("Created user: " + username);
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void deleteUser(String username) {
        // Business logic
        auditService.logAction("Deleted user: " + username);
    }

    public int calculateUserScore(int base, int bonus) {
        return calculator.add(base, bonus);
    }
}
