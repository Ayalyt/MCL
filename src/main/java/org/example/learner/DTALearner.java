package org.example.learner;

import org.apache.commons.lang3.tuple.Triple;
import org.example.*;
import org.example.automata.DFA;
import org.example.automata.DTA;
import org.example.teacher.NormalTeacher;
import org.example.tables.ObservationTable;
import org.example.words.DelayTimedWord;
import org.example.words.ResetClockTimedWord;
import java.util.*;

public class DTALearner {
    private PriorityQueue<ObservationTable> toExplore;
    private final Alphabet alphabet;
    private final int clockNum;
    private final NormalTeacher teacher;
    private final ClockConfiguration configuration;



    public DTALearner(Alphabet alphabet, int clockNum, NormalTeacher teacher, ClockConfiguration configuration) {
        this.toExplore = new PriorityQueue<>((t1, t2) ->
                Integer.compare(t1.getGuessCount(), t2.getGuessCount()));
        this.alphabet = alphabet;
        this.clockNum = clockNum;
        this.teacher = teacher;
        this.configuration = configuration;
    }

    private void initialize() {
        // 创建基础观察表
        ObservationTable baseTable = new ObservationTable(alphabet, clockNum, teacher, configuration);
        // 生成所有可能的重置组合
        List<Set<Clock>> allResetSubsets = baseTable.generateAllSubsetsOfClocks();
        Map<Action, List<Set<Clock>>> actionResetMap = new HashMap<>();
        for (Action action : alphabet.alphabet.values()) {
            actionResetMap.put(action, allResetSubsets);
        }

        // 生成所有可能的重置组合的笛卡尔积
        List<Map<Action, Set<Clock>>> allCombinations = generateAllResetCombinations(actionResetMap);

        // 为每个组合创建一个表实例
        for (Map<Action, Set<Clock>> resetCombination : allCombinations) {
            ObservationTable newTable = new ObservationTable(baseTable);

            // 使用特定的重置组合初始化R集
            for (Map.Entry<Action, Set<Clock>> entry : resetCombination.entrySet()) {
                Action action = entry.getKey();
                Set<Clock> resetSet = entry.getValue();

                ClockValuation initialClockValues = ClockValuation.zero(newTable.getClocks());
                ResetClockTimedWord rWord = new ResetClockTimedWord(
                        Collections.singletonList(Triple.of(action, initialClockValues, resetSet))
                );
                newTable.getR().add(rWord);
            }
            toExplore.addAll(newTable.fillTable());
        }
    }

    public DTA learn() {
        initialize();
        while (!toExplore.isEmpty()) {
            System.out.println("DTALearner-learn：当前候选表数目：" + toExplore.size());
            ObservationTable currentTable = toExplore.poll();

            while (!isPrepared(currentTable)) {
                if (!isClosed(currentTable)) {
                    List<ObservationTable> newTables = guessAndClose(currentTable);
                    toExplore.addAll(newTables);
                    currentTable = toExplore.poll();
                    if (currentTable == null) {
                        break;
                    }
                }
                if (!isConsistent(currentTable)) {
                    List<ObservationTable> newTables = guessAndConsistent(currentTable);
                    toExplore.addAll(newTables);
                    currentTable = toExplore.poll();
                    if (currentTable == null) {
                        break;
                    }
                }
/*                if (!isEvidenceClosed(currentTable)) {
                    List<ObservationTable> newTables = guessAndEvidenceClosed(currentTable);
                    toExplore.addAll(newTables);
                    currentTable = toExplore.poll();
                    if (currentTable == null) {
                        break;
                    }
                }*/
            }

            if (currentTable == null) {
                continue;
            }

            DFA dfa = buildDFA(currentTable);
            DTA hypothesis = buildHypothesis(currentTable, dfa);
            System.out.println("DTALearner-learn：当前假设：" + hypothesis);

            Optional<DelayTimedWord> counterexample = teacher.equivalenceQuery(hypothesis);
            System.out.println("DTALearner-learn：当前假设的反例：" + counterexample);
            if (counterexample.isEmpty()) {
                return hypothesis;
            } else {
                List<ObservationTable> newTables = processCounterexample(currentTable, counterexample.get());
                toExplore.addAll(newTables);
            }
        }

        return null;
    }

    private boolean isPrepared(ObservationTable table) {
        return table.isClosed() && table.isConsistent(false) && table.isEvidenceClosed();
    }

    private boolean isClosed(ObservationTable table) {
        return table.isClosed();
    }

    private boolean isConsistent(ObservationTable table) {
        return table.isConsistent();
    }

    private boolean isEvidenceClosed(ObservationTable table) {
        return table.isEvidenceClosed();
    }

    private List<ObservationTable> guessAndClose(ObservationTable table) {
        return table.guessClosing();
    }

    private List<ObservationTable> guessAndConsistent(ObservationTable table) {
        return table.guessConsistency();
    }

    private List<ObservationTable> guessAndEvidenceClosed(ObservationTable table) {
        return table.guessEvidenceClosing();
    }

    private DFA buildDFA(ObservationTable table) {
        return table.buildDFA();
    }

    private DTA buildHypothesis(ObservationTable table, DFA dfa) {
        return table.buildDTA(dfa);
    }

    private List<ObservationTable> processCounterexample(ObservationTable table, DelayTimedWord cex) {
        return table.processCounterexample(cex, teacher.membershipQuery(cex));
    }

    private List<Map<Action, Set<Clock>>> generateAllResetCombinations(
            Map<Action, List<Set<Clock>>> actionResetMap) {
        List<Map<Action, Set<Clock>>> result = new ArrayList<>();
        generateResetCombinationsHelper(
                new HashMap<>(),
                new ArrayList<>(actionResetMap.entrySet()),
                0,
                result
        );
        return result;
    }

    private void generateResetCombinationsHelper(
            Map<Action, Set<Clock>> current,
            List<Map.Entry<Action, List<Set<Clock>>>> entries,
            int index,
            List<Map<Action, Set<Clock>>> result) {
        if (index == entries.size()) {
            result.add(new HashMap<>(current));
            return;
        }

        Map.Entry<Action, List<Set<Clock>>> entry = entries.get(index);
        Action action = entry.getKey();
        List<Set<Clock>> resetOptions = entry.getValue();

        for (Set<Clock> resetSet : resetOptions) {
            current.put(action, resetSet);
            generateResetCombinationsHelper(current, entries, index + 1, result);
            current.remove(action);
        }
    }
}