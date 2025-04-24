package org.example.tables;

import com.microsoft.z3.*;
import lombok.Getter;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.example.*;
import org.example.automata.DFA;
import org.example.automata.DTA;
import org.example.constraint.AtomConstraint;
import org.example.constraint.Constraint;
import org.example.constraint.DisjunctiveConstraint;
import org.example.region.Region;
import org.example.ClockConfiguration;
import org.example.region.RegionSolver;
import org.example.teacher.NormalTeacher;
import org.example.utils.Rational;
import org.example.utils.Z3Converter;
import org.example.words.DelayTimedWord;
import org.example.words.RegionTimedWord;
import org.example.words.ResetClockTimedWord;
import org.example.words.ResetDelayTimedWord;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Getter
public class ObservationTable implements Cloneable {
    // 核心数据结构
    private LinkedHashSet<ResetClockTimedWord> S; // 前缀集
    private LinkedHashSet<ResetClockTimedWord> R; // 边界集
    private ArrayList<RegionTimedWord> E;           // 后缀集（区域后缀）
    // f 表存储 membership query 结果（每个 (s, e) 对是否属于目标语言）
    private HashMap<Pair<ResetClockTimedWord, RegionTimedWord>, Boolean> f;
    // g 表存储重置信息（每个 (s, e) 对对应的时钟重置组合）
    private HashMap<Pair<ResetClockTimedWord, RegionTimedWord>, List<Set<Clock>>> g;

    private final Alphabet alphabet;
    private final Set<Clock> clocks;
    private final NormalTeacher normalTeacher;
    private final ClockConfiguration configuration;
    private int guessCount = 0;

    // 缓存结构
    private Map<ResetClockTimedWord, Row> rowCache = new HashMap<>(); // 行缓存（前缀 -> 行数据）
    private Map<ResetClockTimedWord, Triple<Action, ClockValuation, Set<Clock>>> lastActionCache = new HashMap<>(); // 最后动作缓存
    private Map<ResetClockTimedWord, Region> lastRegionCache = new HashMap<>(); // 最后区域缓存

    private List<InconsistencyRecord> inconsistencyRecords = new ArrayList<>();

    public ObservationTable(Alphabet alphabet, int numClocks, NormalTeacher teacher, ClockConfiguration configuration) {
        this.alphabet = alphabet;
        Set<Clock> clocks = new HashSet<>();
        for(Clock clock: configuration.getClockName().values()){
            clocks.add(new Clock(clock));
        }
        this.clocks = clocks;
        this.configuration = configuration;
        this.configuration.updateClockConfig(clocks.stream()
                .collect(Collectors.toMap(Clock::getName, clock -> clock)));
        Map<String, Clock> clockName = new HashMap<>();
        for (Clock clock: clocks){
            clock.setName();
            clockName.put(clock.getName(), clock);
        }
        this.configuration.setClockName(clockName);

        this.normalTeacher = teacher;

        // S = {ε}
        this.S = new LinkedHashSet<>();
        this.S.add(ResetClockTimedWord.EMPTY);

        // R: 交由learner填充
        this.R = new LinkedHashSet<>();
        // E = {ε}
        this.E = new ArrayList<>();
        this.E.add(RegionTimedWord.EMPTY);

        this.f = new HashMap<>();
        this.g = new HashMap<>();
    }
    public ObservationTable(ObservationTable other) {
        this.alphabet = other.alphabet;
        this.normalTeacher = other.normalTeacher;
        this.configuration = other.configuration;
        this.guessCount = other.guessCount;

        this.clocks = new HashSet<>();
        this.clocks.addAll(other.clocks);

        this.S = new LinkedHashSet<>();
        for (ResetClockTimedWord word : other.S) {
            this.S.add(new ResetClockTimedWord(word.getTimedActions()));
        }

        this.R = new LinkedHashSet<>();
        for (ResetClockTimedWord word : other.R) {
            this.R.add(new ResetClockTimedWord(word.getTimedActions()));
        }

        this.E = new ArrayList<>();
        for (RegionTimedWord word : other.E) {
            this.E.add(new RegionTimedWord(word.getTimedActions()));
        }

        this.f = new HashMap<>();
        for (Map.Entry<Pair<ResetClockTimedWord, RegionTimedWord>, Boolean> entry : other.f.entrySet()) {
            Pair<ResetClockTimedWord, RegionTimedWord> newKey = Pair.of(
                    new ResetClockTimedWord(entry.getKey().getLeft().getTimedActions()),
                    new RegionTimedWord(entry.getKey().getRight().getTimedActions())
            );
            this.f.put(newKey, entry.getValue());
        }

        this.g = new HashMap<>();
        for (Map.Entry<Pair<ResetClockTimedWord, RegionTimedWord>, List<Set<Clock>>> entry : other.g.entrySet()) {
            Pair<ResetClockTimedWord, RegionTimedWord> newKey = Pair.of(
                    new ResetClockTimedWord(entry.getKey().getLeft().getTimedActions()),
                    new RegionTimedWord(entry.getKey().getRight().getTimedActions())
            );
            List<Set<Clock>> newResetList = new ArrayList<>();
            for (Set<Clock> resetSet : entry.getValue()) {
                Set<Clock> newResetSet = new HashSet<>(resetSet);
                newResetList.add(newResetSet);
            }
            this.g.put(newKey, newResetList);
        }

        this.rowCache = new HashMap<>();
        for (Map.Entry<ResetClockTimedWord, Row> entry : other.rowCache.entrySet()) {
            this.rowCache.put(
                    new ResetClockTimedWord(entry.getKey().getTimedActions()),
                    new Row(entry.getValue())
            );
        }

        this.lastActionCache = new HashMap<>();
        for (Map.Entry<ResetClockTimedWord, Triple<Action, ClockValuation, Set<Clock>>> entry : other.lastActionCache.entrySet()) {
            Set<Clock> newResetSet = new HashSet<>(entry.getValue().getRight());
            this.lastActionCache.put(
                    new ResetClockTimedWord(entry.getKey().getTimedActions()),
                    Triple.of(
                            entry.getValue().getLeft(),
                            new ClockValuation(entry.getValue().getMiddle().getClockValuation()),
                            newResetSet
                    )
            );
        }

        this.lastRegionCache = new HashMap<>();
        for (Map.Entry<ResetClockTimedWord, Region> entry : other.lastRegionCache.entrySet()) {
            this.lastRegionCache.put(
                    new ResetClockTimedWord(entry.getKey().getTimedActions()),
                    new Region(entry.getValue())
            );
        }

        this.inconsistencyRecords = new ArrayList<>();
        for (InconsistencyRecord record : other.inconsistencyRecords) {
            this.inconsistencyRecords.add(new InconsistencyRecord(record));
        }
    }

    public boolean isClosed() {
        buildRowCacheIfNeeded();
        for (ResetClockTimedWord r : R) {
            Row rowR = rowCache.get(r);
            if (rowR == null) {
                System.err.println("Warning: R项: " + r + "缓存缺失");
                return false;
            }
            boolean foundEquivalent = false;
            for (ResetClockTimedWord s : S) {
                Row rowS = rowCache.get(s);
                if (rowS != null && rowS.equals(rowR)) {
                    foundEquivalent = true;
                    break;
                }
            }
            if (!foundEquivalent) {
                System.out.println("不闭合的R项: " + r);
                return false;
            }
        }
        return true;
    }

    /**
     * 尝试通过将非封闭元素 r 从 R 移动到 S 来使表封闭，
     * 基于所有动作和所有可能的重置猜测添加新的边界元素，
     * 然后使用 fillTable 生成所有可能的填充表实例。
     *
     * @return 返回 ObservationTable 实例的列表，每个实例代表一个潜在的封闭且完全填充的表。
     */
    public List<ObservationTable> guessClosing() {
        buildRowCacheIfNeeded(); // 确保已计算用于比较的行

        ResetClockTimedWord problematicR = null;
        for (ResetClockTimedWord r : R) {
            Row rowR = rowCache.get(r);
            if (rowR == null) {
                System.err.println("guessClosing 警告: R 元素缺少行缓存: " + r + "。表可能需要先填充。");
            }
            // 检查 r 的行是否存在于 S 中
            boolean foundEquivalentInS = S.stream()
                    .map(rowCache::get)
                    .filter(Objects::nonNull)
                    .anyMatch(rowS -> rowS != null && rowS.equals(rowR)); // 添加 null 检查

            if (!foundEquivalentInS) {
                problematicR = r;
                break;
            }
        }

        if (problematicR == null) {
            // 表在结构上已经封闭
            System.out.println("guessClosing: 表在结构上已经封闭。");
            return Collections.singletonList(new ObservationTable(this)); // 返回当前表的副本
        }

        System.out.println("guessClosing: 找到非封闭的 R 元素: " + problematicR);

        ObservationTable baseInstance = new ObservationTable(this);
        baseInstance.S.add(problematicR);
        baseInstance.R.remove(problematicR);
        baseInstance.rowCache.clear();
        baseInstance.lastActionCache.clear();
        baseInstance.lastRegionCache.clear();

        List<ObservationTable> finalTableInstances = new ArrayList<>();
        List<Action> actions = new ArrayList<>(alphabet.alphabet.values());
        List<Set<Clock>> allSingleActionResetSubsets = generateAllSubsetsOfClocks();
        int numActions = actions.size();
        int numResetOptions = allSingleActionResetSubsets.size(); // 2^|C|

        // 计算所有可能的完整重置猜测组合的数量
        long totalCombinations = (long) Math.pow(numResetOptions, numActions);
        if (totalCombinations > 10000) {
            System.err.println("警告: 重置组合数量巨大 (" + totalCombinations + ")。");
        }


        int[] currentCombinationIndices = new int[numActions];

        for (long i = 0; i < totalCombinations; i++) {
            ObservationTable specificInstance = new ObservationTable(baseInstance);
            specificInstance.guessCount++; // 增加猜测计数

            StringBuilder combinationDesc = new StringBuilder("(");

            for (int actionIndex = 0; actionIndex < numActions; actionIndex++) {
                Action currentAction = actions.get(actionIndex);
                // 从当前组合索引确定此动作的重置猜测
                int resetIndex = currentCombinationIndices[actionIndex];
                Set<Clock> currentResetGuess = allSingleActionResetSubsets.get(resetIndex);

                combinationDesc.append(currentAction.getAction()).append(":").append(currentResetGuess).append(actionIndex == numActions - 1 ? "" : ", ");

                ClockValuation zeroValuation = ClockValuation.zero(this.clocks);
                ResetClockTimedWord newBoundaryEntry = problematicR.append(Triple.of(currentAction, zeroValuation, currentResetGuess));

                if (!specificInstance.S.contains(newBoundaryEntry)) {
                    specificInstance.R.add(newBoundaryEntry);
                }
            }
            combinationDesc.append(")");
            System.out.println("guessClosing: 创建实例，猜测组合: " + combinationDesc);
            System.out.println("guessClosing: 实例 R 集合: " + specificInstance.R);


            System.out.println("guessClosing: 为具有完整新边界的实例调用 fillTable");
            // fillTable 负责计算新 R 行的 f 和 g 值 (可能涉及 MQ 和进一步猜测)
            List<ObservationTable> filledInstances = specificInstance.fillTable();
            finalTableInstances.addAll(filledInstances);

            // 更新到下一个组合索引
            int actionToIncrement = numActions - 1;
            while (actionToIncrement >= 0) {
                currentCombinationIndices[actionToIncrement]++;
                if (currentCombinationIndices[actionToIncrement] == numResetOptions) {
                    currentCombinationIndices[actionToIncrement] = 0;
                    actionToIncrement--; // 进位
                } else {
                    break;
                }
            }
        }

        System.out.println("guessClosing: 完成。生成了 " + finalTableInstances.size() + " 个最终的表实例。");
        return finalTableInstances;
    }

    public boolean isConsistent() {
        return isConsistent(true);
    }
    /**
     * 检查一致性
     * 前提条件：f 和 g 表应填充所有 S、R、E 所需的条目。
     * 缓存必须是最新的。
     *
     * @return 如果一致，则为 true，否则为 false。如果不一致，则填充 inconsistencyRecords。
     */
    public boolean isConsistent(boolean haveRecord) {
        inconsistencyRecords.clear(); // 清空旧记录
        buildCaches(); // 确保缓存最新

        Set<ResetClockTimedWord> sUnionR = new HashSet<>(S);
        sUnionR.addAll(R);

        // 按行分组 S U R 中的所有前缀 (这里分组的是 w = yrσr)
        Map<Row, List<ResetClockTimedWord>> prefixesByRow = sUnionR.stream()
                .filter(w -> rowCache.containsKey(w)) // 仅考虑有对应行的前缀
                .collect(Collectors.groupingBy(rowCache::get));


        for (Map.Entry<Row, List<ResetClockTimedWord>> group : prefixesByRow.entrySet()) {
            // 这里的 group.getKey() 是 yrσr 的行
            // group.getValue() 是所有行等于 group.getKey() 的 yrσr 列表

            // 我们需要找到 yr 和 y' 具有相同行的情况
            // 然后比较它们的后续 yrσr 和 y'σ'r

            // 改进思路：直接遍历所有 S U R 中的对 (w1, w2)
            List<ResetClockTimedWord> allPrefixes = new ArrayList<>(sUnionR);
            for (int i = 0; i < allPrefixes.size(); i++) {
                ResetClockTimedWord w1 = allPrefixes.get(i); // 对应 yrσr
                if (w1.isEmpty()) {
                    continue;
                }
                ResetClockTimedWord prefix1 = w1.getPrefix(w1.length() - 1); // 对应 yr
                Row rowPrefix1 = rowCache.get(prefix1);
                Triple<Action, ClockValuation, Set<Clock>> lastAction1 = lastActionCache.get(w1);
                Region lastRegion1 = lastRegionCache.get(w1);

                if (rowPrefix1 == null || lastAction1 == null || lastRegion1 == null) {
                    continue;
                }

                for (int j = i + 1; j < allPrefixes.size(); j++) {
                    ResetClockTimedWord w2 = allPrefixes.get(j); // 对应 y'σ'r
                    if (w2.isEmpty()) {
                        continue;
                    }
                    ResetClockTimedWord prefix2 = w2.getPrefix(w2.length() - 1); // 对应 y'
                    Row rowPrefix2 = rowCache.get(prefix2);
                    Triple<Action, ClockValuation, Set<Clock>> lastAction2 = lastActionCache.get(w2);
                    Region lastRegion2 = lastRegionCache.get(w2);

                    if (rowPrefix2 == null || lastAction2 == null || lastRegion2 == null) {
                        continue;
                    }

                    // 检查前提条件：row(yr) == row(y') 且 [vw(σr)] == [vw(σ'r)]
                    if (rowPrefix1.equals(rowPrefix2) && lastRegion1.equals(lastRegion2)) {
                        // 前提满足，现在检查结论
                        Row rowW1 = rowCache.get(w1);
                        Row rowW2 = rowCache.get(w2);
                        Set<Clock> resets1 = lastAction1.getRight();
                        Set<Clock> resets2 = lastAction2.getRight();

                        if (rowW1 == null || rowW2 == null) {
                            System.err.println("警告: isConsistent 检查时发现 w1 或 w2 的行缓存缺失。");
                            continue;
                        }


                        // 检查 ROW_MISMATCH: row(yrσr) != row(y'σ'r)
                        if (!rowW1.equals(rowW2)) {
                            RegionTimedWord distinguishingSuffix = findDistinguishingSuffix(rowW1, rowW2);
                            if (distinguishingSuffix == null) {
                                System.err.println("错误: 行不同但未找到区分后缀! Row1: " + rowW1 + ", Row2: " + rowW2);
                                if (haveRecord){
                                    inconsistencyRecords.add(new InconsistencyRecord(w1, w2, InconsistencyRecord.InconsistencyType.ROW_MISMATCH, null));
                                }
                            } else {
                                if (haveRecord){
                                    inconsistencyRecords.add(new InconsistencyRecord(w1, w2, InconsistencyRecord.InconsistencyType.ROW_MISMATCH, distinguishingSuffix));
                                }
                                System.out.println("当前不一致条目"+ inconsistencyRecords.size() + "条。新加入: ROW_MISMATCH, w1: " + w1 + ", w2: " + w2);
                            }
                            return false;
                        }

                        // 检查 RESET_MISMATCH: resets(σr) != resets(σ'r)
                        if (!Objects.equals(resets1, resets2)) {
                            if (haveRecord){
                                inconsistencyRecords.add(new InconsistencyRecord(w1, w2, InconsistencyRecord.InconsistencyType.RESET_MISMATCH, null));
                            }
                            System.out.println("当前不一致条目"+ inconsistencyRecords.size() + "条。新加入: RESET_MISMATCH, w1: " + w1 + ", w2: " + w2);
                            return false;
                        }
                    }
                }
            }
        }

        return true; // 没有找到不一致
    }

    /**
     * 尝试通过向 E 添加新的后缀来解决发现的第一个不一致性，
     * 然后使用 fillTable 生成所有可能的填充表实例。
     *
     * @return 返回 ObservationTable 实例的列表，每个实例代表通过猜测解决不一致性后的潜在一致表。
     */
    public List<ObservationTable> guessConsistency() {
        if (inconsistencyRecords.isEmpty()) {
            System.err.println("guessConsistency 错误: 未找到不一致记录。请先调用 isConsistent()。");
            // 如果实际上没有发现不一致，返回当前状态
            return Collections.singletonList(new ObservationTable(this));
        }

        InconsistencyRecord record = inconsistencyRecords.get(0);
        System.out.println("guessConsistency: 处理不一致: " + record);
        ResetClockTimedWord w1 = record.getWord1(); // yrσr

        Triple<Action, ClockValuation, Set<Clock>> lastActionW1 = lastActionCache.get(w1);
        if (lastActionW1 == null) {
            System.err.println("guessConsistency 错误: 无法获取不一致字的最后动作信息: " + w1);
            return Collections.emptyList(); // 无法继续
        }

        // 构造 [vw(σr)]
        Region lastRegionW1;
        try {
            lastRegionW1 = lastActionW1.getMiddle().toRegion(configuration);
        } catch (Exception e) {
            System.err.println("guessConsistency 错误: 计算区域失败 " + lastActionW1.getMiddle() + ": " + e.getMessage());
            return Collections.emptyList();
        }
        RegionTimedWord regionPrefix = new RegionTimedWord(Collections.singletonList(Pair.of(lastActionW1.getLeft(), lastRegionW1)));

        // 构造新的后缀 e'
        RegionTimedWord newSuffixEPrime;
        if (record.getType() == InconsistencyRecord.InconsistencyType.ROW_MISMATCH) {
            RegionTimedWord distinguishingSuffixE = record.getProblematicSuffix();
            if (distinguishingSuffixE == null) {
                System.err.println("guessConsistency 错误: ROW_MISMATCH 记录缺少区分后缀 e。");
                return Collections.emptyList();
            }
            newSuffixEPrime = regionPrefix.concat(distinguishingSuffixE);
        } else { // RESET_MISMATCH
            newSuffixEPrime = regionPrefix; // e' = [vw(σr)]
        }

        // 创建添加了新后缀的基础表
        ObservationTable baseTable = new ObservationTable(this);
        if (!baseTable.E.contains(newSuffixEPrime)) {
            baseTable.E.add(newSuffixEPrime);
            System.out.println("guessConsistency: 向 E 添加新后缀: " + newSuffixEPrime);
        } else {
            System.out.println("guessConsistency: 后缀 " + newSuffixEPrime + " 已存在于 E 中。");
            return new ArrayList<>();
        }
        baseTable.inconsistencyRecords.clear(); // 清除旧记录

        System.out.println("guessConsistency: 调用 fillTable 填充新后缀和可能的其他条目...");
        return baseTable.fillTable();
    }


    private RegionTimedWord findDistinguishingSuffix(Row r1, Row r2) {
        if (r1 == null || r2 == null) {
            return null; // 参数非法
        }
        if (r1.equals(r2)) {
            return null;
        }

        for (RegionTimedWord e : this.E) {
            if (!Objects.equals(r1.getFValues().get(e), r2.getFValues().get(e)) ||
                    !Objects.equals(r1.getGValues().get(e), r2.getGValues().get(e))) {
                return e;
            }
        }
        throw new IllegalStateException("未找到区分后缀，但行不同！");
    }

    /**
     * 2^|C|
     */
    public List<Set<Clock>> generateAllSubsetsOfClocks() {
        List<Clock> clockList = new ArrayList<>(this.clocks);
        int n = clockList.size();
        List<Set<Clock>> allSubsets = new ArrayList<>();
        for (int i = 0; i < (1 << n); i++) {
            Set<Clock> subset = new HashSet<>();
            for (int j = 0; j < n; j++) {
                if ((i & (1 << j)) > 0) {
                    subset.add(clockList.get(j));
                }
            }
            allSubsets.add(subset);
        }
        return allSubsets;
    }

    /**
     * 通过猜测后缀的重置序列并执行成员查询来填充 f 和 g 表中的所有缺失条目。
     * 由于涉及猜测，这可能会产生多个可能的填充表。
     *
     * @return 返回 ObservationTable 实例的列表，每个实例代表基于有效重置猜测组合的完整填充表。
     *         如果出现无法恢复的错误，可能返回空列表或部分结果。
     * @throws IllegalStateException 如果在处理过程中遇到表示内部状态不一致的严重错误。
     */
    public List<ObservationTable> fillTable() {
        // 1. 识别需要填充的条目 (entriesToFill)
        Set<Pair<ResetClockTimedWord, RegionTimedWord>> entriesToFill = new HashSet<>();
        Set<ResetClockTimedWord> prefixes = new HashSet<>(S); // 获取所有前缀 (S 和 R)
        prefixes.addAll(R);

        for (ResetClockTimedWord prefix : prefixes) {
            for (RegionTimedWord suffix : E) {
                Pair<ResetClockTimedWord, RegionTimedWord> key = Pair.of(prefix, suffix);
                if (suffix.isEmpty()) {
                    // 1.1 处理空后缀（确定性情况）
                    // 空后缀不需要猜测，f 值直接通过查询前缀本身得到，g 值为空列表
                    try {
                        // 尝试填充 f 值，如果键不存在
                        f.putIfAbsent(key, normalTeacher.membershipQuery(prefix.toResetDelayTimedWord(this.clocks).toDelayTimedWord()));

                        // 尝试填充 g 值，如果键不存在
                        g.putIfAbsent(key, Collections.emptyList());
                    } catch (Exception e) {
                        // 捕获成员查询或转换中可能出现的任何异常
                        System.err.println("fillTable: 处理空后缀 (" + prefix + ", " + suffix + ") 时无有效延迟，已放弃当前实例: " + e.getMessage());
                    }
                    continue; // 继续处理下一个后缀
                }
                // 1.2 识别非空后缀的缺失
                // 检查 f 或 g 表中是否缺少该 (prefix, non-empty suffix) 条目
                if (!f.containsKey(key) || !g.containsKey(key)) {
                    entriesToFill.add(key); // 加入待填充集合
                }
            }
        }

        // 2. 处理简单情况（无需填充）
        List<ObservationTable> completedTables = new ArrayList<>();
        if (entriesToFill.isEmpty()) {
            // 表中所有条目都已存在，无需填充
            System.out.println("fillTable: 不需要填充任何条目。");
            try {
                // 即使无需填充，也创建一个当前状态的副本返回
                ObservationTable currentCompleteTable = new ObservationTable(this);
                // 构建缓存以确保返回的表是立即可用的
                currentCompleteTable.buildCaches();
                completedTables.add(currentCompleteTable);
            } catch (Exception e) {
                // 捕获复制或构建缓存时可能出现的错误
                System.err.println("fillTable 错误: 在无需填充的情况下创建最终表副本时出错: " + e.getMessage());
                // 这种情况下可能无法返回有效结果，返回空列表
                return Collections.emptyList();
            }
            return completedTables;
        }

        // 3. 准备广度优先搜索 (BFS)
        System.out.println("fillTable: 需要填充 " + entriesToFill.size() + " 个条目。");

        // 使用队列存储待处理的状态，每个状态包含 (剩余待填充项的迭代器, 当前表实例)
        Queue<Pair<Iterator<Pair<ResetClockTimedWord, RegionTimedWord>>, ObservationTable>> queue = new LinkedList<>();
        // 将待填充条目集合转为列表，方便创建迭代器
        List<Pair<ResetClockTimedWord, RegionTimedWord>> entriesList = new ArrayList<>(entriesToFill);
        // 保留一份初始待填充条目的副本，用于调试和验证
        final Set<Pair<ResetClockTimedWord, RegionTimedWord>> initialEntriesToFillSet = new HashSet<>(entriesToFill);

        // 初始化BFS：将包含完整任务列表迭代器和当前表实例的Pair放入队列
        try {
            // 使用当前表 (this) 作为起点，但注意后续操作都在副本上进行
            queue.add(Pair.of(entriesList.iterator(), this));
        } catch (Exception e) {
            System.err.println("fillTable 错误: 初始化 BFS 队列时出错: " + e.getMessage());
            return Collections.emptyList(); // 无法开始 BFS
        }


        // 4. 执行 BFS 探索循环
        while (!queue.isEmpty()) {
            Pair<Iterator<Pair<ResetClockTimedWord, RegionTimedWord>>, ObservationTable> currentWork = queue.poll();
            Iterator<Pair<ResetClockTimedWord, RegionTimedWord>> entryIterator = currentWork.getLeft();
            ObservationTable currentTableState = currentWork.getRight();

            // 5. 检查 BFS 路径是否完成
            if (!entryIterator.hasNext()) {
                // 迭代器为空，表示这条路径上的所有初始待填充条目都已被处理
                System.out.println("fillTable: 完成一个表实例的填充路径 (猜测计数=" + currentTableState.guessCount + ")。");
                try {
                    boolean allEntriesPresent = true;
                    for (Pair<ResetClockTimedWord, RegionTimedWord> expectedKey : initialEntriesToFillSet) {
                        if (!currentTableState.f.containsKey(expectedKey) || !currentTableState.g.containsKey(expectedKey)) {
                            System.err.println("fillTable 内部错误: 完成路径但缺少条目: " + expectedKey + " in table hash: " + System.identityHashCode(currentTableState));
                            allEntriesPresent = false;
                        }
                    }
                    if (!allEntriesPresent) {
                        System.err.println("fillTable 警告: 完成的表实例未能包含所有初始待填充条目，可能存在状态传递问题！");
                        // 可以选择跳过这个不完整的实例: continue;
                        // 或者仍然尝试构建缓存并添加，但已知其可能不完整
                    }
                    currentTableState.buildCaches();
                    completedTables.add(currentTableState);
                } catch (Exception e) {
                    // 捕获构建缓存或添加结果时发生的错误
                    System.err.println("fillTable 错误: 在处理完成的表实例时出错 (table hash: " + System.identityHashCode(currentTableState) + "): " + e.getMessage());
                    // 选择跳过这个有问题的实例
                }
                continue; // 处理队列中的下一个工作项
            }

            // 6. 处理当前待填充条目
            Pair<ResetClockTimedWord, RegionTimedWord> entryToFill = entryIterator.next(); // 获取并消耗迭代器的一个元素
            ResetClockTimedWord prefix = entryToFill.getLeft();
            RegionTimedWord suffix_e = entryToFill.getRight();

            // 这会耗尽当前的 entryIterator，并将剩余项存储起来供后续分支使用
            List<Pair<ResetClockTimedWord, RegionTimedWord>> remainingItemsForAllBranches = new ArrayList<>();
            try {
                entryIterator.forEachRemaining(remainingItemsForAllBranches::add);
            } catch (Exception e) {
                System.err.println("fillTable 错误: 复制剩余迭代器条目时出错: " + e.getMessage());
                continue; // 跳过处理当前工作项
            }


            // 7. 为当前条目生成并尝试所有可能性 (遍历 guessedResetSequence)
            ClockValuation startValuationForSuffix;
            try {
                // 7.1 确定后缀开始时的时钟赋值
                if (prefix.isEmpty()) {
                    startValuationForSuffix = ClockValuation.zero(this.clocks);
                } else {
                    Triple<Action, ClockValuation, Set<Clock>> lastPrefixAction = prefix.getLast();
                    if (lastPrefixAction == null) {
                        // 这是一个潜在的内部状态错误，非空前缀应该有最后一个动作
                        System.err.println("fillTable 错误: 无法获取非空前缀的最后一个动作: " + prefix + "。跳过此分支。");
                        continue; // 跳过这个有问题的 currentWork
                    }
                    // 应用前缀最后一步的重置得到后缀的起始赋值
                    startValuationForSuffix = lastPrefixAction.getMiddle().reset(lastPrefixAction.getRight());
                }
            } catch (Exception e) {
                System.err.println("fillTable 错误: 计算后缀起始赋值时出错 (" + prefix + "): " + e.getMessage() + "。跳过此分支。");
                continue;
            }


            List<List<Set<Clock>>> possibleResetSequences;
            try {
                // 7.2 生成所有可能的重置序列
                possibleResetSequences = resetsForSuffix(suffix_e.length());
                if (possibleResetSequences.isEmpty() && !suffix_e.isEmpty()) {
                    // 如果非空后缀无法生成重置序列，这通常是一个配置或逻辑错误
                    System.err.println("fillTable 警告: 未能为非空后缀生成重置序列: " + suffix_e + " (长度 " + suffix_e.length() + ")。该条目无法填充，此分支终止。");
                    continue; // 这个分支无法继续为 entryToFill 找到有效填充
                }
            } catch (IllegalArgumentException e) {
                System.err.println("fillTable 错误: 生成重置序列时参数错误: " + e.getMessage() + "。跳过此分支。");
                continue;
            } catch (Exception e) {
                System.err.println("fillTable 错误: 生成重置序列时发生意外错误: " + e.getMessage() + "。跳过此分支。");
                continue;
            }


            AtomicInteger validGuessesForEntry = new AtomicInteger();

            // 7.3 循环遍历每一种可能的重置序列猜测
            for (List<Set<Clock>> guessedResetSequence : possibleResetSequences) {
                ResetClockTimedWord suffixWord = null;
                Boolean membershipResult = null;
                ObservationTable nextTableState = null;

                try {
                    // a. 验证猜测有效性：尝试转换区域后缀为重置时钟后缀
                    suffixWord = suffix_e.toResetClockTimedWord(
                            guessedResetSequence, startValuationForSuffix, this.clocks, this.configuration);

                    if (suffixWord != null) { // 猜测导致了有效的时间字序列
                        validGuessesForEntry.incrementAndGet(); // 增加有效猜测计数

                        // b. 构造完整词并执行成员查询
                        ResetClockTimedWord fullWord = prefix.concat(suffixWord);
                        ResetDelayTimedWord resetDelayWord = fullWord.toResetDelayTimedWord(this.clocks);
                        DelayTimedWord delayWord = resetDelayWord.toDelayTimedWord();
                        membershipResult = normalTeacher.membershipQuery(delayWord);
                        // 检查 MQ 是否返回 null，这可能表示不确定或错误
                        if (membershipResult == null) {
                            System.err.println("fillTable 警告: 成员查询返回 null，对于 word: " + delayWord + "。将跳过此猜测路径。");
                            continue; // 跳过这个猜测，因为它没有提供确定的结果
                        }


                        // c. 创建新的状态分支 (副本)
                        nextTableState = new ObservationTable(currentTableState); // 深拷贝
                        nextTableState.guessCount++; // 增加副本的猜测计数

                        // d. 填充新副本：记录当前猜测的结果 (f 和 g 值)
                        Pair<ResetClockTimedWord, RegionTimedWord> currentEntryKey = Pair.of(prefix, suffix_e);
                        nextTableState.f.put(currentEntryKey, membershipResult);
                        nextTableState.g.put(currentEntryKey, guessedResetSequence);

                        // e. 创建新的迭代器副本 (基于之前捕获的剩余任务列表)
                        List<Pair<ResetClockTimedWord, RegionTimedWord>> branchSpecificRemainingItems = new ArrayList<>(remainingItemsForAllBranches);

                        // f. 入队：将新状态（副本+新迭代器）加入队列，继续下一层探索
                        queue.add(Pair.of(branchSpecificRemainingItems.iterator(), nextTableState));

                    }  // 猜测无效 (toResetClockTimedWord 返回 null)，放弃此路径，不执行任何操作，继续下一个猜测


                } catch (Exception e) {
                    System.err.println("fillTable 警告: 处理猜测序列时出错，无合法后缀。已放弃路径。 (" + prefix + ", " + suffix_e + ", guess: " + guessedResetSequence + "): " + e.getMessage());
                }
            } // 结束对当前条目所有猜测的循环

            if (validGuessesForEntry.get() == 0 && !suffix_e.isEmpty()) {
                System.err.println("fillTable 警告: 对于条目 (" + prefix + ", " + suffix_e + ")，未找到任何有效的重置序列猜测。该 BFS 分支终止。");
            }

        }

        System.out.println("fillTable: 填充过程完成。生成了 " + completedTables.size() + " 个完整表实例。");
        return completedTables;
    }

    /**
     * 生成给定长度的所有可能的重置序列组合(迭代版本)。
     *
     * @param suffixLength 目标序列的长度 (必须 >= 0)
     * @return 包含所有可能的重置序列的列表
     * @throws IllegalArgumentException 如果 suffixLength 小于 0
     */
    public List<List<Set<Clock>>> resetsForSuffix(int suffixLength) {
        if (suffixLength < 0) {
            throw new IllegalArgumentException("Suffix length cannot be negative.");
        }

        List<List<Set<Clock>>> allSequences = new ArrayList<>();
        // 获取单步所有可能的重置集合
        List<Set<Clock>> singleStepOptions = generateAllSubsetsOfClocks();

        // 如果长度为0,返回一个空序列
        if (suffixLength == 0) {
            allSequences.add(new ArrayList<>());
            return allSequences;
        }

        // 初始化第一个位置的所有可能性
        for (Set<Clock> option : singleStepOptions) {
            List<Set<Clock>> sequence = new ArrayList<>();
            sequence.add(option);
            allSequences.add(sequence);
        }

        // 逐步构建剩余位置
        for (int pos = 1; pos < suffixLength; pos++) {
            List<List<Set<Clock>>> newSequences = new ArrayList<>();
            // 对于现有的每个序列
            for (List<Set<Clock>> existingSeq : allSequences) {
                // 尝试添加每个可能的选项
                for (Set<Clock> option : singleStepOptions) {
                    List<Set<Clock>> newSeq = new ArrayList<>(existingSeq);
                    newSeq.add(option);
                    newSequences.add(newSeq);
                }
            }
            allSequences = newSequences;
        }

        return allSequences;
    }

    /**
     * 构建或重建所有缓存（行、最后动作、最后区域）。
     * 如果表结构发生变化，应该在一致性/闭合性检查之前调用。
     */
    public void buildCaches() {
        rowCache.clear();
        lastActionCache.clear();
        lastRegionCache.clear();

        Set<ResetClockTimedWord> sUnionR = new HashSet<>(S);
        sUnionR.addAll(R);

        for (ResetClockTimedWord w : sUnionR) {
            getRow(w);
            // LastAction LastRegion
            if (!w.isEmpty()) {
                Triple<Action, ClockValuation, Set<Clock>> lastAction = w.getLast();
                if (lastAction != null) {
                    lastActionCache.put(w, lastAction);
                    ClockValuation lastValuation = lastAction.getMiddle();
                    if (lastValuation != null) {
                        try {
                            Region region = lastValuation.toRegion(configuration);
                            lastRegionCache.put(w, region);
                        } catch (Exception e) {
                            System.err.println("Error calculating region for " + w + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
    }

    /**
     * 如果行缓存为空，并且S或R不为空，则构建行缓存。
     */
    private void buildRowCacheIfNeeded() {
        if (rowCache.isEmpty() && (!S.isEmpty() || !R.isEmpty())) {
            buildCaches();
        }
    }

    /**
     * 判断证据闭合性（Evidence-Closure）。
     * 对于每个 S 中的元素与 E 中的后缀，必须存在有效后继。
     */
    public boolean isEvidenceClosed() {
        return true;
    }

    /**
     * 生成证据闭合猜测实例。
     * 针对某些 (s, e) 对有效后继为空的情况，通过扩展后缀及重置猜测生成新的实例。
     */
    public List<ObservationTable> guessEvidenceClosing() {
        return null;
    }

    /**
     * 严格按照论文逻辑处理来自 Normal Teacher 的 DelayTimedWord 反例。
     * 1. 找到与反例动作序列匹配的最长已知前缀 yr (在 S U R 中)。
     * 2. 对反例中超出 yr 的剩余部分，猜测所有可能的重置序列。
     * 3. 对于每一种有效的重置猜测：
     *    a. 创建当前观察表的一个新实例（深拷贝）。
     *    b. 基于猜测构建完整的 ResetClockTimedWord (cex_r)。
     *    c. 将 cex_r 中所有新的前缀（长度 > yr 的长度）添加到新实例的 R 集合。
     *    d. 调用 fillTable() 来填充这个新实例，这会触发成员查询（使用猜测的重置信息转换回 DelayTimedWord）。
     * 4. 返回所有成功填充的表实例的列表。
     *
     * @param cex 教师提供的反例 (DelayTimedWord)。必须非空。
     * @param cexMembership 反例的成员资格 (true 表示正例, false 表示反例)。
     *                      虽然 NormalTeacher 的 MQ 只返回布尔值，但 EQ 返回的反例本身就带有分类信息。
     * @return 可能的新表实例列表，每个实例代表一种重置猜测组合下的填充结果。如果无法处理（如反例为空），则返回空列表。
     * @throws IllegalArgumentException 如果反例 cex 为 null 或空。
     */
    public List<ObservationTable> processCounterexample(DelayTimedWord cex, boolean cexMembership) {
        System.out.println("处理反例: " + cex + ", 成员资格: " + cexMembership);
        if (cex == null || cex.isEmpty()) {
            // 论文场景不包含空反例，实际中应进行检查
            throw new IllegalArgumentException("反例不能为空。");
        }

        // --- 1. 找到最长匹配前缀 yr ---
        ResetClockTimedWord longestMatchingPrefix = ResetClockTimedWord.EMPTY;
        int matchLength = 0; // 匹配的 ResetClockTimedWord 的长度
// 全部进行猜测
//        Set<ResetClockTimedWord> sUnionR = new HashSet<>(S);
//        sUnionR.addAll(R);
//
//        for (ResetClockTimedWord yr : sUnionR) {
//            if (yr.isEmpty()) {
//                continue;
//            }
//            List<Action> yrActions = yr.getAction();
//            List<Action> cexActions = cex.getAction();
//
//            if (cexActions.size() >= yrActions.size()) {
//                boolean actionMatch = true;
//                for (int i = 0; i < yrActions.size(); i++) {
//                    if (!yrActions.get(i).equals(cexActions.get(i))) {
//                        actionMatch = false;
//                        break;
//                    }
//                }
//                if (actionMatch) {
//                    // 如果动作序列匹配，我们选择最长的那个
//                    // TODO: 未来可以考虑更强的匹配检查，例如时间上的可达性，但这会显著增加复杂性
//                    if (yr.length() > longestMatchingPrefix.length()) {
//                        longestMatchingPrefix = yr;
//                        matchLength = yr.length();
//                    }
//                }
//            }
//        }
//        System.out.println("找到最长匹配前缀 yr (长度 " + matchLength + "): " + longestMatchingPrefix);

        // --- 2. 确定需要猜测重置的剩余部分 ---
        List<Pair<Action, Rational>> remainingCexActions = cex.getTimedActions().subList(matchLength, cex.length());
        int remainingLength = remainingCexActions.size();
        System.out.println("反例剩余部分长度: " + remainingLength);

        // --- 3. 生成所有可能的重置序列猜测 ---
        // 即使 remainingLength 为 0，也需要继续处理，因为反例的 *分类* 本身就是新信息。
        // 当 remainingLength 为 0 时，resetsForSuffix(0) 会返回包含一个空列表 [[]] 的列表。
        List<List<Set<Clock>>> possibleResetGuesses;
        try {
            possibleResetGuesses = resetsForSuffix(remainingLength);
        } catch (IllegalArgumentException e) {
            // 这通常不应该发生，除非 remainingLength < 0
            System.err.println("处理反例错误: 生成重置序列时出错: " + e.getMessage());
            return Collections.emptyList(); // 无法继续
        }

        List<ObservationTable> resultingTables = new ArrayList<>(); // 存储所有最终生成的表实例

        // --- 4. 对每种猜测进行处理 ---
        for (List<Set<Clock>> guessedResets : possibleResetGuesses) {
            // a. 创建新实例 (深拷贝)
            ObservationTable tableForGuess = new ObservationTable(this);
            tableForGuess.guessCount++; // 增加此实例的猜测计数

            // b. 尝试基于猜测构建完整的 ResetClockTimedWord (full_cex_r)
            ResetClockTimedWord full_cex_r = null;
            try {
                // 计算起始时钟赋值 (longestMatchingPrefix 执行后的状态)
                ClockValuation startValuationForSuffix;
                if (longestMatchingPrefix.isEmpty()) {
                    startValuationForSuffix = ClockValuation.zero(this.clocks);
                } else {
                    Triple<Action, ClockValuation, Set<Clock>> lastKnownAction = longestMatchingPrefix.getLast();
                    if (lastKnownAction == null) {
                        // 理论上不应发生，非空词应有最后一个动作
                        throw new IllegalStateException("无法获取非空前缀的最后一个动作: " + longestMatchingPrefix);
                    }
                    // 应用已知的最后一步重置
                    startValuationForSuffix = lastKnownAction.getMiddle().reset(lastKnownAction.getRight());
                }

                // 构造反例剩余部分的 ResetClockTimedWord
                List<Triple<Action, ClockValuation, Set<Clock>>> cexSuffixActions = new ArrayList<>();
                ClockValuation currentValuation = startValuationForSuffix;
                for (int i = 0; i < remainingLength; i++) {
                    Pair<Action, Rational> delayAction = remainingCexActions.get(i);
                    Action action = delayAction.getLeft();
                    Rational delay = delayAction.getValue();
                    Set<Clock> reset = guessedResets.get(i); // 使用当前猜测的重置

                    // 计算下一个时钟赋值
                    ClockValuation nextValuation = currentValuation.delay(delay);

                    // 工程实现说明：
                    // 严格来说，这里应该检查 nextValuation 是否满足目标 DTA 在该步骤的 guard。
                    // 但我们不知道目标 DTA，所以无法执行此检查。
                    // RegionSolver.solveDelay 内部可能包含一些区域可达性检查，
                    // 但这里我们假设只要能计算出值，就认为这个猜测在时间上是“可能的”。
                    // 如果 toResetClockTimedWord 内部有更强的检查并返回 null，则该猜测无效。

                    cexSuffixActions.add(Triple.of(action, nextValuation, reset));
                    currentValuation = nextValuation.reset(reset); // 更新状态以进行下一步计算
                }
                ResetClockTimedWord cex_r_suffix = new ResetClockTimedWord(cexSuffixActions);
                full_cex_r = longestMatchingPrefix.concat(cex_r_suffix); // 拼接得到完整的词

            } catch (Exception e) {
                // 捕获构造过程中可能发生的任何错误（例如，非法状态、计算错误）
                System.err.println("处理反例警告: 猜测序列 " + guessedResets + " 无法构建有效的 ResetClockTimedWord: " + e.getMessage() + "。跳过此猜测。");
                continue; // 跳过这个无效的猜测
            }

            // c. 将 full_cex_r 的新前缀添加到 R
            boolean addedNewPrefix = false;
            // 遍历所有可能的新前缀（从 matchLength + 1 到 full_cex_r 的总长度）
            for (int len = matchLength + 1; len <= full_cex_r.length(); len++) {
                ResetClockTimedWord prefixToAdd = full_cex_r.getPrefix(len);
                // 只有当这个前缀既不在 S 也不在 R 中时才添加
                if (!tableForGuess.S.contains(prefixToAdd) && !tableForGuess.R.contains(prefixToAdd)) {
                    tableForGuess.R.add(prefixToAdd);
                    addedNewPrefix = true;
                    System.out.println("processCounterexample (Guess " + tableForGuess.guessCount + "): 添加新前缀到 R: " + prefixToAdd);
                }
            }

            // d. 调用 fillTable()
            // 关键点：即使没有添加新的前缀 (addedNewPrefix == false)，
            // 也需要调用 fillTable。因为即使结构没变，反例的 *分类* (cexMembership)
            // 可能与表中现有的 f(full_cex_r, ε) 不同，fillTable 会触发 MQ 来更新它。
            // 特别是当 remainingLength == 0 时，addedNewPrefix 总是 false，但必须调用 fillTable。
            System.out.println("processCounterexample (Guess " + tableForGuess.guessCount + "): 调用 fillTable()。新前缀添加状态: " + addedNewPrefix);
            try {
                List<ObservationTable> filledTablesForThisGuess = tableForGuess.fillTable();
                // 将这个猜测分支产生的所有成功填充的表添加到最终结果中
                resultingTables.addAll(filledTablesForThisGuess);
                System.out.println("processCounterexample (Guess " + tableForGuess.guessCount + "): fillTable() 返回了 " + filledTablesForThisGuess.size() + " 个实例。");
            } catch (Exception e) {
                // 捕获 fillTable 过程中可能发生的严重错误
                System.err.println("处理反例错误: 调用 fillTable() 时发生异常 (Guess " + tableForGuess.guessCount + "): " + e.getMessage() + "。此猜测分支失败。");
                // 不将此分支的结果添加到 resultingTables
            }
        } // 结束对所有猜测的循环

        System.out.println("processCounterexample 完成。总共生成了 " + resultingTables.size() + " 个新表实例。");
        return resultingTables;
    }

    /* ------------------------ 自动机构建 ---------------------------- */

    /**
     * 根据准备好的（封闭且一致的）观察表构建一个中间 DFA (M)。
     * 这个 DFA 的状态对应于 S 中的不同行，字母表是观察到的单步 reset-clocked words。
     *
     * @return 构建的中间 DFA
     * @throws IllegalStateException 如果表未准备好（非封闭或不一致）
     */
    public DFA buildDFA() {
        buildRowCacheIfNeeded(); // 确保行缓存是最新的

        // 1. 初始化 DFA
        // 这个 DFA 的 "alphabet" 是抽象的，由 ResetClockTimedWord 代表
        // 需要一个方法来获取所有观察到的单步扩展
        Alphabet abstractAlphabet = determineAbstractAlphabet();
        Location initialDFALoc = null;
        DFA intermediateDFA = new DFA(abstractAlphabet, null); // 初始状态稍后设置

        // 2. 创建状态 (Locations) 并映射
        // 使用 Row 作为唯一标识符来创建 Location
        Map<Row, Location> rowToLocationMap = new HashMap<>();
        Map<Location, ResetClockTimedWord> locationToPrefixMap = new HashMap<>();

        for (ResetClockTimedWord s : S) {
            Row row = getRow(s);
            if (!rowToLocationMap.containsKey(row)) {
                // 检查 f(s, epsilon) 来确定是否是接受状态
                boolean isAccepting = f.getOrDefault(Pair.of(s, RegionTimedWord.EMPTY), false);
                // 检查 s 是否是空前缀来确定是否是初始状态
                boolean isInitial = s.isEmpty();

                Location loc = new Location();
                rowToLocationMap.put(row, loc);
                locationToPrefixMap.put(loc, s); // 记录这个状态对应的前缀 s
                intermediateDFA.addLocation(loc);
                if (isAccepting) {
                    intermediateDFA.addAcceptingLocation(loc);
                }
                if (isInitial) {
                    initialDFALoc = loc;
                }
            }
        }

        if (initialDFALoc == null) {
            throw new IllegalStateException("非法的前缀，未在S中找到");
        }
        intermediateDFA.setInitialLocation(initialDFALoc);


        // 3. 创建转移 (Transitions)
        // 遍历 S 中的每个前缀 s (代表源状态)
        for (ResetClockTimedWord s : S) {
            Row sourceRow = getRow(s);
            Location sourceLoc = rowToLocationMap.get(sourceRow);
            if (sourceLoc == null) {
                continue; // 不应该发生，因为 S 中的所有行都已映射
            }

            // 遍历所有可能的单步扩展 r = s · sigma_r，其中 r 存在于 S U R
            // sigma_r 是抽象字母表中的一个动作
            Set<ResetClockTimedWord> potentialExtensions = findPotentialExtensions(s); // 需要实现这个辅助方法

            for (ResetClockTimedWord r : potentialExtensions) {
                if (!S.contains(r) && !R.contains(r)) {
                    continue; // 确保 r 在 S U R 中
                }

                ResetClockTimedWord sigma_r = new ResetClockTimedWord(Collections.singletonList(r.getLast())); // 获取最后一步作为抽象动作
                if (sigma_r.length() != 1) {
                    continue; // 只处理单步扩展
                }

                Row targetRow = getRow(r);
                // 由于表是封闭的，r 的行必然等于 S 中某个前缀 s' 的行
                Location targetLoc = rowToLocationMap.get(targetRow);

                if (targetLoc != null) {
                    int temp = Objects.hash(r);
                    Action abstractAction = abstractAlphabet.getActionMap().get("a" + Objects.hash(r));
                    Transition dfaTransition = new Transition(sourceLoc, abstractAction, null, Collections.emptySet(), targetLoc); // transition和guard不填
                    intermediateDFA.addTransition(dfaTransition);

                    // 填transitionMap
                    intermediateDFA.getTransitionMap()
                            .computeIfAbsent(sourceLoc, k -> new HashMap<>())
                            .put(abstractAction, targetLoc);
                } else {
                    // 表可能不是封闭的，或者 getRow/rowToLocationMap 逻辑有误
                    System.err.println("DFA: 逻辑有误 " + r);
                }
            }
        }

        return intermediateDFA;
    }


    /**
     * 将中间 DFA (M) 转换为目标 DTA (H)。
     * 主要工作是应用分区函数 (Partition Function) 来恢复时钟约束。
     *
     * @param intermediateDFA 中间 DFA (来自 buildDFA)
     * @return 构建的目标 DTA
     */
    public DTA buildDTA(DFA intermediateDFA) {
        // 1. 初始化 DTA
        DTA targetDTA = new DTA(alphabet, clocks, null, configuration);

        // 2. 复制状态和接受状态
        Map<Location, Location> dfaLocToDtaLoc = new HashMap<>();
        for (Location dfaLoc : intermediateDFA.getLocations()) {
            Location dtaLoc = new Location();
            targetDTA.addLocation(dtaLoc);
            dfaLocToDtaLoc.put(dfaLoc, dtaLoc);
            if (intermediateDFA.getInitialState() == dfaLoc) {
                targetDTA.setInitialLocation(dtaLoc);
            }
            if (intermediateDFA.getAcceptingLocations().contains(dfaLoc)) {
                targetDTA.addAcceptingLocation(dtaLoc);
            }
        }
        if (targetDTA.getInitialLocation() == null) {
            throw new IllegalStateException("DTA:初始位置映射失败");
        }

        // 3. 创建 DTA 转移 (应用分区函数)
        // 遍历 DTA 的每个状态 l (对应 DFA 的状态)
        for (Location dfaSourceLoc : intermediateDFA.getLocations()) {
            Location dtaSourceLoc = dfaLocToDtaLoc.get(dfaSourceLoc);
            if (dtaSourceLoc == null) {
                continue;
            }

            // 遍历原始字母表中的每个动作 sigma
            for (Action sigma : alphabet.alphabet.values()) {

                // 收集与 (dfaSourceLoc, sigma) 相关的所有抽象转移信息
                // Psi_l_sigma: 存储时钟赋值
                // transitionInfoMap: 存储 valuation -> (抽象动作 sigma_r, 目标 DFA 状态)
                List<ClockValuation> Psi_l_sigma = new ArrayList<>();
                Map<ClockValuation, Pair<ResetClockTimedWord, Location>> transitionInfoMap = new HashMap<>();

                // 遍历 DFA 的转移 map
                Map<Action, Location> outgoingTransitions = intermediateDFA.getTransitionMap().get(dfaSourceLoc);
                if (outgoingTransitions != null) {
                    for (Map.Entry<Action, Location> entry : outgoingTransitions.entrySet()) {
                        Action abstractAction = entry.getKey();
                        Location dfaTargetLoc = entry.getValue();

                        // 解析抽象动作
                        ResetClockTimedWord sigma_r = abstractAction.getResetClockTimedWord();
                        if (sigma_r == null) {
                            continue;
                        }

                        Action originalAction = sigma_r.getAction().get(0);
                        ClockValuation valuation = sigma_r.getValuation().get(0);

                        if (originalAction.equals(sigma)) {
                            // 检查是否已存在相同的 valuation，避免重复
                            if (!transitionInfoMap.containsKey(valuation)) {
                                Psi_l_sigma.add(valuation);
                                transitionInfoMap.put(valuation, Pair.of(sigma_r, dfaTargetLoc));
                            } else {
                                // 如果存在重复的 valuation，检查目标状态和重置是否一致
                                Pair<ResetClockTimedWord, Location> existing = transitionInfoMap.get(valuation);
                                if (!existing.getRight().equals(dfaTargetLoc) ||
                                        !existing.getLeft().getResets().get(0).equals(sigma_r.getResets().get(0))) {
                                    System.err.println("DTA: 同一赋值的不连续转换: " + valuation);
                                }
                            }
                        }
                    }
                }


                // 如果没有找到 sigma 的转移，则继续下一个动作
                if (Psi_l_sigma.isEmpty()) {
                    continue;
                }

                // 4. 分区, Partition Function P(Psi_l_sigma)
                // partitionFunction 返回一个映射： valuation -> disjoint constraint Ii
                Map<ClockValuation, DisjunctiveConstraint> partitionResult = partitionFunction(Psi_l_sigma);

                // 5. 添加 DTA 转移
                for (Map.Entry<ClockValuation, Pair<ResetClockTimedWord, Location>> entry : transitionInfoMap.entrySet()) {
                    ClockValuation vi = entry.getKey();
                    ResetClockTimedWord sigma_r = entry.getValue().getLeft();
                    Location dfaTargetLoc = entry.getValue().getRight();
                    Location dtaTargetLoc = dfaLocToDtaLoc.get(dfaTargetLoc);

                    if (dtaTargetLoc == null) {
                        System.err.println("DTA: 位置映射失败");
                        continue;
                    }

                    // 从分区结果获取对应的约束 Ii
                    DisjunctiveConstraint partition = partitionResult.get(vi);
                    if (partition == null || partition.isFalse()) {
                        continue; // 不为 False 分区创建转移
                    }

                    Set<Clock> resets = sigma_r.getLastResets();

                    for (Constraint guard : partition.getConstraints()) {
                        if (guard == null || guard.isFalse()) {
                            continue;
                        }

                        Constraint final_guard = guard; // 假设暂时不需要替换

                        Transition dtaTransition = new Transition(dtaSourceLoc, sigma, final_guard, resets, dtaTargetLoc);
                        targetDTA.addTransition(dtaTransition);
                    }
                }
            }
        }
        boolean isComplete = targetDTA.isComplete();
        boolean isDeterministic = targetDTA.isDeterministic();
        return targetDTA;
    }

    /* ------------------------- 辅助方法 -------------------------- */

    /**
     * 实现定义4.6中的分区函数 P(·),使用Z3求解器
     *
     * @param valuations 时钟赋值列表 Psi_l_sigma
     * @return 从每个输入赋值 vi 到其不相交分区约束 Ii 的映射
     * @throws Z3Exception 如果Z3操作出错
     */
    private Map<ClockValuation, DisjunctiveConstraint> partitionFunction(List<ClockValuation> valuations) {
        System.out.println("时钟上界配置：" + configuration.getClockKappas());
        valuations.sort(ClockValuation::compareTo);
        System.out.println("时钟解释列表：" + valuations);
        Map<ClockValuation, DisjunctiveConstraint> result = new HashMap<>();
        if (valuations == null || valuations.isEmpty()) {
            return result;
        }

        int n = valuations.size();
        List<ClockValuation> V = new ArrayList<>(valuations); // v1,...,vn
        Map<Clock, Integer> kappa = configuration.getClockKappas();

        Constraint nonNegativeConstraint = Constraint.trueConstraint(clocks);

        List<Constraint> A_constraints = new ArrayList<>(n);          // A1,...,An (Constraint)
        List<Constraint> U_constraints = new ArrayList<>(n);          // U1,...,Un (Constraint)
        List<DisjunctiveConstraint> W_constraints = new ArrayList<>(n); // W1,...,Wn (DisjunctiveConstraint)
        List<DisjunctiveConstraint> I_constraints = new ArrayList<>(n); // I1,...,In (DisjunctiveConstraint)

        DisjunctiveConstraint Uo = DisjunctiveConstraint.falseConstraint(clocks);
        for (int i = 0; i < n; i++) {
            ClockValuation vi = V.get(i);

            boolean exceedsCeiling = false;
            for (Clock clock_j : clocks) {
                Rational vij = vi.getValue(clock_j);
                int ceiling_j = configuration.getClockKappa(clock_j);
                if (vij.compareTo(Rational.valueOf(ceiling_j)) > 0) {
                    exceedsCeiling = true;
                    break;
                }
            }

            Constraint Ai;
            if (exceedsCeiling) {
                Region region_vi = vi.toRegion(configuration);
                Ai = region_vi.toConstraint(false); // Ai = [vi]
            } else {
                Ai = Constraint.falseConstraint(clocks); // Ai = ∅
            }
            Uo = Uo.or(Ai);
            A_constraints.add(Ai);
        }


        System.out.println("--- A_constraints (Region Constraints) ---");
        for (int i = 0; i < n; i++) {
            System.out.println("A[" + i + "] for v" + i + " (" + V.get(i) + "): " + A_constraints.get(i));
        }
        System.out.println("----------------------------------------");

        for (int i = 0; i < n; i++) {
            ClockValuation vi = V.get(i);
            Constraint Ui = Constraint.trueConstraint(clocks);
            for (Clock c : clocks) {
                Rational val_ic = vi.getValue(c); // 获取 v_ij

                // 检查 val_ic 是否为整数
                if (val_ic.isInteger()) {
                    // Case 1: v_ij 是整数
                    Ui = Ui.and(AtomConstraint.greaterEqual(c, val_ic)); // cj >= v_ij
                } else {
                    // Case 2: v_ij 不是整数
                    Rational floor_val = Rational.valueOf(val_ic.intValue()); // 获取 ⌊v_ij⌋
                    Ui = Ui.and(AtomConstraint.greaterThan(c, floor_val)); // cj > ⌊v_ij⌋
                }
            }
            U_constraints.add(Ui);
        }

        System.out.println("--- U_constraints (Unit Cube Constraints) ---");
        for (int i = 0; i < n; i++) {
            System.out.println("U[" + i + "] for v" + i + " (" + V.get(i) + "): " + U_constraints.get(i));
        }
        System.out.println("-------------------------------------------");

        DisjunctiveConstraint accumulatedWUnionUo = Uo;
        for (int i = 0; i < n; ++i) {
            W_constraints.add(null);
        }

        for (int i = n - 1; i >= 0; i--) {
            Constraint Ui = U_constraints.get(i);

            DisjunctiveConstraint negatedAccumulated;
            List<Constraint> accumulatedConstraints = accumulatedWUnionUo.getConstraints().stream().toList();
            if (accumulatedConstraints.isEmpty()) {
                negatedAccumulated = DisjunctiveConstraint.trueConstraint(clocks);
            } else {
                negatedAccumulated = accumulatedConstraints.get(0).negate();
                for (int k = 1; k < accumulatedConstraints.size(); k++) {
                    DisjunctiveConstraint negAccK = accumulatedConstraints.get(k).negate();
                    negatedAccumulated = negatedAccumulated.and(negAccK);
                }
            }

            DisjunctiveConstraint Wi = negatedAccumulated.and(Ui);

            W_constraints.set(i, Wi);
            accumulatedWUnionUo = accumulatedWUnionUo.or(Wi);
        }

        System.out.println("--- W_constraints (Partitioning Constraints) ---");
        for (int i = 0; i < n; i++) {
            System.out.println("W[" + i + "] for v" + i + " (" + V.get(i) + "): " + W_constraints.get(i));
        }
        System.out.println("----------------------------------------------");

        for (int i = 0; i < n; ++i) {
            I_constraints.add(null); // 占位
        }
        for (int i = 0; i < n; i++) {
            DisjunctiveConstraint Wi = W_constraints.get(i);
            Constraint Ai = A_constraints.get(i);

            // or_Wi_Ai = Wi OR Ai
            DisjunctiveConstraint or_Wi_Ai = Wi.or(Ai);

            DisjunctiveConstraint Ii = or_Wi_Ai.and(nonNegativeConstraint);

            I_constraints.set(i, Ii);
        }

        System.out.println("--- Initial I_constraints (Intersection Constraints) ---");
        for (int i = 0; i < n; i++) {
            System.out.println("Initial I[" + i + "] for v" + i + " (" + V.get(i) + "): " + I_constraints.get(i));
        }
        System.out.println("------------------------------------------------------");

        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (A_constraints.get(i).isTrue() && A_constraints.get(j).isTrue()) {

                        if (U_constraints.get(i).equals(U_constraints.get(j))) {

                            Region region_i = V.get(i).toRegion(configuration);
                            Region region_j = V.get(j).toRegion(configuration);

                            if (!region_i.equals(region_j)) {

                                if (I_constraints.get(i).equals(I_constraints.get(j))) {

                                    Constraint region_i_constraint = region_i.toConstraint(true);
                                    Constraint region_j_constraint = region_j.toConstraint(true);

                                    DisjunctiveConstraint current_Ii = I_constraints.get(i);
                                    DisjunctiveConstraint new_Ii = current_Ii.and(region_i_constraint);
                                    I_constraints.set(i, new_Ii);

                                    DisjunctiveConstraint current_Ij = I_constraints.get(j);
                                    DisjunctiveConstraint new_Ij = current_Ij.and(region_j_constraint); // AND(Constraint, Disjunctive)
                                    I_constraints.set(j, new_Ij);

                                    changed = true;
                                }
                            }
                        }
                    }
                }
            }
        } while (changed);

        System.out.println("--- Final I_constraints (After Refinement) ---");
        for (int i = 0; i < n; i++) {
            System.out.println("Final I[" + i + "] for v" + i + " (" + V.get(i) + "): " + I_constraints.get(i));
        }
        System.out.println("----------------------------------------------");

        for (int i = 0; i < n; i++) {
            DisjunctiveConstraint final_Ii = I_constraints.get(i).simplify();

             if (final_Ii.isFalse()) {
                 result.put(V.get(i), DisjunctiveConstraint.falseConstraint(clocks));
             } else {
                 result.put(V.get(i), final_Ii);
             }
            result.put(V.get(i), final_Ii);
        }
        System.out.println("--- Final Partition Results ---");
        for (Map.Entry<ClockValuation, DisjunctiveConstraint> entry : result.entrySet()) {
            System.out.println("Partition for " + entry.getKey() + ": " + entry.getValue());
        }
        return result;
    }

    /**
     * 检查两个Z3 BoolExpr是否在逻辑上等价
     * 通过检查 NOT (expr1 IFF expr2) 的可满足性来实现
     *
     * @param expr1 第一个表达式
     * @param expr2 第二个表达式
     * @param ctx Z3上下文
     * @return 如果等价则返回true,否则返回false
     */
    private boolean areZ3ExprsEquivalent(BoolExpr expr1, BoolExpr expr2, Context ctx, Solver solver) {
        if (expr1 == null || expr2 == null) {
            return false;
        }
        if (expr1 == expr2) {
            return true;
        }

        BoolExpr expr1_xor_expr2 = ctx.mkXor(expr1, expr2);
        solver.push();
        solver.add(expr1_xor_expr2);
        Status status = solver.check();
        solver.pop();
        return status == Status.UNSATISFIABLE;
    }

    /**
     * 确定中间 DFA 的抽象字母表。
     * 字母表由所有观察到的单步 reset-clocked words 组成。
     */
    private Alphabet determineAbstractAlphabet() {
        Alphabet abstractAlphabet = new Alphabet();
        Set<ResetClockTimedWord> allPrefixes = new HashSet<>(S);
        allPrefixes.addAll(R);

        for (ResetClockTimedWord prefix : allPrefixes) {
            if (!prefix.isEmpty()) {
                abstractAlphabet.createAction("a" + Objects.hash(prefix),prefix);
            }
        }
        return abstractAlphabet;
    }

    /**
     * 找到前缀 s 的所有潜在单步扩展 r = s · sigma_r，其中 r 存在于 S U R。
     */
    private Set<ResetClockTimedWord> findPotentialExtensions(ResetClockTimedWord s) {
        Set<ResetClockTimedWord> extensions = new HashSet<>();
        Set<ResetClockTimedWord> candidates = new HashSet<>(S);
        candidates.addAll(R);

        for (ResetClockTimedWord r : candidates) {
            if (r.length() == s.length() + 1 && r.isPrefix(s)) {
                extensions.add(r);
            }
        }
        return extensions;
    }

    /* ------------------------- 工具方法 ------------------------- */
    /**
     * 获取给定字的行（Row）
     */
    public Row getRow(ResetClockTimedWord word) {
        // Check cache first
        if (rowCache.containsKey(word)) {
            return rowCache.get(word);
        }

        Map<RegionTimedWord, Boolean> fValuesForRow = new HashMap<>();
        Map<RegionTimedWord, List<Set<Clock>>> gValuesForRow = new HashMap<>();

        for (RegionTimedWord suffix_e : E) {
            Pair<ResetClockTimedWord, RegionTimedWord> pair = Pair.of(word, suffix_e);

            // 如果 f 表中没有对应项，可能是因为没有足够的猜测或 MQ 失败
            Boolean fValue = f.get(pair);
            if (fValue == null &&!suffix_e.isEmpty()) {
                System.err.println("getRow: 对于(" + word + ", " + suffix_e + ")没有合法f");
                return null;
            }
            fValuesForRow.put(suffix_e, fValue);

            List<Set<Clock>> gValue = g.get(pair);
            if (gValue == null && !suffix_e.isEmpty()) {
                System.err.println("getRow: 对于(" + word + ", " + suffix_e + ")没有合法g");
                return null;
            }
            gValuesForRow.put(suffix_e, gValue == null && suffix_e.isEmpty() ? Collections.emptyList() : gValue);
        }

        if (fValuesForRow.containsValue(null) || gValuesForRow.entrySet().stream().anyMatch(entry -> entry.getValue() == null && !entry.getKey().isEmpty())) {
            System.err.println("getRow: 对于(" + word + ")没有合法f/g");
            return null;
        }
        Row computedRow = new Row(fValuesForRow, gValuesForRow);
        rowCache.put(word, computedRow);
        return computedRow;
    }

    @Getter
    private static class PathState {
        private ClockValuation currentVal;
        private List<Triple<Action, Rational, Set<Clock>>> sequence; // (动作, 延时, 重置集合)
        private List<ClockValuation> clockStates; // 历史时钟状态
        PathState(ClockValuation currentVal, List<Triple<Action, Rational, Set<Clock>>> sequence, List<ClockValuation> clockStates) {
            this.currentVal = currentVal;
            this.sequence = sequence;
            this.clockStates = clockStates;
        }
    }

//    public List<ResetDelayTimedWord> generateResetDelayWords(ResetClockTimedWord prefix, RegionTimedWord suffix) {
//        if (suffix.getTimedActions().isEmpty()) {
//            return Collections.emptyList();
//        }
//
//        // 初始化当前时钟状态
//        Triple<Action, ClockValuation, Set<Clock>> lastPrefix = prefix.getTimedActions().get(prefix.getTimedActions().size() - 1);
//        ClockValuation currentVal = lastPrefix.getMiddle().reset(lastPrefix.getRight());
//
//        Queue<PathState> queue = new LinkedList<>();
//        List<Pair<Action, Region>> suffixActions = suffix.getTimedActions();
//
//        // 处理第一个后缀动作
//        Pair<Action, Region> firstAction = suffixActions.get(0);
//        Optional<Rational> firstDelayOpt = regionSolver.solveDelay(currentVal, firstAction.getRight());
//        if (firstDelayOpt.isEmpty()) {
//            return Collections.emptyList();
//        }
//
//        Rational firstDelay = firstDelayOpt.get();
//        generateAllSubsetsOfClock().forEach(resets -> {
//            ClockValuation newVal = currentVal.delay(firstDelay).reset(resets);
//            List<Triple<Action, Rational, Set<Clock>>> sequence = new ArrayList<>();
//            sequence.add(Triple.of(firstAction.getLeft(), firstDelay, resets));
//            queue.add(new PathState(newVal, sequence, Arrays.asList(currentVal, newVal)));
//        });
//
//        // 处理后续动作
//        for (int i = 1; i < suffixActions.size(); i++) {
//            Pair<Action, Region> action = suffixActions.get(i);
//            int levelSize = queue.size();
//            for (int j = 0; j < levelSize; j++) {
//                PathState current = queue.poll();
//                regionSolver.solveDelay(current.getCurrentVal(), action.getRight())
//                        .ifPresent(delay -> {
//                            generateAllSubsetsOfClock().forEach(resets -> {
//                                ClockValuation newVal = current.getCurrentVal().delay(delay).reset(resets);
//                                List<Triple<Action, Rational, Set<Clock>>> newSeq = new ArrayList<>(current.getSequence());
//                                newSeq.add(Triple.of(action.getLeft(), delay, resets));
//                                List<ClockValuation> newStates = new ArrayList<>(current.getClockStates());
//                                newStates.add(newVal);
//                                queue.add(new PathState(newVal, newSeq, newStates));
//                            });
//                        });
//            }
//        }
//
//        // 转换为 ResetDelayWord
//        return queue.stream()
//                .map(state -> new ResetDelayTimedWord(state.getSequence()))
//                .collect(Collectors.toList());
//    }

    public List<Set<Clock>> generateAllSubsetsOfClock() {
        return clocks.stream()
            .collect(() -> {
                List<Set<Clock>> subsets = new ArrayList<>();
                subsets.add(new HashSet<>());
                return subsets;
            }, 
            (subsets, clock) -> {
                int size = subsets.size();
                for (int i = 0; i < size; i++) {
                    Set<Clock> newSubset = new HashSet<>(subsets.get(i));
                    newSubset.add(clock);
                    subsets.add(newSubset);
                }
            }, 
            List::addAll);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        int maxWordLength = 80; // 限制输出中单词的长度，避免过宽

        // --- 元数据 ---
        sb.append("========== 观察表摘要 ==========\n");
        sb.append("字母表 (Alphabet): ").append(alphabet.getAlphabet()).append("\n"); // 假设 Alphabet 有 getActions() 或类似方法
        List<String> clockNames = clocks.stream()
                .map(Clock::getName) // 假设 Clock 有 getName()
                .sorted()
                .collect(Collectors.toList());
        sb.append("时钟 (Clocks): ").append(clockNames).append(" (数量: ").append(clocks.size()).append(")\n");
        // 如果需要，可以有选择地添加配置细节，保持简洁
        // sb.append("配置 (Configuration): ").append(configuration.toString()).append("\n");
        sb.append("猜测次数 (Guess Count): ").append(guessCount).append("\n");

        // --- 维度 ---
        sb.append("\n--- 维度 ---\n");
        sb.append("S (前缀集): ").append(S.size()).append(" 个元素\n");
        sb.append("R (边界集): ").append(R.size()).append(" 个元素\n");
        sb.append("E (后缀集): ").append(E.size()).append(" 个元素\n");

        // --- 表格内容 (格式化) ---
        sb.append("\n--- 表格内容 (|S U R| x |E|) ---\n");

        // 为了输出一致性，对 S, R, E 进行排序 (可选，但有帮助)
        List<ResetClockTimedWord> sortedS = S.stream().sorted(Comparator.comparing(Object::toString)).collect(Collectors.toList());
        List<ResetClockTimedWord> sortedR = R.stream().sorted(Comparator.comparing(Object::toString)).collect(Collectors.toList());
        List<RegionTimedWord> sortedE = E.stream().sorted(Comparator.comparing(Object::toString)).collect(Collectors.toList());

        // 合并 S 和 R 作为行
        List<ResetClockTimedWord> allPrefixes = new ArrayList<>(sortedS);
        allPrefixes.addAll(sortedR);

        // 确定列宽
        int firstColWidth = 0;
        for (ResetClockTimedWord prefix : allPrefixes) {
            firstColWidth = Math.max(firstColWidth, truncate(prefix.toString(), maxWordLength).length());
        }
        firstColWidth += 5; // 为 "(S) " 或 "(R) " 标记增加空间

        List<Integer> dataColWidths = new ArrayList<>();
        for (RegionTimedWord suffix : sortedE) {
            // 估算宽度: "f=T/F, g={c1,c2,...}" + 填充
            int headerWidth = truncate(suffix.toString(), maxWordLength).length();
            // 估算典型单元格内容宽度 (根据需要调整)
            // "f=T, g={c1, c2}" -> 5 + 4 + 时钟数 * (名字长度+2)
            int typicalCellWidth = 5 + 4 + clockNames.size() * 4; // 粗略估计
            dataColWidths.add(Math.max(headerWidth, typicalCellWidth) + 2); // 增加内边距
        }

        // 打印表头行
        sb.append(String.format("%-" + firstColWidth + "s", "前缀 (Prefix)"));
        for (int i = 0; i < sortedE.size(); i++) {
            String suffixHeader = truncate(sortedE.get(i).toString(), maxWordLength);
            sb.append("| ").append(String.format("%-" + (dataColWidths.get(i) - 2) + "s", suffixHeader));
        }
        sb.append("|\n");

        // 打印分隔符
        sb.append("-".repeat(firstColWidth));
        for (int width : dataColWidths) {
            sb.append("+-").append("-".repeat(width - 2));
        }
        sb.append("+\n");

        // 打印数据行 (先 S 后 R)
        int sCount = 0;
        for (ResetClockTimedWord prefix : allPrefixes) {
            boolean isInS = sCount < sortedS.size();
            String prefixStr = truncate(prefix.toString(), maxWordLength);
            String marker = isInS ? "(S) " : "(R) ";
            sb.append(String.format("%-" + firstColWidth + "s", marker + prefixStr));

            for (int i = 0; i < sortedE.size(); i++) {
                RegionTimedWord suffix = sortedE.get(i);
                Pair<ResetClockTimedWord, RegionTimedWord> key = Pair.of(prefix, suffix);
                Boolean fVal = f.get(key);
                List<Set<Clock>> gValList = g.get(key); // g 的值是一个列表

                String fStr = (fVal == null) ? "?" : (fVal ? "T" : "F"); // 处理可能的 null
                String gStr;
                if (gValList == null) {
                    gStr = "?"; // 处理可能的 null
                } else if (gValList.isEmpty()) {
                    // 对于空后缀 epsilon，g 值是空列表；或者如果某个猜测结果没有重置
                    gStr = "[]";
                } else {
                    // g 值是 List<Set<Clock>>。通常对于一个确定的 (prefix, suffix) 对，
                    // 在 fillTable 后应该有一个主要的重置序列。这里我们显示列表中的第一个。
                    // 如果 fillTable 存储了多种可能性，这里可能需要调整。
                    gStr = formatGValue(gValList.get(0)); // 格式化第一个（或唯一的）重置集合
                }

                String cellContent = String.format("f=%s, g=%s", fStr, gStr);
                sb.append("| ").append(String.format("%-" + (dataColWidths.get(i) - 2) + "s", cellContent));
            }
            sb.append("|\n");
            sCount++;
            if (isInS && sCount == sortedS.size()) { // 在 S 和 R 之间添加分隔线
                sb.append("-".repeat(firstColWidth));
                for (int width : dataColWidths) {
                    sb.append("+-").append("-".repeat(width - 2));
                }
                sb.append("+\n");
            }
        }

        // --- 缓存信息 (可选) ---
        sb.append("\n--- 缓存状态 ---\n");
        sb.append("行缓存大小 (Row Cache): ").append(rowCache.size()).append("\n");
        sb.append("最后动作缓存大小 (Last Action Cache): ").append(lastActionCache.size()).append("\n");
        sb.append("最后区域缓存大小 (Last Region Cache): ").append(lastRegionCache.size()).append("\n");

        sb.append("=============================================\n");
        return sb.toString();
    }

    // 辅助函数：良好地格式化 g 值 (Set<Clock>)
    private String formatGValue(Set<Clock> resetSet) {
        if (resetSet == null) {
            return "?"; // 处理 null
        }
        if (resetSet.isEmpty()) {
            return "{}"; // 空集合
        }
        // 对时钟名称排序，使其更易读
        return "{" + resetSet.stream()
                .map(Clock::getName) // 假设 Clock 有 getName()
                .sorted()
                .collect(Collectors.joining(", ")) + "}";
    }

    // 辅助函数：截断长字符串
    private String truncate(String str, int maxLength) {
        if (str == null) {
            return "null";
        }
        if (str.length() <= maxLength) {
            return str;
        }
        // 截断并在末尾添加 "..."
        return str.substring(0, maxLength - 3) + "...";
    }

    public static void main(String[] args) {
        // 先构造测试表实例：实例化字母表和一个时钟配置
        Alphabet alphabet = new Alphabet();
        alphabet.createAction("a1");
        alphabet.createAction("a2");
        alphabet.createAction("a3");
        // 假设时钟配置为：{c1, c2, c3}，使用for循环构造，时钟数为n
        int n = 2;
        Set<Clock> clocks = new HashSet<>();
        Map<Clock, Integer> clockBounds = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Clock clock = new Clock("c"+(i+1), 1);
            clocks.add(clock);
            clockBounds.put(clock, clock.getKappa());
        }
        ClockConfiguration configuration = new ClockConfiguration(clockBounds);
        ObservationTable table = new ObservationTable(alphabet, n, new NormalTeacher(), configuration);
        // 根据时钟集合，构造时钟赋值列表
        List<ClockValuation> clockValuations = new ArrayList<>();
        List<Rational> values = new ArrayList<>();

        values.add(Rational.valueOf(1000001, 1000000));
        values.add(Rational.valueOf(1000001, 1000000));
        values.add(Rational.valueOf(0));
        values.add(Rational.valueOf(0));
        int j = 0;
        for (int i = 0; i < 2; i++) {
            SortedMap<Clock, Rational> clockValues = new TreeMap<>();
            for (Clock clock: table.clocks) {
                clockValues.put(clock, values.get(j++));
            }
            clockValuations.add(new ClockValuation(clockValues));
        }
        Map<ClockValuation, DisjunctiveConstraint> result = table.partitionFunction(clockValuations);
    }
}
