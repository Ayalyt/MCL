package org.example;

import lombok.Getter;
import org.example.words.ResetClockTimedWord;

import java.util.*;

/**
 * @author Ayalyt
 */
@Getter
public class Alphabet {
    public final Map<Integer, Action> alphabet = new TreeMap<>();
    public final Map<String, Action> actionMap = new HashMap<>();

    private static IDgenerator idGenerator = new IDgenerator();

    public Action createAction(String action) {
        if (alphabet.values().stream().anyMatch(a -> a.getAction().equals(action))) {
            return actionMap.get(action);
        }
        int id = idGenerator.createId();
        Action newAction = new Action(id, action);
        alphabet.put(id, newAction);
        actionMap.put(action, newAction);
        return newAction;
    }

    public Action createAction(String action, ResetClockTimedWord resetClockTimedWord) {
        if (alphabet.values().stream().anyMatch(a -> a.getAction().equals(action))) {
            return actionMap.get(action);
        }
        int id = idGenerator.createId();
        Action newAction = new Action(id, action);
        newAction.setResetClockTimedWord(resetClockTimedWord);
        alphabet.put(id, newAction);
        actionMap.put(action, newAction);
        return newAction;
    }


    public Alphabet(Set<Action> actions) {
        int idCounter = 0;
        for (Action action : actions) {
            if(action.getAction() != null) {
                Action newAction = new Action(idCounter, action.getAction());
                alphabet.put(idCounter, newAction);
                actionMap.put(action.getAction(), newAction);
                idCounter++;
            }
        }
    }

    public Alphabet(Alphabet alphabet) {
        this.alphabet.putAll(alphabet.alphabet);
        this.actionMap.putAll(alphabet.actionMap);
    }

    public Alphabet() {
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Action action : alphabet.values()) {
            sb.append(action.getAction()).append(", ");
        }
        return "[" + sb.toString() + "]";
    }

    public boolean contains(Action action) {
        return alphabet.containsValue(action);
    }
}
