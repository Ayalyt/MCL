package org.example;

import java.util.*;

import lombok.Getter;
import lombok.Setter;


/**
 * @author Ayalyt
 */
public final class ClockConfiguration {
    @Getter
    private Map<Clock, Integer> clockKappas; // 时钟 → 最大常量κ
    @Getter
    @Setter
    private Map<String, Clock> clockName; // 时钟名 → 时钟

    private int hashCode;

    public ClockConfiguration(Map<Clock, Integer> kappaMapping) {
        this.clockKappas = new HashMap<>(kappaMapping);
        this.clockName = new HashMap<>();
        for (Map.Entry<Clock, Integer> entry : kappaMapping.entrySet()) {
            clockName.put(entry.getKey().getName(), entry.getKey());
        }
        this.hashCode = Objects.hash(clockKappas);
    }

    public void updateClockConfig(Map<String, Clock> clocks){
        Map<Clock, Integer> newClockKappas = new HashMap<>();
        for(Map.Entry<Clock, Integer> entry: clockKappas.entrySet()){
            if(entry.getKey().getName().equals(clocks.get(entry.getKey().getName()).getName())){
                newClockKappas.put(clocks.get(entry.getKey().getName()), entry.getValue());
            }
            else{
                throw new IllegalArgumentException("clock" + entry.getKey().getName() + "的上界未定义。存在修改风险，请检查当前config");
            }
        }
        this.clockKappas = newClockKappas;
        this.clockName = clocks;
        this.hashCode = Objects.hash(clockKappas);
    }

    public int getClockKappa(Clock clock) {
        return Optional.ofNullable(clockKappas.get(clock))
                .orElseThrow(() -> new NoSuchElementException("clock" + clock + "的上界未定义"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ClockConfiguration that = (ClockConfiguration) o;
        return Objects.equals(clockKappas, that.clockKappas);
    }

    public void updateKappa(Map<Clock, Integer> clockKappas) {
        this.clockKappas = new HashMap<>(clockKappas);
        this.hashCode = Objects.hash(clockKappas);
    }


    @Override
    public int hashCode() { return hashCode; }
    public Collection<Clock> getClocks() {
        return clockKappas.keySet().stream().toList();
    }

}