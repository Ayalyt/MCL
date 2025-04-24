package org.example.constraint;

import lombok.Getter;
import org.example.Clock;
import org.example.ClockValuation;
import org.example.utils.Rational;
import org.example.utils.Z3Solver;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 表示原子约束的合取 (Conjunction)。
 * 每个实例都关联一个特定的时钟集合，并显式包含该集合中所有时钟的非负约束 (c >= 0)。
 * 使用 Z3Solver 缓存其逻辑真/假状态。
 *
 * 设计：
 * 1. 持有 `clocks` 字段，表示关联的时钟全集。
 * 2. `constraints` 字段包含所有显式原子约束，包括为所有关联时钟添加的 `c >= 0` 基础约束。
 * 3. 使用 `cachedStatus` 字段缓存 Z3 的判断结果 (TRUE, FALSE, SATISFIABLE_UNKNOWN, NOT_YET_CHECKED)。
 * 4. `isTrue()` / `isFalse()` 方法通过缓存或 Z3Solver 判断逻辑真/假。
 * 5. 操作需要时钟集兼容性检查。
 * 6. 实例是不可变的。
 */
@Getter
public class Constraint {

    /** 关联的时钟全集 */
    private final Set<Clock> clocks;

    /**
     * 包含的原子约束集合 (不可变)。
     * 始终包含所有关联时钟的 c >= 0 基础约束。
     */
    private final Set<AtomConstraint> constraints;

    // --- Caching Mechanism ---
    /** Caches the validity status determined by Z3. Volatile for visibility. */
    private transient volatile ValidityStatus cachedStatus = ValidityStatus.NOT_YET_CHECKED;
    /** Lock object for thread-safe computation of cachedStatus. */
    private final transient Object statusLock = new Object();
    /** Static instance of Z3Solver for checks. Assumes Z3Solver is thread-safe. */
    private static final Z3Solver z3Solver = new Z3Solver(); // Consider dependency injection later if needed


    // --- 构造函数 ---

    /**
     * 私有构造函数，用于创建规范的 TRUE 或 FALSE 实例。
     * 这些实例的缓存状态会被直接设置。
     *
     * @param status    初始缓存状态 (TRUE 或 FALSE)。
     * @param allClocks 关联的时钟集合。不允许为 null 或空。
     */
    private Constraint(ValidityStatus status, Set<Clock> allClocks) {
        Objects.requireNonNull(status, "Constraint-构造函数(特殊): 状态不能为空");
        Objects.requireNonNull(allClocks, "Constraint-构造函数(特殊): 时钟集合不能为 null");
        if (status != ValidityStatus.TRUE && status != ValidityStatus.FALSE) {
            throw new IllegalArgumentException("Constraint-构造函数(特殊): 状态必须是 TRUE 或 FALSE");
        }

        this.clocks = Set.copyOf(allClocks); // 存储不可变副本
        this.cachedStatus = status;

        // 生成代表性的原子约束 (确保所有 c >= 0 存在)
        Set<AtomConstraint> representativeConstraints = new HashSet<>();
        if (status == ValidityStatus.TRUE) {
            // 对于 TRUE，包含所有 c >= 0
            for (Clock clock : this.clocks) {
                if (!clock.isZeroClock()) {
                    representativeConstraints.add(AtomConstraint.greaterEqual(clock, Rational.ZERO));
                }
            }
            // 如果时钟集为空，TRUE 约束应该也是空的
            if (allClocks.isEmpty()) {
                this.constraints = Collections.emptySet();
                return; // 直接返回
            }
        } else { // status == ValidityStatus.FALSE
            if (!this.clocks.isEmpty()) {
                List<Clock> clocks = this.clocks.stream().filter(c -> !c.isZeroClock()).toList();
                for (Clock clock : clocks) {
                    // 添加 c < 0，这与隐含的 c >= 0 矛盾
                    representativeConstraints.add(AtomConstraint.lessThan(clock, Rational.ZERO));
                    representativeConstraints.add(AtomConstraint.greaterEqual(clock, Rational.ZERO));
                }
            } else {
                // 时钟集为空的 FALSE 约束
                representativeConstraints.add(AtomConstraint.lessThan(Clock.getZeroClock(), Rational.ZERO)); // 0 < 0
            }
        }
        this.constraints = Set.copyOf(representativeConstraints);
    }

    /**
     * 构造函数，用于创建普通的合取约束。
     * 会自动为关联时钟集中所有时钟添加 `clock >= 0` 的基础约束。
     *
     * @param inputConstraints 用户提供的原子约束集合。可以为 null 或空。
     * @param allClocks        关联的时钟集合。不允许为 null。
     * @throws NullPointerException     如果 allClocks 为 null。
     * @throws IllegalArgumentException 如果 inputConstraints 中的时钟不在 allClocks 中（零时钟除外）。
     */
    public Constraint(Set<AtomConstraint> inputConstraints, Set<Clock> allClocks) {
        Objects.requireNonNull(allClocks, "Constraint-构造函数(普通): 时钟集合不能为 null");
        this.clocks = Set.copyOf(allClocks); // 存储不可变副本

        Set<AtomConstraint> workingConstraints = new HashSet<>();

        // 1. 添加所有 c >= 0 基础约束
        for (Clock clock : this.clocks) {
            if (!clock.isZeroClock()) {
                // We add c >= 0 regardless of input constraints. Simplification (like removing
                // c >= 0 if c >= 5 exists) is not done here; Z3 handles redundancy.
                workingConstraints.add(AtomConstraint.greaterEqual(clock, Rational.ZERO));
            }
        }

        // 2. 添加并验证用户输入约束
        if (inputConstraints != null && !inputConstraints.isEmpty()) {
            for (AtomConstraint ac : inputConstraints) {
                // 2a. 验证时钟是否属于 allClocks
                Clock c1 = ac.getClock1();
                Clock c2 = ac.getClock2();
                if (!c1.isZeroClock() && !this.clocks.contains(c1)) {
                    throw new IllegalArgumentException("Constraint-构造函数(普通): 原子约束 " + ac + " 包含未在关联时钟集中的时钟 " + c1);
                }
                if (!c2.isZeroClock() && !this.clocks.contains(c2)) {
                    throw new IllegalArgumentException("Constraint-构造函数(普通): 原子约束 " + ac + " 包含未在关联时钟集中的时钟 " + c2);
                }
                // 2b. 检查原子约束自身是否平凡矛盾 (构造函数已检查) - 防御性
                if (ac.getClock1().equals(ac.getClock2())) {
                    int comparison = ac.getUpperbound().compareTo(Rational.ZERO);
                    if ((!ac.isClosed() && comparison <= 0) || (ac.isClosed() && comparison < 0)) {
                        // If input contains e.g. x-x < 0, the whole constraint becomes FALSE.
                        // We could detect this here and set status, but relying on Z3 check
                        // during isTrue/isFalse is more general. For now, just add it.
                        // Setting status directly requires care with concurrent access.
                    }
                }
                workingConstraints.add(ac);
            }
        }

        // 3. 设置最终状态
        this.constraints = Set.copyOf(workingConstraints);
        this.cachedStatus = ValidityStatus.NOT_YET_CHECKED; // 普通约束需要 Z3 检查
    }

    public ValidityStatus getKnownStatus() {
        return this.cachedStatus;
    }

    // --- 工厂方法 ---

    /**
     * 工厂方法，获取表示逻辑真 (⊤) 的约束实例。
     * @param allClocks 关联的时钟集合。
     * @return Constraint 实例。
     */
    public static Constraint trueConstraint(Set<Clock> allClocks) {
        // 使用私有构造函数创建，并设置缓存状态为 TRUE
        return new Constraint(ValidityStatus.TRUE, allClocks);
    }

    /**
     * 工厂方法，获取表示逻辑假 (⊥) 的约束实例。
     * @param allClocks 关联的时钟集合。
     * @return Constraint 实例。
     */
    public static Constraint falseConstraint(Set<Clock> allClocks) {
        // 使用私有构造函数创建，并设置缓存状态为 FALSE
        return new Constraint(ValidityStatus.FALSE, allClocks);
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
     * 注意：这是一个具体的检查，不使用 Z3 或缓存。
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
                // Z3 检查假设非负，但具体赋值必须提供所有值
                throw new IllegalArgumentException("Constraint-isSatisfied: ClockValuation 缺少关联时钟 " + clock.getName() + " 的值");
            }
        }

        // 检查是否满足所有原子约束
        for (AtomConstraint ac : this.constraints) {
            Rational v1 = clockValues.getValue(ac.getClock1());
            Rational v2 = clockValues.getValue(ac.getClock2());
            Rational difference = v1.subtract(v2);
            int comparison = difference.compareTo(ac.getUpperbound());

            boolean satisfied = ac.isClosed() ? (comparison <= 0) : (comparison < 0);

            if (!satisfied) {
                return false; // 只要有一个不满足，整个合取就不满足
            }
        }
        return true; // 所有原子约束都满足
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

        // 短路优化：基于缓存状态（或直接比较规范实例）
        ValidityStatus thisStatus = this.getOrComputeValidityStatus();
        ValidityStatus otherStatus = other.getOrComputeValidityStatus();

        if (thisStatus == ValidityStatus.FALSE || otherStatus == ValidityStatus.FALSE) {
            return Constraint.falseConstraint(this.clocks);
        }
        if (thisStatus == ValidityStatus.TRUE) {
            return other;
        }
        if (otherStatus == ValidityStatus.TRUE) {
            return this;
        }

        // 两个都是普通（或至少非 FALSE/TRUE）约束，合并原子约束集合
        Set<AtomConstraint> merged = new HashSet<>(this.constraints);
        merged.addAll(other.constraints);

        // 创建新的 Constraint 实例，构造函数会处理基础约束
        // 新实例的状态需要重新检查
        return new Constraint(merged, this.clocks);
    }

    public Constraint and(AtomConstraint other) {
        Objects.requireNonNull(other, "Constraint-and: 输入的约束不能为 null");
        // 短路优化：基于缓存状态（或直接比较规范实例）
        ValidityStatus thisStatus = this.getOrComputeValidityStatus();
        if (thisStatus == ValidityStatus.FALSE) {
            return Constraint.falseConstraint(this.clocks);
        }
        if (thisStatus == ValidityStatus.TRUE) {
            return Constraint.of(this.clocks, other);
        }
        // 两个都是普通（或至少非 FALSE/TRUE）约束，合并原子约束集合
        Set<AtomConstraint> merged = new HashSet<>(this.constraints);
        merged.add(other);
        // 创建新的 Constraint 实例，构造函数会处理基础约束
        // 新实例的状态需要重新检查
        return new Constraint(merged, this.clocks);
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
        Constraint first = null;
        for (Constraint c : constraints) {
            Objects.requireNonNull(c, "Constraint-and(Collection): 输入集合不能包含 null 约束");
            if (commonClocks == null) {
                commonClocks = c.getClocks();
                first = c;
            } else if (!commonClocks.equals(c.getClocks())) {
                throw new IllegalArgumentException("Constraint-and(Collection): 约束具有不兼容的时钟集合");
            }
        }

        if(constraints.size() == 1){
            return first; // 如果只有一个约束，直接返回
        }

        Constraint result = Constraint.trueConstraint(commonClocks);
        for (Constraint constraint : constraints) {
            result = result.and(constraint); // and 方法内部会进行短路和检查
            if (result.isFalse()) { // 使用 isFalse() 访问缓存/Z3结果
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
        // 普通约束：对每个原子约束取反，然后将结果 OR 起来
        if (constraints.isEmpty()) {
            // 理论上不应发生，因为至少有 c>=0
            System.err.println("Constraint-negate: 警告：普通约束的原子约束集为空，视为 TRUE，否定为 FALSE");
            return DisjunctiveConstraint.falseConstraint(this.clocks);
        }

        Set<Constraint> negatedAtomConstraints = new HashSet<>();
        boolean resultIsTrue = false;

        for (AtomConstraint ac : constraints) {

            try {
                AtomConstraint negatedAcAtom = ac.negateAtom();
                // 将取反后的原子约束包装成 Constraint
                // 使用 .of 会自动添加所有 c>=0，确保时钟集一致
                negatedAtomConstraints.add(Constraint.of(this.clocks, negatedAcAtom));
            } catch (IllegalStateException e) {
                // negateAtom 抛出异常意味着原约束是平凡的
                if (e.getMessage().contains("逻辑 TRUE")) {
                    // 原约束平凡为 False (e.g., x-x < 0), 其否定为 True
                    resultIsTrue = true;
                    break; // 整个析取结果为 True
                } else if (e.getMessage().contains("逻辑 FALSE")) {
                    // 原约束平凡为 True (e.g., x-x <= 0), 其否定为 False
                    // 这个 case 已在上面 if 块处理，这里是防御性
                } else {
                    throw e; // 其他异常
                }
            }
        }

        if (resultIsTrue) {
            return DisjunctiveConstraint.trueConstraint(this.clocks);
        } else {
            // 如果 negatedAtomConstraints 为空（例如原约束是 x-x<=5 ∧ y-y<10），结果应为 FALSE
            if (negatedAtomConstraints.isEmpty()) {
                return DisjunctiveConstraint.falseConstraint(this.clocks);
            }
            // 使用工厂方法创建析取约束
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

        // 1. 处理特殊情况 (使用缓存状态)
        ValidityStatus thisStatus = this.getOrComputeValidityStatus();
        ValidityStatus otherStatus = other.getOrComputeValidityStatus();

        if (thisStatus == ValidityStatus.FALSE || otherStatus == ValidityStatus.TRUE) {
            return DisjunctiveConstraint.falseConstraint(this.clocks); // ⊥ - B = ⊥, A - ⊤ = ⊥
        }
        if (otherStatus == ValidityStatus.FALSE) {
            return this.toDisjunctiveConstraint(); // A - ⊥ = A
        }
        // if (thisStatus == ValidityStatus.TRUE) { // Don't check thisStatus==TRUE here, negate might be complex
        //     return other.negate(); // ⊤ - B = ¬B
        // }

        // 2. 计算 ¬other
        DisjunctiveConstraint negatedOther = other.negate(); // negate() 内部会处理 other 的状态

        // 3. 计算 this ∧ ¬other
        if (negatedOther.isFalse()) { // Use isFalse() on the result of negate()
            return DisjunctiveConstraint.falseConstraint(this.clocks); // A ∧ ⊥ = ⊥
        }
        if (negatedOther.isTrue()) {
            return this.toDisjunctiveConstraint(); // A ∧ ⊤ = A
        }

        // this 是普通约束，negatedOther 是普通析取约束
        // (A) ∧ (¬B1 ∨ ¬B2 ∨ ...) = (A ∧ ¬B1) ∨ (A ∧ ¬B2) ∨ ...
        // DisjunctiveConstraint.and() handles this distribution
        return this.toDisjunctiveConstraint().and(negatedOther);
    }

    /**
     * 将当前合取约束转换为析取约束（包含单个子句的 DNF）。
     * @return DisjunctiveConstraint 实例。
     */
    public DisjunctiveConstraint toDisjunctiveConstraint() {
        ValidityStatus status = this.getOrComputeValidityStatus();
        if (status == ValidityStatus.TRUE) {
            return DisjunctiveConstraint.trueConstraint(this.clocks);
        }
        if (status == ValidityStatus.FALSE) {
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
        // 使用 DisjunctiveConstraint 的工厂方法处理优化和创建
        return DisjunctiveConstraint.of(Set.of(this, other), this.clocks);
    }

    boolean implies(Constraint c2) {
        if (this.equals(c2)) {
            return true;
        }

        // 使用缓存状态进行快速判断
        ValidityStatus c1Status = getOrComputeValidityStatus();
        ValidityStatus c2Status = c2.getOrComputeValidityStatus();

        if (c1Status == ValidityStatus.FALSE) {
            return true;
        }
        if (c2Status == ValidityStatus.TRUE) {
            return true;
        }
        if (c1Status == ValidityStatus.TRUE) {
            return false;
        }
        if (c2Status == ValidityStatus.FALSE) {
            return false;
        }


        try {
            // 计算 !c2
            DisjunctiveConstraint notC2 = c2.negate();

            DisjunctiveConstraint intersection = notC2.and(this);

            return intersection.isFalse();

        } catch (Exception e) {
            System.err.println("Warning: Exception during implication check (implies " + this + " => " + c2 + "): " + e.getMessage());
            return false;
        }
    }

    private static class Bounds {
        Rational lowerBoundValue = Rational.ZERO; // Default: c >= 0
        boolean lowerBoundStrict = false;
        Rational upperBoundValue = null; // Represents +Infinity
        boolean upperBoundStrict = false; // Doesn't matter for infinity

        // 更新下界
        void updateLower(Rational newValue, boolean newStrict) {
            // 比较当前最紧下界和新下界
            int cmp = newValue.compareTo(this.lowerBoundValue);
            if (cmp > 0) { // 新下界更大，更紧
                this.lowerBoundValue = newValue;
                this.lowerBoundStrict = newStrict;
            } else if (cmp == 0 && newStrict && !this.lowerBoundStrict) {
                // 值相同，但新界是严格的 (>)，旧界是非严格 (>=)，严格的更紧
                this.lowerBoundStrict = true;
            }
            // 隐含 c >= 0
            if (this.lowerBoundValue.compareTo(Rational.ZERO) < 0) {
                this.lowerBoundValue = Rational.ZERO;
                this.lowerBoundStrict = false; // Reset strictness if floor is 0
            }
        }

        // 更新上界
        void updateUpper(Rational newValue, boolean newStrict) {
            // 比较当前最紧上界和新上界
            if (this.upperBoundValue == null) { // 当前是无穷大
                this.upperBoundValue = newValue;
                this.upperBoundStrict = newStrict;
                return;
            }

            int cmp = newValue.compareTo(this.upperBoundValue);
            if (cmp < 0) { // 新上界更小，更紧
                this.upperBoundValue = newValue;
                this.upperBoundStrict = newStrict;
            } else if (cmp == 0 && newStrict && !this.upperBoundStrict) {
                // 值相同，但新界是严格的 (<)，旧界是非严格 (<=)，严格的更紧
                this.upperBoundStrict = true;
            }
        }

        // 检查矛盾
        boolean hasContradiction() {
            if (upperBoundValue == null) {
                return false; // No upper bound, no contradiction
            }

            int cmp = lowerBoundValue.compareTo(upperBoundValue);
            if (cmp > 0) {
                return true; // Lower bound > Upper bound
            }
            if (cmp == 0 && (lowerBoundStrict || upperBoundStrict)) {
                return true; // Lower bound == Upper bound, but at least one is strict
            }
            return false;
        }

        // 生成原子约束
        List<AtomConstraint> toConstraints(Clock clock) {
            List<AtomConstraint> result = new ArrayList<>();
            Clock zeroClock = Clock.getZeroClock();

            // 添加下界约束 (如果不是平凡的 c >= 0)
            if (lowerBoundValue.compareTo(Rational.ZERO) > 0 || lowerBoundStrict) {
                if (lowerBoundStrict) { // c > lbVal  =>  0 - c < -lbVal
                    // Need AtomConstraint factory for ">" or represent via "<"
                    // Assuming AtomConstraint can represent c > val
                    // or we use the internal form directly: 0 - c < -lbVal
                    Rational negLbVal = lowerBoundValue.negate();
                    result.add(AtomConstraint.lessThan(zeroClock, clock, negLbVal)); // 0 - c < -lb
                } else { // c >= lbVal => 0 - c <= -lbVal
                    Rational negLbVal = lowerBoundValue.negate();
                    result.add(AtomConstraint.lessEqual(zeroClock, clock, negLbVal)); // 0 - c <= -lb
                }
            }
            // else: 下界是 c >= 0，让 Constraint 构造函数处理

            // 添加上界约束 (如果不是无穷大)
            if (upperBoundValue != null) {
                if (upperBoundStrict) { // c < ubVal => c - 0 < ubVal
                    result.add(AtomConstraint.lessThan(clock, zeroClock, upperBoundValue));
                } else { // c <= ubVal => c - 0 <= ubVal
                    result.add(AtomConstraint.lessEqual(clock, zeroClock, upperBoundValue));
                }
            }
            return result;
        }
    }

    /**
     * 简化约束，假设所有约束都是相对于零时钟的形式。
     * c op k  or  c - 0 op k or 0 - c op k
     *
     * @return 简化后的 Constraint。可能是 TRUE, FALSE, 或包含最紧边界的新约束。
     */
    public Constraint simplify() {
        // 前置检查
        ValidityStatus knownStatus = this.getKnownStatus();
        if (knownStatus == ValidityStatus.FALSE) {
            return Constraint.falseConstraint(this.clocks);
        }
        if (knownStatus == ValidityStatus.TRUE) {
            return Constraint.trueConstraint(this.clocks);
        }

        Map<Clock, Bounds> clockBounds = new HashMap<>();
        Set<Clock> nonZeroClocks = this.clocks.stream()
                .filter(c -> !c.isZeroClock())
                .collect(Collectors.toSet());

        for (Clock clock : nonZeroClocks) {
            clockBounds.put(clock, new Bounds());
        }

        List<AtomConstraint> nonZeroConstraints = new ArrayList<>();

        for (AtomConstraint ac : this.constraints) {
            Clock c1 = ac.getClock1();
            Clock c2 = ac.getClock2();
            Rational bound = ac.getUpperbound();
            boolean isStrict = !ac.isClosed();

            Clock targetClock = null;
            boolean isUpperBound = false;
            boolean isLowerBound = false;
            Rational effectiveBound = bound;
            boolean effectiveStrict = isStrict;

            if (!c1.isZeroClock() && c2.isZeroClock()) {
                targetClock = c1;
                isUpperBound = true;
            } else if (c1.isZeroClock() && !c2.isZeroClock()) {
                targetClock = c2;
                isLowerBound = true;
                effectiveBound = bound.negate();
                effectiveStrict = isStrict;
            } else if (!c1.isZeroClock() && !c2.isZeroClock()){
                nonZeroConstraints.add(ac);
                continue;
            } else {
                int kComparison = bound.compareTo(Rational.ZERO);
                if ((isStrict && kComparison <= 0) || (!isStrict && kComparison < 0)) {
                    this.cachedStatus = ValidityStatus.FALSE;
                    return Constraint.falseConstraint(this.clocks);
                }
                continue;
            }

            Bounds bounds = clockBounds.get(targetClock);
            if (bounds == null) {
                System.err.println("Warning: Clock " + targetClock + " found in constraint but not in clock set during simplification.");
                continue;
            }

            if (isUpperBound) {
                bounds.updateUpper(effectiveBound, effectiveStrict);
            } else {
                bounds.updateLower(effectiveBound, effectiveStrict);
            }
        }

        // 检查矛盾
        for (Bounds bounds : clockBounds.values()) {
            if (bounds.hasContradiction()) {
                this.cachedStatus = ValidityStatus.FALSE;
                return Constraint.falseConstraint(this.clocks);
            }
        }

        // 提取简化约束
        Set<AtomConstraint> simplifiedConstraints = new HashSet<>();
        boolean onlyImplicit = true;
        for (Map.Entry<Clock, Bounds> entry : clockBounds.entrySet()) {
            List<AtomConstraint> clockSpecificConstraints = entry.getValue().toConstraints(entry.getKey());
            if (!clockSpecificConstraints.isEmpty()) {
                onlyImplicit = false;
                simplifiedConstraints.addAll(clockSpecificConstraints);
            }
        }

        if (onlyImplicit && nonZeroClocks.isEmpty()){
            return Constraint.trueConstraint(this.clocks);
        }
        if (onlyImplicit) {
            return Constraint.trueConstraint(this.clocks);
        }

        simplifiedConstraints.addAll(nonZeroConstraints);
        Constraint simplifiedConstraint = new Constraint(simplifiedConstraints, this.clocks);
        simplifiedConstraint.cachedStatus = ValidityStatus.SATISFIABLE_UNKNOWN;
        if(this.constraints.equals(simplifiedConstraint.constraints)) {
            return this;
        }

        return simplifiedConstraint;
    }

    // --- Status Check Methods ---

    /** 检查此约束是否逻辑恒为真 (tautology)。 */
    public boolean isTrue() {
        return getOrComputeValidityStatus() == ValidityStatus.TRUE;
    }

    /** 检查此约束是否逻辑恒为假 (unsatisfiable/contradiction)。 */
    public boolean isFalse() {
        return getOrComputeValidityStatus() == ValidityStatus.FALSE;
    }

    /**
     * 获取或计算此约束的逻辑有效性状态。
     * 使用 Z3Solver 进行检查，并缓存结果。线程安全。
     * @return ValidityStatus (TRUE, FALSE, or SATISFIABLE_UNKNOWN).
     */
    ValidityStatus getOrComputeValidityStatus() {
        ValidityStatus localStatus = cachedStatus; // Read volatile field once
        if (localStatus == ValidityStatus.NOT_YET_CHECKED) {
            synchronized (statusLock) { // Synchronize block for computation
                localStatus = cachedStatus; // Double-check inside lock
                if (localStatus == ValidityStatus.NOT_YET_CHECKED) {
                    try {
                        boolean isTrue = z3Solver.isTrue(this);
                        if (isTrue) {
                            cachedStatus = ValidityStatus.TRUE;
                        } else {
                            // If not TRUE, check if it's FALSE (unsatisfiable)
                            // Z3Solver.isSatisfiable checks if it's NOT FALSE
                            boolean isSat = z3Solver.isSatisfiable(this);
                            if (!isSat) {
                                cachedStatus = ValidityStatus.FALSE;
                            } else {
                                // Satisfiable but not TRUE
                                cachedStatus = ValidityStatus.SATISFIABLE_UNKNOWN;
                            }
                        }
                    } catch (Exception e) { // Catch potential Z3SolverException or others
                        // Log the error? For now, treat check failure as unknown.
                        // Re-throwing might be better depending on desired behavior.
                        System.err.println("Error checking constraint validity: " + e.getMessage());
                        cachedStatus = ValidityStatus.SATISFIABLE_UNKNOWN; // Or a specific ERROR status?
                        // To avoid re-checking constantly on error, we set it to unknown.
                    }
                    localStatus = cachedStatus;
                }
            }
        }
        return localStatus;
    }


    // --- Object Methods ---

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Constraint that = (Constraint) o;
        // Equality depends only on clocks and the set of atomic constraints.
        // Cached status is derived and not part of equality.
        return Objects.equals(clocks, that.clocks) &&
                Objects.equals(constraints, that.constraints);
    }

    @Override
    public int hashCode() {
        // Hash code depends only on clocks and the set of atomic constraints.
        return Objects.hash(clocks, constraints);
    }

    @Override
    public String toString() {

        return constraints.stream()
                .sorted() // Sort for consistent output
                .map(AtomConstraint::toString)
                .collect(Collectors.joining(" ∧ "));
    }
}