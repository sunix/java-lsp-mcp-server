package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for Calculator functionality.
 */
class CalculatorTest {
    
    private Calculator calculator;
    
    @BeforeEach
    void setUp() {
        calculator = new Calculator();
    }
    
    @Test
    @DisplayName("Should add two numbers correctly")
    void testAdd() {
        double result = calculator.add(5.0, 3.0);
        assertEquals(8.0, result, 0.001);
    }
    
    @Test
    @DisplayName("Should subtract two numbers correctly")
    void testSubtract() {
        double result = calculator.subtract(10.0, 4.0);
        assertEquals(6.0, result, 0.001);
    }
    
    @Test
    @DisplayName("Should multiply two numbers correctly")
    void testMultiply() {
        double result = calculator.multiply(3.0, 4.0);
        assertEquals(12.0, result, 0.001);
    }
    
    @Test
    @DisplayName("Should divide two numbers correctly")
    void testDivide() {
        double result = calculator.divide(15.0, 3.0);
        assertEquals(5.0, result, 0.001);
    }
    
    @Test
    @DisplayName("Should throw exception when dividing by zero")
    void testDivideByZero() {
        assertThrows(IllegalArgumentException.class, () -> {
            calculator.divide(10.0, 0.0);
        });
    }
    
    @Test
    @DisplayName("Should store calculation history")
    void testHistory() {
        calculator.add(1.0, 2.0);
        calculator.multiply(3.0, 4.0);
        
        assertEquals(2, calculator.getHistory().size());
        assertEquals(3.0, calculator.getHistory().get(0), 0.001);
        assertEquals(12.0, calculator.getHistory().get(1), 0.001);
    }
    
    @Test
    @DisplayName("Should return last result correctly")
    void testGetLastResult() {
        assertEquals(0.0, calculator.getLastResult(), 0.001);
        
        calculator.add(5.0, 5.0);
        assertEquals(10.0, calculator.getLastResult(), 0.001);
        
        calculator.subtract(10.0, 3.0);
        assertEquals(7.0, calculator.getLastResult(), 0.001);
    }
    
    @Test
    @DisplayName("Should clear history correctly")
    void testClearHistory() {
        calculator.add(1.0, 2.0);
        calculator.multiply(3.0, 4.0);
        
        assertFalse(calculator.getHistory().isEmpty());
        
        calculator.clearHistory();
        assertTrue(calculator.getHistory().isEmpty());
        assertEquals(0.0, calculator.getLastResult(), 0.001);
    }
}