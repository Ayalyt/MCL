//import lombok.Getter;
//import org.example.Clock;
//import org.example.tables.Row;
//import org.example.words.RegionTimedWord;
//import org.example.words.ResetClockTimedWord;
//import org.example.words.ResetDelayTimedWord;
//
//import java.util.List;
//import java.util.Set;
//
//@Getter
//public final class ClockValuation {
//    @Getter
//    private final SortedMap<Clock, Rational> clockValuation; // 时钟 → 值
//
//    // ---------------------------
//    // 构造方法
//    // ---------------------------
//    public ClockValuation(SortedMap<Clock, Rational> valuation) {}
//
//    public ClockValuation copy();
//    /**
//     * 静态工厂方法：创建全零时钟赋值
//     * @param clocks 所有时钟集合
//     */
//    public static ClockValuation zero(Collection<Clock> clocks);
//    public Set<Clock> getClocks();
//
//    public Rational getValue(Clock clock);
//
//    public ClockValuation delay(Rational delay);
//
//    /**
//     * @param resets 要重置的时钟集合
//     */
//    public ClockValuation reset(Set<Clock> resets);
//
//    // ---------------------------
//    // 区域相关方法
//    // ---------------------------
//    public boolean isInRegion(Region region);
//
//    public Region toRegion(RegionConfiguration config);
//
//    // ---------------------------
//    // 内部工具方法
//    // ---------------------------
//    public boolean isFractionZero(Clock clock);
//
//    public Rational getFraction(Clock clock);
//}
//
//@Getter
//public class Location {
//    private static final IDgenerator ID_GENERATOR = new IDgenerator();
//    private final Integer id;
//    private final boolean isInitial;
//    private final boolean isAccepting;
//    private final boolean isSink;
//
//    public Location(boolean isInitial, boolean isAccepting) {}
//
//    private Location(boolean isInitial, boolean isAccepting, boolean isSink) {}
//
//    public static Location createSink();
//
//    public boolean isSink();
//}
//
//@Getter
//public final class Transition implements Comparable<Transition> {
//
//    private static final IDgenerator ID_GENERATOR = new IDgenerator();
//    private final Location source;
//    private final Location target;
//    private final Action action;
//    private final Constraint guard;
//    private final Set<Clock> resets;
//    private final int id;
//    @Override
//    public int compareTo(Transition o); // 签名占位符
//}
//
//@Getter
//public class AtomConstraint implements Comparable<AtomConstraint>{
//
//    private final Clock clock;
//    private final ValOrder valOrder; // 假设 ValOrder 是一个枚举或类
//    private final int boundValue;
//
//    private AtomConstraint(Clock clock, ValOrder valOrder, int boundValue) {}
//
//    public static AtomConstraint satisfies(Clock clock, String valOrder, int value);
//
//    public boolean isSatisfied(Rational clockValue);
//
//    public BoolExpr toZ3Expr(Context ctx);
//
//    public DisjunctiveConstraint negate();
//    @Override
//    public int compareTo(AtomConstraint o); // 签名占位符
//}
//
//@Getter
//public class Constraint {
//    public static final Constraint TRUE = new Constraint();
//    private final Set<AtomConstraint> constraints;
//    private final Set<Clock> relatedClocks;
//
//    public Constraint() {}
//
//    private Constraint(Set<AtomConstraint> constraints) {}
//
//    public static Constraint of(AtomConstraint... constraints);
//
//    public boolean isSatisfied(ClockValuation clockValues);
//
//    public Constraint merge(Constraint other);
//
//    public Constraint and(Constraint constraint);
//
//    public static Constraint and(Constraint... constraints);
//
//    public Constraint and(AtomConstraint constraint);
//
//    public BoolExpr toZ3Expr(Context ctx);
//
//    public DisjunctiveConstraint negate();
//
//    /**
//     * 计算约束差集：返回满足当前约束但不满足other约束的条件
//     * 数学定义：this - other ≡ this ∧ ¬other
//     */
//    public DisjunctiveConstraint minus(Constraint other);
//
//    /**
//     * 将当前约束转换为析取约束（单子句的DNF）
//     */
//    public DisjunctiveConstraint toDisjunctiveConstraint();
//
//    public DisjunctiveConstraint or(Constraint other);
//
//    public boolean isMutuallyExclusive(Constraint other);
//
//    private List<AtomConstraint> getConstraintsForClock(Clock clock);
//
//    /**
//     * 判断约束条件是否与另一个约束存在公共解
//     */
//    public boolean intersects(Constraint other);
//
//    /**
//     * 判断当前约束是否是另一个约束的子集
//     */
//    public boolean isSubsetOf(Constraint other);
//
//    public int getMaxConstantForClock(Clock clock);
//}
//
//@Getter
//public class DisjunctiveConstraint {
//
//    public static final DisjunctiveConstraint FALSE = DisjunctiveConstraint.of();
//    private final List<Constraint> disjunction;
//
//    public DisjunctiveConstraint(List<Constraint> disjuncts) {}
//
//    public static DisjunctiveConstraint of(Constraint... constraints);
//
//    public BoolExpr toZ3Expr(Context ctx);
//
//    public boolean isSatisfied(ClockValuation valuation);
//
//    public DisjunctiveConstraint or(DisjunctiveConstraint other);
//
//    public boolean isFalse();
//
//    public DisjunctiveConstraint and(DisjunctiveConstraint other);
//
//    public Constraint negate();
//}
//
//@Getter
//public class DFA {
//    private final Alphabet alphabet; // 假设 Alphabet 是一个类
//    private Location initialState;
//    private final Set<Location> locations;
//    private final Set<Location> acceptingLocations;
//    private final Set<Transition> transitions;
//    private final Map<Location, Map<Action, Location>> transitionMap; // 假设 Action 是一个类
//
//    public DFA(Alphabet alphabet, Location initialState) {}
//
//    // ================== 基础管理方法 ==================
//
//    public void addLocation(Location loc);
//
//    public void addAcceptingLocation(Location loc);
//
//    public void setInitialLocation(Location loc);
//
//    public boolean isAccepting(Location loc);
//
//    public void addTransition(Transition t);
//
//    // ================== 图结构查询方法 ==================
//    public Location getNextLocation(Location current, Action action);
//
//    public Set<Location> getReachableLocations();
//
//    // ================== DFA核心功能 ==================
//    public boolean accepts(List<Action> word);
//
//    // ================== 验证方法 ==================
//    public boolean isComplete();
//
//    public DFA toCompleteDFA();
//
//    private Location addSinkLocation();
//
//    // ================== 转换方法 ==================
//    public static DFA fromDTA(DTA dta);
//}
//
//@Getter
//public class DTA {
//    // 基础组件
//    private final Alphabet alphabet;
//    private final Set<Clock> clocks;
//    private Location initialLocation;
//    private final Set<Location> locations;
//    private final Set<Location> acceptingLocations;
//    private final Set<Transition> transitions;
//    private final RegionConfiguration configuration;
//
//    // 图结构索引（双向索引）
//    private final Map<Location, Set<Transition>> outgoingTransitions;
//    private final Map<Location, Set<Transition>> incomingTransitions;
//    private final Map<Location, Map<Action, List<Transition>>> actionTransitions;
//
//    // 最大常量缓存（用于区域计算）
//    private Integer maxConstant = null;
//
//    public DTA(Alphabet alphabet, Set<Clock> clocks, Location initialLocation, RegionConfiguration configuration) {}
//
//    // ================== 基础管理方法 ==================
//
//    /**
//     * 创建当前DTA的浅拷贝副本
//     *
//     * @return 新DTA实例（与原始对象共享组件引用）
//     */
//    public DTA copy();
//
//    private void rebuildInternalIndicesForCopy(DTA copy);
//
//    public void addLocation(Location loc);
//
//    public void addAcceptingLocation(Location loc);
//
//    public void setInitialLocation(Location loc);
//
//    public boolean isAccepting(Location loc);
//
//    public void addTransition(Transition t);
//
//    public Location addSinkLocation();
//
//    // ================== 图结构查询方法 ==================
//    public Set<Transition> getOutgoingTransitions(Location loc);
//
//    public Set<Transition> getIncomingTransitions(Location loc);
//
//    public Set<Location> getPredecessors(Location loc);
//
//    public Set<Location> getSuccessors(Location loc);
//
//    public List<Transition> getTransitions(Location source, Action action);
//
//    // ================== DTA核心功能 ==================
//    public Optional<Transition> findEnabledTransition(Location current, ClockValuation valuation,
//                                                      Action action, Rational delay);
//
//    // ================== 学习算法支持 ==================
//    public Set<Region> getAllRegions();
//
//    public void updateConfiguration();
//
//    public Map<Clock, Integer> getMaxConstants();
//
//    // ================== 验证与工具方法 ==================
//    public DTARuntime createRuntime(); // 假设 DTARuntime 是一个类
//
//    public boolean isComplete();
//
//    public DTA toCTA();
//
//    public boolean isDeterministic();
//
//    // 注意：虽然有 @Getter，但部分字段（如 maxConstant）可能没有 getter。
//    // 索引 Map 通常不直接暴露。
//}
//
//@Getter
//public class ObservationTable implements Cloneable {
//    // 核心数据结构
//    private LinkedHashSet<ResetClockTimedWord> S; // 前缀集
//    private LinkedHashSet<ResetClockTimedWord> R; // 边界集
//    private ArrayList<RegionTimedWord> E;           // 后缀集（区域后缀）
//    // f 表存储 membership query 结果（每个 (s, e) 对是否属于目标语言）
//    private HashMap<Pair<ResetClockTimedWord, RegionTimedWord>, Boolean> f;
//    // g 表存储重置信息（每个 (s, e) 对对应的时钟重置组合）
//    private HashMap<Pair<ResetClockTimedWord, RegionTimedWord>, List<Set<Clock>>> g;
//
//    private final Alphabet alphabet;
//    private final Set<Clock> clocks;
//    private final NormalTeacher normalTeacher;
//    private RegionSolver regionSolver = new RegionSolver();
//    private final RegionConfiguration configuration;
//    private int guessCount = 0;
//
//    // 缓存结构
//    private Map<ResetClockTimedWord, Row> rowCache = new HashMap<>(); // 行缓存（前缀 -> 行数据），假设 Row 是一个内部类或独立类
//    private Map<ResetClockTimedWord, Triple<Action, ClockValuation, Set<Clock>>> lastActionCache = new HashMap<>(); // 最后动作缓存
//    private Map<ResetClockTimedWord, Region> lastRegionCache = new HashMap<>(); // 最后区域缓存
//
//    private List<InconsistencyRecord> inconsistencyRecords = new ArrayList<>(); // 假设 InconsistencyRecord 是一个内部类或独立类
//
//    public ObservationTable(Alphabet alphabet, int numClocks, NormalTeacher teacher, RegionConfiguration configuration) {}
//
//    public ObservationTable(ObservationTable other) {}
//
//    public boolean isClosed();
//
//    /**
//     * 尝试通过将非封闭元素 r 从 R 移动到 S 来使表封闭，
//     * 基于所有动作和所有可能的重置猜测添加新的边界元素，
//     * 然后使用 fillTable 生成所有可能的填充表实例。
//     *
//     * @return 返回 ObservationTable 实例的列表，每个实例代表一个潜在的封闭且完全填充的表。
//     */
//    public List<ObservationTable> guessClosing();
//
//    /**
//     * 检查一致性
//     * 前提条件：f 和 g 表应填充所有 S、R、E 所需的条目。
//     * 缓存必须是最新的。
//     *
//     * @return 如果一致，则为 true，否则为 false。如果不一致，则填充 inconsistencyRecords。
//     */
//    public boolean isConsistent();
//
//    /**
//     * 尝试通过向 E 添加新的后缀来解决发现的第一个不一致性，
//     * 然后使用 fillTable 生成所有可能的填充表实例。
//     *
//     * @return 返回 ObservationTable 实例的列表，每个实例代表通过猜测解决不一致性后的潜在一致表。
//     */
//    public List<ObservationTable> guessConsistency();
//
//    private RegionTimedWord findDistinguishingSuffix(Row r1, Row r2);
//
//    /**
//     * 2^|C|
//     */
//    public List<Set<Clock>> generateAllSubsetsOfClocks();
//
//    public List<List<Set<Clock>>> resetsForSuffix(int suffixLength);
//
//    public void buildCaches();
//
//    private void buildRowCacheIfNeeded();
//
//    public Row getRow(ResetClockTimedWord word);
//
//    public List<ResetDelayTimedWord> generateResetDelayWords(ResetClockTimedWord prefix, RegionTimedWord suffix);
//}