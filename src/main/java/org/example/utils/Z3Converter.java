package org.example.utils;

import com.microsoft.z3.*;
import com.microsoft.z3.enumerations.Z3_decl_kind;
import org.example.Clock;
import org.example.DBM;
import org.example.constraint.AtomConstraint;
import org.example.constraint.Constraint;
import org.example.constraint.DisjunctiveConstraint;
import org.example.constraint.ValidityStatus;
import org.example.region.Region;

import java.math.BigInteger;
import java.util.*;

public class Z3Converter {
    public static Rational ratnum2Rational(RatNum input) {
        try {
            BigInteger num = input.getNumerator().getBigInteger();
            BigInteger den = input.getDenominator().getBigInteger();
            return Rational.valueOf(num, den);
        } catch (Z3Exception | ClassCastException e) {
            throw new ArithmeticException("Z3Converter: 无法从 RatNum 获取 BigInteger: " + input + " Error: " + e.getMessage());
        }
    }


    public static RatNum rational2Ratnum(Rational input, Context ctx) {

        if (input.isInfinity() || input.isNaN()) {
            throw new ArithmeticException("Z3Converter: 无法将 Infinity 或 NaN 直接转换为标准 Z3 实数/整数: " + input);
        }

        try {
            String rationalStr = input.toString();
            return ctx.mkReal(rationalStr);
        } catch (Z3Exception e) {
            throw new RuntimeException("Z3Converter: Z3 无法将字符串 '" + input + "' 解析为实数: " + e.getMessage(), e);
        } catch (ClassCastException e) {
            try {
                if (input.isInteger()) {
                    return ctx.mkReal(input.toString());
                } else {
                    throw new RuntimeException("Z3Converter: mkReal 未返回 RatNum，且输入不是整数: " + input, e);
                }
            } catch (Z3Exception | ClassCastException e2) {
                throw new RuntimeException("Z3Converter: 尝试 mkInt 失败: " + input, e2);
            }
        }
    }

    public static BoolExpr disjunctiveConstraint2Boolexpr(DisjunctiveConstraint input, Context ctx, Map<Clock, RealExpr> clockVarMap) {
        if (input.getKnownStatus() == ValidityStatus.FALSE) {
            return ctx.mkFalse();
        }
        return ctx.mkOr(input.getConstraints().stream()
                .map(c -> constraint2Boolexpr(c, ctx, clockVarMap))
                .toArray(BoolExpr[]::new));
    }

    public static BoolExpr constraint2Boolexpr(Constraint input, Context ctx, Map<Clock, RealExpr> clockVarMap) {
        if (input.getKnownStatus() == ValidityStatus.TRUE) {
            return ctx.mkTrue();
        }
        // 将每个原子约束转换为Z3表达式
        BoolExpr[] z3Constraints = input.getConstraints().stream()
                .map(ac -> atomConstraint2Boolexpr(ac, ctx, clockVarMap))
                .toArray(BoolExpr[]::new);

        // 构建合取表达式
        return ctx.mkAnd(z3Constraints);
    }

    public static BoolExpr atomConstraint2Boolexpr(AtomConstraint input, Context ctx, Map<Clock, RealExpr> clockVarMap) {
        Objects.requireNonNull(input, "AtomConstraint 输入不能为空");
        Objects.requireNonNull(ctx, "Z3 context 不能为空");
        Objects.requireNonNull(clockVarMap, "clockVarMap 不能为空");

        Clock c1 = input.getClock1();
        Clock c2 = input.getClock2();
        Rational boundValue = input.getUpperbound();
        boolean closed = input.isClosed();

        if (boundValue.isPositiveInfinity()) {
            return ctx.mkTrue(); // c1 - c2 < ∞ or c1 - c2 <= ∞
        }
        if (boundValue.isNegativeInfinity()) {
            // c1 - c2 <= -∞
            // c1 - c2 < -∞
            return ctx.mkFalse();
        }

        // 获取时钟对应的 Z3 实数变量，处理零时钟
        RealExpr expr1 = c1.isZeroClock() ? ctx.mkReal(0) : clockVarMap.get(c1);
        RealExpr expr2 = c2.isZeroClock() ? ctx.mkReal(0) : clockVarMap.get(c2);

        // 检查时钟变量是否存在
        if (expr1 == null && !c1.isZeroClock()) {
            throw new IllegalArgumentException("Z3Converter: clockVarMap 中缺少时钟变量: " + c1.getName());
        }
        if (expr2 == null && !c2.isZeroClock()) {
            throw new IllegalArgumentException("Z3Converter: clockVarMap 中缺少时钟变量: " + c2.getName());
        }

        // 计算差分表达式 c1 - c2
        ArithExpr<RealSort> difference = ctx.mkSub(expr1, expr2);

        // 将边界值转换为 Z3 实数表达式
        RatNum z3Bound = rational2Ratnum(boundValue, ctx);

        // 根据闭区间标志选择比较运算符
        if (closed) {
            // c1 - c2 <= V
            return ctx.mkLe(difference, z3Bound);
        } else {
            // c1 - c2 < V
            return ctx.mkLt(difference, z3Bound);
        }
    }

    public static BoolExpr region2Boolexpr(Region input, Context ctx, Map<Clock, RealExpr> clockVarMap) {
        List<BoolExpr> constraints = new ArrayList<>();

        // 1. 整数部分约束
        for (Map.Entry<Clock, Integer> entry : input.getIntegerParts().entrySet()) {
            Clock clock = entry.getKey();
            int intPart = entry.getValue();
            RealExpr clockExpr = clockVarMap.get(clock);
            int kappa = input.getConfig().getClockKappa(clock);

            if (intPart <= kappa) {
                // 当整数部分≤κ时: intPart ≤ c < intPart + 1
                constraints.add(ctx.mkGe(clockExpr, ctx.mkReal(intPart)));
                constraints.add(ctx.mkLt(clockExpr, ctx.mkReal(intPart + 1)));
            } else {
                // 当整数部分>κ时: c > κ
                constraints.add(ctx.mkGt(clockExpr, ctx.mkReal(kappa)));
            }
        }

        // 2. 零小数部分约束
        for (Clock clock : input.getZeroFractionClocks()) {
            RealExpr clockExpr = clockVarMap.get(clock);
            int intPart = input.getIntegerParts().get(clock);
            // 对于分数部分为0的时钟：c = intPart
            constraints.add(ctx.mkEq(clockExpr, ctx.mkReal(intPart)));
        }

        // 3. 非零小数部分的顺序约束
        if (!input.getFractionOrder().isEmpty()) {
            for (int i = 0; i < input.getFractionOrder().size(); i++) {
                Clock clock = input.getClockFromClockFraction(i);
                RealExpr clockExpr = clockVarMap.get(clock);
                int intPart = input.getIntegerParts().get(clock);

                // frac(c) > 0 约束
                constraints.add(ctx.mkGt(clockExpr, ctx.mkReal(intPart)));

                // frac(c) < 1 约束
                constraints.add(ctx.mkLt(clockExpr, ctx.mkReal(intPart + 1)));

                // 相邻时钟的小数部分顺序关系
                if (i > 0) {
                    Clock prevClock = input.getClockFromClockFraction(i - 1);
                    RealExpr prevClockExpr = clockVarMap.get(prevClock);
                    int prevIntPart = input.getIntegerParts().get(prevClock);

                    // frac(prev) < frac(curr) 约束
                    BoolExpr comparison = ctx.mkLt(
                            ctx.mkSub(prevClockExpr, ctx.mkReal(prevIntPart)),
                            ctx.mkSub(clockExpr, ctx.mkReal(intPart))
                    );
                    constraints.add(comparison);
                }
            }
        }

        // 合并所有约束
        if (constraints.isEmpty()) {
            return ctx.mkTrue();
        } else {
            return ctx.mkAnd(constraints.toArray(new BoolExpr[0]));
        }
    }

    public static DisjunctiveConstraint boolexpr2DisjunctiveConstraint(BoolExpr input, Context ctx, Map<Clock, RealExpr> clockVarMap, Set<Clock> allClocks) {

        if (input == null) {
            throw new IllegalArgumentException("Z3Converter: 输入不能为null");
        }

        // 处理基本常量
        if (input.isTrue()) {
            return DisjunctiveConstraint.trueConstraint(allClocks);
        }
        if (input.isFalse()) {
            return DisjunctiveConstraint.falseConstraint(allClocks);
        }

        // 尝试直接转换为单个 Constraint
        if (isConjunctive(input, ctx, clockVarMap)) {
            try {
                Constraint singleConstraint = boolexpr2Constraint(input, ctx, clockVarMap, allClocks);
                // 结果是 FALSE，返回 DisjunctiveConstraint.falseConstraint()
                if (Objects.equals(singleConstraint, Constraint.falseConstraint(allClocks))) {
                    return DisjunctiveConstraint.falseConstraint(allClocks);
                }
                // 如果是 TRUE 或普通约束，包装起来
                return DisjunctiveConstraint.of(allClocks, singleConstraint);
            } catch (IllegalArgumentException e) {
               throw new RuntimeException("Z3Converter: Conversion不连续: " + input, e);
            }
        }

        // 如果不能直接转换，检查是否是顶层 OR
        if (input.isApp() && input.getFuncDecl().getDeclKind() == Z3_decl_kind.Z3_OP_OR) {
            DisjunctiveConstraint result = DisjunctiveConstraint.falseConstraint(allClocks); // OR 的幺元
            for (Expr<?> arg : input.getArgs()) {
                if (arg instanceof BoolExpr) {
                    // 递归转换 OR 的每个分支
                    DisjunctiveConstraint branchConstraint = boolexpr2DisjunctiveConstraint((BoolExpr) arg, ctx, clockVarMap, allClocks);
                    result = result.or(branchConstraint);
                } else {
                    // OR 的参数必须是布尔表达式
                    throw new IllegalArgumentException("Z3Converter: OR表达式没有布尔值: " + arg + " in " + input);
                }
            }
            return result;
        }

        // 如果代码执行到这里，意味着表达式不是 TRUE/FALSE，
        // 不能直接转为 Constraint，也不是顶层 OR。可能是 NOT, ITE, AND (包含 OR 子项), Quantifier 等。后面再说吧。

        // 简单的 NOT 处理：
        if (input.isApp() && input.getFuncDecl().getDeclKind() == Z3_decl_kind.Z3_OP_NOT) {
            Expr<?> arg = input.getArgs()[0];
            if (arg instanceof BoolExpr) {
                // 1. 递归转换内部表达式
                DisjunctiveConstraint innerDc = boolexpr2DisjunctiveConstraint((BoolExpr) arg, ctx, clockVarMap, allClocks);
                // 2. 取反
                return innerDc.negate();
            } else {
                throw new IllegalArgumentException("Z3Converter: not里面没有布尔值：: " + arg + " in " + input);
            }
        }

        throw new IllegalArgumentException(
                "Z3Converter: 复杂约束 " + input + "暂时无法解决");
    }

    public static Constraint boolexpr2Constraint(BoolExpr input, Context ctx, Map<Clock, RealExpr> clockVarMap, Set<Clock> allClocks) {

        Set<AtomConstraint> atoms = new HashSet<>();
        Optimize optimizer = ctx.mkOptimize();
        optimizer.Assert(input);

        List<Clock> clocksList = new ArrayList<>(allClocks);
        clocksList.remove(Clock.getZeroClock());

        // 1. 寻找每个时钟 ci 的上下界 (ci - x0)
        for (Clock ci : clocksList) {
            RealExpr ciVar = clockVarMap.get(ci);
            if (ciVar == null) {
                continue;
            }

            // 找上界 ci <= V
            optimizer.Push();
            // ****************************************************
            // 修改点：获取 Handle 对象
            Optimize.Handle<RealSort> maxHandle = optimizer.MkMaximize(ciVar);
            // ****************************************************
            Status status = optimizer.Check();
            if (status == Status.SATISFIABLE) {
                // ****************************************************
                // 修改点：通过 Handle 获取上界
                Expr<RealSort> upperBoundExpr = maxHandle.getUpper();
                // ****************************************************
                Rational upperBound = parseNumeral(upperBoundExpr);
                if (upperBound != null) {
                    optimizer.Push();
                    optimizer.Assert(ctx.mkEq(ciVar, upperBoundExpr));
                    boolean isNonStrict = (optimizer.Check() == Status.SATISFIABLE);
                    optimizer.Pop();
                    if (isNonStrict) {
                        atoms.add(AtomConstraint.lessEqual(ci, Clock.getZeroClock(), upperBound));
                    } else {
                        atoms.add(AtomConstraint.lessThan(ci, Clock.getZeroClock(), upperBound));
                    }
                }
            }
            optimizer.Pop();

            // 找下界 ci >= V
            optimizer.Push();
            // ****************************************************
            // 修改点：获取 Handle 对象
            Optimize.Handle<RealSort> minHandle = optimizer.MkMinimize(ciVar);
            // ****************************************************
            status = optimizer.Check();
            if (status == Status.SATISFIABLE) {
                // ****************************************************
                // 修改点：通过 Handle 获取下界
                Expr<RealSort> lowerBoundExpr = minHandle.getLower();
                // ****************************************************
                Rational lowerBound = parseNumeral(lowerBoundExpr);
                if (lowerBound != null) {
                    optimizer.Push();
                    optimizer.Assert(ctx.mkEq(ciVar, lowerBoundExpr));
                    boolean isNonStrict = (optimizer.Check() == Status.SATISFIABLE);
                    optimizer.Pop();
                    if (isNonStrict) {
                        atoms.add(AtomConstraint.lessEqual(Clock.getZeroClock(), ci, lowerBound.negate()));
                    } else {
                        atoms.add(AtomConstraint.lessThan(Clock.getZeroClock(), ci, lowerBound.negate()));
                    }
                }
            }
            optimizer.Pop();
        }

        // 2. 寻找每对时钟 ci - cj 的上下界
        for (int i = 0; i < clocksList.size(); i++) {
            for (int j = i + 1; j < clocksList.size(); j++) {
                Clock ci = clocksList.get(i);
                Clock cj = clocksList.get(j);
                RealExpr ciVar = clockVarMap.get(ci);
                RealExpr cjVar = clockVarMap.get(cj);
                if (ciVar == null || cjVar == null) {
                    continue;
                }

                ArithExpr<RealSort> diff = ctx.mkSub(ciVar, cjVar);

                // 找上界 ci - cj <= V
                optimizer.Push();
                // ****************************************************
                Optimize.Handle<RealSort> maxDiffHandle = optimizer.MkMaximize(diff);
                // ****************************************************
                Status status = optimizer.Check();
                if (status == Status.SATISFIABLE) {
                    // ****************************************************
                    Expr<RealSort> upperBoundExpr = maxDiffHandle.getUpper();
                    // ****************************************************
                    Rational upperBound = parseNumeral(upperBoundExpr);
                    if (upperBound != null) {
                        optimizer.Push();
                        optimizer.Assert(ctx.mkEq(diff, upperBoundExpr));
                        boolean isNonStrict = (optimizer.Check() == Status.SATISFIABLE);
                        optimizer.Pop();
                        if (isNonStrict) {
                            atoms.add(AtomConstraint.lessEqual(ci, cj, upperBound));
                        } else {
                            atoms.add(AtomConstraint.lessThan(ci, cj, upperBound));
                        }
                    }
                }
                optimizer.Pop();

                // 找下界 ci - cj >= V
                optimizer.Push();
                // ****************************************************
                Optimize.Handle<RealSort> minDiffHandle = optimizer.MkMinimize(diff);
                // ****************************************************
                status = optimizer.Check();
                if (status == Status.SATISFIABLE) {
                    // ****************************************************
                    Expr<RealSort> lowerBoundExpr = minDiffHandle.getLower();
                    // ****************************************************
                    Rational lowerBound = parseNumeral(lowerBoundExpr);
                    if (lowerBound != null) {
                        optimizer.Push();
                        optimizer.Assert(ctx.mkEq(diff, lowerBoundExpr));
                        boolean isNonStrict = (optimizer.Check() == Status.SATISFIABLE);
                        optimizer.Pop();
                        if (isNonStrict) {
                            atoms.add(AtomConstraint.lessEqual(cj, ci, lowerBound.negate()));
                        } else {
                            atoms.add(AtomConstraint.lessThan(cj, ci, lowerBound.negate()));
                        }
                    }
                }
                optimizer.Pop();
            }
        }

        return new Constraint(atoms, allClocks);
    }

    public static AtomConstraint boolexpr2AtomConstraint(BoolExpr input, Context ctx, Map<Clock, RealExpr> clockVarMap, Set<Clock> allClocks) {
        if (input == null) {
            throw new IllegalArgumentException("Z3Converter: 输入为空");
        }

        // 1. 处理 NOT (取反操作符)
        boolean isNegated = false;
        Expr<?> coreExpr = input;
        if (input.isApp() && input.getFuncDecl().getDeclKind() == Z3_decl_kind.Z3_OP_NOT) {
            if (input.getNumArgs() != 1) {
                throw new IllegalArgumentException("Z3Converter: NOT 参数量不为1: " + input);
            }
            isNegated = true;
            coreExpr = input.getArgs()[0];
            if (!(coreExpr instanceof BoolExpr)) {
                throw new IllegalArgumentException("Z3Converter: NOT 参数非布尔: " + input);
            }
        }

        if (!coreExpr.isApp()) {
            throw new IllegalArgumentException("Z3Converter: 核心表达式非 Z3 应用: " + coreExpr);
        }

        FuncDecl<?> decl = coreExpr.getFuncDecl();
        Expr<?>[] args = coreExpr.getArgs();
        if (args.length != 2) {
            throw new IllegalArgumentException("Z3Converter: 核心表达式非二元: " + coreExpr);
        }

        // 2. 确定原始操作符 (处理 NOT 之前)
        Operator originalOp = getOperator(decl.getDeclKind(), coreExpr);

        // 3. 如果有 NOT，反转操作符
        Operator effectiveOp = isNegated ? negateOperator(originalOp, input) : originalOp;

        // 我们只支持 < 和 <= 的 AtomConstraint，所以 GT/GE/EQ 需要转换或拒绝
        if (effectiveOp == Operator.EQ) {
            throw new IllegalArgumentException("Z3Converter: 不支持转换等于约束: " + input);
        }

        // 4. 解析参数 arg0 和 arg1
        Expr<?> arg0 = args[0];
        Expr<?> arg1 = args[1];

        // 尝试匹配模式: c1 - c2 op V
        if (arg0.isApp() && arg0.getFuncDecl().getDeclKind() == Z3_decl_kind.Z3_OP_SUB && isNumeral(arg1)) {
            Expr<?>[] subArgs = arg0.getArgs();
            if (subArgs.length == 2) {
                Clock c1 = findClockForExpr(subArgs[0], clockVarMap, true, ctx); // Allow zero clock
                Clock c2 = findClockForExpr(subArgs[1], clockVarMap, true, ctx); // Allow zero clock
                Rational V = parseNumeral(arg1);
                if (c1 != null && c2 != null && V != null) {
                    // 匹配成功: (c1 - c2) op V
                    return createAtomConstraint(c1, c2, effectiveOp, V, input);
                }
            }
        }

        // 尝试匹配模式: c1 op c2 + V (等价于 c1 - c2 op V)
        if (arg1.isApp() && arg1.getFuncDecl().getDeclKind() == Z3_decl_kind.Z3_OP_ADD && isKnownClockVar(arg0, clockVarMap)) {
            Expr<?>[] addArgs = arg1.getArgs();
            if (addArgs.length == 2) {
                Clock c1 = findClockForExpr(arg0, clockVarMap, false, ctx); // Must be real clock
                Clock c2 = findClockForExpr(addArgs[0], clockVarMap, false, ctx);
                Rational V = parseNumeral(addArgs[1]);
                if (c1 != null && c2 != null && V != null) {
                    // 匹配成功: c1 op (c2 + V) => c1 - c2 op V
                    return createAtomConstraint(c1, c2, effectiveOp, V, input);
                }
                // 检查另一种加法顺序
                c2 = findClockForExpr(addArgs[1], clockVarMap, false, ctx);
                V = parseNumeral(addArgs[0]);
                if (c1 != null && c2 != null && V != null) {
                    // 匹配成功: c1 op (V + c2) => c1 - c2 op V
                    return createAtomConstraint(c1, c2, effectiveOp, V, input);
                }
            }
        }
        // 尝试匹配模式: c1 + V op c2 (等价于 c2 - c1 op' -V)
        if (arg0.isApp() && arg0.getFuncDecl().getDeclKind() == Z3_decl_kind.Z3_OP_ADD && isKnownClockVar(arg1, clockVarMap)) {
            Expr<?>[] addArgs = arg0.getArgs();
            if (addArgs.length == 2) {
                Clock c2 = findClockForExpr(arg1, clockVarMap, false, ctx); // Must be real clock
                Clock c1 = findClockForExpr(addArgs[0], clockVarMap, false, ctx);
                Rational V = parseNumeral(addArgs[1]);
                if (c1 != null && c2 != null && V != null) {
                    // 匹配成功: (c1 + V) op c2 => c2 - c1 op' -V
                    Operator flippedOp = flipOperator(effectiveOp); // GT -> LT, GE -> LE, etc.
                    return createAtomConstraint(c2, c1, flippedOp, V.negate(), input);
                }
                // 检查另一种加法顺序
                c1 = findClockForExpr(addArgs[1], clockVarMap, false, ctx);
                V = parseNumeral(addArgs[0]);
                if (c1 != null && c2 != null && V != null) {
                    // 匹配成功: (V + c1) op c2 => c2 - c1 op' -V
                    Operator flippedOp = flipOperator(effectiveOp);
                    return createAtomConstraint(c2, c1, flippedOp, V.negate(), input);
                }
            }
        }


        // 尝试匹配模式: c1 op V
        if (isKnownClockVar(arg0, clockVarMap) && isNumeral(arg1)) {
            Clock c1 = findClockForExpr(arg0, clockVarMap, false, ctx);
            Rational V = parseNumeral(arg1);
            if (c1 != null && V != null) {
                // 匹配成功: c1 op V (c1 - x0 op V)
                return createAtomConstraint(c1, Clock.getZeroClock(), effectiveOp, V, input);
            }
        }

        // 尝试匹配模式: V op c2
        if (isNumeral(arg0) && isKnownClockVar(arg1, clockVarMap)) {
            Rational V = parseNumeral(arg0);
            Clock c2 = findClockForExpr(arg1, clockVarMap, false, ctx);
            if (V != null && c2 != null) {
                // 匹配成功: V op c2 (x0 - c2 op' -V)
                Operator flippedOp = flipOperator(effectiveOp); // GT -> LT, GE -> LE, etc.
                return createAtomConstraint(Clock.getZeroClock(), c2, flippedOp, V.negate(), input);
            }
        }

        // 如果所有模式都不匹配
        throw new IllegalArgumentException("Z3Converter: 无法将表达式解析为支持的 AtomConstraint 形式: " + input + " (Core: " + coreExpr + ")");
    }


    private enum Operator { LT, LE, GT, GE, EQ, INVALID }

    private static Operator getOperator(Z3_decl_kind kind, Expr<?> contextExpr) {
        return switch (kind) {
            case Z3_OP_LT -> Operator.LT;
            case Z3_OP_LE -> Operator.LE;
            case Z3_OP_GT -> Operator.GT;
            case Z3_OP_GE -> Operator.GE;
            case Z3_OP_EQ -> Operator.EQ;
            default -> throw new IllegalArgumentException("Z3Converter: 不支持的操作符类型: " + kind + " in " + contextExpr);
        };
    }

    private static Operator negateOperator(Operator op, Expr<?> contextExpr) {
        return switch (op) {
            case LT -> Operator.GE; // ¬(<) is >=
            case LE -> Operator.GT; // ¬(<=) is >
            case GT -> Operator.LE; // ¬(>) is <=
            case GE -> Operator.LT; // ¬(>=) is <
            case EQ -> throw new IllegalArgumentException("Z3Converter: 不支持对等于约束取反: " + contextExpr);
            case INVALID -> Operator.INVALID;
        };
    }

    private static Operator flipOperator(Operator op) {
        return switch (op) {
            case LT -> Operator.GT;
            case LE -> Operator.GE;
            case GT -> Operator.LT;
            case GE -> Operator.LE;
            case EQ -> Operator.EQ; // 等于号翻转不变
            case INVALID -> Operator.INVALID;
        };
    }


    private static Rational parseNumeral(Expr<?> expr) {
        if (expr instanceof RatNum) {
            return ratnum2Rational((RatNum) expr); // 使用修正后的转换
        } else if (expr instanceof IntNum) {
            try {
                // 优先使用 BigInteger
                return Rational.valueOf(((IntNum) expr).getBigInteger());
            } catch (Z3Exception e) {
                // 处理获取失败
                return null;
            }
        }
        return null;
    }

    private static Clock findClockForExpr(Expr<?> expr, Map<Clock, RealExpr> clockVarMap, boolean allowZero, Context ctx) {
        if (allowZero && expr.equals(ctx.mkReal(0))) {
            return Clock.getZeroClock();
        }
        for (Map.Entry<Clock, RealExpr> entry : clockVarMap.entrySet()) {
            if (entry.getValue().equals(expr)) {
                // 确保返回的不是零时钟，除非 allowZero 为 true 且匹配 ctx.mkReal(0)
                if (!entry.getKey().isZeroClock()) {
                    return entry.getKey();
                }
            }
        }
        return null; // 未找到或不允许零时钟但匹配了零
    }

    private static AtomConstraint createAtomConstraint(Clock c1, Clock c2, Operator op, Rational value, Expr<?> contextExpr) {
        return switch (op) {
            case LT -> AtomConstraint.lessThan(c1, c2, value);    // c1 - c2 < V
            case LE -> AtomConstraint.lessEqual(c1, c2, value);   // c1 - c2 <= V
            // GT 和 GE 需要转换为 LT/LE 形式
            case GT -> AtomConstraint.lessThan(c2, c1, value.negate()); // c1 - c2 > V  => c2 - c1 < -V
            case GE -> AtomConstraint.lessEqual(c2, c1, value.negate());// c1 - c2 >= V => c2 - c1 <= -V
            case EQ, INVALID -> throw new IllegalArgumentException("Z3Converter: 无法为操作符 " + op + " 创建 AtomConstraint from " + contextExpr);
        };
    }

    public static BoolExpr DBM2Boolexpr(DBM dbm, Context ctx, Map<Clock, RealExpr> clockVarMap) {
        Objects.requireNonNull(dbm, "DBM为null");
        Objects.requireNonNull(ctx, "Context为null");
        Objects.requireNonNull(clockVarMap, "clockVarMap为null");

        List<BoolExpr> constraints = new ArrayList<>();
        List<Clock> clockList = dbm.getClockList();
        int size = dbm.getSize();

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                Rational value = dbm.getValue(i, j);

                // 跳过无穷约束 (ci - cj < inf 或 ci - cj <= inf)
                if (value.equals(Rational.INFINITY)) {
                    continue;
                }

                boolean closed = dbm.getClosed(i, j);
                Clock ci = clockList.get(i);
                Clock cj = clockList.get(j);

                // 获取 Z3 变量
                RealExpr expr_i = ci.isZeroClock() ? ctx.mkReal(0) : clockVarMap.get(ci);
                RealExpr expr_j = cj.isZeroClock() ? ctx.mkReal(0) : clockVarMap.get(cj);

                // 检查变量是否存在
                if (expr_i == null && !ci.isZeroClock()) {
                    throw new IllegalArgumentException("clockVarMap没有这个时钟: " + ci.getName());
                }
                if (expr_j == null && !cj.isZeroClock()) {
                    throw new IllegalArgumentException("clockVarMap没有这个时钟: " + cj.getName());
                }

                // 差分表达式 ci - cj
                ArithExpr<RealSort> difference = ctx.mkSub(expr_i, expr_j);

                // 构建边界值
                RatNum z3_value = rational2Ratnum(value, ctx);

                // 构建比较表达式
                BoolExpr constraint;
                if (closed) {
                    constraint = ctx.mkLe(difference, z3_value); // ci - cj <= V
                } else {
                    constraint = ctx.mkLt(difference, z3_value); // ci - cj < V
                }
                constraints.add(constraint);
            }
        }

        if (constraints.isEmpty()) {
            return ctx.mkTrue();
        } else {
            return ctx.mkAnd(constraints.toArray(new BoolExpr[0]));
        }
    }

    public static boolean isConjunctive(BoolExpr expr, Context ctx, Map<Clock, RealExpr> clockVarMap) {
        if (expr == null || ctx == null || clockVarMap == null) {
            return false;
        }
        // 简化：如果 boolexpr2Constraint 能成功转换，就认为是合取的
        // （这假设 boolexpr2Constraint 只接受合取形式）
        // 或者，我们可以保留递归检查，但只检查 AND 和 "看起来像原子" 的节点
        return isConjunctiveRecursive(expr, ctx, clockVarMap, new HashSet<>());
    }

    private static boolean isConjunctiveRecursive(
            Expr<?> expr, Context ctx, Map<Clock, RealExpr> clockVarMap, Set<Expr<?>> visited) {

        if (expr == null || !(expr instanceof BoolExpr) || !visited.add(expr)) {
            return false; // null, 非布尔, 或循环
        }

        BoolExpr boolExpr = (BoolExpr) expr;

        if (boolExpr.isTrue() || boolExpr.isFalse()) {
            return true; // 常量是合取的
        }

        if (!boolExpr.isApp()) {
            return false; // 不是 Z3 应用
        }

        FuncDecl<?> decl = boolExpr.getFuncDecl();
        Z3_decl_kind kind = decl.getDeclKind();

        if (kind == Z3_decl_kind.Z3_OP_AND) {
            // 如果是 AND，递归检查所有子节点
            for (Expr<?> arg : boolExpr.getArgs()) {
                if (!isConjunctiveRecursive(arg, ctx, clockVarMap, visited)) {
                    return false;
                }
            }
            return true; // 所有子节点都满足
        } else {
            // 如果不是 AND, TRUE, FALSE，则它必须是单个原子约束
            // 我们不在这里做复杂解析，而是假设 boolexpr2AtomConstraint 能处理它
            // 只需要检查它是不是一个基本的比较操作符
            return kind == Z3_decl_kind.Z3_OP_LT ||
                    kind == Z3_decl_kind.Z3_OP_LE ||
                    kind == Z3_decl_kind.Z3_OP_GT ||
                    kind == Z3_decl_kind.Z3_OP_GE ||
                    (kind == Z3_decl_kind.Z3_OP_EQ && boolExpr.getArgs().length == 2 && boolExpr.getArgs()[0].getSort() instanceof ArithSort);
        }
    }

    private static boolean isKnownClockVar(Expr<?> expr, Map<Clock, RealExpr> clockVarMap) {
        // 检查是否是 RealExpr 常量并且存在于 clockVarMap 的值中
        // Z3有时可能用 IntExpr 表示整数时钟，但我们通常映射到 RealExpr
        return expr instanceof RealExpr && expr.isConst() && clockVarMap.containsValue(expr);
    }

    // 检查表达式是否为数值常量
    private static boolean isNumeral(Expr<?> expr) {
        return expr instanceof ArithExpr && ((ArithExpr<?>) expr).isNumeral();
    }
    
}