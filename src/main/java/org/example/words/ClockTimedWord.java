package org.example.words;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.example.*;
import org.example.ClockConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Collections.EMPTY_LIST;

/**
 * 时钟值时间字 (σ, v) ∈ Σ × ℝ^{|C|}
 * @author Ayalyt
 */
public final class ClockTimedWord extends Word<Pair<Action, ClockValuation>> {
    public static final ClockTimedWord EMPTY = new ClockTimedWord(EMPTY_LIST);
    public ClockTimedWord(List<Pair<Action, ClockValuation>> timedActions) {
        super(validateActions(timedActions));
    }

    private static List<Pair<Action, ClockValuation>> validateActions(List<Pair<Action, ClockValuation>> timedActions) {
        // 验证时钟值连续性（可选）
        return timedActions;
    }

    @Override
    public WordType getType() {
        return WordType.CLOCK_TIMED;
    }

    public RegionTimedWord toRegionWord(ClockConfiguration config) {
        return new RegionTimedWord(
                timedActions.stream()
                        .map(p -> Pair.of(
                                p.getLeft(),
                                p.getRight().toRegion(config)
                        ))
                        .collect(Collectors.toList())
        );
    }

    public ResetClockTimedWord withResets(List<Set<Clock>> resets) {
        List<Triple<Action, ClockValuation, Set<Clock>>> newActions = new ArrayList<>();
        for (int i = 0; i < timedActions.size(); i++) {
            newActions.add(Triple.of(
                    timedActions.get(i).getLeft(),
                    timedActions.get(i).getRight(),
                    resets.get(i)
            ));
        }
        return new ResetClockTimedWord(newActions);
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
     * 获取所有时钟赋值组成的列表（按时间顺序）
     * @return 时间赋值列表
     */
    public List<ClockValuation> getValuation() {
        return this.timedActions.stream()
                .map(Pair::getRight)
                .collect(Collectors.toList());
    }
}