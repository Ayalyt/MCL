package org.example.words;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.example.Action;
import org.example.Clock;
import org.example.utils.Rational;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.EMPTY_LIST;

/**
 * @author Ayalyt
 */
public final class DelayTimedWord extends Word<Pair<Action, Rational>> {
    public static final DelayTimedWord EMPTY = new DelayTimedWord(EMPTY_LIST);
    public DelayTimedWord(List<Pair<Action, Rational>> actions) {
        super(actions);
    }

    @Override
    public WordType getType() {
        return WordType.DELAY_TIMED;
    }

    public Rational getTotalDelay() {
        return timedActions.stream().map(Pair::getRight).reduce(Rational.ZERO, Rational::add);
    }

    public List<DelayTimedWord> prefixes() {
        List<DelayTimedWord> prefixes = new ArrayList<>();
        for (int i = 0; i <= timedActions.size(); i++) {
            prefixes.add(new DelayTimedWord(timedActions.subList(0, i)));
        }
        return prefixes;
    }
    public ResetDelayTimedWord withResets(List<Set<Clock>> resets) {
        if (timedActions.size() != resets.size()) {
            throw new IllegalArgumentException("重置列表长度不匹配");
        }
        List<Triple<Action, Rational, Set<Clock>>> newActions = new ArrayList<>();
        for (int i = 0; i < timedActions.size(); i++) {
            newActions.add(Triple.of(
                    timedActions.get(i).getLeft(),
                    timedActions.get(i).getRight(),
                    resets.get(i)
            ));
        }
        return new ResetDelayTimedWord(newActions);
    }
    /**
     * 获取所有Action组成的列表（按时间顺序）
     * @return Action列表
     */
    public List<Action> getAction() {
        return this.timedActions.stream()
                .map(Pair::getLeft)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有时间延迟组成的列表（按时间顺序）
     * @return 时间延迟列表
     */
    public List<Rational> getDelay() {
        return this.timedActions.stream()
                .map(Pair::getRight)
                .collect(Collectors.toList());
    }

    /**
     * 连接两个DelayTimedWord实例
     * @param other 要连接的另一个DelayTimedWord
     * @return 新的连接后的DelayTimedWord
     */
    public DelayTimedWord concat(DelayTimedWord other) {
        if (other == null || other.getTimedActions().isEmpty()) {
            return this;
        }
        
        List<Pair<Action, Rational>> newActions = new ArrayList<>(this.timedActions);
        newActions.addAll(other.getTimedActions());
        return new DelayTimedWord(newActions);
    }

}