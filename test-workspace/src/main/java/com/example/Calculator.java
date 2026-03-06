package com.example;

import java.util.List;
import java.util.ArrayList;

/**
 * A simple calculator class for testing jdtls functionality.
 */
public class Calculator {
    
    private List<Double> history;
    
    public Calculator() {
        this.history = new ArrayList<>();
    }
    
    /**
     * Adds two numbers and stores the result in history.
     * @param a first number
     * @param b second number
     * @return the sum of a and b
     */
    public double add(double a, double b) {
        double result = a + b;
        history.add(result);
        return result;
    }
    
    /**
     * Subtracts b from a and stores the result in history.
     * @param a first number
     * @param b second number
     * @return the difference of a and b
     */
    public double subtract(double a, double b) {
        double result = a - b;
        history.add(result);
        return result;
    }
    
    /**
     * Multiplies two numbers and stores the result in history.
     * @param a first number
     * @param b second number
     * @return the product of a and b
     */
    public double multiply(double a, double b) {
        double result = a * b;
        history.add(result);
        return result;
    }
    
    /**
     * Divides a by b and stores the result in history.
     * @param a dividend
     * @param b divisor
     * @return the quotient of a and b
     * @throws IllegalArgumentException if b is zero
     */
    public double divide(double a, double b) {
        if (b == 0) {
            throw new IllegalArgumentException("Division by zero is not allowed");
        }
        double result = a / b;
        history.add(result);
        return result;
    }
    
    /**
     * Gets the calculation history.
     * @return list of previous calculation results
     */
    public List<Double> getHistory() {
        return new ArrayList<>(history);
    }
    
    /**
     * Clears the calculation history.
     */
    public void clearHistory() {
        history.clear();
    }
    
    /**
     * Gets the last calculation result.
     * @return the last result, or 0.0 if no calculations performed
     */
    public double getLastResult() {
        return history.isEmpty() ? 0.0 : history.get(history.size() - 1);
    }
}