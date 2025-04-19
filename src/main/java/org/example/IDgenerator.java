package org.example;

import java.util.concurrent.atomic.AtomicInteger;

public class IDgenerator {
    private final AtomicInteger currentId = new AtomicInteger(1);
    private final AtomicInteger currentMove = new AtomicInteger(1);

    public int createId() {
        return currentId.getAndIncrement();
    }

    public int getMaxId() {
        return currentId.get();
    }

    public String createAction() {
        int num = currentMove.getAndIncrement();
        return convertToStr(num);
    }

    private String convertToStr(int num) {
        StringBuilder sb = new StringBuilder();

        while (num >= 0) {
            int remainder = num % 26;
            sb.append((char) (remainder + 'A'));
            num = (num / 26) - 1;
            if (num < 0) {
                break;
            }
        }

        return sb.reverse().toString();
    }
}
