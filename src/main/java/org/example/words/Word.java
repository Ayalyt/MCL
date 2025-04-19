package org.example.words;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 所有时间行为词的抽象基类
 * @param <T> 动作序列元素类型（如Pair/Triple）
 */
public abstract class Word<T> {
    @Getter
    protected final List<T> timedActions;  // 时间动作序列（不可变）

    // --- 构造方法 ---
    protected Word(List<T> timedActions) {
        this.timedActions = List.copyOf(timedActions);
    }

    // --- 抽象方法 ---
    public abstract WordType getType();

    // --- 公共方法 ---
    public int length() {
        return timedActions.size();
    }

    public boolean isEmpty() {
        return timedActions.isEmpty();
    }

    // --- 重写Object方法 ---
    @Override
    public String toString() {
        return timedActions.stream().map(Object::toString).collect(Collectors.joining(" -> "));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Word<?> that)) {
            return false;
        }
        return timedActions.equals(that.timedActions);
    }

    @Override
    public int hashCode() {
        return timedActions.hashCode();
    }

}