package org.example.constraint;

import lombok.Getter;
import org.example.Clock;
import org.example.ClockValuation;
import org.example.utils.Rational;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 表示原子约束的合取 (Conjunction)。
 * 每个实例都关联一个特定的时钟集合，并隐式包含对该集合中所有时钟的约束。
 *
 * 设计：
 * 1. 持有 `clocks` 字段，表示关联的时钟全集。
 * 2. `isTrue` 标志：null 表示普通约束，true/false 表示逻辑 TRUE/FALSE。
 * 3. `constraints` 字段：
 *    - 对于普通约束，包含所有显式原子约束及为未提及的关联时钟添加的默认 `c >= 0` 约束。
 *    - 对于 TRUE 约束，包含所有关联时钟的 `c >= 0` 约束。
 *    - 对于 FALSE 约束，包含所有关联时钟的 `c < 0` 约束（作为不可满足的代表）。
 * 4. 操作需要时钟集兼容性检查。
 */
@Getter
public class Constraint {

    /** 关联的时钟全集 */
    private final Set<Clock> clocks;

    /** 标记是否为 TRUE (true) 或 FALSE (false)，null 表示普通约束 */
    private final Boolean isTrue;

    /**
     * 包含的原子约束集合 (不可变)。
     * 对于 TRUE/FALSE，包含根据 clocks 生成的代表性原子约束。
     */
    private final Set<AtomConstraint> constraints;

    // --- 构造函数 ---

    /**
     * 私有构造函数，用于创建 TRUE 或 FALSE 实例。
     *
     * @param isTrue    标记是 TRUE (true) 还是 FALSE (false)。
     * @param allClocks 关联的时钟集合。不允许为 null 或空。
     */
    private Constraint(boolean isTrue, Set<Clock> allClocks) {
        Objects.requireNonNull(allClocks, "Constraint-构造函数(特殊): 时钟集合不能为 null");
        this.clocks = Set.copyOf(allClocks); // 存储不可变副本
        this.isTrue = isTrue;

        // 生成代表性的原子约束
        Set<AtomConstraint> representativeConstraints = new HashSet<>();
        if (isTrue) {
            // TRUE: c >= 0 for all c in clocks
            for (Clock clock : this.clocks) {
                if (!clock.isZeroClock()) {
                    representativeConstraints.add(AtomConstraint.greaterEqual(clock, Rational.ZERO));
                }
            }
        } else {
            // FALSE: c < 0 for all c in clocks
            for (Clock clock : this.clocks) {
                if (!clock.isZeroClock()) {
                    representativeConstraints.add(AtomConstraint.lessThan(clock, Rational.ZERO));
                }
            }
        }
        this.constraints = Set.copyOf(representativeConstraints);
    }

    /**
     * 构造函数，用于创建普通的合取约束。
     * 会自动为关联时钟集中未在输入原子约束中提及的时钟添加 `clock >= 0` 的默认约束。
     *
     * @param inputConstraints 显式提供的原子约束集合。可以为 null 或空。
     * @param allClocks        关联的时钟集合。不允许为 null。
     * @throws NullPointerException     如果 allClocks 为 null。
     * @throws IllegalArgumentException 如果 inputConstraints 包含自身矛盾的原子约束，
     *                                  或者 inputConstraints 中的时钟不在 allClocks 中（零时钟除外）。
     */
    public Constraint(Set<AtomConstraint> inputConstraints, Set<Clock> allClocks) {
        Objects.requireNonNull(allClocks, "Constraint-构造函数(普通): 时钟集合不能为 null");
        // if (allClocks.isEmpty()) {
        //     throw new IllegalArgumentException("Constraint-构造函数(普通): 时钟集合不能为空");
        // }
        this.clocks = Set.copyOf(allClocks); // 存储不可变副本

        Set<AtomConstraint> workingConstraints = (inputConstraints == null) ? new HashSet<>() : new HashSet<>(inputConstraints);

        // 1. 验证输入约束中的时钟是否都在 allClocks 中
        Set<Clock> mentionedClocks = new HashSet<>();
        for (AtomConstraint ac : workingConstraints) {
            Clock c1 = ac.getClock1();
            Clock c2 = ac.getClock2();
            if (!c1.isZeroClock() && !this.clocks.contains(c1)) {
                throw new IllegalArgumentException("Constraint-构造函数(普通): 原子约束 " + ac + " 包含未在关联时钟集中的时钟 " + c1);
            }
            if (!c2.isZeroClock() && !this.clocks.contains(c2)) {
                throw new IllegalArgumentException("Constraint-构造函数(普通): 原子约束 " + ac + " 包含未在关联时钟集中的时钟 " + c2);
            }
            // 构造函数已检查 ac 自身是否矛盾
            if (!c1.isZeroClock()) {
                mentionedClocks.add(c1);
            }
            if (!c2.isZeroClock()) {
                mentionedClocks.add(c2);
            }
        }

        // 2. 检查是否有直接矛盾 (例如 x-x < 0) - 构造函数已处理，这里是防御性检查
        boolean hasImmediateContradiction = workingConstraints.stream().anyMatch(ac ->
                ac.getClock1().equals(ac.getClock2()) &&
                        (ac.getUpperbound().compareTo(Rational.ZERO) < 0 ||
                                (ac.getUpperbound().compareTo(Rational.ZERO) == 0 && !ac.isClosed()))
        );
        if (hasImmediateContradiction) {
            // 如果发现矛盾，则此约束等价于 FALSE
            // System.err.println("Constraint-构造函数(普通): 输入包含矛盾，构造 FALSE");
            Constraint falseEquivalent = Constraint.falseConstraint(this.clocks);
            this.isTrue = falseEquivalent.isTrue;
            this.constraints = falseEquivalent.constraints;
            return;
        }

        // 3. 添加默认约束 (c >= 0)
        for (Clock clock : this.clocks) {
            if (!clock.isZeroClock() && !mentionedClocks.contains(clock)) {
                workingConstraints.add(AtomConstraint.greaterEqual(clock, Rational.ZERO));
            }
        }


        // 4. 设置最终状态
        this.constraints = Set.copyOf(workingConstraints);
        this.isTrue = null;
    }

    // --- 工厂方法 ---

    /**
     * 工厂方法，获取表示逻辑真 (⊤) 的约束实例。
     * @param allClocks 关联的时钟集合。
     * @return Constraint 实例。
     */
    public static Constraint trueConstraint(Set<Clock> allClocks) {
        return new Constraint(true, allClocks);
    }

    /**
     * 工厂方法，获取表示逻辑假 (⊥) 的约束实例。
     * @param allClocks 关联的时钟集合。
     * @return Constraint 实例。
     */
    public static Constraint falseConstraint(Set<Clock> allClocks) {
        return new Constraint(false, allClocks);
    }

    /**
     * 工厂方法，从可变参数创建普通 Constraint。
     * @param allClocks 关联的时钟集合。
     * @param constraints 原子约束。
     * @return Constraint 实例。
     */
    public static Constraint of(Set<Clock> allClocks, AtomConstraint... constraints) {
        return new Constraint(constraints == null ? null : Set.of(constraints), allClocks);
    }

    /**
     * 工厂方法，从集合创建普通 Constraint。
     * @param allClocks 关联的时钟集合。
     * @param constraints 原子约束集合。
     * @return Constraint 实例。
     */
    public static Constraint of(Set<Clock> allClocks, Collection<AtomConstraint> constraints) {
        return new Constraint(constraints == null ? null : new HashSet<>(constraints), allClocks);
    }

    // --- 核心方法 ---

    /**
     * 检查给定的时钟赋值是否满足此合取约束。
     *
     * @param clockValues 时钟到其 Rational 值的映射。需要包含所有关联时钟的值。
     * @return 如果赋值满足所有原子约束，则返回 true。
     * @throws NullPointerException 如果 clockValues 为 null。
     * @throws IllegalArgumentException 如果 clockValues 缺少关联时钟的值。
     */
    public boolean isSatisfied(ClockValuation clockValues) {
        Objects.requireNonNull(clockValues, "Constraint-isSatisfied: ClockValuation 不能为 null");

        // 检查 clockValues 是否包含所有关联时钟
        for (Clock clock : this.clocks) {
            if (!clock.isZeroClock() && clockValues.getValue(clock) == null) {
                throw new IllegalArgumentException("Constraint-isSatisfied: ClockValuation 缺少关联时钟 " + clock.getName() + " 的值");
            }
        }

        // isTrue 标志已处理 TRUE/FALSE 情况，只需检查原子约束
        // 注意：TRUE/FALSE 实例的 constraints 集合也需要被满足
        for (AtomConstraint ac : this.constraints) {
            Rational v1 = clockValues.getValue(ac.getClock1());
            Rational v2 = clockValues.getValue(ac.getClock2());
            Rational difference = v1.subtract(v2);
            int comparison = difference.compareTo(ac.getUpperbound());

            boolean satisfied = ac.isClosed() ? (comparison <= 0) : (comparison < 0);

            if (!satisfied) {
                return false;
            }
        }
        return true;
    }

    /**
     * 计算当前约束与另一个约束的逻辑与 (合取)。
     * this ∧ other
     *
     * @param other 另一个约束。
     * @return 合并后的新 Constraint 实例。
     * @throws IllegalArgumentException 如果两个约束的关联时钟集不同。
     */
    public Constraint and(Constraint other) {
        Objects.requireNonNull(other, "Constraint-and: 输入的约束不能为 null");
        if (!this.clocks.equals(other.clocks)) {
            throw new IllegalArgumentException("Constraint-and: 操作数具有不兼容的时钟集合");
        }

        // 处理 TRUE/FALSE 情况
        if (this.isFalse() || other.isFalse()) {
            return Constraint.falseConstraint(this.clocks);
        }

        // 两个都是普通约束，合并原子约束集合
        Set<AtomConstraint> merged = new HashSet<>(this.constraints);
        merged.addAll(other.constraints);

        // 创建新的 Constraint 实例，构造函数会自动处理默认约束和矛盾检查
        // 因为合并可能引入矛盾（例如 x<=5 ∧ x>=6），构造函数需要能处理
        try {
            return new Constraint(merged, this.clocks);
        } catch (IllegalArgumentException e) {
            // 如果合并导致原子约束自身矛盾 (理论上不太可能在这里发生)
            // System.err.println("Constraint-and: 合并后原子约束矛盾 " + e.getMessage());
            return Constraint.falseConstraint(this.clocks);
        }
        // 注意：更复杂的矛盾（如 x<=5 ∧ x>=6）不会在这里的构造函数中捕获，
        // 结果会是一个包含这两个约束的普通 Constraint。
        // isSatisfied 会正确处理它，DBM/Z3 也能发现。
    }

    /**
     * 计算多个约束的逻辑与 (合取)。
     * @param constraints 要进行合取的约束。必须具有相同的关联时钟集。
     * @return 合取结果。
     * @throws IllegalArgumentException 如果约束列表为空或时钟集不兼容。
     */
    public static Constraint and(Collection<Constraint> constraints) {
        if (constraints == null || constraints.isEmpty()) {
            throw new IllegalArgumentException("Constraint-and(Collection): 输入集合不能为空");
        }
        // 检查时钟集兼容性并获取时钟集
        Set<Clock> commonClocks = null;
        for (Constraint c : constraints) {
            if (commonClocks == null) {
                commonClocks = c.getClocks();
            } else if (!commonClocks.equals(c.getClocks())) {
                throw new IllegalArgumentException("Constraint-and(Collection): 约束具有不兼容的时钟集合");
            }
        }

        Constraint result = Constraint.trueConstraint(commonClocks);
        for (Constraint constraint : constraints) {
            result = result.and(constraint); // and 方法内部会再次检查时钟集
            if (result.isFalse()) {
                return result; // 提前退出
            }
        }
        return result;
    }
    public static Constraint and(Constraint... constraints) {
        if (constraints == null || constraints.length == 0) {
            throw new IllegalArgumentException("Constraint-and(varargs): 输入数组不能为空");
        }
        return and(Arrays.asList(constraints));
    }


    /**
     * 计算当前合取约束的逻辑非。
     * ¬(A ∧ B ∧ ...) = ¬A ∨ ¬B ∨ ...
     * 结果是一个析取约束 (DisjunctiveConstraint)。
     *
     * @return 代表否定的 DisjunctiveConstraint。
     */
    public DisjunctiveConstraint negate() {
        if (this.isTrue()) {
            return DisjunctiveConstraint.falseConstraint(this.clocks); // ¬⊤ = ⊥
        }
        if (this.isFalse()) {
            return DisjunctiveConstraint.trueConstraint(this.clocks);  // ¬⊥ = ⊤
        }

        // 普通约束：对每个原子约束取反，然后将结果 OR 起来
        if (constraints.isEmpty()) {
            // 普通约束的 constraints 不应为空
            System.err.println("Constraint-negate: 警告：普通约束的原子约束集为空，视为 TRUE，否定为 FALSE");
            return DisjunctiveConstraint.falseConstraint(this.clocks);
        }

        Set<Constraint> negatedAtomConstraints = new HashSet<>();
        boolean resultIsTrue = false;

        for (AtomConstraint ac : constraints) {
            try {
                AtomConstraint negatedAcAtom = ac.negateAtom();
                // 将取反后的原子约束包装成 Constraint
                negatedAtomConstraints.add(Constraint.of(this.clocks, negatedAcAtom));
            } catch (IllegalStateException e) {
                // negateAtom 抛出异常意味着原约束是平凡的
                if (e.getMessage().contains("逻辑 TRUE")) {
                    // 原约束平凡为 False (e.g., x-x<0), 其否定为 True
                    resultIsTrue = true;
                    break; // 整个析取结果为 True
                } else if (e.getMessage().contains("逻辑 FALSE")) {
                    // 原约束平凡为 True (e.g., x-x<=0), 其否定为 False
                    // 在析取中忽略 False 项
                } else {
                    throw e; // 其他异常
                }
            }
        }

        if (resultIsTrue) {
            return DisjunctiveConstraint.trueConstraint(this.clocks);
        } else {
            // 使用工厂方法创建析取约束，会自动处理空集（如果所有否定都是 False）
            return DisjunctiveConstraint.of(negatedAtomConstraints, this.clocks);
        }
    }


    /**
     * 计算约束差集：this - other ≡ this ∧ ¬other
     * 返回一个析取约束 (DisjunctiveConstraint)。
     *
     * @param other 要减去的约束。
     * @return 代表差集的 DisjunctiveConstraint。
     * @throws IllegalArgumentException 如果两个约束的关联时钟集不同。
     */
    public DisjunctiveConstraint minus(Constraint other) {
        Objects.requireNonNull(other, "Constraint-minus: 输入的约束不能为 null");
        if (!this.clocks.equals(other.clocks)) {
            throw new IllegalArgumentException("Constraint-minus: 操作数具有不兼容的时钟集合");
        }

        // 1. 处理特殊情况
        if (this.isFalse() || other.isTrue()) {
            return DisjunctiveConstraint.falseConstraint(this.clocks); // ⊥ - B = ⊥, A - ⊤ = ⊥
        }
        if (other.isFalse()) {
            return this.toDisjunctiveConstraint(); // A - ⊥ = A
        }
        if (this.isTrue()) {
            return other.negate(); // ⊤ - B = ¬B
        }

        // 2. 计算 ¬other
        DisjunctiveConstraint negatedOther = other.negate();

        // 3. 计算 this ∧ ¬other
        if (negatedOther.isFalse()) {
            return DisjunctiveConstraint.falseConstraint(this.clocks); // A ∧ ⊥ = ⊥
        }
        if (negatedOther.isTrue()) {
            return this.toDisjunctiveConstraint(); // A ∧ ⊤ = A
        }

        // this 是普通约束，negatedOther 是普通析取约束
        // (A) ∧ (¬B1 ∨ ¬B2 ∨ ...) = (A ∧ ¬B1) ∨ (A ∧ ¬B2) ∨ ...
        Set<Constraint> resultConstraints = new HashSet<>();
        for (Constraint negatedTerm : negatedOther.getConstraints()) {
            Constraint intersection = this.and(negatedTerm); // and 内部检查时钟集
            if (!intersection.isFalse()) {
                resultConstraints.add(intersection);
            }
        }

        return DisjunctiveConstraint.of(resultConstraints, this.clocks);
    }

    /**
     * 将当前合取约束转换为析取约束（包含单个子句的 DNF）。
     * @return DisjunctiveConstraint 实例。
     */
    public DisjunctiveConstraint toDisjunctiveConstraint() {
        if (this.isTrue()) {
            return DisjunctiveConstraint.trueConstraint(this.clocks);
        }
        if (this.isFalse()) {
            return DisjunctiveConstraint.falseConstraint(this.clocks);
        }
        // 普通约束，创建一个包含自身的析取约束
        return DisjunctiveConstraint.of(Set.of(this), this.clocks);
    }


    /**
     * 计算当前约束与另一个约束的逻辑或。
     * this ∨ other
     * 返回一个析取约束 (DisjunctiveConstraint)。
     * @param other 另一个约束。
     * @return 代表析取的 DisjunctiveConstraint。
     * @throws IllegalArgumentException 如果两个约束的关联时钟集不同。
     */
    public DisjunctiveConstraint or(Constraint other) {
        Objects.requireNonNull(other, "Constraint-or: 输入的约束不能为 null");
        if (!this.clocks.equals(other.clocks)) {
            throw new IllegalArgumentException("Constraint-or: 操作数具有不兼容的时钟集合");
        }
        return DisjunctiveConstraint.of(Set.of(this, other), this.clocks);
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
        Constraint that = (Constraint) o;
        // 必须时钟集相同，状态相同（特殊或普通），且原子约束集相同
        return Objects.equals(clocks, that.clocks) &&
                Objects.equals(isTrue, that.isTrue) &&
                constraints.equals(that.constraints); // Set.equals 依赖 AtomConstraint.equals
    }

    @Override
    public int hashCode() {
        // 基于时钟集、状态和原子约束集计算哈希码
        return Objects.hash(clocks, isTrue, constraints); // Set.hashCode 依赖 AtomConstraint.hashCode
    }

    @Override
    public String toString() {
        if (isFalse()) {
            return "⊥";
        }

        // 普通约束，对原子约束排序
        return constraints.stream()
                .sorted()
                .map(AtomConstraint::toString)
                .collect(Collectors.joining(" ∧ "));
    }
}