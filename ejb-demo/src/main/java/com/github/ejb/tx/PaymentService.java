package com.github.ejb.tx;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.ejb.TransactionManagement;
import javax.ejb.TransactionManagementType;

import com.github.ejb.stateless.AuditService;

/**
 * Service demonstrating all TransactionAttributeTypes.
 * Also shows @TransactionManagement (Container-Managed).
 */
@Stateless
@TransactionManagement(TransactionManagementType.CONTAINER)
public class PaymentService {

    @EJB
    private AuditService auditService;

    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public void processPayment(String paymentId, double amount) {
        // Default: join existing or create new transaction
        auditService.logAction("Processing payment: " + paymentId);
        // ... payment logic
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void processRefund(String paymentId, double amount) {
        // Always create new transaction
        auditService.logAction("Processing refund: " + paymentId);
        // ... refund logic
    }

    @TransactionAttribute(TransactionAttributeType.MANDATORY)
    public void validatePayment(String paymentId) {
        // Must be called within existing transaction
        // ... validation logic
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public boolean isPaymentValid(String paymentId) {
        // Use transaction if available, otherwise run without
        return paymentId != null && !paymentId.isEmpty();
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void sendNotification(String message) {
        // Run outside any transaction
        System.out.println("Notification: " + message);
    }

    @TransactionAttribute(TransactionAttributeType.NEVER)
    public void generateReport(String reportType) {
        // Must NOT be called within a transaction
        System.out.println("Generating report: " + reportType);
    }
}
