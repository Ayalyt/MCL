package org.example.constraint;

import lombok.Getter;

import java.util.Arrays;
@Getter
public enum ValOrder{
    LESS_THAN("<"),
    LESS_EQUAL("<="),
    GREATER_THAN(">"),
    GREATER_EQUAL(">="),
    EQUALS("==");

    private final String symbol;

    private final int order;

    ValOrder(String symbol) {
        this.symbol = symbol;
        this.order = switch (symbol){
            case "==" -> 0;
            case "<" -> 1;
            case ">" -> 2;
            case "<=" -> 3;
            case ">=" -> 4;
            default -> throw new IllegalStateException("无效的运算符：" + symbol);
        };
    }

    public static ValOrder fromSymbol(String symbol) {
        return Arrays.stream(values())
                .filter(op -> op.symbol.equals(symbol))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("无效的运算符：" + symbol));
    }

}
