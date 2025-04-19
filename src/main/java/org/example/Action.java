package org.example;

import lombok.Getter;
import lombok.Setter;
import org.example.words.ResetClockTimedWord;

import java.util.Objects;

/**
 * @author Ayalyt
 */
@Getter
public class Action implements Comparable<Action>{

    private final boolean isEpsilon;
    private final int index;
    private final String action;
    @Setter
    private ResetClockTimedWord resetClockTimedWord = null; // 抽象动作标签

    public Action(int index, String action){
        this.index = index;
        this.action = action;
        this.isEpsilon = action == null || action.isEmpty();
    }

    public Action(Action action){
        this.isEpsilon = action.isEpsilon;
        this.index = action.index;
        this.action = action.action;
        this.resetClockTimedWord = action.resetClockTimedWord;
    }

    @Override
    public String toString() {
        return action;
    }

    @Override
    public int compareTo(Action other) {
        return Integer.compare(this.index, other.index);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Action other = (Action) o;
        return Objects.equals(action, other.action) &&
                isEpsilon == other.isEpsilon &&
                Objects.equals(resetClockTimedWord, other.resetClockTimedWord);
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, isEpsilon);
    }
}


