package org.example.words;

import org.apache.commons.lang3.tuple.Triple;
import org.example.Action;
import org.example.Clock;
import org.example.ClockValuation;
import org.example.ClockConfiguration;
import org.example.utils.Rational;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.EMPTY_LIST;

public final class ResetClockTimedWord extends Word<Triple<Action, ClockValuation, Set<Clock>>> {

    public static final ResetClockTimedWord EMPTY = new ResetClockTimedWord(EMPTY_LIST);
    public ResetClockTimedWord(List<Triple<Action, ClockValuation, Set<Clock>>> actions) {
        super(actions);
    }

    public ResetClockTimedWord(ClockConfiguration configuration, List<Triple<Action, ClockValuation, Set<Clock>>> actions) {
        super(actions.stream()
                .map(action -> {
                    Set<Clock> resetClocks = action.getRight().stream()
                            .map(clock -> configuration.getClockName().get(clock.getName()))
                            .collect(Collectors.toSet());

                    SortedMap<Clock, Rational> clockValuationMap = action.getMiddle().getClockValuation().entrySet().stream()
                            .collect(Collectors.toMap(
                                    entry -> configuration.getClockName().get(entry.getKey().getName()),
                                    Map.Entry::getValue,
                                    (v1, v2) -> v1,
                                    TreeMap::new
                            ));

                    return Triple.of(action.getLeft(), new ClockValuation(clockValuationMap), resetClocks);
                })
                .collect(Collectors.toList()));
    }

    @Override
    public WordType getType() {
        return WordType.RESET_CLOCK_TIMED;
    }

    public ResetDelayTimedWord toResetDelayTimedWord(Collection<Clock> clocks) {
        List<Triple<Action, Rational, Set<Clock>>> delayActions = new ArrayList<>();
        if (this.getTimedActions().isEmpty()) {
            return new ResetDelayTimedWord(delayActions);
        }

        // Process first action
        Triple<Action, ClockValuation, Set<Clock>> first = this.getTimedActions().get(0);
        ClockValuation initialValuation = ClockValuation.zero(clocks); // Initialized to 0 for all clocks
        ClockValuation firstValuation = first.getMiddle();
        Set<Clock> firstReset = first.getRight();

        // Calculate t1 from non-reset clocks
        Rational t1 = Rational.ZERO;
        boolean t1Initialized = false;
        for (Clock c : firstValuation.getClocks()) {
            if (!firstReset.contains(c)) {
                Rational value = firstValuation.getValue(c);
                if (!t1Initialized) {
                    t1 = value;
                    t1Initialized = true;
                } else if (!t1.equals(value)) {
                    throw new IllegalStateException("Invalid clock valuations for first action");
                }
            }
        }
        if (!t1Initialized) {
            // 如果所有时钟都被重置，使用任意时钟的值作为delay
            t1 = firstValuation.getValue(firstValuation.getClocks().iterator().next());
            // 验证所有时钟值一致
            for (Clock c : firstValuation.getClocks()) {
                if (!firstValuation.getValue(c).equals(t1)) {
                    throw new IllegalStateException("Inconsistent clock values in first action");
                }
            }
        }

        for (Clock c : firstReset) {
            if (!firstValuation.getValue(c).equals(Rational.ZERO)) {
                throw new IllegalStateException("Reset clock not zero in first action");
            }
        }
        delayActions.add(Triple.of(first.getLeft(), t1, firstReset));
        ClockValuation prevValuation = initialValuation.delay(t1).reset(firstReset);

        // Process remaining actions
        for (int i = 1; i < this.getTimedActions().size(); i++) {
            Triple<Action, ClockValuation, Set<Clock>> current = getTimedActions().get(i);
            ClockValuation currentValuation = current.getMiddle();
            Set<Clock> currentReset = current.getRight();

            // Calculate delay time ti
            Rational ti = Rational.ZERO;
            boolean tiInitialized = false;
            for (Clock c : prevValuation.getClocks()) {
                if (!currentReset.contains(c)) {
                    Rational expected = prevValuation.getValue(c).add(tiInitialized ? ti : Rational.ZERO);
                    Rational actual = currentValuation.getValue(c);
                    if (!tiInitialized) {
                        ti = actual.subtract(prevValuation.getValue(c));
                        tiInitialized = true;
                        expected = actual;
                    }
                    if (!actual.equals(expected) || ti.compareTo(Rational.ZERO) < 0) {
                        throw new IllegalStateException("Invalid clock valuations for action " + i);
                    }
                }
            }
            if (!tiInitialized) {
                // 通过任意时钟计算delay
                Clock anyClock = prevValuation.getClocks().iterator().next();
                ti = currentValuation.getValue(anyClock).subtract(prevValuation.getValue(anyClock));

                // 验证这个delay对所有时钟都成立
                ClockValuation tempValuation = prevValuation.delay(ti);
                for (Clock c : prevValuation.getClocks()) {
                    if (!currentValuation.getValue(c).equals(tempValuation.getValue(c))) {
                        throw new IllegalStateException("Invalid clock valuations for action " + i);
                    }
                }
            }
            // Verify reset clocks are 0
            for (Clock c : currentReset) {
                if (!currentValuation.getValue(c).equals(Rational.ZERO)) {
                    throw new IllegalStateException("Reset clock not zero in action " + i);
                }
            }
            delayActions.add(Triple.of(current.getLeft(), ti, currentReset));
            prevValuation = prevValuation.delay(ti).reset(currentReset);
        }

        return new ResetDelayTimedWord(delayActions);
    }

    public ResetRegionTimedWord toResetRegionWord(ClockConfiguration config) {
        return new ResetRegionTimedWord(
                timedActions.stream()
                        .map(t -> Triple.of(
                                t.getLeft(),
                                t.getMiddle().toRegion(config),
                                t.getRight()
                        ))
                        .collect(Collectors.toList())
        );
    }

    public ResetClockTimedWord append(Triple<Action, ClockValuation, Set<Clock>> resetClockAction) {
        List<Triple<Action, ClockValuation, Set<Clock>>> updatedActions = new ArrayList<>(this.timedActions);
        updatedActions.add(resetClockAction);
        return new ResetClockTimedWord(updatedActions);
    }

    // 另一个实例是否是当前实例的前缀
    public boolean isPrefix(ResetClockTimedWord other) {
        if (other.timedActions.size() > this.timedActions.size()) {
            return false;
        }
        for (int i = 0; i < other.timedActions.size(); i++) {
            if (!this.timedActions.get(i).equals(other.timedActions.get(i))) {
                return false;
            }
        }
        return true;
    }

    public Triple<Action, ClockValuation, Set<Clock>> getLast() {
        return timedActions.get(timedActions.size() - 1);
    }

    public ClockValuation getLastValuation() {
        return timedActions.get(timedActions.size() - 1).getMiddle();
    }

    public Set<Clock> getLastResets() {
        return timedActions.get(timedActions.size() - 1).getRight();
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
     * 获取所有时钟赋值组成的列表（按时间顺序）
     * @return 时间赋值列表
     */
    public List<ClockValuation> getValuation() {
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

    public ResetClockTimedWord getPrefix(int i) {
        if (i < 0 || i > this.timedActions.size()) {
            throw new IllegalArgumentException("Index out of bounds");
        }
        return new ResetClockTimedWord(this.timedActions.subList(0, i));
    }

    public ResetClockTimedWord getSuffix(int i) {
        if (i < 0 || i > this.timedActions.size()) {
            throw new IllegalArgumentException("Index out of bounds");
        }
        return new ResetClockTimedWord(this.timedActions.subList(this.timedActions.size() - i, this.timedActions.size()));
    }

    /**
     * 将当前实例与另一个ResetClockTimedWord实例连接，返回新的实例
     * @param other 要连接的另一实例
     * @return 连接后的新实例
     */
    public ResetClockTimedWord concat(ResetClockTimedWord other) {
        List<Triple<Action, ClockValuation, Set<Clock>>> combined = new ArrayList<>(this.timedActions);
        combined.addAll(other.timedActions);
        return new ResetClockTimedWord(combined);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResetClockTimedWord that)) {
            return false;
        }
        return super.equals(that);
    }
}