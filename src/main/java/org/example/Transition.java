package org.example;

import lombok.Getter;
import org.example.constraint.Constraint;

import java.util.Set;

/**
 * @author Ayalyt
 */
@Getter
public final class Transition implements Comparable<Transition> {

    private static final IDgenerator ID_GENERATOR = new IDgenerator();
    private final Location source;
    private final Location target;
    private final Action action;
    private final Constraint guard;
    private final Set<Clock> resets;
    private final int id;

    public Transition(Location source, Action action,
                      Constraint guard, Set<Clock> resets, Location target) {
        this.source = source;
        this.target = target;
        this.action = action;
        this.guard = guard;
        this.resets = Set.copyOf(resets);
        this.id = ID_GENERATOR.createId();
    }


    @Override
    public int compareTo(Transition o) {
        return Integer.compare(this.id, o.id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Transition that = (Transition) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }

    @Override
    public String toString() {
        return "Transition{" +
                "source=" + source +
                ", target=" + target +
                ", action=" + action +
                ", guard=" + guard +
                ", resets=" + resets +
                ", id=" + id +
                '}';
    }
}
