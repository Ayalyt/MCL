package org.example.constraint;

import lombok.Getter;
import org.example.Clock;
import org.example.utils.Rational;

import java.util.Objects;
import java.util.Set;


/**
 * 表示一个原子差分约束 (Atom Difference Constraint), 形如 c1 - c2 < V 或 c1 - c2 <= V.
 * 这是 DBM (Difference Bound Matrix) 和合取/析取约束的基本构建块。
 *
 * @author Ayalyt
 */
@Getter
public class AtomConstraint implements Comparable<AtomConstraint> {

    private final Clock clock1;       // 第一个时钟 (c_i)
    private final Clock clock2;       // 第二个时钟 (c_j), 可能为零时钟 x0
    private final Rational upperbound; // 上界值 (V)
    private final boolean closed;     // true 表示 <=, false 表示 <

    /**
     * 私有构造函数, 用于创建原子差分约束。
     * 会进行基本的有效性检查 (例如 x - x < 0 是无效的)。
     *
     * @param clock1     第一个时钟 (c1)。不允许为 null。
     * @param clock2     第二个时钟 (c2)。不允许为 null。
     * @param upperbound 上界值 V for c1 - c2。不允许为 null。
     * @param closed     true for <= V, false for < V.
     * @throws NullPointerException     如果任何时钟或上界为 null。
     * @throws IllegalArgumentException 如果约束自身矛盾 (例如 x - x < 0)。
     */
    public AtomConstraint(Clock clock1, Clock clock2, Rational upperbound, boolean closed) {
        Objects.requireNonNull(clock1, "AtomConstraint-构造函数: clock1 不能为 null");
        Objects.requireNonNull(clock2, "AtomConstraint-构造函数: clock2 不能为 null");
        Objects.requireNonNull(upperbound, "AtomConstraint-构造函数: upperbound 不能为 null");

        // 检查自身矛盾: c - c op V
        if (clock1.equals(clock2)) {
            int comparison = upperbound.compareTo(Rational.ZERO);
            // c - c < V (V <= 0) -> 矛盾 (包括 c - c < 0)
            // c - c <= V (V < 0) -> 矛盾
            if ((!closed && comparison <= 0) || (closed && comparison < 0)) {
                throw new IllegalArgumentException("AtomConstraint-构造函数: 约束 " + clock1 + " - " + clock2 + (closed ? " <= " : " < ") + upperbound + " 自身矛盾");
            }
        }

        this.clock1 = clock1;
        this.clock2 = clock2;
        this.upperbound = upperbound;
        this.closed = closed;
    }

    // --- 工厂方法 (保持不变) ---
    public static AtomConstraint lessThan(Clock c1, Clock c2, Rational value) { return new AtomConstraint(c1, c2, value, false); }
    public static AtomConstraint lessThan(Clock c1, Clock c2, int value) { return new AtomConstraint(c1, c2, Rational.valueOf(value), false); }
    public static AtomConstraint lessEqual(Clock c1, Clock c2, Rational value) { return new AtomConstraint(c1, c2, value, true); }
    public static AtomConstraint lessEqual(Clock c1, Clock c2, int value) { return new AtomConstraint(c1, c2, Rational.valueOf(value), true); }
    public static AtomConstraint lessThan(Clock c, Rational value) { return new AtomConstraint(c, Clock.getZeroClock(), value, false); }
    public static AtomConstraint lessThan(Clock c, int value) { return new AtomConstraint(c, Clock.getZeroClock(), Rational.valueOf(value), false); }
    public static AtomConstraint lessEqual(Clock c, Rational value) { return new AtomConstraint(c, Clock.getZeroClock(), value, true); }
    public static AtomConstraint lessEqual(Clock c, int value) { return new AtomConstraint(c, Clock.getZeroClock(), Rational.valueOf(value), true); }
    public static AtomConstraint greaterThan(Clock c, Rational value) { return new AtomConstraint(Clock.getZeroClock(), c, value.negate(), false); }
    public static AtomConstraint greaterThan(Clock c, int value) { return new AtomConstraint(Clock.getZeroClock(), c, Rational.valueOf(value).negate(), false); }
    public static AtomConstraint greaterEqual(Clock c, Rational value) { return new AtomConstraint(Clock.getZeroClock(), c, value.negate(), true); }
    public static AtomConstraint greaterEqual(Clock c, int value) { return new AtomConstraint(Clock.getZeroClock(), c, Rational.valueOf(value).negate(), true); }


    /**
     * 取反当前原子约束。
     * 返回包含单个取反后原子约束的 DisjunctiveConstraint。
     * 如果取反结果恒为真或恒为假，则返回相应的常量。
     * 注意：返回的 DisjunctiveConstraint 需要知道时钟集，这里无法提供，
     * 调用者需要用 `DisjunctiveConstraint.of(Constraint.of(negatedAtom, clocks), clocks)` 来包装。
     * 因此，这个方法最好返回 AtomConstraint，让上层处理包装。
     *
     * @return 取反后的 AtomConstraint。
     * @throws IllegalStateException 如果取反结果是平凡的 TRUE 或 FALSE（这表示原约束是平凡的）。
     *         调用者应先检查原约束是否平凡。
     */
    public AtomConstraint negateAtom() {
        AtomConstraint negatedConstraint;
        // ¬(c1 - c2 <= V) => c2 - c1 < -V
        if (closed) {
            negatedConstraint = AtomConstraint.lessThan(this.clock2, this.clock1, this.upperbound.negate());
        }
        // ¬(c1 - c2 < V) => c2 - c1 <= -V
        else {
            negatedConstraint = AtomConstraint.lessEqual(this.clock2, this.clock1, this.upperbound.negate());
        }

        // 检查平凡情况 (例如 ¬(x-x <= 0) => x-x > 0 => FALSE)
        if (negatedConstraint.getClock1().equals(negatedConstraint.getClock2())) {
            int comparison = negatedConstraint.getUpperbound().compareTo(Rational.ZERO);
            // c - c < V (V <= 0) 或 c - c <= V (V < 0) -> 否定结果平凡为 FALSE
            if ((!negatedConstraint.isClosed() && comparison <= 0) || (negatedConstraint.isClosed() && comparison < 0)) {
                throw new IllegalStateException("AtomConstraint-negateAtom: 对平凡约束 " + this + " 取反导致逻辑 FALSE");
            }
            // c - c <= V (V >= 0) 或 c - c < V (V > 0) -> 否定结果平凡为 TRUE
            if ((negatedConstraint.isClosed() && comparison >= 0) || (!negatedConstraint.isClosed() && comparison > 0)) {
                throw new IllegalStateException("AtomConstraint-negateAtom: 对平凡约束 " + this + " 取反导致逻辑 TRUE");
            }
        }
        return negatedConstraint;
    }

    // equals, hashCode, toString, compareTo 保持不变
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AtomConstraint that = (AtomConstraint) o;
        return closed == that.closed &&
                Objects.equals(clock1, that.clock1) &&
                Objects.equals(clock2, that.clock2) &&
                Objects.equals(upperbound, that.upperbound);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clock1, clock2, upperbound, closed);
    }

    @Override
    public String toString() {
        String op = closed ? "<=" : "<";
        if (clock2.isZeroClock()) {
            return clock1 + " " + op + " " + upperbound;
        } else if (clock1.isZeroClock()) {
            String reversedOp = closed ? ">=" : ">";
            Rational negatedBound = upperbound.negate();
            return clock2 + " " + reversedOp + " " + negatedBound;
        } else {
            return clock1 + " - " + clock2 + " " + op + " " + upperbound;
        }
    }
    @Override
    public int compareTo(AtomConstraint other) {
        int clock1Comparison = this.clock1.compareTo(other.clock1);
        if (clock1Comparison != 0) {
            return clock1Comparison;
        }
        int clock2Comparison = this.clock2.compareTo(other.clock2);
        if (clock2Comparison != 0) {
            return clock2Comparison;
        }
        int boundComparison = this.upperbound.compareTo(other.upperbound);
        if (boundComparison != 0) {
            return boundComparison;
        }
        return Boolean.compare(this.closed, other.closed);
    }
}