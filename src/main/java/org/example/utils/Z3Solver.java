package org.example.utils;

import com.microsoft.z3.*;
import org.example.Clock;
import org.example.ClockValuation;
import org.example.constraint.Constraint;
import org.example.constraint.DisjunctiveConstraint;
import org.example.constraint.ValidityStatus;
import org.example.region.Region;
// 假设 Rational 和 Z3Converter 类存在且功能正确
// import org.example.utils.Rational;
// import org.example.utils.Z3Converter;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 提供与 Z3 SMT Solver 交互的功能，用于检查约束的可满足性和有效性，
 * 以及解决特定问题，例如计算到达目标区域所需的时间延迟。
 *
 * 此类采用每个线程独立的 Z3 Context 和 Z3 Solver 实例，并利用增量求解
 * （push/pop）来优化相关查询序列的性能。它设计为可以处理不同时钟集合的查询。
 *
 * @author Ayalyt (重构基于用户需求)
 */
public class Z3Solver implements AutoCloseable {

    // 为每个线程提供独立的 Z3 Context
    private final ThreadLocal<Context> threadCtx;
    // 为每个线程提供独立的 Z3 Solver，用于增量求解
    private final ThreadLocal<Solver> threadSolver;

    /**
     * 创建一个新的 Z3Solver 实例。
     * 初始化 ThreadLocal，以便每个线程首次使用时创建自己的 Z3 Context 和 Solver。
     * Solver 初始化为空，不预设任何约束。
     */
    public Z3Solver() {
        this.threadCtx = ThreadLocal.withInitial(Context::new);
        this.threadSolver = ThreadLocal.withInitial(() -> {
            Context ctx = threadCtx.get(); // 获取当前线程的 Context
            Solver solver = ctx.mkSolver();
            System.out.println("为线程 " + Thread.currentThread().getId() + " 初始化空的 Z3 Solver");
            return solver;
        });
    }

    /**
     * 获取当前线程的 Z3 Context。
     * @return 当前线程关联的 Z3 Context。
     */
    private Context getContext() {
        return threadCtx.get();
    }

    /**
     * 获取当前线程的 Z3 Solver。
     * @return 当前线程关联的 Z3 Solver。
     */
    private Solver getSolver() {
        Solver solver = threadSolver.get();
        if (solver == null) {
            // 理论上不应发生，withInitial 会处理
            throw new IllegalStateException("线程 Solver 未初始化！");
        }
        return solver;
    }

    // --- 核心约束检查方法 (使用增量 Solver) ---

    /**
     * 检查给定的合取约束 (Constraint) 是否可满足。
     * 使用增量求解，在当前线程的 Solver 上执行 push/pop。
     *
     * @param constraint 要检查的合取约束。
     * @return 如果可满足，则返回 true；否则返回 false。
     * @throws Z3SolverException 如果发生 Z3 错误。
     */
    public boolean isSatisfiable(Constraint constraint) {
        Objects.requireNonNull(constraint, "Constraint 不能为空");

        // 快速检查已知状态
        ValidityStatus knownStatus = constraint.getKnownStatus();
        if (knownStatus == ValidityStatus.FALSE) return false;
        // 注意：不能在这里检查 TRUE，因为可满足性不等于恒真

        Solver solver = getSolver();
        Context ctx = getContext();
        boolean result = false;
        List<BoolExpr> addedAssertions = new ArrayList<>(); // 用于调试

        try {
            solver.push(); // 创建回溯点

            // 1. 动态创建本次查询所需的时钟变量
            Map<Clock, RealExpr> clockVarMap = createClockVarMap(constraint.getClocks(), ctx);

            // 2. 添加本次查询的特定约束
            BoolExpr formula = Z3Converter.constraint2Boolexpr(constraint, ctx, clockVarMap);
            solver.add(formula);
            addedAssertions.add(formula);

            // 3. 添加相关的非负约束 (c >= 0)
            //    这些约束只在本次 push/pop 范围内有效
            addNonNegativityConstraints(solver, clockVarMap, ctx, addedAssertions);

            // 4. 执行检查
            Status status = solver.check();
            result = (status == Status.SATISFIABLE);

            // System.out.println("isSatisfiable check for " + constraint + ": " + status); // 调试输出

        } catch (Z3Exception e) {
            handleZ3Exception("isSatisfiable", e, addedAssertions); // 统一处理异常
            throw new Z3SolverException("Z3 错误 (isSatisfiable-inc): " + e.getMessage(), e);
        } catch (Exception e) { // 捕获 Z3Converter 等可能的其他异常
            handleGenericException("isSatisfiable", e, addedAssertions);
            throw new Z3SolverException("意外错误 (isSatisfiable-inc): " + e.getMessage(), e);
        } finally {
            popSolver(solver); // 统一处理 pop
        }
        return result;
    }

    /**
     * 检查给定的合取约束 (Constraint) 是否恒为真。
     * 通过检查其否定形式是否不可满足来实现。
     * 使用增量求解。
     *
     * @param constraint 要检查的合取约束。
     * @return 如果恒为真，则返回 true；否则返回 false。
     * @throws Z3SolverException 如果发生 Z3 错误。
     */
    public boolean isTrue(Constraint constraint) {
        Objects.requireNonNull(constraint, "Constraint 不能为空");

        // 快速检查已知状态
        ValidityStatus knownStatus = constraint.getKnownStatus();
        if (knownStatus == ValidityStatus.TRUE) return true;
        if (knownStatus == ValidityStatus.FALSE) return false;

        Solver solver = getSolver();
        Context ctx = getContext();
        boolean result = false;
        List<BoolExpr> addedAssertions = new ArrayList<>(); // 用于调试

        try {
            solver.push(); // 创建回溯点

            // 1. 动态创建时钟变量
            Map<Clock, RealExpr> clockVarMap = createClockVarMap(constraint.getClocks(), ctx);

            // 2. 添加原始公式的 *否定* 形式
            BoolExpr originalFormula = Z3Converter.constraint2Boolexpr(constraint, ctx, clockVarMap);
            BoolExpr negatedFormula = ctx.mkNot(originalFormula);
            solver.add(negatedFormula);
            addedAssertions.add(negatedFormula);

            // 3. 添加相关的非负约束 (c >= 0)
            addNonNegativityConstraints(solver, clockVarMap, ctx, addedAssertions);

            // 4. 执行检查
            Status status = solver.check();
            // 如果否定形式不可满足 (UNSAT)，则原公式恒为真
            result = (status == Status.UNSATISFIABLE);

            // System.out.println("isTrue check for " + constraint + " (negation sat check): " + status); // 调试输出

        } catch (Z3Exception e) {
            handleZ3Exception("isTrue", e, addedAssertions);
            throw new Z3SolverException("Z3 错误 (isTrue-inc): " + e.getMessage(), e);
        } catch (Exception e) {
            handleGenericException("isTrue", e, addedAssertions);
            throw new Z3SolverException("意外错误 (isTrue-inc): " + e.getMessage(), e);
        } finally {
            popSolver(solver); // 回滚
        }
        return result;
    }

    /**
     * 检查给定的析取约束 (DisjunctiveConstraint) 是否可满足。
     * 使用增量求解。
     *
     * @param dc 要检查的析取约束。
     * @return 如果可满足，则返回 true；否则返回 false。
     * @throws Z3SolverException 如果发生 Z3 错误。
     */
    public boolean isSatisfiable(DisjunctiveConstraint dc) {
        Objects.requireNonNull(dc, "DisjunctiveConstraint 不能为空");

        // 快速检查已知状态
        ValidityStatus knownStatus = dc.getKnownStatus();
        if (knownStatus == ValidityStatus.FALSE) return false;

        Solver solver = getSolver();
        Context ctx = getContext();
        boolean result = false;
        List<BoolExpr> addedAssertions = new ArrayList<>();

        try {
            solver.push();

            // 1. 动态创建时钟变量 (需要获取析取约束涉及的所有时钟)
            Map<Clock, RealExpr> clockVarMap = createClockVarMap(dc.getClocks(), ctx);

            // 2. 添加析取公式
            BoolExpr formula = Z3Converter.disjunctiveConstraint2Boolexpr(dc, ctx, clockVarMap);
            solver.add(formula);
            addedAssertions.add(formula);

            // 3. 添加相关的非负约束
            addNonNegativityConstraints(solver, clockVarMap, ctx, addedAssertions);

            // 4. 执行检查
            Status status = solver.check();
            result = (status == Status.SATISFIABLE);

            // System.out.println("isSatisfiable check for " + dc + ": " + status); // 调试输出

        } catch (Z3Exception e) {
            handleZ3Exception("isSatisfiable(DC)", e, addedAssertions);
            throw new Z3SolverException("Z3 错误 (isSatisfiable(DC)-inc): " + e.getMessage(), e);
        } catch (Exception e) {
            handleGenericException("isSatisfiable(DC)", e, addedAssertions);
            throw new Z3SolverException("意外错误 (isSatisfiable(DC)-inc): " + e.getMessage(), e);
        } finally {
            popSolver(solver);
        }
        return result;
    }

    /**
     * 检查给定的析取约束 (DisjunctiveConstraint) 是否恒为真。
     * 通过检查其否定形式是否不可满足来实现。
     * 使用增量求解。
     *
     * @param dc 要检查的析取约束。
     * @return 如果恒为真，则返回 true；否则返回 false。
     * @throws Z3SolverException 如果发生 Z3 错误。
     */
    public boolean isTrue(DisjunctiveConstraint dc) {
        Objects.requireNonNull(dc, "DisjunctiveConstraint 不能为空");

        // 快速检查已知状态
        ValidityStatus knownStatus = dc.getKnownStatus();
        if (knownStatus == ValidityStatus.TRUE) return true;
        if (knownStatus == ValidityStatus.FALSE) return false;

        Solver solver = getSolver();
        Context ctx = getContext();
        boolean result = false;
        List<BoolExpr> addedAssertions = new ArrayList<>();

        try {
            solver.push();

            // 1. 动态创建时钟变量
            Map<Clock, RealExpr> clockVarMap = createClockVarMap(dc.getClocks(), ctx);

            // 2. 添加析取公式的 *否定*
            BoolExpr originalFormula = Z3Converter.disjunctiveConstraint2Boolexpr(dc, ctx, clockVarMap);
            BoolExpr negatedFormula = ctx.mkNot(originalFormula);
            solver.add(negatedFormula);
            addedAssertions.add(negatedFormula);

            // 3. 添加相关的非负约束
            addNonNegativityConstraints(solver, clockVarMap, ctx, addedAssertions);

            // 4. 执行检查
            Status status = solver.check();
            // 如果否定形式不可满足 (UNSAT)，则原公式恒为真
            result = (status == Status.UNSATISFIABLE);

            // System.out.println("isTrue check for " + dc + " (negation sat check): " + status); // 调试输出

        } catch (Z3Exception e) {
            handleZ3Exception("isTrue(DC)", e, addedAssertions);
            throw new Z3SolverException("Z3 错误 (isTrue(DC)-inc): " + e.getMessage(), e);
        } catch (Exception e) {
            handleGenericException("isTrue(DC)", e, addedAssertions);
            throw new Z3SolverException("意外错误 (isTrue(DC)-inc): " + e.getMessage(), e);
        } finally {
            popSolver(solver);
        }
        return result;
    }

    /**
     * 使用 Z3 查找一个满足条件的非负延迟 'd'，使得将此延迟应用于给定的
     * `currentValuation` 后，得到的时钟状态满足 `targetRegion` 的定义。
     * 使用增量求解。
     *
     * @param currentValuation 当前的时钟赋值。
     * @param targetRegion     目标区域定义。
     * @return 如果找到满足条件的非负延迟 'd'，则返回包含该延迟的 Optional；
     *         如果不存在这样的延迟（不可满足）或发生错误，则返回 Optional.empty()。
     * @throws Z3SolverException 如果在 Z3 求解过程中发生错误。
     */
    public Optional<Rational> solveDelay(ClockValuation currentValuation, Region targetRegion) {
        Objects.requireNonNull(currentValuation, "当前 ClockValuation 不能为空");
        Objects.requireNonNull(targetRegion, "目标 Region 不能为空");

        Solver solver = getSolver();
        Context ctx = getContext();
        Optional<Rational> result = Optional.empty();
        List<BoolExpr> addedAssertions = new ArrayList<>();

        try {
            solver.push();

            // 1. 定义延迟变量 'd' (局部于本次 push/pop)
            RealExpr d = ctx.mkRealConst("d"); // 使用简单名称 "d" 即可

            // 2. 添加 d >= 0 约束
            BoolExpr dNonNeg = ctx.mkGe(d, Z3Converter.rational2Ratnum(Rational.ZERO, ctx));
            solver.add(dNonNeg);
            addedAssertions.add(dNonNeg);

            // 3. 获取当前查询所需的时钟变量 (包括 currentValuation 和 targetRegion 涉及的)
            Set<Clock> relevantClocks = new HashSet<>(currentValuation.getClocks());
            // 假设 Region 有方法 getReferencedClocks()
            relevantClocks.addAll(targetRegion.getConfig().getClocks());
            Map<Clock, RealExpr> clockVarMap = createClockVarMap(relevantClocks, ctx);

            // 4. 添加区域相关的约束 (v+d 满足区域定义)
            //    这需要一个辅助方法来构建这些约束
            List<BoolExpr> regionConstraints = buildRegionConstraintsForDelay(currentValuation, targetRegion, d, ctx);
            solver.add(regionConstraints.toArray(new BoolExpr[0]));
            addedAssertions.addAll(regionConstraints);

            // 5. 添加相关时钟的非负约束 c >= 0
            //    注意：这里添加的是 relevantClocks 的非负约束
            addNonNegativityConstraints(solver, clockVarMap, ctx, addedAssertions);

            // 6. 执行检查
            Status status = solver.check();
            // System.out.println("solveDelay check status: " + status); // 调试输出

            // 7. 处理结果
            if (status == Status.SATISFIABLE) {
                Model model = solver.getModel();
                Expr<?> resultExpr = model.evaluate(d, false);
                Rational delayResult = parseZ3ResultToRational(resultExpr, ctx);
                if (delayResult != null) {
                    // 确保结果非负
                    if (delayResult.compareTo(Rational.ZERO) < 0) {
                        // System.out.println("Z3 返回了一个微小的负延迟 {}，强制修正为零。" + delayResult);
                        delayResult = Rational.ZERO;
                    }
                    result = Optional.of(delayResult);
                } else {
                    System.err.println("无法从 Z3 模型中解析延迟 'd' 的值: " + resultExpr);
                }
            } else {
                // System.out.println("solveDelay: 未找到满足条件的延迟，状态: " + status);
            }

        } catch (Z3Exception e) {
            handleZ3Exception("solveDelay", e, addedAssertions);
            throw new Z3SolverException("Z3 错误 (solveDelay-inc): " + e.getMessage(), e);
        } catch (Exception e) {
            handleGenericException("solveDelay", e, addedAssertions);
            throw new Z3SolverException("意外错误 (solveDelay-inc): " + e.getMessage(), e);
        } finally {
            popSolver(solver);
        }
        return result;
    }


    // --- 资源管理 ---

    /**
     * 清理与当前线程关联的 Z3 Context 和 Solver 资源。
     * 调用此方法后，当前线程不应再使用此 Z3Solver 实例。
     */
    @Override
    public void close() {
        // 移除 Solver 和 Context
        // 移除的顺序通常不重要，但先移除依赖者 (Solver) 可能更清晰
        threadSolver.remove();
        threadCtx.remove();
        System.out.println("线程 " + Thread.currentThread().getId() + " 的 Z3 资源已清理");
    }

    // --- 私有辅助方法 ---

    /**
     * 为给定的时钟集合创建 Z3 实数变量映射。
     * 这个方法现在在每次需要时调用，创建局部的变量映射。
     *
     * @param clocks 时钟集合。
     * @param ctx    要使用的 Z3 Context。
     * @return 从 Clock 对象到对应的 Z3 RealExpr 变量的映射。
     */
    private Map<Clock, RealExpr> createClockVarMap(Set<Clock> clocks, Context ctx) {
        if (clocks == null || clocks.isEmpty()) {
            return Collections.emptyMap();
        }
        return clocks.stream()
                .filter(c -> c != null && !c.isZeroClock()) // 过滤 null 和零时钟
                .collect(Collectors.toMap(
                        Function.identity(),
                        c -> {
                            try {
                                return ctx.mkRealConst(c.getName());
                            } catch (Z3Exception e) {
                                // 处理创建变量时的异常
                                System.err.println("创建 Z3 变量失败: " + c.getName() + " - " + e.getMessage());
                                throw new Z3SolverException("创建 Z3 变量失败", e); // 重新抛出包装后的异常
                            }
                        }
                ));
    }

    /**
     * 向求解器添加给定映射中所有时钟变量必须大于等于 0 的约束。
     * 这个方法现在在 push/pop 块内部调用。
     *
     * @param solver          目标 Z3 Solver。
     * @param clockVarMap     本次查询相关的时钟变量映射。
     * @param ctx             要使用的 Z3 Context。
     * @param addedAssertions (可选) 用于记录添加的断言以供调试。
     */
    private void addNonNegativityConstraints(Solver solver, Map<Clock, RealExpr> clockVarMap, Context ctx, List<BoolExpr> addedAssertions) throws Z3Exception {
        if (clockVarMap == null || clockVarMap.isEmpty()) {
            return;
        }
        // 创建 Z3 的 0 实数常量 (可以缓存 ctx 级别的 0)
        RatNum zero = Z3Converter.rational2Ratnum(Rational.ZERO, ctx);
        for (RealExpr clockVar : clockVarMap.values()) {
            BoolExpr nonNegConstraint = ctx.mkGe(clockVar, zero);
            solver.add(nonNegConstraint);
            if (addedAssertions != null) {
                addedAssertions.add(nonNegConstraint);
            }
        }
    }

    /**
     * 安全地执行 solver.pop() 并处理潜在的异常。
     * 如果 pop 失败，会尝试重置当前线程的 Solver。
     * @param solver 要操作的 Solver 实例。
     */
    private void popSolver(Solver solver) {
        try {
            solver.pop();
        } catch (Z3Exception e) {
            System.err.println("Z3 错误 (pop): " + e.getMessage());
            // pop 失败可能表示 Solver 状态损坏，尝试重置
            resetSolverForCurrentThread();
        } catch (Exception e) { // 捕获其他可能的运行时异常
            System.err.println("执行 pop 时发生意外错误: " + e.getMessage());
            resetSolverForCurrentThread();
        }
    }

    /**
     * 重置当前线程的 Solver。在 pop 失败或其他需要恢复的情况下调用。
     * 移除旧 Solver，让 ThreadLocal 在下次访问时重新初始化。
     */
    private void resetSolverForCurrentThread() {
        System.err.println("警告: 重置线程 " + Thread.currentThread().getId() + " 的 Z3 Solver...");
        threadSolver.remove(); // 移除旧的 (可能有问题的) Solver
        // 下次调用 getSolver() 时会重新初始化一个新的 Solver
    }

    /**
     * 统一处理 Z3Exception 的日志记录。
     * @param operation 发生错误的操作名称 (用于日志)。
     * @param e         捕获到的 Z3Exception。
     * @param assertions 当前 push/pop 范围内添加的断言列表 (用于调试)。
     */
    private void handleZ3Exception(String operation, Z3Exception e, List<BoolExpr> assertions) {
        System.err.println("!!! Z3 错误 (" + operation + "): " + e.getMessage());
        if (assertions != null && !assertions.isEmpty()) {
            // 打印部分断言帮助定位问题
            int limit = Math.min(assertions.size(), 5); // 最多打印5条
            System.err.println("  本次查询添加的部分断言 (最多" + limit + "条):");
            for (int i = 0; i < limit; i++) {
                try {
                    System.err.println("    - " + assertions.get(i));
                } catch (Exception toStringEx) {
                    System.err.println("    - (无法打印断言: " + toStringEx.getMessage() + ")");
                }
            }
        }
    }

    /**
     * 统一处理非 Z3Exception 的通用异常日志记录。
     */
    private void handleGenericException(String operation, Exception e, List<BoolExpr> assertions){
        System.err.println("!!! 意外错误 (" + operation + "): " + e.getClass().getSimpleName() + " - " + e.getMessage());
        // 可以选择性地打印断言或堆栈跟踪
        // e.printStackTrace(System.err); // 打印完整堆栈跟踪
        if (assertions != null && !assertions.isEmpty()) {
            System.err.println("  (检查相关的断言...)");
        }
    }


    /**
     * 辅助方法：根据当前状态、目标区域和延迟变量构建 Z3 约束列表。
     * (这是 solveDelay 内部逻辑的提取)
     *
     * @param currentValuation 当前时钟赋值。
     * @param targetRegion     目标区域。
     * @param d                Z3 延迟变量 (RealExpr)。
     * @param ctx              Z3 Context。
     * @return 代表区域约束的 Z3 BoolExpr 列表。
     * @throws Z3Exception 如果 Z3 操作失败。
     */
    private List<BoolExpr> buildRegionConstraintsForDelay(ClockValuation currentValuation,
                                                          Region targetRegion,
                                                          RealExpr d,
                                                          Context ctx) throws Z3Exception {
        List<BoolExpr> constraints = new ArrayList<>();
        Map<Clock, ArithExpr<RealSort>> fracExpressions = new HashMap<>();

        // 需要确定目标区域实际依赖哪些时钟
        Set<Clock> regionClocks = new HashSet<>(targetRegion.getConfig().getClocks()); // 假设 Region 提供此方法

        for (Clock clock : regionClocks) {
            Rational currentVal = currentValuation.getValue(clock);
            if (currentVal == null) {
                // 如果当前赋值缺少区域所需的时钟，这是一个问题
                System.err.println("警告 (buildRegionConstraints): currentValuation 缺少区域时钟 " + clock.getName());
                // 可能需要抛出异常或返回空列表，取决于错误处理策略
                throw new IllegalArgumentException("currentValuation 缺少区域所需的时钟值: " + clock.getName());
                // continue; // 或者跳过这个时钟？但这可能导致结果不正确
            }

            // 零时钟通常没有约束，或者由 Region 内部处理
            if (clock.isZeroClock()) continue;

            // 时钟配置和目标区域定义
            int kappa = targetRegion.getConfig().getClockKappa(clock); // 假设 Region 关联了配置
            Integer targetInt = targetRegion.getIntegerParts().get(clock); // 整数部分

            // 构建 Z3 表达式 v + d
            RatNum currentExprZ3 = Z3Converter.rational2Ratnum(currentVal, ctx);
            ArithExpr<RealSort> vPlusD = ctx.mkAdd(currentExprZ3, d);

            // 整数部分约束 floor(v + d)
            IntExpr floorVPlusD = ctx.mkReal2Int(vPlusD);

            if (targetInt != null) { // 情况 1: 目标整数部分 targetInt <= kappa
                constraints.add(ctx.mkEq(floorVPlusD, ctx.mkInt(targetInt)));

                // 小数部分约束 frac(v + d) = (v + d) - floor(v + d)
                ArithExpr<RealSort> fracPart = ctx.mkSub(vPlusD, ctx.mkInt2Real(floorVPlusD));

                if (targetRegion.getZeroFractionClocks().contains(clock)) { // 小数部分为 0
                    constraints.add(ctx.mkEq(fracPart, Z3Converter.rational2Ratnum(Rational.ZERO, ctx)));
                } else { // 小数部分 > 0
                    constraints.add(ctx.mkGt(fracPart, Z3Converter.rational2Ratnum(Rational.ZERO, ctx)));
                    // 存储用于比较顺序
                    fracExpressions.put(clock, fracPart);
                }
            } else { // 情况 2: 目标整数部分 > kappa
                constraints.add(ctx.mkGt(floorVPlusD, ctx.mkInt(kappa)));
            }
        }

        // 添加小数部分的顺序约束
        List<Region.ClockFraction> fractionOrder = targetRegion.getFractionOrder();
        for (int i = 0; i < fractionOrder.size() - 1; i++) {
            Clock c1 = fractionOrder.get(i).clock();
            Clock c2 = fractionOrder.get(i + 1).clock();

            ArithExpr<RealSort> frac1 = fracExpressions.get(c1);
            ArithExpr<RealSort> frac2 = fracExpressions.get(c2);

            // 仅当两个时钟都预期有非零小数部分时才添加约束
            if (frac1 != null && frac2 != null) {
                constraints.add(ctx.mkLt(frac1, frac2)); // frac(c1) < frac(c2)
            }
            // else { // 调试信息
            //    System.out.println("跳过小数排序约束: " + c1.getName() + " 或 " + c2.getName() + " 的小数部分未定义或为0");
            // }
        }

        return constraints;
    }

    /**
     * 将 Z3 模型评估返回的表达式解析为 Rational 类型。
     * (这个方法保持不变)
     */
    private Rational parseZ3ResultToRational(Expr<?> resultExpr, Context ctx) {
        try {
            if (resultExpr instanceof RatNum) {
                return Z3Converter.ratnum2Rational((RatNum) resultExpr);
            } else if (resultExpr instanceof IntNum) {
                try {
                    return Rational.valueOf(((IntNum) resultExpr).getBigInteger());
                } catch (Z3Exception | ClassCastException bigIntEx) {
                    // System.out.println("无法从 IntNum 获取 BigInteger，尝试 int64 回退...");
                    try {
                        return Rational.valueOf(((IntNum) resultExpr).getInt64());
                    } catch (Z3Exception | ClassCastException longEx) {
                        System.err.println("无法从 IntNum " + resultExpr + " 获取 int64，解析失败: " + longEx.getMessage());
                        return null;
                    }
                }
            } else {
                System.err.println("Z3 返回了预料之外的类型: " + resultExpr.getClass().getName() + "，尝试字符串解析。");
                // 尝试字符串解析，但这可能不可靠或格式错误
                try {
                    return Rational.valueOf(resultExpr.toString());
                } catch (NumberFormatException | ArithmeticException strEx) {
                    System.err.println("将 Z3 结果表达式字符串 '" + resultExpr.toString() + "' 解析为 Rational 时失败: " + strEx.getMessage());
                    return null;
                }
            }
        } catch (Exception e) { // 捕获任何其他意外错误
            System.err.println("将 Z3 结果表达式 '" + resultExpr + "' 解析为 Rational 时发生意外错误: " + e.getMessage());
            return null;
        }
    }


    /**
     * 自定义运行时异常，用于包装 Z3 相关的错误。
     */
    public static class Z3SolverException extends RuntimeException {
        public Z3SolverException(String message, Throwable cause) {
            super(message, cause);
        }
        public Z3SolverException(String message) {
            super(message);
        }
    }
}