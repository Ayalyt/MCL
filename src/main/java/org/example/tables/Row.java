package org.example.tables;

import lombok.Getter;
import org.apache.commons.lang3.tuple.Pair;
import org.example.Clock;
import org.example.words.RegionTimedWord;

import java.util.*;
import java.util.stream.Collectors;

@Getter
class Row {
    private final Map<RegionTimedWord, Boolean> fValues;
    private final Map<RegionTimedWord, List<Set<Clock>>> gValues;

    public Row(Map<RegionTimedWord, Boolean> fValues, Map<RegionTimedWord, List<Set<Clock>>> gValues) {
        this.fValues = new HashMap<>(fValues);
        this.gValues = new HashMap<>(gValues);
    }
    public Row(Row other) {
        this.fValues = new HashMap<>(other.fValues);
        this.gValues = new HashMap<>();
        for (Map.Entry<RegionTimedWord, List<Set<Clock>>> entry : other.gValues.entrySet()) {
            this.gValues.put(
                    new RegionTimedWord(entry.getKey().getTimedActions()),
                    new ArrayList<>(entry.getValue())
            );
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Row row = (Row) o;
        if (!Objects.equals(fValues.size(), row.fValues.size()) || !fValues.entrySet().stream().allMatch(e -> Objects.equals(e.getValue(), row.fValues.get(e.getKey())))) {
            return false;
        }
        return Objects.equals(gValues.size(), row.gValues.size()) && gValues.entrySet().stream().allMatch(e -> {
            List<Set<Clock>> thisList = e.getValue();
            List<Set<Clock>> otherList = row.gValues.get(e.getKey());
            return Objects.equals(thisList, otherList);
        });
    }

    @Override
    public int hashCode() {
        int gHash = gValues.entrySet().stream()
                .mapToInt(entry -> Objects.hash(entry.getKey(), entry.getValue()))
                .sum();
        return Objects.hash(fValues, gHash);
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Row{");
        sb.append("f=").append(fValues);
        sb.append(", g=").append(gValues.entrySet().stream()
                .map(e -> e.getKey() + "->" + e.getValue())
                .collect(Collectors.joining(", ", "{", "}")));
        sb.append('}');
        return sb.toString();
    }
}
