package com.github.ejb.stateless;

import javax.ejb.Stateless;

/**
 * Simple stateless EJB demonstrating @Stateless annotation.
 * Should be migrated to @Service.
 */
@Stateless
public class CalculatorService {

    public int add(int a, int b) {
        return a + b;
    }

    public int subtract(int a, int b) {
        return a - b;
    }

    public int multiply(int a, int b) {
        return a * b;
    }

    public int divide(int a, int b) {
        if (b == 0) {
            throw new IllegalArgumentException("Division by zero");
        }
        return a / b;
    }
}
