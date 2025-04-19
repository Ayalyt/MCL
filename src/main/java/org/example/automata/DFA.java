package org.example.automata;

import lombok.Getter;
import org.example.*;
import org.example.constraint.Constraint;

import java.util.*;

@Getter
public class DFA {
    private final Alphabet alphabet;
    private Location initialState;
    private final Set<Location> locations;
    private final Set<Location> acceptingLocations;
    private final Set<Transition> transitions;

    private final Map<Location, Map<Action, Location>> transitionMap;

    public DFA(Alphabet alphabet, Location initialState) {
        this.alphabet = alphabet;
        this.initialState = initialState;
        this.locations = new HashSet<>();
        this.acceptingLocations = new HashSet<>();
        this.transitions = new HashSet<>();
        this.transitionMap = new HashMap<>();
        if(initialState != null) {
            addLocation(initialState);
        }

    }

    // ================== 基础管理方法 ==================
    
    public void addLocation(Location loc) {
        if (locations.add(loc)) {
            transitionMap.put(loc, new HashMap<>());
        }
    }

    public void addAcceptingLocation(Location loc) {
        addLocation(loc);
        acceptingLocations.add(loc);
    }

    public void setInitialLocation(Location loc) {
        addLocation(loc);
        this.initialState = loc;
    }

    public boolean isAccepting(Location loc) {
        return acceptingLocations.contains(loc);
    }

    public void addTransition(Transition t) {
        if (!locations.contains(t.getSource()) || !locations.contains(t.getTarget())) {
            throw new IllegalArgumentException("Transition含有未知Location: "+ t.getSource() + "或" + t.getTarget());
        }
        if (!alphabet.contains(t.getAction())) {
            throw new IllegalArgumentException("无效的Action: "+ t.getAction());
        }
        if (transitions.add(t)) {
            transitionMap.get(t.getSource()).put(t.getAction(), t.getTarget());
        }
    }

    // ================== 图结构查询方法 ==================
    public Location getNextLocation(Location current, Action action) {
        return transitionMap.getOrDefault(current, Map.of())
                .get(action);
    }

    public Set<Location> getReachableLocations() {
        Set<Location> reachable = new HashSet<>();
        Queue<Location> queue = new LinkedList<>();
        queue.add(initialState);
        reachable.add(initialState);

        while (!queue.isEmpty()) {
            Location current = queue.poll();
            for (Action action : alphabet.alphabet.values()) {
                Location next = getNextLocation(current, action);
                if (next != null && !reachable.contains(next)) {
                    reachable.add(next);
                    queue.add(next);
                }
            }
        }
        return reachable;
    }

    // ================== DFA核心功能 ==================
    public boolean accepts(List<Action> word) {
        Location current = initialState;
        for (Action action : word) {
            current = getNextLocation(current, action);
            if (current == null) {
                return false;
            }
        }
        return isAccepting(current);
    }

    // ================== 验证方法 ==================
    public boolean isComplete() {
        for (Location loc : locations) {
            for (Action action : alphabet.alphabet.values()) {
                if (getNextLocation(loc, action) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    public DFA toCompleteDFA() {
        DFA complete = new DFA(alphabet, initialState);
        Location sink = complete.addSinkLocation();

        // 复制所有位置
        locations.forEach(loc -> {
            complete.addLocation(loc);
            if (isAccepting(loc)) {
                complete.addAcceptingLocation(loc);
            }
        });

        // 补全转移关系
        for (Location loc : locations) {
            for (Action action : alphabet.alphabet.values()) {
                Location target = getNextLocation(loc, action);
                complete.addTransition(new Transition(
                        loc, 
                        action, 
                        null, // DFA不需要时钟约束
                        Collections.emptySet(), // 无时钟重置
                        target != null ? target : sink
                ));
            }
        }
        return complete;
    }

    private Location addSinkLocation() {
        Location sink = Location.createSink();
        addLocation(sink);
        // 为sink添加自循环
        for (Action action : alphabet.alphabet.values()) {
            addTransition(new Transition(
                    sink, 
                    action, 
                    null,
                    Collections.emptySet(), 
                    sink
            ));
        }
        return sink;
    }

    // ================== 转换方法 ==================
    public static DFA fromDTA(DTA dta) {
        DFA dfa = new DFA(dta.getAlphabet(), dta.getInitialLocation());
        
        // 复制所有位置
        dta.getLocations().forEach(loc -> {
            dfa.addLocation(loc);
            if (dta.isAccepting(loc)) {
                dfa.addAcceptingLocation(loc);
            }
        });

        // 转换转移关系（忽略时钟约束）
        for (Transition t : dta.getTransitions()) {
            dfa.addTransition(new Transition(
                    t.getSource(),
                    t.getAction(),
                    null, // 忽略原有时钟约束
                    Collections.emptySet(), // 忽略时钟重置
                    t.getTarget()
            ));
        }
        return dfa;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DFA:\n");
        sb.append("Alphabet: ").append(alphabet).append("\n");
        sb.append("Initial State: ").append(initialState).append("\n");
        sb.append("Locations: ").append(locations).append("\n");
        sb.append("Accepting Locations: ").append(acceptingLocations).append("\n");
        sb.append("Transitions: ").append(transitions).append("\n");
        return sb.toString();
    }
}
