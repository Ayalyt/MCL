package org.example.automata;

import com.microsoft.z3.*;
import lombok.Getter;
import org.apache.commons.lang3.tuple.Pair;
import org.example.*;
import org.example.ClockConfiguration;
import org.example.constraint.AtomConstraint;
import org.example.constraint.Constraint;
import org.example.constraint.DisjunctiveConstraint;
import org.example.region.RegionSolver;
import org.example.utils.Rational;
import org.example.utils.Z3Converter;
import org.example.words.DelayTimedWord;

import java.util.*;
import java.util.stream.Collectors;

@Getter
public class DTA {
    // 基础组件
    private final Alphabet alphabet;
    private final Set<Clock> clocks;
    private Location initialLocation;
    private final Set<Location> acceptingLocations;
    private final ClockConfiguration configuration;
    private final Set<Location> locations;
    private final Set<Transition> transitions;

    // 图结构索引（双向索引）
    private final Map<Location, Set<Transition>> outgoingTransitions;
    private final Map<Location, Set<Transition>> incomingTransitions;
    private final Map<Location, Map<Action, List<Transition>>> actionTransitions;

    // 最大常量缓存（用于区域计算）
    private Integer maxConstant = null;

    public DTA(Alphabet alphabet, Set<Clock> clocks, Location initialLocation, ClockConfiguration configuration) {
        this.alphabet = alphabet;
        this.clocks = Set.copyOf(clocks);
        this.initialLocation = initialLocation;
        this.configuration = configuration;
        this.locations = Collections.synchronizedSet(new HashSet<>());
        this.acceptingLocations = new HashSet<>();
        this.transitions = Collections.synchronizedSet(new HashSet<>());
        this.outgoingTransitions = new HashMap<>();
        this.incomingTransitions = new HashMap<>();
        this.actionTransitions = new HashMap<>();
        if (initialLocation!= null){
            addLocation(initialLocation);
        }
    }

    // ================== 基础管理方法 ==================

    /**
     * 创建当前DTA的浅拷贝副本
     *
     * @return 新DTA实例（与原始对象共享组件引用）
     */
    public DTA copy() {
        // 1. 使用构造器复制基础不可变组件
        DTA copy = new DTA(
                this.alphabet,
                new HashSet<>(this.clocks),
                this.initialLocation,
                this.configuration
        );


        // 2. 复制集合类成员（不复制元素对象）
        copy.locations.addAll(this.locations);
        copy.acceptingLocations.addAll(this.acceptingLocations);
        copy.transitions.addAll(this.transitions);

        // 3. 重建内部索引
        this.rebuildInternalIndicesForCopy(copy);

        // 4. 复制缓存状态
        copy.maxConstant = this.maxConstant;

        return copy;
    }

    private void rebuildInternalIndicesForCopy(DTA copy) {
        // 清空目标索引
        copy.outgoingTransitions.clear();
        copy.incomingTransitions.clear();
        copy.actionTransitions.clear();

        // 重建转移关系索引
        for (Transition t : this.transitions) {
            // 出边索引
            copy.outgoingTransitions
                    .computeIfAbsent(t.getSource(), k -> new HashSet<>())
                    .add(t);

            // 入边索引
            copy.incomingTransitions
                    .computeIfAbsent(t.getTarget(), k -> new HashSet<>())
                    .add(t);

            // 动作索引
            copy.actionTransitions
                    .computeIfAbsent(t.getSource(), k -> new HashMap<>())
                    .computeIfAbsent(t.getAction(), k -> new ArrayList<>())
                    .add(t);
        }
    }


    public void addLocation(Location loc) {
        if (locations.add(loc)) {
            outgoingTransitions.put(loc, new HashSet<>());
            incomingTransitions.put(loc, new HashSet<>());
            actionTransitions.put(loc, new HashMap<>());
        }
    }

    public void addAcceptingLocation(Location loc) {
        addLocation(loc);
        acceptingLocations.add(loc);
    }

    public void setInitialLocation(Location loc) {
        addLocation(loc);
        this.initialLocation = loc;
    }

    public boolean isAccepting(Location loc) {
        return acceptingLocations.contains(loc);
    }

    public void addTransition(Transition t) {
        if (!locations.contains(t.getSource()) || !locations.contains(t.getTarget())) {
            throw new IllegalArgumentException("Transition含有未知Location"+ t.getSource() + "或" + t.getTarget());
        }
        if (!alphabet.contains(t.getAction())) {
            throw new IllegalArgumentException("无效的Action"+ t.getAction());
        }
        if (transitions.add(t)) {
            if (!outgoingTransitions.containsKey(t.getSource())) {
                outgoingTransitions.put(t.getSource(), new HashSet<>());
            }
            outgoingTransitions.get(t.getSource()).add(t);
            if (!incomingTransitions.containsKey(t.getTarget())) {
                incomingTransitions.put(t.getTarget(), new HashSet<>());
            }
            incomingTransitions.get(t.getTarget()).add(t);
            if (!actionTransitions.containsKey(t.getSource())) {
                actionTransitions.put(t.getSource(), new HashMap<>());
            }
            actionTransitions.get(t.getSource())
                    .computeIfAbsent(t.getAction(), k -> new ArrayList<>())
                    .add(t);
            maxConstant = null; // 清除缓存
        }
    }

    public Location addSinkLocation() {
        Optional<Location> existingSink = locations.stream()
                .filter(Location::isSink)
                .findFirst();
        if (existingSink.isPresent()) {
            return existingSink.get();
        }

        // 创建并添加新 Sink
        Location sink = Location.createSink();
        addLocation(sink);
        return sink;
    }

    // ================== 图结构查询方法 ==================
    public Set<Transition> getOutgoingTransitions(Location loc) {
        return Collections.unmodifiableSet(outgoingTransitions.getOrDefault(loc, Set.of()));
    }

    public Set<Transition> getIncomingTransitions(Location loc) {
        return Collections.unmodifiableSet(incomingTransitions.getOrDefault(loc, Set.of()));
    }

    public Set<Location> getPredecessors(Location loc) {
        return getIncomingTransitions(loc).stream()
                .map(Transition::getSource)
                .collect(Collectors.toSet());
    }

    public Set<Location> getSuccessors(Location loc) {
        return getOutgoingTransitions(loc).stream()
                .map(Transition::getTarget)
                .collect(Collectors.toSet());
    }

    public List<Transition> getTransitions(Location source, Action action) {
        return Collections.unmodifiableList(
                actionTransitions.getOrDefault(source, Map.of())
                        .getOrDefault(action, List.of())
        );
    }

    // ================== DTA核心功能 ==================
    public Optional<Transition> findEnabledTransition(Location current, ClockValuation valuation,
                                                      Action action, Rational delay) {
        ClockValuation newValuation = valuation.delay(delay);
        return getTransitions(current, action).stream()
                .filter(t -> t.getGuard().isSatisfied(newValuation))
                .findFirst();
    }


    /**
     * 计算当前 DTA 的补集。
     * 该方法首先将当前 DTA 转换为一个等价的完全时间自动机 (CTA)，
     * 然后通过交换接受状态和非接受状态来构造补集。
     *
     * @return 表示当前 DTA 语言补集的 DTA。
     */
    public DTA complement() {
        // 步骤 1: 确保自动机是完备的，获取 CTA 副本
        // toCTA() 应该返回一个新的、完备的 DTA 实例

        DTA cta = this.toCTA();

        // 步骤 2: 获取 CTA 的所有位置
        Set<Location> allLocationsInCTA = cta.getLocations(); // 使用 getter

        // 步骤 3: 获取 CTA (也是原 DTA 转换后) 的接受位置
        Set<Location> originalAcceptingInCTA = cta.getAcceptingLocations(); // 使用 getter

        // 步骤 4: 计算补集的接受状态 (所有状态 - 原接受状态)
        Set<Location> complementAccepting = new HashSet<>(allLocationsInCTA);
        complementAccepting.removeAll(originalAcceptingInCTA);

        // 步骤 5: 更新 CTA 副本的接受状态集
        cta.acceptingLocations.clear();
        cta.acceptingLocations.addAll(complementAccepting);
        return cta;
    }

    /**
     * 计算当前 DTA (this) 与另一个 DTA (otherDTA) 的交集。
     * 返回一个新的 DTA 实例，其语言是两个输入 DTA 语言的交集。
     * 采用按需构造同步乘积的方法，并使用 Z3 检查guard可行性。
     *
     * @param otherDTA 要进行交集运算的另一个 DTA。
     * @return 代表交集语言的新 DTA 实例。
     * @throws IllegalArgumentException 如果两个 DTA 的时钟配置不兼容或无法合并，
     *                                  或者在 Z3 转换/求解过程中发生错误。
     */
    public DTA intersect(DTA otherDTA) {
        DTA dta1 = this;
        Objects.requireNonNull(otherDTA, "DTA-intersect: otherDTA 不能为 null");

        // --- 步骤 0: 初始化 ---

        // 合并时钟集合
        Set<Clock> unionClocks = new HashSet<>(dta1.getClocks());
        unionClocks.addAll(otherDTA.getClocks());

        // 合并kappa
        ClockConfiguration intersectConfig = mergeConfigurations(dta1.getConfiguration(), otherDTA.getConfiguration(), unionClocks);

        Set<Action> sharedActions = dta1.getAlphabet().alphabet.values().stream()
                .filter(action -> otherDTA.getAlphabet().contains(action))
                .collect(Collectors.toSet());
        Alphabet intersectAlphabet = new Alphabet(sharedActions);

        // 乘积DTA骨架
        DTA dtaIntersect = new DTA(intersectAlphabet, unionClocks, null, intersectConfig); // 初始位置稍后设置

        // 1: 状态空间探索准备

        Map<Pair<Location, Location>, Location> visitedOrCreated = new HashMap<>();
        Queue<Pair<Location, Location>> worklist = new LinkedList<>();

        Location l1_init = dta1.getInitialLocation();
        Location l2_init = otherDTA.getInitialLocation();
        if (l1_init == null || l2_init == null) {
            throw new IllegalStateException("DTA-intersect: 一个或两个 DTA 没有初始位置");
        }
        Pair<Location, Location> initialPair = Pair.of(l1_init, l2_init);

        Location l_intersect_init = new Location("loc_" + l1_init.getId() + "_" + l2_init.getId());
        dtaIntersect.addLocation(l_intersect_init);
        dtaIntersect.setInitialLocation(l_intersect_init);
        visitedOrCreated.put(initialPair, l_intersect_init);
        worklist.add(initialPair);

        if (dta1.isAccepting(l1_init) && otherDTA.isAccepting(l2_init)) {
            dtaIntersect.addAcceptingLocation(l_intersect_init);
        }

        // 2: 工作列表 (BFS)
        try (Context ctx = new Context()) {
            // Z3 变量映射现在基于 unionClocks
            Map<Clock, RealExpr> clockVarMap = DTARuntimeContext.getVarMap(unionClocks, ctx);
            Solver solver = ctx.mkSolver();
            while (!worklist.isEmpty()) {
                Pair<Location, Location> currentPair = worklist.poll();
                Location l1_curr = currentPair.getLeft();
                Location l2_curr = currentPair.getRight();
                Location l_intersect_src = visitedOrCreated.get(currentPair);

                for (Action action : sharedActions) {
                    List<Transition> transitions1 = dta1.getTransitions(l1_curr, action);
                    List<Transition> transitions2 = otherDTA.getTransitions(l2_curr, action);

                    for (Transition t1 : transitions1) {
                        for (Transition t2 : transitions2) {
                            Constraint g1 = t1.getGuard(); // g1 定义在 dta1.clocks
                            Constraint g2 = t2.getGuard(); // g2 定义在 otherDTA.clocks

                            // --- 合并guard的关键修改 ---
                            Constraint g_intersect;
                            if (g1.isFalse() || g2.isFalse()) {
                                // 如果任一guard为 False (在其各自时钟集上)，则交集为 False (在 unionClocks 上)
                                g_intersect = Constraint.falseConstraint(unionClocks);
                            } else if (g1.isTrue() && g2.isTrue()) {
                                // 如果两者都为 True (在其各自时钟集上)，则交集为 True (在 unionClocks 上)
                                g_intersect = Constraint.trueConstraint(unionClocks);
                            } else {
                                // 合并原子约束并在 unionClocks 上下文中创建新约束
                                Set<AtomConstraint> combinedAtoms = new HashSet<>();
                                // 添加 g1 的原子约束 (包含了 dta1.clocks 的默认约束)
                                combinedAtoms.addAll(g1.getConstraints());
                                // 添加 g2 的原子约束 (包含了 otherDTA.clocks 的默认约束)
                                combinedAtoms.addAll(g2.getConstraints());

                                try {
                                    g_intersect = new Constraint(combinedAtoms, unionClocks);
                                } catch (IllegalArgumentException e) {
                                    // 如果合并后的原子约束直接导致矛盾
                                    System.err.println("DTA-intersect: 合并原子约束时出错，视为 FALSE: " + e.getMessage());
                                    g_intersect = Constraint.falseConstraint(unionClocks);
                                }
                            }

                            try {
                                // Z3Converter 需要能处理基于 unionClocks 的 g_intersect
                                BoolExpr guardExpr = Z3Converter.constraint2Boolexpr(g_intersect, ctx, clockVarMap);
                                solver.push();
                                solver.add(guardExpr);
                                Status status = solver.check();
                                solver.pop();

                                if (status == Status.UNSATISFIABLE) {
                                    continue; // 合并后的guard不可满足，此同步路径不可行
                                }
                                // 如果 status 是 UNKNOWN，可能需要处理或抛出异常
                                if (status == Status.UNKNOWN) {
                                    System.err.println("DTA-intersect: Z3 求解器返回未知状态，guard: " + g_intersect);
                                    continue;
                                    // throw new RuntimeException("DTA-intersect: Z3 求解器返回未知状态");
                                }
                            } catch (Z3Exception e) {
                                throw new RuntimeException("DTA-intersect: Z3 转换或求解时出错: " + e.getMessage(), e);
                            }


                            // 合并重置集合
                            Set<Clock> r1 = t1.getResets();
                            Set<Clock> r2 = t2.getResets();
                            Set<Clock> r_intersect = new HashSet<>(r1);
                            r_intersect.addAll(r2);

                            // 获取目标状态对
                            Location l1_next = t1.getTarget();
                            Location l2_next = t2.getTarget();
                            Pair<Location, Location> nextPair = Pair.of(l1_next, l2_next);

                            // 获取或创建交集图中的目标位置
                            Location l_intersect_tgt = visitedOrCreated.get(nextPair);
                            if (l_intersect_tgt == null) {
                                l_intersect_tgt = new Location("loc_" + l1_next.getId() + "_" + l2_next.getId());
                                dtaIntersect.addLocation(l_intersect_tgt);
                                visitedOrCreated.put(nextPair, l_intersect_tgt);
                                worklist.add(nextPair);
                                if (dta1.isAccepting(l1_next) && otherDTA.isAccepting(l2_next)) {
                                    dtaIntersect.addAcceptingLocation(l_intersect_tgt);
                                }
                            }

                            // 创建并添加交集图中的转移
                            // 使用新计算的 g_intersect
                            Transition t_intersect = new Transition(
                                    l_intersect_src,
                                    action,
                                    g_intersect, // 使用合并后的guard
                                    r_intersect,
                                    l_intersect_tgt
                            );
                            dtaIntersect.addTransition(t_intersect);
                        }
                    }
                }
            }
        }
        return dtaIntersect;
    }
    public Optional<DelayTimedWord> isEquivalent(DTA otherDta) {
        Objects.requireNonNull(otherDta, "参数为null");
        // L(this) ∩ L(complement(other)) 是否为空
        DTA otherComplement = otherDta.complement();
        DTA intersection1 = intersect(otherComplement);
        Optional<DelayTimedWord> witness1 = findWitness(intersection1);
        if (witness1.isPresent()) {
            return witness1;
        }
        // L(other) ∩ L(complement(this))是否为空
        DTA thisComplement = complement();
        DTA intersection2 = otherDta.intersect(thisComplement);
        return findWitness(intersection2);
    }

    /**
     * 查找给定 DTA 语言中的一个 timed word (见证/反例)。
     * 如果语言为空，返回 Optional.empty()。
     * 否则，返回包含一个 timed word 的 Optional。
     *
     * @param dta 要查找见证的 DTA。
     * @return 如果语言非空，返回 Optional<TimedWord>；否则返回 Optional.empty()。
     */
//    public Optional<DelayTimedWord> findWitness(DTA dta) {
//        Objects.requireNonNull(dta, "DTA不能为null");
//        Set<Clock> clocks = dta.getClocks();
//        // System.out.println("findWitness: 要寻找见证的DTA：" + dta);
//
//        // 状态表示: (Location, DBM)
//        // 路径回溯信息: Map<State, Pair<PreviousState, Transition>>
//        Map<Pair<Location, DBM>, Pair<Pair<Location, DBM>, Transition>> predecessors = new HashMap<>();
//
//        Location initialLocation = dta.getInitialLocation();
//        DBM initialDBM = DBM.createInitial(clocks);
//        Pair<Location, DBM> initialState = Pair.of(initialLocation, initialDBM);
//        System.out.println("findWitness: [初始状态] 位置=" + initialLocation + ", DBM=\n" + initialDBM);
//        Queue<Pair<Location, DBM>> worklist = new LinkedList<>();
//        // 使用 Map<Location, List<DBM>> 来存储已访问区域，用于包含性检查
//        Map<Location, List<DBM>> passed = new HashMap<>();
//
//        // 检查初始状态是否接受 (特殊情况: 空字)
//        if (dta.isAccepting(initialLocation)) {
//            // 初始 DBM 必须非空才能接受空词 (通常初始 DBM 总是非空)
//            if (!initialDBM.isEmpty()) {
//                // System.out.println("[初始检查] 初始状态即接受且 DBM 非空，找到空词见证 epsilon。");
//                return Optional.of(new DelayTimedWord(Collections.emptyList()));
//            } else {
//                // System.out.println("[初始检查] 初始状态是接受状态，但初始 DBM 为空 (异常情况)，无法接受空词。");
//                // 理论上 createInitial 不会返回空 DBM，除非时钟集为空或 DBM 实现有误
//            }
//        } else {
//            // System.out.println("findWitness: [初始检查] 初始状态不是接受状态。");
//        }
//
//        // System.out.println("findWitness: [BFS 初始化] 将初始状态加入 worklist 和 passed。");
//        worklist.add(initialState);
//        passed.computeIfAbsent(initialLocation, k -> new ArrayList<>()).add(initialDBM);
//
//        int iteration = 0;
//        while (!worklist.isEmpty()) {
//            iteration++;
//            System.out.println("\nfindWitness: --- BFS 迭代 #" + iteration + " ---");
//            Pair<Location, DBM> currentPair = worklist.poll();
//            Location currentLocation = currentPair.getLeft();
//            DBM currentDBM = currentPair.getRight();
//            // System.out.println("findWitness: [处理状态] 位置=" + currentLocation + ", DBM (hash=" + currentDBM + "):\n" + currentDBM);
//
//            // System.out.println("findWitness:   [探索转移] 探索从 " + currentLocation + " 出发的所有转移:");
//            // 探索从当前位置出发的所有转移
//            for (Transition transition : dta.getOutgoingTransitions(currentLocation)) {
//                Constraint guard = transition.getGuard();
//                Set<Clock> resets = transition.getResets();
//                Location targetLocation = transition.getTarget();
//                Action action = transition.getAction(); // 获取动作信息
//
//                // System.out.println("findWitness:     --> [考虑转移] " + transition); // 打印转移详情
//
//                // --- 核心逻辑 ---
//                DBM nextDBM = currentDBM.copy();
//                // System.out.println("findWitness:       [步骤 1] 复制当前 DBM (新 hash=" + nextDBM + ")"); // 可选：打印复制操作
//                nextDBM.future();
//                System.out.println("findWitness:       [步骤 1.5] 时间流逝 (新 hash=" + nextDBM + ")");
//                // System.out.println("findWitness:       [步骤 2] 应用 Guard: " + guard);
//                nextDBM.intersect(guard);
//                // System.out.println("        Guard 应用：" + guard + "；DBM (hash=" + nextDBM.hashCode() + "):\n" + nextDBM);
//                // System.out.println("findWitness:         Guard 应用后 (未规范化):\n" + nextDBM); // 可选：打印中间结果
//                nextDBM.canonical();
//                // System.out.println("findWitness:         规范化后 DBM (hash=" + nextDBM.hashCode() + "):\n" + nextDBM);
//
//                boolean emptyAfterGuard = nextDBM.isEmpty();
//                // System.out.println("findWitness:         Guard 应用后是否为空? " + emptyAfterGuard);
//
//                if (!emptyAfterGuard) {
//                    // System.out.println("findWitness:       [步骤 3] 应用重置: " + resets);
//                    for (Clock clockToReset : resets) {
//                        // System.out.println("        重置时钟: " + clockToReset); // 可选：打印每个重置
//                        nextDBM.reset(clockToReset);
//                    }
//                    // 重置后通常不需要立刻规范化，future 会处理
//                    // System.out.println("findWitness:         重置后 DBM (hash=" + nextDBM.hashCode() + "):\n" + nextDBM);
//
//                    // System.out.println("findWitness:       [步骤 4] 应用时间流逝 (future = up + canonical)");
//                    nextDBM.future(); // future 内部包含 up() 和 canonical()
//                    // System.out.println("findWitness:         时间流逝后 DBM (hash=" + nextDBM.hashCode() + "):\n" + nextDBM);
//
//                    boolean emptyAfterFuture = nextDBM.isEmpty();
//                    // System.out.println("findWitness:         时间流逝后是否为空? " + emptyAfterFuture);
//
//                    if (!emptyAfterFuture) {
//                        // System.out.println("findWitness:       [步骤 5] 检查位置 " + targetLocation + " 的覆盖情况 (新 DBM hash=" + nextDBM.hashCode() + ")");
//                        // --- 检查覆盖与添加 ---
//                        // 获取目标位置已记录的 DBM 列表，如果不存在则返回空列表
//                        List<DBM> passedDBMsForTarget = passed.getOrDefault(targetLocation, Collections.emptyList());
//                        // System.out.println("        目标位置已存在 " + passedDBMsForTarget.size() + " 个已处理 DBM 区域。");
//
//                        boolean covered = false;
//                        DBM coveringDBM = null;
//                        int passedIndex = -1;
//                        for (int i = 0; i < passedDBMsForTarget.size(); i++) {
//                            DBM passedDBM = passedDBMsForTarget.get(i);
//                            // 检查已存在的 DBM 是否包含新的 DBM
//                            if (passedDBM.include(nextDBM)) {
//                                covered = true;
//                                coveringDBM = passedDBM;
//                                passedIndex = i;
//                                break;
//                            }
//                        }
//
//                        // System.out.println("        新 DBM 是否被已存在的区域覆盖? " + covered);
//
//                        if (!covered) {
//                            // System.out.println("findWitness:         *** 新状态未被覆盖 ***");
//                            // 移除被新 DBM 包含的旧 DBM (优化，可选但推荐)
//                            // 注意：直接修改 passedDBMsForTarget 可能导致 ConcurrentModificationException，如果迭代器未正确处理
//                            // 一个安全的做法是创建一个新列表
//                            List<DBM> updatedPassedDBMs = new ArrayList<>();
//                            boolean addedNew = false;
//                            for (DBM existingDBM : passedDBMsForTarget) {
//                                if (!nextDBM.include(existingDBM)) { // 如果新 DBM 不包含旧 DBM，保留旧 DBM
//                                    updatedPassedDBMs.add(existingDBM);
//                                } else {
//                                    // System.out.println("findWitness:           优化: 新 DBM (hash=" + nextDBM.hashCode() + ") 包含了旧 DBM (hash=" + existingDBM.hashCode() + ")，将移除旧 DBM。");
//                                }
//                            }
//                            updatedPassedDBMs.add(nextDBM); // 添加新的 DBM
//                            passed.put(targetLocation, updatedPassedDBMs); // 更新 passed 列表
//                            // System.out.println("findWitness:         已更新位置 " + targetLocation + " 的 passed 列表 (现在有 " + updatedPassedDBMs.size() + " 个区域)。");
//
//
//                            Pair<Location, DBM> nextPair = Pair.of(targetLocation, nextDBM);
//                            // System.out.println("findWitness:         添加新状态到 worklist: 位置=" + targetLocation + ", DBM (hash=" + nextDBM.hashCode() + ")");
//                            worklist.add(nextPair);
//
//                            // 记录前驱信息用于回溯
//                            // System.out.println("findWitness:         记录前驱: (" + targetLocation + ", DBM hash=" + nextDBM.hashCode() + ") <-- ("
//                            //        + currentLocation + ", DBM hash=" + currentDBM.hashCode() + ") via " + action);
//                            predecessors.put(nextPair, Pair.of(currentPair, transition));
//
//                            // --- 找到接受状态 ---
//                            if (dta.isAccepting(targetLocation)) {
//                                // System.out.println("findWitness:     !!!!!! [找到接受状态] 位置: " + targetLocation + " !!!!!!");
//                                // System.out.println("findWitness:     !!!!!! 对应的 DBM (hash=" + nextDBM.hashCode() + "):\n" + nextDBM);
//                                // System.out.println("findWitness:     ====== 开始回溯构造 TimedWord ======");
//                                // 找到了到达接受状态的路径，回溯构造反例
//                                return Optional.of(reconstructTimedWord(predecessors, nextPair));
//                            } else {
//                                // System.out.println("        目标状态 " + targetLocation + " 不是接受状态。");
//                            }
//                        } else {
//                            // System.out.println("        新 DBM (hash=" + nextDBM.hashCode() + ") 被已存在的 DBM #" + passedIndex + " (hash=" + coveringDBM.hashCode() + ") 覆盖，剪枝此路径。");
//                            // 可选：打印覆盖它的 DBM
//                            // System.out.println("        覆盖它的 DBM:\n" + coveringDBM);
//                        }
//                    } else { // emptyAfterFuture
//                        // System.out.println("      [剪枝] 时间流逝后 DBM 为空，剪枝此路径。");
//                    }
//                } else { // emptyAfterGuard
//                    // System.out.println("      [剪枝] 应用 Guard 后 DBM 为空，剪枝此路径。");
//                }
//                // System.out.println("    <-- [完成转移处理] " + action); // 标记转移处理结束
//            } // End for transition
//            // System.out.println("  [完成探索] 完成对来自 " + currentLocation + " 的所有转移的探索。");
//        } // End while
//
//        // 工作列表为空，未找到接受状态
//        // System.out.println("\n==> BFS 结束，worklist 为空，未找到到达接受状态的路径。");
//        return Optional.empty();
//    }
//
//    /**
//     * 从前驱信息中回溯构造一个 timed word。
//     * (内部可以添加日志)
//     */
//    private DelayTimedWord reconstructTimedWord(Map<Pair<Location, DBM>, Pair<Pair<Location, DBM>, Transition>> predecessors, Pair<Location, DBM> finalState) {
//        // 1. 回溯获取转移序列 (从前到后)
//        LinkedList<Transition> transitions = new LinkedList<>();
//        Pair<Location, DBM> currentState = finalState;
//        while (predecessors.containsKey(currentState)) {
//            Pair<Pair<Location, DBM>, Transition> predInfo = predecessors.get(currentState);
//            transitions.addFirst(predInfo.getRight()); // 添加到前端，得到正序
//            currentState = predInfo.getLeft();
//        }
//        // System.out.println("findWitness:   [回溯完成] 得到转移序列: " + transitions);
//
//        // 2. 前向模拟计算精确延迟
//        LinkedList<Pair<Action, Rational>> timedActions = new LinkedList<>();
//        Set<Clock> clocks = finalState.getValue().getClocks();
//        clocks.remove(Clock.getZeroClock());
//        ClockValuation currentClockValues = ClockValuation.zero(clocks);
//        // System.out.println("findWitness:   [前向模拟开始] 初始时钟值: " + currentClockValues);
//
//        for (Transition transition : transitions) {
//            // System.out.println("findWitness:     [前向模拟步骤] 处理转移: " + transition);
//            Constraint guard = transition.getGuard();
//            Set<Clock> resets = transition.getResets();
//            Action action = transition.getAction();
//
//            Rational delay = RegionSolver.solveDelay(currentClockValues, guard).get();
//            // System.out.println("findWitness:       计算得到的点延迟: " + delay);
//
//            timedActions.add(Pair.of(action, delay));
//
//            // 更新时钟值：先加延迟，再重置
//            ClockValuation valuesAfterDelay = currentClockValues.delay(delay);
//            currentClockValues = valuesAfterDelay.reset(resets);
//            // System.out.println("findWitness:       动作 '" + action + "' 执行后时钟值: " + currentClockValues);
//        }
//
//        // System.out.println("findWitness:   [前向模拟结束] 构造的路径有 " + timedActions.size() + " 个 (动作, 延迟) 对。");
//        return new DelayTimedWord(timedActions);
//    }
    public Optional<DelayTimedWord> findWitness(DTA dta) {
        Objects.requireNonNull(dta, "DTA不能为null");
        Set<Clock> clocks = dta.getClocks();
        System.out.println("findWitness: 要寻找见证的DTA：" + dta);

        Map<Pair<Location, DBM>, Pair<Pair<Location, DBM>, Transition>> predecessors = new HashMap<>();

        Location initialLocation = dta.getInitialLocation();
        DBM initialDBM = DBM.createInitial(clocks);
        Pair<Location, DBM> initialState = Pair.of(initialLocation, initialDBM);
        System.out.println("findWitness: [初始状态] 位置=" + initialLocation + ", DBM=\n" + initialDBM);
        Queue<Pair<Location, DBM>> worklist = new LinkedList<>();
        Map<Location, List<DBM>> passed = new HashMap<>();

        if (dta.isAccepting(initialLocation)) {
            if (!initialDBM.isEmpty()) {
                System.out.println("[初始检查] 初始状态即接受且 DBM 非空，找到空词见证 epsilon。");
                return Optional.of(new DelayTimedWord(Collections.emptyList()));
            } else {
                System.out.println("[初始检查] 初始状态是接受状态，但初始 DBM 为空 (异常情况)，无法接受空词。");
            }
        } else {
            System.out.println("findWitness: [初始检查] 初始状态不是接受状态。");
        }

        System.out.println("findWitness: [BFS 初始化] 将初始状态加入 worklist 和 passed。");
        worklist.add(initialState);
        passed.computeIfAbsent(initialLocation, k -> new ArrayList<>()).add(initialDBM);

        int iteration = 0;
        while (!worklist.isEmpty()) {
            iteration++;
            System.out.println("\nfindWitness: --- BFS 迭代 #" + iteration + " ---");
            Pair<Location, DBM> currentPair = worklist.poll();
            Location currentLocation = currentPair.getLeft();
            DBM currentDBM = currentPair.getRight();
            System.out.println("findWitness: [处理状态] 位置=" + currentLocation + ", DBM (hash=" + currentDBM + "):\n" + currentDBM);

            System.out.println("findWitness:   [探索转移] 探索从 " + currentLocation + " 出发的所有转移:");
            for (Transition transition : dta.getOutgoingTransitions(currentLocation)) {
                Constraint guard = transition.getGuard();
                Set<Clock> resets = transition.getResets();
                Location targetLocation = transition.getTarget();
                Action action = transition.getAction();

                System.out.println("findWitness:     --> [考虑转移] " + transition);

                DBM nextDBM = currentDBM.copy();
                System.out.println("findWitness:       [步骤 1] 复制当前 DBM (新 hash=" + nextDBM + ")");
                nextDBM.future();
                System.out.println("findWitness:       [步骤 1.5] 时间流逝 (新 hash=" + nextDBM + ")");
                System.out.println("findWitness:       [步骤 2] 应用 Guard: " + guard);
                nextDBM.intersect(guard);
                System.out.println("        Guard 应用：" + guard + "；DBM (hash=" + nextDBM.hashCode() + "):\n" + nextDBM);
                System.out.println("findWitness:         Guard 应用后 (未规范化):\n" + nextDBM);
                nextDBM.canonical();
                System.out.println("findWitness:         规范化后 DBM (hash=" + nextDBM.hashCode() + "):\n" + nextDBM);

                boolean emptyAfterGuard = nextDBM.isEmpty();
                System.out.println("findWitness:         Guard 应用后是否为空? " + emptyAfterGuard);

                if (!emptyAfterGuard) {
                    System.out.println("findWitness:       [步骤 3] 应用重置: " + resets);
                    for (Clock clockToReset : resets) {
                        System.out.println("        重置时钟: " + clockToReset);
                        nextDBM.reset(clockToReset);
                    }
                    System.out.println("findWitness:         重置后 DBM (hash=" + nextDBM.hashCode() + "):\n" + nextDBM);

                    System.out.println("findWitness:       [步骤 4] 应用时间流逝 (future = up + canonical)");
                    nextDBM.future();
                    System.out.println("findWitness:         时间流逝后 DBM (hash=" + nextDBM.hashCode() + "):\n" + nextDBM);

                    boolean emptyAfterFuture = nextDBM.isEmpty();
                    System.out.println("findWitness:         时间流逝后是否为空? " + emptyAfterFuture);

                    if (!emptyAfterFuture) {
                        System.out.println("findWitness:       [步骤 5] 检查位置 " + targetLocation + " 的覆盖情况 (新 DBM hash=" + nextDBM.hashCode() + ")");
                        List<DBM> passedDBMsForTarget = passed.getOrDefault(targetLocation, Collections.emptyList());
                        System.out.println("        目标位置已存在 " + passedDBMsForTarget.size() + " 个已处理 DBM 区域。");

                        boolean covered = false;
                        DBM coveringDBM = null;
                        int passedIndex = -1;
                        for (int i = 0; i < passedDBMsForTarget.size(); i++) {
                            DBM passedDBM = passedDBMsForTarget.get(i);
                            if (passedDBM.include(nextDBM)) {
                                covered = true;
                                coveringDBM = passedDBM;
                                passedIndex = i;
                                break;
                            }
                        }

                        System.out.println("        新 DBM 是否被已存在的区域覆盖? " + covered);

                        if (!covered) {
                            System.out.println("findWitness:         *** 新状态未被覆盖 ***");
                            List<DBM> updatedPassedDBMs = new ArrayList<>();
                            boolean addedNew = false;
                            for (DBM existingDBM : passedDBMsForTarget) {
                                if (!nextDBM.include(existingDBM)) {
                                    updatedPassedDBMs.add(existingDBM);
                                } else {
                                    System.out.println("findWitness:           优化: 新 DBM (hash=" + nextDBM.hashCode() + ") 包含了旧 DBM (hash=" + existingDBM.hashCode() + ")，将移除旧 DBM。");
                                }
                            }
                            updatedPassedDBMs.add(nextDBM);
                            passed.put(targetLocation, updatedPassedDBMs);
                            System.out.println("findWitness:         已更新位置 " + targetLocation + " 的 passed 列表 (现在有 " + updatedPassedDBMs.size() + " 个区域)。");

                            Pair<Location, DBM> nextPair = Pair.of(targetLocation, nextDBM);
                            System.out.println("findWitness:         添加新状态到 worklist: 位置=" + targetLocation + ", DBM (hash=" + nextDBM.hashCode() + ")");
                            worklist.add(nextPair);

                            System.out.println("findWitness:         记录前驱: (" + targetLocation + ", DBM hash=" + nextDBM.hashCode() + ") <-- ("
                                    + currentLocation + ", DBM hash=" + currentDBM.hashCode() + ") via " + action);
                            predecessors.put(nextPair, Pair.of(currentPair, transition));

                            if (dta.isAccepting(targetLocation)) {
                                System.out.println("findWitness:     !!!!!! [找到接受状态] 位置: " + targetLocation + " !!!!!!");
                                System.out.println("findWitness:     !!!!!! 对应的 DBM (hash=" + nextDBM.hashCode() + "):\n" + nextDBM);
                                System.out.println("findWitness:     ====== 开始回溯构造 TimedWord ======");
                                return Optional.of(reconstructTimedWord(predecessors, nextPair));
                            } else {
                                System.out.println("        目标状态 " + targetLocation + " 不是接受状态。");
                            }
                        } else {
                            System.out.println("        新 DBM (hash=" + nextDBM.hashCode() + ") 被已存在的 DBM #" + passedIndex + " (hash=" + coveringDBM.hashCode() + ") 覆盖，剪枝此路径。");
                            System.out.println("        覆盖它的 DBM:\n" + coveringDBM);
                        }
                    } else {
                        System.out.println("      [剪枝] 时间流逝后 DBM 为空，剪枝此路径。");
                    }
                } else {
                    System.out.println("      [剪枝] 应用 Guard 后 DBM 为空，剪枝此路径。");
                }
                System.out.println("    <-- [完成转移处理] " + action);
            }
            System.out.println("  [完成探索] 完成对来自 " + currentLocation + " 的所有转移的探索。");
        }

        System.out.println("\n==> BFS 结束，worklist 为空，未找到到达接受状态的路径。");
        return Optional.empty();
    }

    private DelayTimedWord reconstructTimedWord(Map<Pair<Location, DBM>, Pair<Pair<Location, DBM>, Transition>> predecessors, Pair<Location, DBM> finalState) {
        LinkedList<Transition> transitions = new LinkedList<>();
        Pair<Location, DBM> currentState = finalState;
        while (predecessors.containsKey(currentState)) {
            Pair<Pair<Location, DBM>, Transition> predInfo = predecessors.get(currentState);
            transitions.addFirst(predInfo.getRight());
            currentState = predInfo.getLeft();
        }
        System.out.println("findWitness:   [回溯完成] 得到转移序列: " + transitions);

        LinkedList<Pair<Action, Rational>> timedActions = new LinkedList<>();
        Set<Clock> clocks = finalState.getValue().getClocks();
        clocks.remove(Clock.getZeroClock());
        ClockValuation currentClockValues = ClockValuation.zero(clocks);
        System.out.println("findWitness:   [前向模拟开始] 初始时钟值: " + currentClockValues);

        for (Transition transition : transitions) {
            System.out.println("findWitness:     [前向模拟步骤] 处理转移: " + transition);
            Constraint guard = transition.getGuard();
            Set<Clock> resets = transition.getResets();
            Action action = transition.getAction();

            Rational delay = RegionSolver.solveDelay(currentClockValues, guard).get();
            System.out.println("findWitness:       计算得到的点延迟: " + delay);

            timedActions.add(Pair.of(action, delay));

            ClockValuation valuesAfterDelay = currentClockValues.delay(delay);
            currentClockValues = valuesAfterDelay.reset(resets);
            System.out.println("findWitness:       动作 '" + action + "' 执行后时钟值: " + currentClockValues);
        }

        System.out.println("findWitness:   [前向模拟结束] 构造的路径有 " + timedActions.size() + " 个 (动作, 延迟) 对。");
        return new DelayTimedWord(timedActions);
    }



    /**
     * 合并两个时钟配置用于DTA的交集运算。
     * 对于合并后时钟集合中的每个时钟,结果配置会使用两个配置中对应的最大kappa值。
     *
     * @param config1 第一个DTA的时钟配置
     * @param config2 第二个DTA的时钟配置
     * @param unionClocks 两个DTA中所有时钟的并集
     * @return 交集DTA的合并时钟配置
     */
    private ClockConfiguration mergeConfigurations(ClockConfiguration config1, ClockConfiguration config2, Set<Clock> unionClocks) {
        Map<Clock, Integer> mergedKappa = new HashMap<>();
        for (Clock clock : unionClocks) {
            int k1 = config1.getClockKappas().getOrDefault(clock, 0);
            int k2 = config2.getClockKappas().getOrDefault(clock, 0);
            // 取两个kappa值的最大值
            mergedKappa.put(clock, Math.max(k1, k2));

        }
        return new ClockConfiguration(mergedKappa);
    }



    // ================== 验证与工具方法 ==================
    public DTARuntime createRuntime() {
        return new DTARuntime(this);
    }

    public boolean isComplete() {
        try (Context ctx = new Context()) {
            Solver solver = ctx.mkSolver();
            Map<Clock, RealExpr> clockVarMap = DTARuntimeContext.getVarMap(this.clocks, ctx);
            // 添加时钟非负约束，这对于确保检查在有效空间内进行很重要
            List<BoolExpr> nonNegativeConstraints = new ArrayList<>();
            for(Clock clock : this.clocks) {
                if (clockVarMap.containsKey(clock)) {
                    nonNegativeConstraints.add(ctx.mkGe(clockVarMap.get(clock), ctx.mkReal(0)));
                }
            }
            BoolExpr nonNegativeExpr = ctx.mkAnd(nonNegativeConstraints.toArray(new BoolExpr[0]));


            for (Location loc : getLocations()) {
                if (loc.isSink()) {
                    continue;
                }

                for (Action action : getAlphabet().alphabet.values()) {
                    List<Transition> transitions = getTransitions(loc, action);

                    List<BoolExpr> guardExprs = new ArrayList<>();
                    for (Transition t : transitions) {
                        // 假设 Constraint 可以可靠地转为 Z3 BoolExpr
                        Constraint guard = t.getGuard();
                        if (guard != null) {
                            // 确保转换函数存在且正确
                            BoolExpr guardExpr = Z3Converter.constraint2Boolexpr(guard, ctx, clockVarMap);
                            guardExprs.add(guardExpr);
                        } else {
                            // 如果允许 null guard 代表 true
                            guardExprs.add(ctx.mkTrue());
                            // System.err.println("警告: 状态 " + loc.getName() + " 动作 " + action.getName() + " 的转移目标 " + t.getTarget().getName() + " Guard 为 null");
                        }
                    }

                    BoolExpr unionGuardsExpr = ctx.mkOr(guardExprs.toArray(new BoolExpr[0]));

                    solver.push();
                    solver.add(nonNegativeExpr);
                    solver.add(ctx.mkNot(unionGuardsExpr));

                    Status status = solver.check();
                    solver.pop();

                    // 如果 (NonNegative AND NOT UnionGuards) 是可满足的 (SATISFIABLE)，
                    // 说明存在一个有效的时钟赋值使得所有 Guard 都不满足，因此自动机不完备。
                    if (status == Status.SATISFIABLE) {
                        System.out.println("不完备: 状态 " + loc + " 对于动作 " + action + " 的转移未覆盖所有有效时钟空间。");
                        return false;
                    }
                    else if (status == Status.UNKNOWN) {
                        System.err.println("警告: Z3 无法确定状态 " + loc + " 动作 " + action + " 的完备性。");
                        return false;
                    }
                }
            }
            // 所有非 Sink 状态对于所有动作都检查通过
            return true;
        } catch (Exception e) {
            // 处理 Z3 或其他潜在异常
            System.err.println("检查完备性时发生错误: " + e.getMessage());
            e.printStackTrace();
            return false; // 出现错误时，保守地认为不完备
        }
    }

    public DTA toCTA() {
        if (this.isComplete()) {
            System.out.println("DTA-toCTA: 自动机已完备，无需转换。");
            return this.copy();
        }

        System.out.println("DTA-toCTA: 自动机不完备，开始转换为 CTA...");
        DTA cta = this.copy(); // 创建副本进行修改
        Location sink = cta.addSinkLocation(); // 添加或获取 Sink 状态
        Set<Clock> allClocks = cta.getClocks(); // 获取 CTA 的时钟集

        // 使用 try-with-resources 管理 Z3 上下文
        try (Context ctx = new Context()) {
            Solver solver = ctx.mkSolver();
            // 为 Z3 准备时钟变量映射
            Map<Clock, RealExpr> clockVarMap = DTARuntimeContext.getVarMap(allClocks, ctx);

            Set<Location> locationsToProcess = new HashSet<>(cta.getLocations());
            for (Location loc : locationsToProcess) {
                // 遍历字母表中的所有动作
                for (Action action : cta.getAlphabet().alphabet.values()) {
                    List<Transition> transitions = cta.getTransitions(loc, action);

                    // --- 情况 1: 没有定义任何关于 (loc, action) 的转移 ---
                    if (transitions.isEmpty()) {
                        // 添加一个从 loc 到 sink，guard为 TRUE 的转移
                        Constraint trueGuard = Constraint.trueConstraint(allClocks);
                        // TRUE guard总是可满足的，无需检查
                        Transition sinkTrans = new Transition(
                                loc, action, trueGuard, allClocks, sink
                        );
                        cta.addTransition(sinkTrans);
                        System.out.println("DTA-toCTA: 添加缺失转移 (TRUE): " + sinkTrans);
                        continue; // 处理下一个动作
                    }

                    // --- 情况 2: 存在关于 (loc, action) 的转移 ---
                    // 计算所有非 Sink 目标转移的guard的析取 (覆盖区域)
                    DisjunctiveConstraint covered = DisjunctiveConstraint.falseConstraint(allClocks);
                    boolean hasNonSinkTransition = false;
                    for (Transition t : transitions) {
                        if (!t.getTarget().isSink()) {
                            covered = covered.or(t.getGuard().toDisjunctiveConstraint());
                            hasNonSinkTransition = true;
                        }
                    }

                    // 计算未覆盖区域: Universe - covered ≡ Universe ∧ ¬covered
                    // 使用 negateDisjoint() 来获取互不相交的 DNF 形式
                    DisjunctiveConstraint uncovered = covered.negateDisjoint(); // negateDisjoint 使用 Constraint 内部的 clocks

                    // 如果 uncovered 是 FALSE，说明已完全覆盖，无需添加 Sink 转移
                    if (uncovered.isFalse()) {
                        System.out.println("DTA-toCTA: (" + loc + ", " + action + ") 已完全覆盖，无需添加 Sink 转移。");
                        continue; // 处理下一个动作
                    }
                    // 如果 uncovered 是 TRUE (理论上不应发生，因为 Universe ∧ ¬covered)，说明 covered 是 FALSE
                    // 这意味着所有转移都去往 Sink，或者没有非 Sink 转移。
                    // 如果 covered 是 FALSE，negateDisjoint(Universe ∧ ⊤) 应该等于 Universe (即 trueConstraint)
                    if (uncovered.isTrue()) {
                        if (!hasNonSinkTransition) {
                            // 如果确实没有非 Sink 转移，那么未覆盖区域就是 TRUE
                            Constraint trueGuard = Constraint.trueConstraint(allClocks);
                            // 检查是否已存在guard为 TRUE 的到 Sink 的转移，避免重复添加
                            boolean trueSinkExists = transitions.stream()
                                    .anyMatch(t -> t.getTarget().isSink() && t.getGuard().isTrue());
                            if (!trueSinkExists) {
                                Transition sinkTrans = new Transition(
                                        loc, action, trueGuard, allClocks, sink
                                );
                                cta.addTransition(sinkTrans);
                                System.out.println("DTA-toCTA: 添加未覆盖转移 (TRUE): " + sinkTrans);
                            }
                        } else {
                            // 如果有非 Sink 转移，但 uncovered 仍为 TRUE，这可能表示 negateDisjoint 逻辑有问题
                            System.err.println("DTA-toCTA: 警告：计算出的未覆盖区域为 TRUE，但存在非 Sink 转移。 Covered: " + covered);
                            // 仍然添加 TRUE guard的转移以确保完备性
                            Constraint trueGuard = Constraint.trueConstraint(allClocks);
                            boolean trueSinkExists = transitions.stream()
                                    .anyMatch(t -> t.getTarget().isSink() && t.getGuard().isTrue());
                            if (!trueSinkExists) {
                                Transition sinkTrans = new Transition(
                                        loc, action, trueGuard, allClocks, sink
                                );
                                cta.addTransition(sinkTrans);
                                System.out.println("DTA-toCTA: 添加未覆盖转移 (TRUE，可能异常): " + sinkTrans);
                            }
                        }
                        continue; // 处理下一个动作
                    }


                    System.out.println("DTA-toCTA: (" + loc + ", " + action + ") 未覆盖区域: " + uncovered);
                    for (Constraint term : uncovered.getConstraints()) {
                        // term 是一个合取约束，定义在 allClocks 上
                        solver.push();;
                        boolean termIsSatisfiable = false;
                        try {
                            BoolExpr termExpr = Z3Converter.constraint2Boolexpr(term.and(Constraint.trueConstraint(allClocks)), ctx, clockVarMap);
                            solver.add(termExpr);
                            Status status = solver.check();
                            solver.pop();

                            if (status == Status.SATISFIABLE) {
                                termIsSatisfiable = true;
                            } else if (status == Status.UNKNOWN) {
                                System.err.println("DTA-toCTA: Z3 检查未覆盖项返回 UNKNOWN，项: " + term);
                                termIsSatisfiable = false; // 保守处理
                            }
                            // UNSATISFIABLE -> termIsSatisfiable 保持 false
                        } catch (Z3Exception e) {
                            // 处理 Z3 异常
                            System.err.println("DTA-toCTA: Z3 检查未覆盖项时出错: " + e.getMessage() + "，项: " + term);
                            // 可以选择抛出异常或将此项视为不可满足
                            termIsSatisfiable = false; // 保守处理
                            // throw new RuntimeException("Z3 error during CTA completion check", e);
                        }

                        // 只有当 term 可满足时，才添加对应的 Sink 转移
                        if (termIsSatisfiable) {
                            // 检查是否已存在完全相同的 Sink 转移（相同的源、动作、guard、目标）
                            Constraint finalTerm = term.and(Constraint.trueConstraint(allClocks));
                            boolean exactSinkExists = transitions.stream()
                                    .anyMatch(t -> t.getTarget().isSink() && t.getGuard().equals(finalTerm));

                            if (!exactSinkExists) {
                                Transition sinkTrans = new Transition(
                                        loc, action, finalTerm, clocks, sink
                                );
                                cta.addTransition(sinkTrans);
                                System.out.println("DTA-toCTA: 添加未覆盖转移 (检查后): " + sinkTrans);
                            } else {
                                System.out.println("DTA-toCTA: 跳过添加重复的 Sink 转移: " + loc + " --" + action + "/" + term + "--> " + sink);
                            }
                        } else {
                            System.out.println("DTA-toCTA: 跳过添加不可满足的 Sink 转移: " + loc + " --" + action + "/" + term + "--> " + sink);
                        }
                    } // 结束遍历 uncovered 的 terms
                } // 结束遍历 actions
            } // 结束遍历 locations
        } // 结束 Z3 Context
        return cta;
    }

    public boolean isDeterministic() {
        try (Context ctx = new Context()) {
            Solver solver = ctx.mkSolver();
            Map<Clock, RealExpr> clockVarMap = DTARuntimeContext.getVarMap(clocks,ctx);
            for (Location loc : getLocations()) {
                for (Action action : getAlphabet().alphabet.values()) {
                    List<Transition> transitions = getTransitions(loc, action);

                    // 检查所有转移对的互斥性
                    for (int i = 0; i < transitions.size(); i++) {
                        for (int j = i + 1; j < transitions.size(); j++) {
                            Transition t1 = transitions.get(i);
                            Transition t2 = transitions.get(j);

                            BoolExpr intersection = ctx.mkAnd(Z3Converter.constraint2Boolexpr(t1.getGuard(), ctx, clockVarMap), Z3Converter.constraint2Boolexpr(t2.getGuard(), ctx, clockVarMap));

                            solver.push();
                            solver.add(intersection);
                            Status status = solver.check();
                            solver.pop();

                            if (status != Status.UNSATISFIABLE) {
                                return false;
                            }
                        }
                    }
                }
            }
            return true;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Alphabet: ").append(getAlphabet().alphabet.values()).append("\n");
        sb.append("Clocks: ").append(getClocks()).append("\n");
        sb.append("Initial Location: ").append(getInitialLocation()).append("\n");
        sb.append("Accepting Locations: ").append(getAcceptingLocations()).append("\n");
        sb.append("Locations: ").append(getLocations()).append("\n");
        sb.append("Transitions: ").append(getTransitions()).append("\n");
        return sb.toString();
    }
}