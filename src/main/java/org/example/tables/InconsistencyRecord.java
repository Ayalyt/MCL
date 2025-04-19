package org.example.tables;

import lombok.Getter;
import org.example.words.RegionTimedWord;
import org.example.words.ResetClockTimedWord;
@Getter
public class InconsistencyRecord {
    private final ResetClockTimedWord word1;
    private final ResetClockTimedWord word2;
    private final InconsistencyType type;
    private final RegionTimedWord problematicSuffix; // 导致行不同的后缀 e

    public enum InconsistencyType {
        ROW_MISMATCH, // row(yrσr) != row(y'σ'r)
        RESET_MISMATCH // resets(σr) != resets(σ'r)
    }

    public InconsistencyRecord(ResetClockTimedWord w1, ResetClockTimedWord w2, InconsistencyType type, RegionTimedWord suffix) {
        this.word1 = new ResetClockTimedWord(w1.getTimedActions()); // Clone
        this.word2 = new ResetClockTimedWord(w2.getTimedActions()); // Clone
        this.type = type;
        this.problematicSuffix = suffix != null ? new RegionTimedWord(suffix.getTimedActions()) : null; // Clone
    }

    // Copy constructor
    public InconsistencyRecord(InconsistencyRecord other) {
        this.word1 = new ResetClockTimedWord(other.word1.getTimedActions());
        this.word2 = new ResetClockTimedWord(other.word2.getTimedActions());
        this.type = other.type;
        this.problematicSuffix = other.problematicSuffix != null ? new RegionTimedWord(other.problematicSuffix.getTimedActions()) : null;
    }


    @Override
    public String toString() {
        return "InconsistencyRecord{" +
                "type=" + type +
                ", word1=" + word1 +
                ", word2=" + word2 +
                ", suffix=" + problematicSuffix +
                '}';
    }
}