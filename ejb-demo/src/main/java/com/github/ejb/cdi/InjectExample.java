package com.github.ejb.cdi;

import javax.ejb.Stateless;
import javax.inject.Inject;

import com.github.ejb.stateless.CalculatorService;
import com.github.ejb.stateless.AuditService;

/**
 * EJB using CDI @Inject instead of @EJB.
 * @Inject should be migrated to @Autowired.
 */
@Stateless
public class InjectExample {

    @Inject
    private CalculatorService calculator;

    @Inject
    private AuditService auditService;

    public int compute(int a, int b) {
        auditService.logAction("Computing: " + a + " + " + b);
        return calculator.add(a, b);
    }
}
