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

    /**
     * 将当前的 Reset-Clocked Word 转换为 Reset-Delay-Timed Word。
     * Reset-Clocked Word 是序列 (σ1, v1, B1)...(σn, vn, Bn)，其中 vi 是在动作 σi 发生前（延迟 ti 之后）
     * 但在重置 Bi 应用之前的时钟估值。
     * Reset-Delay-Timed Word 是序列 (σ1, t1, B1)...(σn, tn, Bn)，其中 ti 是动作 σi 发生前的延迟。
     * 核心关系：vi = v'_{i-1} + ti，其中 v'_{i-1} 是重置 B_{i-1} 应用后的估值
     * (v'_{i-1} = [B_{i-1} -> 0] v_{i-1})，并且 v'_0 = 全零估值。
     *
     * @param clocks 涉及估值的完整时钟集合。
     * @return 对应的 ResetDelayTimedWord 对象。
     * @throws IllegalStateException 如果输入的 Reset-Clocked Word 不一致或不对应于有效的定时运行
     * @throws NoSuchElementException 如果估值中缺少 'clocks' 参数中定义的时钟。
     */
    public ResetDelayTimedWord toResetDelayTimedWord(Collection<Clock> clocks) {
        List<Triple<Action, Rational, Set<Clock>>> delayActions = new ArrayList<>();

        if (this.timedActions == null || this.timedActions.isEmpty()) {
            return new ResetDelayTimedWord(delayActions);
        }

        // v'_0: 第一个动作之前的（隐式）重置后的估值（全零）
        ClockValuation prevValuationAfterReset = ClockValuation.zero(clocks);

        // 遍历输入的每个定时动作 (σi, vi, Bi)
        for (int i = 0; i < this.timedActions.size(); i++) {
            Triple<Action, ClockValuation, Set<Clock>> currentTimedAction = this.timedActions.get(i);
            Action currentAction = currentTimedAction.getLeft(); // 当前动作 σi
            // vi: 动作 i 发生前（重置 Bi 之前）的估值
            ClockValuation currentValuationBeforeReset = currentTimedAction.getMiddle();
            // Bi: 动作 i 的重置集合
            Set<Clock> currentResetSet = currentTimedAction.getRight();

            // 检查是否有可用的时钟来确定延迟
            if (clocks.isEmpty()) {
                if (!currentValuationBeforeReset.equals(ClockValuation.zero(clocks))) {
                    throw new IllegalStateException("步骤 " + i + ": 存在非零估值但没有时钟。");
                }
                delayActions.add(Triple.of(currentAction, Rational.ZERO, currentResetSet));
                continue;
            }

            // --- 计算延迟 ti ---
            // ti = vi,c - v'_{i-1},c
            Clock referenceClock = clocks.iterator().next();
            Rational calculatedDelay;
            try {
                Rational currentValRef = currentValuationBeforeReset.getValue(referenceClock); // vi,c
                Rational prevValRef = prevValuationAfterReset.getValue(referenceClock);    // v'_{i-1},c
                calculatedDelay = currentValRef.subtract(prevValRef); // ti = vi,c - v'_{i-1},c
            } catch (NoSuchElementException e) {
                throw new NoSuchElementException("步骤 " + i + ": 参考时钟 " + referenceClock +
                        " 在提供的估值中未找到。");
            }

            // --- 验证计算出的延迟 ti ---
            // 1. 检查延迟是否为非负数
            if (calculatedDelay.compareTo(Rational.ZERO) < 0) {
                throw new IllegalStateException("步骤 " + i + ": 计算得到负延迟 (" + calculatedDelay + ")。" +
                        " 上一步重置后估值: " + prevValuationAfterReset +
                        ", 当前重置前估值: " + currentValuationBeforeReset);
            }

            // 2. 验证一致性: 检查 vi == v'_{i-1} + ti 是否对所有时钟都成立
            //    基于 v'_{i-1} 和计算出的 ti，重新构建预期的 vi
            ClockValuation expectedValuationBeforeReset = prevValuationAfterReset.delay(calculatedDelay);

            if (!currentValuationBeforeReset.equals(expectedValuationBeforeReset)) {
                throw new IllegalStateException(
                        "步骤 " + i + ": 对于计算出的延迟 " + calculatedDelay + "，时钟估值不一致。" +
                                " 上一步重置后估值: " + prevValuationAfterReset +
                                ", 当前重置前估值: " + currentValuationBeforeReset);
            }

            delayActions.add(Triple.of(currentAction, calculatedDelay, currentResetSet));

            // v'_i = [Bi -> 0] vi
            prevValuationAfterReset = currentValuationBeforeReset.reset(currentResetSet);
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