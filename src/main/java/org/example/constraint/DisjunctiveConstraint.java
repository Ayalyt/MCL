package org.example.constraint;

import lombok.Getter;
import org.example.Clock;
import org.example.ClockValuation;

import java.util.*;
import java.util.stream.Collectors;


/**
 * 表示合取约束的析取 (Disjunctive Normal Form - DNF)。
 * 每个实例关联一个特定的时钟集合。
 *
 * 设计：
 * 1. 持有 `clocks` 字段。
 * 2. `isTrue` 标志：null 表示普通析取，true/false 表示逻辑 TRUE/FALSE。
 * 3. `constraints` 字段：包含的 `Constraint` 实例集合，所有实例共享相同的 `clocks` 集合。
 *    - TRUE 实例包含 {Constraint.trueConstraint(clocks)}。
 *    - FALSE 实例为空集。
 * 4. 操作需要时钟集兼容性检查。
 * 5. `negateDisjoint` 使用实例自身的 `clocks`。
 */
@Getter
public final class DisjunctiveConstraint {

    /** 关联的时钟全集 */
    private final Set<Clock> clocks;

    /** 标记是否为 TRUE (true) 或 FALSE (false)，null 表示普通析取 */
    private final Boolean isTrue;

    /** 包含的合取约束 (Constraint) 集合 (不可变) */
    private final Set<Constraint> constraints;

    // --- 构造函数 ---

    /**
     * 私有构造函数，用于创建 TRUE 或 FALSE 实例。
     * @param isTrue    标记是 TRUE (true) 还是 FALSE (false)。
     * @param allClocks 关联的时钟集合。
     */
    private DisjunctiveConstraint(boolean isTrue, Set<Clock> allClocks) {
        Objects.requireNonNull(allClocks, "DisjunctiveConstraint-构造函数(特殊): 时钟集合不能为 null");
        this.clocks = Set.copyOf(allClocks);
        this.isTrue = isTrue;

        if (isTrue) {
            this.constraints = Set.of(Constraint.trueConstraint(this.clocks));
        } else {
            this.constraints = Collections.emptySet();
        }
    }

    /**
     * 私有构造函数，用于根据给定的合取约束集合创建实例。
     * 会进行规范化处理并检查时钟集兼容性。
     *
     * @param inputConstraints 合取约束的集合。可以为 null 或空。
     * @param allClocks        关联的时钟集合。不允许为 null。
     * @throws NullPointerException     如果 allClocks 为 null。
     * @throws IllegalArgumentException 如果 inputConstraints 中的约束具有与 allClocks 不兼容的时钟集，
     *                                  或者 inputConstraints 为空但 allClocks 不为空（应使用 falseConstraint）。
     */
    private DisjunctiveConstraint(Set<Constraint> inputConstraints, Set<Clock> allClocks) {
        Objects.requireNonNull(allClocks, "DisjunctiveConstraint-构造函数(普通): 时钟集合不能为 null");
        this.clocks = Set.copyOf(allClocks);

        if (inputConstraints == null || inputConstraints.isEmpty()) {
            // 空析取等于 FALSE
            Constraint falseConst = Constraint.falseConstraint(this.clocks);
            this.constraints = Collections.emptySet();
            this.isTrue = falseConst.isTrue(); // isTrue = false
            return;
        }

        Set<Constraint> normalizedConstraints = new HashSet<>();
        boolean containsTrue = false;

        for (Constraint c : inputConstraints) {
            // 检查时钟集兼容性
            if (!this.clocks.equals(c.getClocks())) {
                throw new IllegalArgumentException("DisjunctiveConstraint-构造函数(普通): 输入的 Constraint " + c + " 时钟集与目标时钟集不匹配");
            }
            if (c.isTrue()) {
                containsTrue = true;
                break;
            }
            if (!c.isFalse()) {
                normalizedConstraints.add(c);
            }
        }

        if (containsTrue) {
            Constraint trueConst = Constraint.trueConstraint(this.clocks);
            this.constraints = Set.of(trueConst);
            this.isTrue = trueConst.isTrue(); // isTrue = true
        } else if (normalizedConstraints.isEmpty()) {
            Constraint falseConst = Constraint.falseConstraint(this.clocks);
            this.constraints = Collections.emptySet();
            this.isTrue = falseConst.isTrue(); // isTrue = false
        } else {
            this.constraints = Set.copyOf(normalizedConstraints);
            this.isTrue = null; // 普通析取约束
        }
    }

    // --- 工厂方法 ---

    /**
     * 工厂方法，获取表示逻辑真 (⊤) 的析取约束实例。
     * @param allClocks 关联的时钟集合。
     * @return DisjunctiveConstraint 实例。
     */
    public static DisjunctiveConstraint trueConstraint(Set<Clock> allClocks) {
        return new DisjunctiveConstraint(true, allClocks);
    }

    /**
     * 工厂方法，获取表示逻辑假 (⊥) 的析取约束实例。
     * @param allClocks 关联的时钟集合。
     * @return DisjunctiveConstraint 实例。
     */
    public static DisjunctiveConstraint falseConstraint(Set<Clock> allClocks) {
        return new DisjunctiveConstraint(false, allClocks);
    }

    /**
     * 工厂方法，从合取约束集合创建 DisjunctiveConstraint。
     * @param constraints 合取约束的集合。所有约束必须共享相同的时钟集。
     * @param allClocks   期望的关联时钟集合 (必须与 constraints 中的时钟集匹配)。
     * @return DisjunctiveConstraint 实例。
     * @throws IllegalArgumentException 如果时钟集不匹配或 constraints 为空。
     */
    public static DisjunctiveConstraint of(Set<Constraint> constraints, Set<Clock> allClocks) {
        // 构造函数会处理 null/empty 和规范化及检查
        return new DisjunctiveConstraint(constraints, allClocks);
    }

    /**
     * 工厂方法，从可变参数的合取约束创建 DisjunctiveConstraint。
     * @param allClocks   关联的时钟集合。
     * @param constraints 合取约束。所有约束必须具有与 allClocks 相同的时钟集。
     * @return DisjunctiveConstraint 实例。
     * @throws IllegalArgumentException 如果时钟集不匹配或 constraints 为空。
     */
    public static DisjunctiveConstraint of(Set<Clock> allClocks, Constraint... constraints) {
        if (constraints == null || constraints.length == 0) {
            // 允许创建 False，但需要时钟集
            return DisjunctiveConstraint.falseConstraint(allClocks);
            // throw new IllegalArgumentException("DisjunctiveConstraint-of(varargs): 输入数组不能为空以确定时钟集");
        }
        // 构造函数会检查时钟集
        return new DisjunctiveConstraint(Set.of(constraints), allClocks);
    }

    // --- 核心方法 ---

    /**
     * 检查给定的时钟赋值是否满足此析取约束。
     * @param clockValues 时钟到其 Rational 值的映射。
     * @return 如果赋值满足至少一个合取约束，则返回 true。
     * @throws NullPointerException 如果 clockValues 为 null。
     * @throws IllegalArgumentException 如果 clockValues 缺少关联时钟的值。
     */
    public boolean isSatisfied(ClockValuation clockValues) {
        Objects.requireNonNull(clockValues, "DisjunctiveConstraint-isSatisfied: ClockValuation 不能为 null");

        // 检查 clockValues 是否包含所有关联时钟
        for (Clock clock : this.clocks) {
            if (!clock.isZeroClock() && clockValues.getValue(clock) == null) {
                throw new IllegalArgumentException("DisjunctiveConstraint-isSatisfied: ClockValuation 缺少关联时钟 " + clock.getName() + " 的值");
            }
        }

        if (isTrue()) {
            return true;
        }
        if (isFalse()) {
            return false;
        }

        for (Constraint constraint : constraints) {
            if (constraint.isSatisfied(clockValues)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算当前析取约束与另一个析取约束的逻辑或。
     * (A ∨ B) ∨ (C ∨ D) = A ∨ B ∨ C ∨ D
     * @param other 另一个析取约束。
     * @return 合并后的新 DisjunctiveConstraint 实例。
     * @throws IllegalArgumentException 如果两个约束的关联时钟集不同。
     */
    public DisjunctiveConstraint or(DisjunctiveConstraint other) {
        Objects.requireNonNull(other, "DisjunctiveConstraint-or: 输入的约束不能为 null");
        if (!this.clocks.equals(other.clocks)) {
            throw new IllegalArgumentException("DisjunctiveConstraint-or: 操作数具有不兼容的时钟集合");
        }

        if (this.isTrue() || other.isTrue()) {
            return DisjunctiveConstraint.trueConstraint(this.clocks);
        }
        if (this.isFalse()) {
            return other;
        }
        if (other.isFalse()) {
            return this;
        }

        Set<Constraint> combined = new HashSet<>(this.constraints);
        combined.addAll(other.constraints);
        return DisjunctiveConstraint.of(combined, this.clocks);
    }

    /**
     * 计算多个析取约束的逻辑或。
     * @param disjunctions 要进行析取的约束。必须具有相同的关联时钟集。
     * @return 析取结果。
     * @throws IllegalArgumentException 如果约束列表为空或时钟集不兼容。
     */
    public static DisjunctiveConstraint or(Collection<DisjunctiveConstraint> disjunctions) {
        if (disjunctions == null || disjunctions.isEmpty()) {
            throw new IllegalArgumentException("DisjunctiveConstraint-or(Collection): 输入集合不能为空");
        }
        Set<Clock> commonClocks = null;
        for (DisjunctiveConstraint dc : disjunctions) {
            if (commonClocks == null) {
                commonClocks = dc.getClocks();
            } else if (!commonClocks.equals(dc.getClocks())) {
                throw new IllegalArgumentException("DisjunctiveConstraint-or(Collection): 约束具有不兼容的时钟集合");
            }
        }

        DisjunctiveConstraint result = DisjunctiveConstraint.falseConstraint(commonClocks);
        for(DisjunctiveConstraint dc : disjunctions) {
            result = result.or(dc);
            if (result.isTrue()) {
                return result;
            }
        }
        return result;
    }
    public static DisjunctiveConstraint or(DisjunctiveConstraint... disjunctions) {
        if (disjunctions == null || disjunctions.length == 0) {
            throw new IllegalArgumentException("DisjunctiveConstraint-or(varargs): 输入数组不能为空");
        }
        return or(Arrays.asList(disjunctions));
    }


    /**
     * 计算当前析取约束与另一个析取约束的逻辑与 (分配律)。
     * (A ∨ B) ∧ (C ∨ D) = (A ∧ C) ∨ (A ∧ D) ∨ (B ∧ C) ∨ (B ∧ D)
     * @param other 另一个析取约束。
     * @return 应用分配律后的新 DisjunctiveConstraint 实例。
     * @throws IllegalArgumentException 如果两个约束的关联时钟集不同。
     */
    public DisjunctiveConstraint and(DisjunctiveConstraint other) {
        Objects.requireNonNull(other, "DisjunctiveConstraint-and: 输入的约束不能为 null");
        if (!this.clocks.equals(other.clocks)) {
            throw new IllegalArgumentException("DisjunctiveConstraint-and: 操作数具有不兼容的时钟集合");
        }

        if (this.isFalse() || other.isFalse()) {
            return DisjunctiveConstraint.falseConstraint(this.clocks);
        }
        if (this.isTrue()) {
            return other;
        }
        if (other.isTrue()) {
            return this;
        }

        Set<Constraint> resultingConstraints = new HashSet<>();
        for (Constraint c1 : this.constraints) {
            for (Constraint c2 : other.constraints) {
                Constraint intersection = c1.and(c2); // and 内部检查时钟集
                if (!intersection.isFalse()) {
                    resultingConstraints.add(intersection);
                }
            }
        }
        return DisjunctiveConstraint.of(resultingConstraints, this.clocks);
    }

    /**
     * 计算多个析取约束的逻辑与。
     * @param disjunctions 要进行合取的约束。必须具有相同的关联时钟集。
     * @return 合取结果。
     * @throws IllegalArgumentException 如果约束列表为空或时钟集不兼容。
     */
    public static DisjunctiveConstraint and(Collection<DisjunctiveConstraint> disjunctions) {
        if (disjunctions == null || disjunctions.isEmpty()) {
            throw new IllegalArgumentException("DisjunctiveConstraint-and(Collection): 输入集合不能为空");
        }
        Set<Clock> commonClocks = null;
        for (DisjunctiveConstraint dc : disjunctions) {
            if (commonClocks == null) {
                commonClocks = dc.getClocks();
            } else if (!commonClocks.equals(dc.getClocks())) {
                throw new IllegalArgumentException("DisjunctiveConstraint-and(Collection): 约束具有不兼容的时钟集合");
            }
        }

        DisjunctiveConstraint result = DisjunctiveConstraint.trueConstraint(commonClocks);
        for(DisjunctiveConstraint dc : disjunctions) {
            result = result.and(dc);
            if (result.isFalse()) {
                return result;
            }
        }
        return result;
    }
    public static DisjunctiveConstraint and(DisjunctiveConstraint... disjunctions) {
        if (disjunctions == null || disjunctions.length == 0) {
            throw new IllegalArgumentException("DisjunctiveConstraint-and(varargs): 输入数组不能为空");
        }
        return and(Arrays.asList(disjunctions));
    }


    /**
     * 计算当前析取约束的逻辑非 (德摩根定律)。
     * ¬(C1 ∨ C2 ∨ ... ∨ Cn) = ¬C1 ∧ ¬C2 ∧ ... ∧ ¬Cn
     * 结果仍然是一个析取约束 (DNF)。
     * @return 代表否定的新 DisjunctiveConstraint。
     */
    public DisjunctiveConstraint negate() {
        if (isTrue()) {
            return DisjunctiveConstraint.falseConstraint(this.clocks);
        }
        if (isFalse()) {
            return DisjunctiveConstraint.trueConstraint(this.clocks);
        }

        if (constraints.isEmpty()) {
            return DisjunctiveConstraint.trueConstraint(this.clocks); // ¬⊥ = ⊤
        }

        // 1. 对每个合取项 Ci 取反，得到 ¬Ci (结果是 DisjunctiveConstraint)
        List<DisjunctiveConstraint> negatedConstraints = new ArrayList<>();
        for (Constraint c : this.constraints) {
            DisjunctiveConstraint negatedC = c.negate(); // negate() 返回与 c 相同 clocks 的析取约束
            if (negatedC.isFalse()) {
                return DisjunctiveConstraint.falseConstraint(this.clocks); // ... ∧ ⊥ ∧ ... = ⊥
            }
            if (!negatedC.isTrue()) { // 忽略 TRUE 项
                negatedConstraints.add(negatedC);
            }
        }

        // 2. 将所有非平凡的 ¬Ci AND 起来
        if (negatedConstraints.isEmpty()) { // 如果所有 ¬Ci 都是 TRUE
            return DisjunctiveConstraint.trueConstraint(this.clocks);
        }

        // 使用 DisjunctiveConstraint.and 进行合取，它会处理时钟集检查和分配律
        return DisjunctiveConstraint.and(negatedConstraints);
    }


    /**
     * 计算当前析取约束在关联时钟集下的否定，并确保结果析取范式中的合取项互不相交。
     * 使用迭代减法 (Iterative Subtraction) 方法。
     * 逻辑: Target = Universe ∧ ¬(C1 ∨ ... ∨ Cn)
     * 其中 Universe 是由 this.clocks 定义的全集 (c >= 0 for all c)。
     *
     * @return 代表否定的新的 DisjunctiveConstraint，其合取项互不相交。
     */
    public DisjunctiveConstraint negateDisjoint() {
        // --- 步骤 0: 处理特殊常量情况 ---
        if (isTrue()) {
            return DisjunctiveConstraint.falseConstraint(this.clocks);
        }
        if (isFalse()) {
            return DisjunctiveConstraint.trueConstraint(this.clocks);
        }

        // --- 步骤 1: 计算 SimpleNegation = ¬C1 ∧ ... ∧ ¬Cn ---
        DisjunctiveConstraint simpleNegation = this.negate();

        if (simpleNegation.isTrue()) {
            return DisjunctiveConstraint.trueConstraint(this.clocks);
        }
        if (simpleNegation.isFalse()) {
            return DisjunctiveConstraint.falseConstraint(this.clocks);
        }

        // --- 步骤 2: 获取 Universe Constraint ---
        Constraint universeConstraint = Constraint.trueConstraint(this.clocks);

        // --- 步骤 3: 计算 OverlappingRegion = Universe ∧ SimpleNegation ---
        // 因为 Universe 是 TRUE，OverlappingRegion = TRUE ∧ SimpleNegation = SimpleNegation
        DisjunctiveConstraint overlappingRegion = simpleNegation;

        // 获取构成重叠区域的合取项列表
        List<Constraint> overlappingTerms = new ArrayList<>(overlappingRegion.getConstraints());
        overlappingTerms.sort(Comparator.comparing(Constraint::toString)); // 可选排序

        // --- 步骤 4: 迭代生成不相交项 ---
        Set<Constraint> finalDisjointTerms = new HashSet<>();
        DisjunctiveConstraint accumulatedDisjointRegion = DisjunctiveConstraint.falseConstraint(this.clocks);

        for (Constraint currentOriginalTerm : overlappingTerms) {
            // 计算 Ti' = Ti - Accumulated = Ti ∧ ¬Accumulated
            // minus 和 negate 内部会处理时钟集
            DisjunctiveConstraint disjointPortionOfTi = currentOriginalTerm.toDisjunctiveConstraint().minus(accumulatedDisjointRegion);

            if (!disjointPortionOfTi.isFalse()) {
                finalDisjointTerms.addAll(disjointPortionOfTi.getConstraints());
                accumulatedDisjointRegion = accumulatedDisjointRegion.or(disjointPortionOfTi);
            }
            // if (accumulatedDisjointRegion.isTrue()) break; // 可选优化
        }

        // --- 步骤 5: 返回结果 ---
        return DisjunctiveConstraint.of(finalDisjointTerms, this.clocks);
    }


    /**
     * 计算当前析取约束与另一个析取约束的差集。
     * this - other = this ∧ ¬other
     * @param other 要减去的析取约束。
     * @return 代表差集的新 DisjunctiveConstraint。
     * @throws IllegalArgumentException 如果两个约束的关联时钟集不同。
     */
    public DisjunctiveConstraint minus(DisjunctiveConstraint other) {
        Objects.requireNonNull(other, "DisjunctiveConstraint-minus: 输入的约束不能为 null");
        if (!this.clocks.equals(other.clocks)) {
            throw new IllegalArgumentException("DisjunctiveConstraint-minus: 操作数具有不兼容的时钟集合");
        }

        if (this.isFalse() || other.isTrue()) {
            return DisjunctiveConstraint.falseConstraint(this.clocks);
        }
        if (other.isFalse()) {
            return this;
        }
        if (this.isTrue()) {
            return other.negate();
        }

        DisjunctiveConstraint negatedOther = other.negate();

        if (negatedOther.isFalse()) {
            return DisjunctiveConstraint.falseConstraint(this.clocks);
        }
        if (negatedOther.isTrue()) {
            return this;
        }

        return this.and(negatedOther); // and 内部检查时钟集
    }

    /** 检查是否为 TRUE 实例 */
    public boolean isTrue() { return Boolean.TRUE.equals(isTrue); }
    /** 检查是否为 FALSE 实例 */
    public boolean isFalse() { return Boolean.FALSE.equals(isTrue); }


    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DisjunctiveConstraint that = (DisjunctiveConstraint) o;
        // 必须时钟集相同，状态相同，且合取项集合相同
        return Objects.equals(clocks, that.clocks) &&
                Objects.equals(isTrue, that.isTrue) &&
                constraints.equals(that.constraints); // Set.equals 依赖 Constraint.equals
    }

    @Override
    public int hashCode() {
        // 基于时钟集、状态和合取项集合计算哈希码
        return Objects.hash(clocks, isTrue, constraints); // Set.hashCode 依赖 Constraint.hashCode
    }

    @Override
    public String toString() {
        if (isFalse() || constraints.isEmpty()) {
            return "⊥";
        }

        // 对合取项排序
        List<String> constraintStrings = constraints.stream()
                .map(Constraint::toString)
                .sorted()
                .toList();

        // 用圆括号括起每个合取项（如果它包含多个原子约束）
        List<String> formattedStrings = constraintStrings.stream()
                .map(s -> s.contains("∧") ? "(" + s + ")" : s)
                .toList();

        return String.join(" ∨ ", formattedStrings);
    }
}