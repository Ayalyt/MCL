package org.example.automata;


import lombok.Getter;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.example.*;
import org.example.region.Region;
import org.example.utils.Rational;
import org.example.words.DelayTimedWord;
import org.example.words.ResetClockTimedWord;
import org.example.words.ResetDelayTimedWord;
import org.example.words.ResetRegionTimedWord;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 确定性时间自动机运行时引擎
 * 职责：管理DTA的执行状态、处理时间推进和事件分发
 * @author Ayalyt
 */
public class DTARuntime {
    private final DTA dta;
    @Getter
    private Location currentLocation;

    private ClockValuation currentValuation;
    private final Deque<Snapshot> historyStack = new ArrayDeque<>();
    private final List<RuntimeListener> listeners = new CopyOnWriteArrayList<>();

    //==================== 构造与基础状态 ====================
    public DTARuntime(DTA dta) {
        this.dta = Objects.requireNonNull(dta);
        this.currentLocation = dta.getInitialLocation();
        this.currentValuation = ClockValuation.zero(dta.getClocks());
        reset();
    }

    public void reset() {
        this.currentLocation = dta.getInitialLocation();
        this.currentValuation = this.currentValuation.reset(dta.getClocks());
        fireEvent(EventType.RESET);
    }

    private StepResult step(Action action, ClockValuation valuation, Set<Clock> expectedResets) {
        // 检查是否存在对应动作的迁移
        List<Transition> transitions = dta.getTransitions(currentLocation, action);
        if (transitions.isEmpty()) {
            return StepResult.rejected(currentLocation, valuation, "没有这样的转换");
        }

        // 检查是否有guard条件满足的迁移
        List<Transition> guardSatisfied = transitions.stream()
                .filter(t -> t.getGuard().isSatisfied(valuation))
                .toList();
        if (guardSatisfied.isEmpty()) {
            return StepResult.rejected(currentLocation, valuation, "没有可被满足的guard");
        }

        // 检查重置集合是否匹配
        List<Transition> resetMatched = guardSatisfied.stream()
                .filter(t -> t.getResets().equals(expectedResets))
                .toList();
        if (resetMatched.isEmpty()) {
            return StepResult.rejected(currentLocation, valuation, "重置信息不匹配");
        }

        // 执行迁移...
        Transition t = resetMatched.get(0);
        currentValuation = valuation.reset(t.getResets());
        currentLocation = t.getTarget();
        return StepResult.accepted(t, currentLocation, currentValuation);
    }

    public AcceptResult execute(DelayTimedWord word) {
        reset();
        List<StepResult> steps = new ArrayList<>();

        ClockValuation currentVal = currentValuation.copy();
        Location currentLoc = currentLocation;

        for (Pair<Action, Rational> timedAction : word.getTimedActions()) {
            Action action = timedAction.getLeft();
            Rational delay = timedAction.getRight();

            // 推进时间
            currentVal = currentVal.delay(delay);

            // 获取当前状态下所有可能的转移
            List<Transition> transitions = dta.getTransitions(currentLoc, action);
            if (transitions.isEmpty()) {
                StepResult result = StepResult.rejected(currentLoc, currentVal, "没有对应的转移");
                steps.add(result);
                return new AcceptResult(steps, false);
            }

            // 筛选出满足guard条件的转移
            ClockValuation finalCurrentVal = currentVal;
            List<Transition> validTransitions = transitions.stream()
                    .filter(t -> t.getGuard().isSatisfied(finalCurrentVal))
                    .toList();

            if (validTransitions.isEmpty()) {
                StepResult result = StepResult.rejected(currentLoc, currentVal, "没有满足guard条件的转移");
                steps.add(result);
                return new AcceptResult(steps, false);
            }

            // 确保DTA的确定性
            if (validTransitions.size() > 1) {
                StepResult result = StepResult.rejected(currentLoc, currentVal, "存在多个满足条件的转移，DTA应为确定性");
                steps.add(result);
                return new AcceptResult(steps, false);
            }

            Transition t = validTransitions.get(0);
            // 应用重置操作
            ClockValuation newVal = currentVal.reset(t.getResets());
            Location newLoc = t.getTarget();

            // 记录步骤结果
            StepResult step = StepResult.accepted(t, newLoc, newVal);
            steps.add(step);

            // 更新当前状态和时钟评估
            currentLoc = newLoc;
            currentVal = newVal;
        }

        // 检查最终状态是否接受
        boolean accepted = dta.isAccepting(currentLoc);
        return new AcceptResult(steps, accepted);
    }

    public AcceptResult execute(ResetDelayTimedWord word) {
        reset();
        List<StepResult> steps = new ArrayList<>();

        ClockValuation tempValuation = currentValuation.copy();
        for (Triple<Action, Rational, Set<Clock>> timedAction : word.getTimedActions()) {
            tempValuation = tempValuation.delay(timedAction.getMiddle());
            StepResult result = step(timedAction.getLeft(), tempValuation, timedAction.getRight());
            steps.add(result);
            if (!result.isAccepted()) {
                break;
            }
        }

        return new AcceptResult(steps, dta.isAccepting(currentLocation));
    }

    public AcceptResult execute(ResetClockTimedWord word) {
        reset();
        List<StepResult> steps = new ArrayList<>();

        for (Triple<Action, ClockValuation, Set<Clock>> clockAction : word.getTimedActions()) {
            StepResult result = step(clockAction.getLeft(), clockAction.getMiddle(), clockAction.getRight());
            steps.add(result);
            if (!result.isAccepted()) {
                break;
            }
        }

        return new AcceptResult(steps, dta.isAccepting(currentLocation));
    }

    public AcceptResult execute(ResetRegionTimedWord word) {
        reset();
        List<StepResult> steps = new ArrayList<>();

        for (Triple<Action, Region, Set<Clock>> regionAction : word.getTimedActions()) {
            Optional<ClockValuation> sample = Optional.of(regionAction.getMiddle().buildValuation());

            StepResult result = step(regionAction.getLeft(), sample.get(), regionAction.getRight());
            steps.add(result);
            if (!result.isAccepted()) {
                break;
            }
        }

        return new AcceptResult(steps, dta.isAccepting(currentLocation));
    }

    //==================== 状态控制 ====================
    public void saveSnapshot() {
        historyStack.push(new Snapshot(
                currentLocation,
                currentValuation.copy()
        ));
        fireEvent(EventType.SNAPSHOT_CREATED);
    }

    public void restoreLastSnapshot() {
        if (historyStack.isEmpty()) {
            return;
        }

        Snapshot snap = historyStack.pop();
        this.currentLocation = snap.location;
        this.currentValuation = snap.valuation.copy();
        fireEvent(EventType.SNAPSHOT_RESTORED);
    }

    //==================== 状态查询 ====================

    public ClockValuation getCurrentValuation() {
        return currentValuation.copy();
    }

    public boolean isInAcceptingState() {
        return dta.isAccepting(currentLocation);
    }

    //==================== 事件监听 ====================
    public void addListener(RuntimeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(RuntimeListener listener) {
        listeners.remove(listener);
    }

    private void fireEvent(EventType type, Object... data) {
        RuntimeEvent event = new RuntimeEvent(this, type, data);
        listeners.forEach(l -> l.onEvent(event));
    }

    //==================== 内部类定义 ====================
    public enum EventType {
        STEP, RESET, COMPLETE, SNAPSHOT_CREATED, SNAPSHOT_RESTORED
    }

    public interface RuntimeListener {
        void onEvent(RuntimeEvent event);
    }

    public static class RuntimeEvent {
        public final DTARuntime source;
        public final EventType type;
        public final Object[] data;

        public RuntimeEvent(DTARuntime source, EventType type, Object... data) {
            this.source = source;
            this.type = type;
            this.data = data;
        }
    }

    public static final class StepResult {
        private final Transition transition;
        private final Location location;
        private final ClockValuation valuation;
        @Getter
        private final boolean accepted;
        @Getter
        private final String reason;

        // 构造器私有化，使用静态工厂方法
        private StepResult(Transition transition, Location location,
                           ClockValuation valuation, boolean accepted, String reason) {
            this.transition = transition;
            this.location = location;
            this.valuation = valuation;
            this.accepted = accepted;
            this.reason = reason;
        }

        public static StepResult accepted(Transition t, Location loc, ClockValuation v) {
            return new StepResult(t, loc, v, true, null);
        }

        public static StepResult rejected(Location loc, ClockValuation v, String reason) {
            return new StepResult(null, loc, v, false, reason);
        }
    }

    @Getter
    public static final class AcceptResult {
        private final List<StepResult> steps;
        private final boolean accepted;

        public AcceptResult(List<StepResult> steps, boolean accepted) {
            this.steps = Collections.unmodifiableList(steps);
            this.accepted = accepted;
        }

        public Optional<String> getFirstRejectReason() {
            return steps.stream()
                    .filter(s -> !s.isAccepted())
                    .map(StepResult::getReason)
                    .findFirst();
        }
    }

    private record Snapshot(Location location, ClockValuation valuation) {}
}