package org.example;

import lombok.Getter;

/**
 * 表示定时自动机（DTA）中的一个状态位置
 * @author Ayalyt
 */
@Getter
public final class Location {
    private static final IDgenerator ID_GENERATOR = new IDgenerator();
    private final Integer id;
    private final String label;
    private final boolean isSink;

    public Location() {
        this(false);
    }

    private Location(boolean isSink) {
        this.id = ID_GENERATOR.createId();
        this.isSink = isSink;
        if (isSink){
            this.label = "sink";
        } else {
            this.label = "L" + id;
        }
    }

    public Location(String label) {
        this.id = ID_GENERATOR.createId();
        this.isSink = false;
        this.label = label;
    }

    public static Location createSink() {
        return new Location(true);
    }

    public boolean isSink() {
        return isSink;
    }

    public Location copy() {
        return new Location(this.isSink);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Location location = (Location) o;
        return id.equals(location.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return String.format(label);
    }
}