package org.example.words;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.example.Action;
import org.example.Clock;
import org.example.ClockValuation;
import org.example.ClockConfiguration;
import org.example.utils.Rational;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.EMPTY_LIST;

public final class ResetDelayTimedWord extends Word<Triple<Action, Rational, Set<Clock>>> {
    public static final ResetDelayTimedWord EMPTY = new ResetDelayTimedWord(EMPTY_LIST);
    public ResetDelayTimedWord(List<Triple<Action, Rational, Set<Clock>>> timedActions) {
        super(timedActions);
    }

    @Override
    public WordType getType() {
        return WordType.RESET_DELAY_TIMED;
    }

    public DelayTimedWord toDelayTimedWord() {
        List<Pair<Action, Rational>> converted = new ArrayList<>();
        for (Triple<Action, Rational, Set<Clock>> triple : timedActions) {
            converted.add(Pair.of(triple.getLeft(), triple.getMiddle()));
        }
        return new DelayTimedWord(converted);
    }

    public ResetClockTimedWord toResetClockTimedWord(Collection<Clock> clocks) {
        List<Triple<Action, ClockValuation, Set<Clock>>> converted = new ArrayList<>();
        ClockValuation current = ClockValuation.zero(clocks);

        for (Triple<Action, Rational, Set<Clock>> triple : timedActions) {
            current = current.delay(triple.getMiddle());
            converted.add(Triple.of(
                    triple.getLeft(),
                    current,
                    triple.getRight()
            ));
            if (!triple.getRight().isEmpty()) {
                current = current.reset(triple.getRight());
            }
        }
        return new ResetClockTimedWord(converted);
    }

    public ResetRegionTimedWord toResetRegionWord(ClockConfiguration config) {
        return toResetClockTimedWord(config.getClocks()).toResetRegionWord(config);
    }


    /**
     * 获取所有Action组成的列表（按时间顺序）
     * @return Action列表
     */
    public List<Action> getAction() {
        return this.timedActions.stream()
                .map(Triple::getLeft)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有时间延迟组成的列表（按时间顺序）
     * @return 时间延迟列表
     */
    public List<Rational> getDelay() {
        return this.timedActions.stream()
                .map(Triple::getMiddle)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有重置组成的列表（按时间顺序）
     * @return 重置列表
     */
    public List<Set<Clock>> getResets() {
        return this.timedActions.stream()
                .map(Triple::getRight)
                .collect(Collectors.toList());
    }
}