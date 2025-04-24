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
    @Getter
    private int numOfMq= 0;
    @Getter
    private int numOfEq = 0;

    public NormalTeacher(DTA target) {
        this.target = target;
        this.targetRuntime = new DTARuntime(this.target);
    }

    public NormalTeacher() {
        target = null;
        targetRuntime = null;
    }

    /**
     * @param word
     * @return
     */
    @Override
    public Boolean membershipQuery(DelayTimedWord word) {
        numOfMq++;
        DTARuntime.AcceptResult result = targetRuntime.execute(word);
        return result.isAccepted();
    }

    /**
     * @param hypothesis
     * @return
     */
    @Override
    public Optional<DelayTimedWord> equivalenceQuery(DTA hypothesis) {
        numOfEq++;
        return target.isEquivalent(hypothesis);
    }
}

