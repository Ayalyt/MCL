// src/main/java/org/example/serialization/Serializer.java
package org.example.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.commons.lang3.tuple.Pair;
import org.example.*;
import org.example.automata.DTA;
import org.example.constraint.AtomConstraint;
import org.example.constraint.Constraint;
import org.example.serialization.model.DTAFormat;
import org.example.serialization.model.LocationFormat;
import org.example.serialization.model.TransitionFormat;
import org.example.utils.Rational;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Ayalyt
 */
public class Serializer {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static void save(DTA dta, String filePath) throws IOException {
        DTAFormat format = convertToFormat(dta);
        objectMapper.writeValue(new File(filePath), format);
    }

    public static DTA load(String filePath) throws IOException {
        DTAFormat format = objectMapper.readValue(new File(filePath), DTAFormat.class);
        DTA dta = convertFromFormat(format);
        boolean isComplete = dta.isComplete();
        return isComplete ? dta : dta.toCTA();
    }

    // --- DTA -> DTAFormat ---
    private static DTAFormat convertToFormat(DTA dta) {
        DTAFormat format = new DTAFormat();

        // Clocks
        List<String> clockNames = dta.getClocks().stream()
                .map(Clock::getName)
                .sorted()
                .collect(Collectors.toList());
        format.setClocks(clockNames);
        // Actions
        List<String> actionNames = dta.getAlphabet().alphabet.values().stream()
                .map(Action::getAction)
                .sorted()
                .collect(Collectors.toList());
        format.setActions(actionNames);

        // Locations
        Map<Location, String> locationNameMap = dta.getLocations().stream()
                .collect(Collectors.toMap(loc -> loc, Location::getLabel));

        List<LocationFormat> locationFormats = dta.getLocations().stream()
                .map(loc -> {
                    LocationFormat lf = new LocationFormat();
                    lf.setName(locationNameMap.get(loc));
                    lf.setAccepting(dta.isAccepting(loc));
                    // lf.setInvariant(Collections.emptyMap());
                    return lf;
                })
                .sorted(Comparator.comparing(LocationFormat::getName))
                .collect(Collectors.toList());
        format.setLocations(locationFormats);

        // Initial Location
        format.setInitLocation(locationNameMap.get(dta.getInitialLocation()));

        List<TransitionFormat> transitionFormats = dta.getTransitions().stream()
                .map(t -> {
                    TransitionFormat tf = new TransitionFormat();
                    tf.setSource(locationNameMap.get(t.getSource()));
                    tf.setAction(t.getAction().getAction());
                    tf.setGuard(formatGuard(t.getGuard(), dta.getClocks()));
                    tf.setReset(t.getResets().stream()
                            .map(Clock::getName)
                            .sorted()
                            .collect(Collectors.toList()));
                    tf.setTarget(locationNameMap.get(t.getTarget()));
                    return tf;
                })
                .sorted(Comparator.comparing(TransitionFormat::getSource)
                        .thenComparing(TransitionFormat::getAction)
                        .thenComparing(TransitionFormat::getTarget))
                .collect(Collectors.toList());
        format.setTransitions(transitionFormats);

        format.setName(dta.getLocations().size() + "_" +
                dta.getAlphabet().alphabet.size() + "_" +
                dta.getTransitions().size() + "_" +
                dta.getClocks().size() + "_export");

        return format;
    }

    // --- DTAFormat -> DTA ---
    private static DTA convertFromFormat(DTAFormat format) {
        // 1. 创建 Clocks
        Map<String, Clock> clockMap = new HashMap<>();
        Set<Clock> clocks = new HashSet<>();
        for (String clockName : format.getClocks()) {
            Clock clock = new Clock(clockName);
            clocks.add(clock);
            clockMap.put(clockName, clock);
        }

        // 2. Actions, 填充Alphabet
        Alphabet alphabet = new Alphabet();
        Map<String, Action> actionMap = new HashMap<>();
        for (String actionName : format.getActions()) {
            Action action = alphabet.createAction(actionName);
            actionMap.put(actionName, action);
        }

        // 3. Location
        Map<String, Location> locationMap = new HashMap<>();
        Set<Location> acceptingLocations = new HashSet<>();
        Location initialLocation = null;
        Map<Location, Map<String, String>> locationInvariantMaps = new HashMap<>();

        for (LocationFormat lf : format.getLocations()) {
            Location loc = new Location(lf.getName());
            locationMap.put(lf.getName(), loc);
            if (lf.isAccepting()) {
                acceptingLocations.add(loc);
            }
            if (lf.getName().equals(format.getInitLocation())) {
                initialLocation = loc;
            }
            if (lf.getInvariant() != null && !lf.getInvariant().isEmpty()) {
                locationInvariantMaps.put(loc, lf.getInvariant());
            }
        }
        if (initialLocation == null) {
            throw new IllegalArgumentException("Initial location " + format.getInitLocation() + "不存在");
        }

        // 收集所有约束中出现的整数常量，以确定 kappa
        Map<Clock, Integer> clockKappas = new HashMap<>();
        for (Clock clock : clocks) {
            clockKappas.put(clock, 0);
        }

        // 4. 解析 Location 不变量并更新 kappa
        Map<Location, Constraint> locationInvariants = new HashMap<>();
        for (Map.Entry<Location, Map<String, String>> entry : locationInvariantMaps.entrySet()) {
            Location loc = entry.getKey();
            Map<String, String> invMap = entry.getValue();
            Constraint invariant = parseGuardAndUpdateKappa(invMap, clockMap, clockKappas);
            locationInvariants.put(loc, invariant);
        }

        // 5. Transitions, 解析guard并更新kappa
        Set<Transition> transitions = new HashSet<>();
        for (TransitionFormat tf : format.getTransitions()) {
            Location source = locationMap.get(tf.getSource());
            Location target = locationMap.get(tf.getTarget());
            Action action = actionMap.get(tf.getAction());
            Set<Clock> resets = tf.getReset().stream()
                    .map(clockMap::get)
                    .collect(Collectors.toSet());

            if (source == null || target == null || action == null) {
                throw new IllegalArgumentException("无效的转换" + tf);
            }

            Constraint guard = parseGuardAndUpdateKappa(tf.getGuard(), clockMap, clockKappas);

            Transition transition = new Transition(source, action, guard, resets, target);
            transitions.add(transition);
        }

        // 6. ClockConfiguration
        ClockConfiguration configuration = new ClockConfiguration(clockKappas);

        // 7. DTA
        DTA dta = new DTA(alphabet, clocks, initialLocation, configuration);

        // 8. Locations (包括 accepting 和设置不变量)
        for (Location loc : locationMap.values()) {
            dta.addLocation(loc);
        }
        for (Location accLoc : acceptingLocations) {
            dta.addAcceptingLocation(accLoc);
        }
        dta.setInitialLocation(initialLocation);

        // 9. Transitions
        transitions.forEach(dta::addTransition);

        return dta;
    }

    /**
     * 解析guard Map (形如 {"c1": "[0, 5)", "c2": "(1, +)"}) 为 Constraint 对象,
     * 并更新时钟的最大整数常量 (kappa).
     *
     * @param guardMap    guard的 Map 表示.
     * @param clockMap    时钟名称到 Clock 对象的映射.
     * @param clockKappas 用于累积每个时钟遇到的最大整数常量的 Map.
     * @return 解析得到的 Constraint 对象 (原子约束的合取).
     * @throws IllegalArgumentException 如果格式无效或时钟未知.
     */
    private static Constraint parseGuardAndUpdateKappa(
            Map<String, String> guardMap,
            Map<String, Clock> clockMap,
            Map<Clock, Integer> clockKappas) {

        Set<AtomConstraint> constraints = new HashSet<>();

        for (Map.Entry<String, String> entry : guardMap.entrySet()) {
            String clockName = entry.getKey();
            String interval = entry.getValue().trim();
            Clock clock = clockMap.get(clockName);
            if (clock == null) {
                throw new IllegalArgumentException("Serializer: 未知的时钟：" + clockName);
            }

            // 移除首尾空格
            interval = interval.trim();
            if (interval.isEmpty()) {
                continue;
            }

            // 解析开闭括号
            boolean lowerClosed = interval.startsWith("[");
            boolean upperClosed = interval.endsWith("]");
            if (!interval.startsWith("(") && !lowerClosed) {
                throw new IllegalArgumentException("Serializer: 无效区间格式(start): " + entry.getValue());
            }
            if (!interval.endsWith(")") && !upperClosed) {
                throw new IllegalArgumentException("Serializer: 无效区间格式(end): " + entry.getValue());
            }

            // 移除括号并按逗号分割
            String content = interval.substring(1, interval.length() - 1).trim();
            String[] parts = content.split(",", 2); // 最多分割成两部分
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "Serializer: 无效区间格式(comma): " + entry.getValue());
            }

            String lowerBoundStr = parts[0].trim();
            String upperBoundStr = parts[1].trim();

            // --- 处理下界 ---
            if (!lowerBoundStr.isEmpty() && !"-".equals(lowerBoundStr)) { // -inf
                try {
                    if ("+".equals(lowerBoundStr)) {
                        throw new IllegalArgumentException("Serializer: 下界不能是'+' (infinity)，clock " + clockName);
                    }
                    Rational lowerBound = Rational.valueOf(lowerBoundStr);

                    // 更新
                    if (lowerBound.isInteger()) {
                        try {
                            int intValue = lowerBound.getNumerator().intValueExact(); // 确保不会溢出 int
                            clockKappas.merge(clock, intValue, Integer::max);
                        } catch (ArithmeticException e) {
                            // 如果 BigInteger 值太大无法放入 int，可以选择忽略或抛出错误
                            System.err.println("Serializer: BigInteger 值太大无法放入 int: " + lowerBound.getNumerator() + ", clock: " + clockName);
                        }
                    }

                    // 添加约束: c >= L 或 c > L
                    if (lowerClosed) { // c >= L  (等价于 x0 - c <= -L)
                        constraints.add(AtomConstraint.greaterEqual(clock, lowerBound));
                    } else { // c > L (等价于 x0 - c < -L)
                        constraints.add(AtomConstraint.greaterThan(clock, lowerBound));
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Invalid lower bound number format for clock " + clockName + ": " + lowerBoundStr, e);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "Invalid lower bound value for clock " + clockName + ": " + lowerBoundStr, e);
                }
            }

            // --- 处理上界 ---
            if (!upperBoundStr.isEmpty() && !"+".equals(upperBoundStr)) { // "+" 表示无上界 (+inf)
                try {
                    if ("-".equals(upperBoundStr)) {
                        throw new IllegalArgumentException("Serializer: 上界不能是'-' (-infinity)，clock " + clockName);
                    }
                    // 假设 Rational.valueOf 可以处理数字和分数
                    Rational upperBound = Rational.valueOf(upperBoundStr);

                    // 更新 kappa (仅对整数常量)
                    if (upperBound.isInteger()) {
                        try {
                            int intValue = upperBound.getNumerator().intValueExact();
                            clockKappas.merge(clock, intValue, Integer::max);
                        } catch (ArithmeticException e) {
                            System.err.println("Serializer: BigInteger 值太大无法放入 int: " + upperBound.getNumerator() + ", clock: " + clockName);
                        }
                    }

                    // 添加约束: c <= U 或 c < U
                    if (upperClosed) { //  c - x0 <= U
                        constraints.add(AtomConstraint.lessEqual(clock, upperBound));
                    } else { // c - x0 < U
                        constraints.add(AtomConstraint.lessThan(clock, upperBound));
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "clock " + clockName + "的上界形式非法: " + upperBoundStr, e);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "clock " + clockName + "上界值非法: " + upperBoundStr, e);
                }
            }
        }
        Set<Clock> set = new HashSet<>(clockMap.values());
        return new Constraint(constraints, set);
    }


    // --- 格式化 Guard 为 Map ---
    /**
     * 将 Constraint 对象格式化为 Map<String, String> 的区间表示.
     * 此方法仅能正确转换由 `c op K` 或 `K op c` (即 `c - x0 op V` 或 `x0 - c op V`)
     * 形式的原子约束构成的 Constraint. 它会忽略所有差分约束 (`c1 - c2 op V`).
     * 如果 Constraint 包含差分约束，则输出的 Map 会丢失这些信息.
     *
     * @param guard      要格式化的 Constraint 对象.
     * @param allClocks DTA 中的所有时钟集合 (用于初始化界限).
     * @return 表示区间约束的 Map, key 是时钟名称, value 是区间字符串 (如 "[0, 5)").
     */
    private static Map<String, String> formatGuard(Constraint guard, Set<Clock> allClocks) {
        if (guard == null || guard.isTrue()) {
            return Collections.emptyMap();
        }
        if (guard.isFalse()) {
            throw new IllegalArgumentException("无法在区间表里面加入false");
        }

        Map<String, Pair<Bound, Bound>> clockBounds = new HashMap<>();

        // 初始化所有时钟的界限为 (-inf, +inf)
        for (Clock c : allClocks) {
            clockBounds.put(c.getName(), Pair.of(
                    new Bound(Rational.NEG_INFINITY, false), // Lower bound: (-inf, open
                    new Bound(Rational.INFINITY, false)      // Upper bound: +inf), open
            ));
        }

        // 从约束中提取单时钟界限
        for (AtomConstraint ac : guard.getConstraints()) {
            Clock c1 = ac.getClock1();
            Clock c2 = ac.getClock2();
            Rational value = ac.getUpperbound();
            boolean closed = ac.isClosed();

            String targetClockName = null;
            Bound currentLower = null;
            Bound currentUpper = null;
            Bound newBound = null;

            // Case 1: c - x0 op V  (Upper bound for c)
            if (!c1.isZeroClock() && c2.isZeroClock()) {
                targetClockName = c1.getName();
                currentUpper = clockBounds.get(targetClockName).getRight();
                newBound = new Bound(value, closed);
                // 更新上界 (取更严格的)
                if (newBound.isStricterUpper(currentUpper)) {
                    clockBounds.put(targetClockName, Pair.of(clockBounds.get(targetClockName).getLeft(), newBound));
                }
            }
            else if (c1.isZeroClock() && !c2.isZeroClock()) {
                targetClockName = c2.getName();
                currentLower = clockBounds.get(targetClockName).getLeft();
                Rational lowerVal = value.negate(); // Lower bound is -V
                // closed (<= V) 对应 >= -V (closed lower)
                // !closed (< V) 对应 > -V (open lower)
                newBound = new Bound(lowerVal, closed);
                // 更新下界
                if (newBound.isStricterLower(currentLower)) {
                    clockBounds.put(targetClockName, Pair.of(newBound, clockBounds.get(targetClockName).getRight()));
                }
            }
            else if (!c1.isZeroClock() && !c2.isZeroClock()) {
                System.err.println("Serializer: 差分约束被互联: " + ac);
            }
        }

        // 格式化为字符串 Map
        Map<String, String> guardMap = new HashMap<>();
        for (Map.Entry<String, Pair<Bound, Bound>> entry : clockBounds.entrySet()) {
            String clockName = entry.getKey();
            Bound lower = entry.getValue().getLeft();
            Bound upper = entry.getValue().getRight();

            // 只有当界限不是 (-inf, +inf) 时才添加到 map
            boolean isDefaultNonNegative = lower.value.equals(Rational.NEG_INFINITY) && upper.value.equals(Rational.INFINITY);
            // 另一种可能的默认是 [0, +inf)
            boolean isExplicitlyZeroNonNegative = lower.value.equals(Rational.ZERO) && lower.closed && upper.value.equals(Rational.INFINITY);

            // 如果界限不是 (-inf, +inf)，就输出它。
            // 如果需要像 UPPAAL 那样默认 [0, +inf)，则需要调整此逻辑。
            // 暂时只有非 (-inf, +inf) 的才输出
            if (isDefaultNonNegative) {
                // guardMap.put(clockName, "[0,+)");
                continue;
            }

            StringBuilder sb = new StringBuilder();
            // 下界
            sb.append(lower.closed ? "[" : "(");
            if (lower.value.equals(Rational.NEG_INFINITY)) {
                // 暂时用 "0" 作为非负假设下的表示
                sb.append("0");
            } else {
                sb.append(lower.value);
            }
            sb.append(",");
            // 上界
            if (upper.value.equals(Rational.INFINITY)) {
                sb.append("+"); // 使用 "+" 表示 infinity
            } else {
                sb.append(upper.value);
            }
            sb.append(upper.closed ? "]" : ")");

            guardMap.put(clockName, sb.toString());
        }

        return guardMap;
    }

    // 辅助，表示界限
    private static class Bound {
        Rational value;
        boolean closed;

        Bound(Rational value, boolean closed) {
            this.value = value;
            this.closed = closed;
        }

        // 是否比现有的上界更严格
        boolean isStricterUpper(Bound existing) {
            int cmp = this.value.compareTo(existing.value);
            if (cmp < 0) {
                return true; // new value is smaller
            }
            // Values are equal, open bound is stricter than closed bound
            if (cmp == 0 && !this.closed && existing.closed) {
                return true;
            }
            return false;
        }

        // 是否比现有的下界更严格
        boolean isStricterLower(Bound existing) {
            int cmp = this.value.compareTo(existing.value);
            if (cmp > 0) {
                return true;
            }
            return cmp == 0 && !this.closed && existing.closed;
        }

        @Override
        public String toString() {
            return (closed ? "[" : "(") + value + (closed ? "]" : ")");
        }
    }
}
