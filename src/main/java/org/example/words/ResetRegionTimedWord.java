package org.example.words;

import org.apache.commons.lang3.tuple.Triple;
import org.example.Action;
import org.example.Clock;
import org.example.region.Region;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Collections.EMPTY_LIST;

public final class ResetRegionTimedWord extends Word<Triple<Action, Region, Set<Clock>>> {
    public static final ResetRegionTimedWord EMPTY = new ResetRegionTimedWord(EMPTY_LIST);
    public ResetRegionTimedWord(List<Triple<Action, Region, Set<Clock>>> timedActions) {
        super(timedActions);
    }

    @Override
    public WordType getType() {
        return WordType.RESET_REGION_TIMED;
    }

    public boolean isPrefixOf(ResetRegionTimedWord other) {
        if (this.timedActions.size() > other.timedActions.size()) {
            return false;
        }
        for (int i = 0; i < this.timedActions.size(); i++) {
            if (!this.timedActions.get(i).equals(other.timedActions.get(i))) {
                return false;
            }
        }
        return true;
    }

    public boolean isEquivalent(ResetRegionTimedWord other) {
        if (this.timedActions.size() != other.timedActions.size()) {
            return false;
        }
        for (int i = 0; i < timedActions.size(); i++) {
            Triple<Action, Region, Set<Clock>> a1 = this.timedActions.get(i);
            Triple<Action, Region, Set<Clock>> a2 = other.timedActions.get(i);
            if (!a1.getLeft().equals(a2.getLeft()) ||
                    a1.getMiddle().equals(a2.getMiddle()) ||
                    !a1.getRight().equals(a2.getRight())) {
                return false;
            }
        }
        return true;
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
     * 获取所有时钟区域组成的列表（按时间顺序）
     * @return 时间区域列表
     */
    public List<Region> getRegion() {
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