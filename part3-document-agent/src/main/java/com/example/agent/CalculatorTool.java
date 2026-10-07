package com.example.agent;

import java.math.BigDecimal;
import java.math.MathContext;

/** Recursive-descent arithmetic parser. No scripting engine, eval, names, or executable code. */
public final class CalculatorTool {
    public String calculate(String expression) throws AgentException {
        if (expression == null || expression.isBlank() || expression.length() > 200)
            throw new AgentException("Calculator expression must contain 1 to 200 characters.");
        try {
            Parser parser = new Parser(expression.replace('×', '*').replace('÷', '/').replace('−', '-'));
            BigDecimal result = parser.expression(0);
            parser.space();
            if (parser.position != parser.text.length()) throw new AgentException("Invalid arithmetic expression.");
            if (result.abs().compareTo(new BigDecimal("1E100")) > 0)
                throw new AgentException("Calculation result exceeds the supported magnitude.");
            return result.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new AgentException("Invalid arithmetic expression or numeric range.", e);
        }
    }
    private static final class Parser {
        private static final MathContext PRECISION = MathContext.DECIMAL128;
        private final String text;
        private int position;
        Parser(String text) { this.text = text; }
        void space() { while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++; }
        boolean take(char token) {
            space();
            if (position < text.length() && text.charAt(position) == token) { position++; return true; }
            return false;
        }
        BigDecimal expression(int depth) throws AgentException {
            BigDecimal value = term(depth);
            while (true) {
                if (take('+')) value = value.add(term(depth), PRECISION);
                else if (take('-')) value = value.subtract(term(depth), PRECISION);
                else return value;
            }
        }
        BigDecimal term(int depth) throws AgentException {
            BigDecimal value = factor(depth);
            while (true) {
                if (take('*')) value = value.multiply(factor(depth), PRECISION);
                else if (take('/')) {
                    BigDecimal divisor = factor(depth);
                    if (divisor.signum() == 0) throw new AgentException("Division by zero is not allowed.");
                    value = value.divide(divisor, PRECISION);
                } else return value;
            }
        }
        BigDecimal factor(int depth) throws AgentException {
            if (depth > 20) throw new AgentException("Arithmetic expression is nested too deeply.");
            if (take('+')) return factor(depth + 1);
            if (take('-')) return factor(depth + 1).negate();
            if (take('(')) {
                BigDecimal value = expression(depth + 1);
                if (!take(')')) throw new AgentException("Unclosed arithmetic parentheses.");
                return value;
            }
            space();
            int start = position;
            while (position < text.length() && ((text.charAt(position) >= '0' && text.charAt(position) <= '9')
                    || text.charAt(position) == '.')) position++;
            if (start == position || position - start > 40) throw new AgentException("Invalid arithmetic number.");
            return new BigDecimal(text.substring(start, position), PRECISION);
        }
    }
}
