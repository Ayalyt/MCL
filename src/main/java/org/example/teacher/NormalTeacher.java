package org.example.teacher;

import lombok.Getter;
import org.example.automata.DTA;
import org.example.automata.DTARuntime;
import org.example.words.DelayTimedWord;

import java.util.Optional;

public class NormalTeacher implements Teacher<DelayTimedWord>{
    @Getter
    private final DTA target;

    @Getter
    private final DTARuntime targetRuntime;

    public NormalTeacher(DTA target) {
        this.target = target;
        this.targetRuntime = new DTARuntime(this.target);
    }

    /**
     * @param word
     * @return
     */
    @Override
    public Boolean membershipQuery(DelayTimedWord word) {
        DTARuntime.AcceptResult result = targetRuntime.execute(word);
        return result.isAccepted();
    }

    /**
     * @param hypothesis
     * @return
     */
    @Override
    public Optional<DelayTimedWord> equivalenceQuery(DTA hypothesis) {
        return target.isEquivalent(hypothesis);
    }
}

