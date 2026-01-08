package com.github.ejb.jndi;

import javax.naming.InitialContext;
import javax.naming.NamingException;

import com.github.ejb.stateless.CalculatorService;

/**
 * Example demonstrating JNDI lookup.
 * JNDI lookups should be migrated to @Autowired.
 */
public class JndiLookupExample {

    public int performCalculation(int a, int b) {
        try {
            InitialContext ctx = new InitialContext();
            CalculatorService calculator = (CalculatorService) ctx.lookup("java:global/app/CalculatorService");
            return calculator.add(a, b);
        } catch (NamingException e) {
            throw new RuntimeException("JNDI lookup failed", e);
        }
    }

    public void multipleLookupsInMethod(int value) {
        try {
            InitialContext ctx = new InitialContext();
            CalculatorService calc = (CalculatorService) ctx.lookup("java:module/CalculatorService");
            int doubled = calc.multiply(value, 2);
            System.out.println("Doubled: " + doubled);
        } catch (NamingException e) {
            throw new RuntimeException("JNDI lookup failed", e);
        }
    }
}
