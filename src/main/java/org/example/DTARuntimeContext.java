package org.example;

import com.microsoft.z3.Context;
import com.microsoft.z3.RealExpr;
// import lombok.Getter; // 不再需要 Getter，因为没有实例字段了
import com.microsoft.z3.Solver;
import org.example.automata.DTA; // 假设 Clock 在这个包或其子包

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class DTARuntimeContext {

    private static final ThreadLocal<Set<Clock>> currentClocksContext = new ThreadLocal<>();

    private DTARuntimeContext() {}

    /**
     * 获取当前操作上下文中的时钟集合。
     *
     * @return 当前上下文设置的时钟集合（不可修改的视图）。
     * @throws IllegalStateException 如果当前线程/操作没有设置时钟上下文。
     */
    public static Set<Clock> getClocks() {
        Set<Clock> clocks = currentClocksContext.get();
        if (clocks == null) {
            // 强制要求在使用前必须设置上下文
            throw new IllegalStateException("DTARuntimeContext Error: Clock context has not been set for the current operation/thread. " +
                    "Use DTARuntimeContext.runWithContext(...) to set it.");
        }
        // 返回一个不可修改的视图，防止外部意外修改 ThreadLocal 中的 Set
        return Collections.unmodifiableSet(clocks);
    }

    /**
     * （内部使用）设置当前线程的时钟上下文。
     * @param clocks 要设置的时钟集合，为 null 则清除上下文。
     */
    private static void setClocksInternal(Set<Clock> clocks) {
        if (clocks != null) {
            // 存储时钟集合的不可变副本，确保安全
            currentClocksContext.set(Set.copyOf(clocks));
        } else {
            currentClocksContext.remove(); // 清除当前线程的上下文
        }
    }

    /**
     * 在指定的时钟上下文中执行一个操作。
     * 自动处理上下文的设置和恢复。
     *
     * @param operationClocks 此操作需要使用的时钟集合。
     * @param action 要在设定好上下文中执行的操作 (Runnable)。
     */
    public static void runWithContext(Set<Clock> operationClocks, Runnable action) {
        if (operationClocks == null) {
            throw new IllegalArgumentException("operationClocks cannot be null for runWithContext");
        }

        // 1. 保存当前线程之前的上下文
        Set<Clock> previousClocks = currentClocksContext.get();

        try {
            // 2. 设置当前操作的上下文
            setClocksInternal(operationClocks);

            // 3. 执行操作
            action.run();

        } finally {
            // 4. 无论操作是否成功或抛出异常，恢复之前的上下文
            // setClocksInternal(null) 会调用 remove()
            setClocksInternal(previousClocks);
        }
    }

    /**
     * 为给定的时钟集合和 Z3 上下文创建 Z3 变量映射。
     * 这个方法保持不变，因为它不依赖于全局状态。
     *
     * @param clocks 需要创建变量的时钟集合。
     * @param ctx    当前的 Z3 Context。
     * @return Clock 到 Z3 RealExpr 的映射。
     */
    public static Map<Clock, RealExpr> getVarMap(Set<Clock> clocks, Context ctx) {
        if (clocks == null || ctx == null) {
            throw new IllegalArgumentException("Clocks and Z3 Context cannot be null for getVarMap");
        }
        // 使用 Collectors.toMap 更简洁
        return clocks.stream()
                .collect(Collectors.toMap(
                        clock -> clock,
                        clock -> ctx.mkRealConst(clock.getName())
                ));
    }
}