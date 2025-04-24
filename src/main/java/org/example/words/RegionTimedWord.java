package org.example.words;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.example.Action;
import org.example.Clock;
import org.example.ClockValuation;
import org.example.region.Region;
import org.example.ClockConfiguration;
import org.example.region.RegionSolver;
import org.example.utils.Rational;
import org.example.utils.Z3Solver;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Collections.EMPTY_LIST;

/**
 * @author Ayalyt
 */
public final class RegionTimedWord extends Word<Pair<Action, Region>> {
    public static final RegionTimedWord EMPTY = new RegionTimedWord(EMPTY_LIST);
    private static final Z3Solver SOLVER = new Z3Solver();

    public RegionTimedWord(List<Pair<Action, Region>> actions) {
        super(actions);
    }

    @Override
    public WordType getType() {
        return WordType.REGION_TIMED;
    }

    /**
     * 将区域字根据给定的重置序列和起始时钟状态转换为重置时钟字。
     *
     * @param resetSequence  每个区域动作对应的重置集合列表，长度必须与本区域字相同。
     * @param startValuation 执行本区域字之前的时钟状态。
     * @param clocks         系统中的时钟集合。
     * @param configuration  区域配置。
     * @return 转换后的 ResetClockTimedWord，如果无法找到有效的延时序列则返回 null。
     * @throws IllegalArgumentException 如果 resetSequence 长度不匹配。
     */
    public ResetClockTimedWord toResetClockTimedWord(
            List<Set<Clock>> resetSequence,
            ClockValuation startValuation,
            Set<Clock> clocks,
            ClockConfiguration configuration) {

        if (resetSequence.size() != this.timedActions.size()) {
            throw new IllegalArgumentException("重置长度与区域字不符");
        }
        if (this.isEmpty()) {
            return ResetClockTimedWord.EMPTY;
        }

        List<Triple<Action, ClockValuation, Set<Clock>>> resultingActions = new ArrayList<>();
        ClockValuation currentValuation = startValuation;

        for (int i = 0; i < this.timedActions.size(); i++) {
            Pair<Action, Region> timedAction = this.timedActions.get(i);
            Action action = timedAction.getLeft();
            Region region = timedAction.getRight();
            Set<Clock> resets = resetSequence.get(i);

            // 查找满足区域约束的延时
            Optional<Rational> delayOpt = RegionSolver.solveDelay(currentValuation, region);

            if (delayOpt.isEmpty()) {
                throw new IllegalArgumentException("无法为区域 " + region + " 从状态 " + currentValuation + " 找到有效延时");
            }

            Rational delay = delayOpt.get();
            ClockValuation valuationBeforeReset = currentValuation.delay(delay);
            ClockValuation nextValuation = valuationBeforeReset.reset(resets);

            // 创建 ResetClockTimedWord 的动作三元组
            // ResetClockTimedWord 存储的是重置前的状态和重置集合
            resultingActions.add(Triple.of(action, valuationBeforeReset, resets));

            currentValuation = nextValuation; // 更新当前状态以进行下一步计算
        }

        return new ResetClockTimedWord(resultingActions);
    }

    public boolean isEquivalent(RegionTimedWord other) {
        if (this.timedActions.size() != other.timedActions.size()) {
            return false;
        }
        for (int i = 0; i < timedActions.size(); i++) {
            if (timedActions.get(i).getRight().equals(other.timedActions.get(i).getRight())) {
                return false;
            }
        }
        return true;
    }

    public ResetRegionTimedWord withResets(List<Set<Clock>> resets) {
        List<Triple<Action, Region, Set<Clock>>> newActions = new ArrayList<>();
        for (int i = 0; i < timedActions.size(); i++) {
            newActions.add(Triple.of(
                    timedActions.get(i).getLeft(),
                    timedActions.get(i).getRight(),
                    resets.get(i)
            ));
        }
        return new ResetRegionTimedWord(newActions);
    }

    public RegionTimedWord concat(RegionTimedWord other) {
        List<Pair<Action, Region>> combinedActions = new ArrayList<>(this.timedActions);
        combinedActions.addAll(other.timedActions);
        return new RegionTimedWord(combinedActions);
    }

    public RegionTimedWord append(RegionTimedWord other) {
        List<Pair<Action, Region>> combinedActions = new ArrayList<>(this.timedActions);
        combinedActions.addAll(other.timedActions);
        return new RegionTimedWord(combinedActions);
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
     * 获取所有时钟区域组成的列表（按时间顺序）
     * @return 时间区域列表
     */
    public List<Region> getRegion() {
        return this.timedActions.stream()
                .map(Pair::getRight)
                .collect(Collectors.toList());
    }
}