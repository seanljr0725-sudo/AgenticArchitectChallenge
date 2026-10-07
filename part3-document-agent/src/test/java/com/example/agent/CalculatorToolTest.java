package com.example.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CalculatorToolTest {
    private final CalculatorTool calculator = new CalculatorTool();
    @ParameterizedTest
    @CsvSource({"2+3,5", "8-11,-3", "25*16,400", "25×16,400", "12/4,3", "12÷4,3",
            "(2+3)*4,20", "2+3*4,14", "-2 * -3,6", "0.1+0.2,0.3", "1200*3,3600", "1200*4,4800"})
    void evaluatesArithmetic(String expression, String expected) throws Exception {
        assertEquals(expected, calculator.calculate(expression));
    }
    @ParameterizedTest
    @ValueSource(strings={"", " ", "abc", "1+", "1..2", "(1+2", "2**3", "2^3", "1e20", "1;exit()", "2(3)", "1,200*3"})
    void rejectsInvalidExpressions(String expression) {
        assertThrows(AgentException.class, () -> calculator.calculate(expression));
    }
    @Test void rejectsDivisionByZeroAndExcessiveInput() {
        assertTrue(assertThrows(AgentException.class, () -> calculator.calculate("5/(2-2)")).getMessage().contains("zero"));
        assertThrows(AgentException.class, () -> calculator.calculate(null));
        assertThrows(AgentException.class, () -> calculator.calculate("1".repeat(201)));
        assertThrows(AgentException.class, () -> calculator.calculate("(".repeat(22) + "1" + ")".repeat(22)));
    }
}
