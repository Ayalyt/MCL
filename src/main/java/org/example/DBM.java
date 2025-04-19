package org.example;


import lombok.Getter;
import org.example.constraint.AtomConstraint;
import org.example.constraint.Constraint;
import org.example.utils.Rational;

import java.util.*;


public class DBM {

    // --- 实例变量 ---

    /** 将时钟映射到其在矩阵中的索引 (行/列). */
    private final Map<Clock, Integer> clockIndexMap;

    /** 按索引顺序存储时钟列表, clockList.get(0)是零时钟. */
    private final List<Clock> clockList;

    /**大小(时钟数量 + 1)*/
    @Getter
    private final int size;

    /**
     * 上界矩阵. upperBoundMatrix[i][j]存储 c_i - c_j的上界值 V
     */
    private final Rational[][] upperBoundMatrix;

    /**
     * 闭包矩阵. closedMatrix[i][j] 为 true 表示约束是 <= V, false 表示 < V.
     */
    private final boolean[][] closedMatrix;

    /**
     * 私有构造函数, 用于内部创建 DBM 实例 (如 copy).
     *
     * @param clockIndexMap 时钟到索引的映射.
     * @param clockList     按索引排序的时钟列表.
     * @param size          矩阵大小.
     * @param upperBoundMatrix 上界矩阵 (将被深拷贝).
     * @param closedMatrix  闭包矩阵 (将被深拷贝).
     */
    private DBM(Map<Clock, Integer> clockIndexMap, List<Clock> clockList, int size,
                Rational[][] upperBoundMatrix, boolean[][] closedMatrix) {
        this.clockIndexMap = new HashMap<>(clockIndexMap);
        this.clockList = new ArrayList<>(clockList);
        this.size = size;
        this.upperBoundMatrix = new Rational[size][size];
        this.closedMatrix = new boolean[size][size];

        // 深拷贝矩阵内容
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                this.upperBoundMatrix[i][j] = upperBoundMatrix[i][j];
                this.closedMatrix[i][j] = closedMatrix[i][j];
            }
        }
    }

    /**
     * 创建一个表示所有时钟非负区域 (ci >= 0) 的初始 DBM.
     * @param clocks 时钟集合 (不应包含零时钟, 会自动添加).
     * @return 代表初始非负区域 (ci >= 0 for all i) 的 DBM.
     */
    public static DBM createInitial(Set<Clock> clocks) {
        List<Clock> clockList = new ArrayList<>(clocks.size() + 1);
        Map<Clock, Integer> clockIndexMap = new HashMap<>(clocks.size() + 1);

        // 0 号索引固定为零时钟
        Clock zeroClock = Clock.getZeroClock();
        clockList.add(zeroClock);
        clockIndexMap.put(zeroClock, 0);

        // 添加其他时钟
        int index = 1;
        List<Clock> sortedClocks = new ArrayList<>(clocks);
        Collections.sort(sortedClocks);

        for (Clock clock : sortedClocks) {
            if (!clock.equals(zeroClock)) { // 避免重复添加零时钟
                clockList.add(clock);
                clockIndexMap.put(clock, index++);
            }
        }

        int size = clockList.size();
        Rational[][] initialUpperBounds = new Rational[size][size];
        boolean[][] initialClosed = new boolean[size][size];

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (i == j) {
                    initialUpperBounds[i][j] = Rational.ZERO;
                    initialClosed[i][j] = true;
                } else if (i == 0) { // 处理第 0 行 (x0 - cj)
                    initialUpperBounds[i][j] = Rational.ZERO; // x0 - cj <= 0 (cj >= 0)
                    initialClosed[i][j] = true;
                } else { // 处理 i > 0 的行
                    initialUpperBounds[i][j] = Rational.INFINITY; // ci - cj < infinity (无上界)
                    initialClosed[i][j] = false; // < inf
                }
            }
        }
        return new DBM(clockIndexMap, clockList, size, initialUpperBounds, initialClosed);
    }
    /**
     * 深拷贝
     */
    public DBM copy() {
        // 使用私有构造函数进行深拷贝
        return new DBM(this.clockIndexMap, this.clockList, this.size,
                this.upperBoundMatrix, this.closedMatrix);
    }

    /**
     * 获取时钟列表 (不可变，索引顺序)
     * @return 时钟列表
     */
    public List<Clock> getClockList() {
        return Collections.unmodifiableList(clockList);
    }

    /**
     * 获取时钟到索引的映射，不可变
     * @return 时钟到索引的映射.
     */
    public Map<Clock, Integer> getClockIndexMap() {
        return Collections.unmodifiableMap(clockIndexMap);
    }


    // === DBM 核心操作 ===

    /**
     * 移除相对于零时钟的上界约束，将 M[i][0] (代表 c_i - x0 <= V, 即 c_i <= V) 设置为无穷大 (< ∞).
     * future的第一步，操作是原地修改
     */
    public void up() {
        for (int i = 1; i < size; i++) { // 从 1 开始，跳过 M[0][0]
            upperBoundMatrix[i][0] = Rational.INFINITY;
            closedMatrix[i][0] = false; // < infinity
        }
    }

    /**
     * 时间流逝，先up后canonical
     * 操作是原地修改
     */
    public void future() {
        this.up();
        this.canonical();
    }

    /**
     * 将指定的时钟重置为 0，将时钟 x 对应的行和列设置为与零时钟 (索引 0) 相同.
     * 操作是原地修改.
     *
     * @param clock 要重置的时钟
     * @throws IllegalArgumentException 时钟不存在
     */
    public void reset(Clock clock) {
        if (clock.equals(Clock.getZeroClock())) {
            return;
        }
        Integer index = clockIndexMap.get(clock);
        if (index == null) {
            throw new IllegalArgumentException("Clock " + clock + " 未找到");
        }

        for (int j = 0; j < size; j++) {
            // index行: x - cj <= x0 - cj (即 M[index][j] = M[0][j])
            upperBoundMatrix[index][j] = upperBoundMatrix[0][j];
            closedMatrix[index][j] = closedMatrix[0][j];

            // index列: ci - x <= ci - x0 (即 M[j][index] = M[j][0])
            upperBoundMatrix[j][index] = upperBoundMatrix[j][0];
            closedMatrix[j][index] = closedMatrix[j][0];
        }
        // M[index][index] x - x <= 0
        upperBoundMatrix[index][index] = Rational.ZERO;
        closedMatrix[index][index] = true;
    }

    /**
     * 原地修改.
     * @param constraint 要交的约束
     */
    public void intersect(Constraint constraint) {
        for (AtomConstraint atom : constraint.getConstraints()) {
            this.intersect(atom);
        }
        // 应用完所有原子约束后或需要精确比较/判空之前调用 canonical()
        // 需要立即规范化时添加
    }

    /**
     * 原地修改
     *
     * @param atomConstraint 原子约束
     * @throws IllegalArgumentException 时钟不在DBM中
     */
    private void intersect(AtomConstraint atomConstraint) {
        Clock c1 = atomConstraint.getClock1();
        Clock c2 = atomConstraint.getClock2();
        Rational value = atomConstraint.getUpperbound();
        boolean closed = atomConstraint.isClosed();

        Integer index1 = clockIndexMap.get(c1);
        Integer index2 = clockIndexMap.get(c2);

        if (index1 == null || index2 == null) {
            throw new IllegalArgumentException(atomConstraint + " 的时钟未找到");
        }

        int i = index1;
        int j = index2;

        Rational currentValue = upperBoundMatrix[i][j];
        boolean currentClosed = closedMatrix[i][j];

        int comparison = Rational.compare(value, currentValue);

        if (comparison < 0) {
            upperBoundMatrix[i][j] = value;
            closedMatrix[i][j] = closed;
        } else if (comparison == 0) {
            closedMatrix[i][j] = currentClosed && closed;
        }
        // else (comparison > 0) 新约束更宽松
    }

    /**
     * 将 DBM 转换为规范形式 (Canonical Form).
     * 使用 Floyd-Warshall 算法计算所有点对之间的最短路径，收紧约束.
     * 规范形式对于判空和包含检查是必需的.
     * 操作是原地修改.
     */
    public void canonical() {
        for (int k = 0; k < size; k++) {
            for (int i = 0; i < size; i++) {
                if (upperBoundMatrix[i][k] == Rational.INFINITY) {
                    continue;
                }

                for (int j = 0; j < size; j++) {
                    if (upperBoundMatrix[k][j] == Rational.INFINITY) {
                        continue;
                    }

                    Rational pathValue = upperBoundMatrix[i][k].add(upperBoundMatrix[k][j]);
                    boolean pathClosed = closedMatrix[i][k] && closedMatrix[k][j];

                    Rational directValue = upperBoundMatrix[i][j];
                    boolean directClosed = closedMatrix[i][j];

                    int comparison = Rational.compare(pathValue, directValue);

                    if (comparison < 0) {
                        upperBoundMatrix[i][j] = pathValue;
                        closedMatrix[i][j] = pathClosed;
                    } else if (comparison == 0) {
                        if (directClosed && !pathClosed) {
                            closedMatrix[i][j] = false;
                        }
                    }
                }
            }
        }
    }

    // === DBM 检查操作 ===

    /**
     * 检查 DBM 所表示的区域是否为空 (即是否存在矛盾).
     * DBM 必须先转换为规范形式才能进行此检查.
     * 如果 DBM 未处于规范形式，结果可能不正确 (可能返回 false 而实际为空).
     *
     * @return 如果区域为空 (存在矛盾) 则返回 true, 否则返回 false.
     */
    public boolean isEmpty() {
        // 在规范形式下，当且仅当存在某个对角线元素 M[i][i] < 0
        // 或者 M[i][i] <= 0 但约束是 '<' (closed=false) 时，区域为空
        // M[i][i] < 0 意味着 c_i - c_i < 0，矛盾
        // M[i][i] = 0 且 closed=false 意味着 c_i - c_i < 0，矛盾
        for (int i = 0; i < size; i++) {
            int comparison = Rational.compare(upperBoundMatrix[i][i], Rational.ZERO);
            if (comparison < 0) {
                return true; // M[i][i] < 0
            }
            // M[i][i] 应该是 (<=, 0)， 如果变成 (<, 0), 则为空
            if (comparison == 0 && !closedMatrix[i][i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查当前 DBM (this) 所表示的区域是否包含另一个 DBM (other) 的区域.
     * 两个 DBM 必须基于相同的时钟集合和顺序.
     * 为了保证结果正确，this应该处于规范形式. other不需要是规范形式.
     *
     * @param other 要检查是否被包含的 DBM.
     * @return 如果 this 包含 other，则返回 true.
     * @throws IllegalArgumentException 如果两个 DBM 的时钟列表不匹配.
     */
    public boolean include(DBM other) {
        if (!this.clockList.equals(other.clockList)) {
            throw new IllegalArgumentException("DBM的时钟集合或顺序不同，无法比较包含关系。");
        }
        if (this.size != other.size) {
            throw new IllegalArgumentException("DBM的大小不同，无法比较包含关系。");


        }

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                Rational thisValue = this.upperBoundMatrix[i][j];
                boolean thisClosed = this.closedMatrix[i][j];
                Rational otherValue = other.upperBoundMatrix[i][j];
                boolean otherClosed = other.closedMatrix[i][j];

                int comparison = Rational.compare(otherValue, thisValue);

                if (comparison > 0) {
                    return false;
                } else if (comparison == 0) {
                    if (!thisClosed && otherClosed) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // === 辅助方法 ===

    /**
     * 获取指定索引位置的上界值.
     * @param i 行索引.
     * @param j 列索引.
     * @return Rational 值.
     */
    public Rational getValue(int i, int j) {
        if (i < 0 || i >= size || j < 0 || j >= size) {
            throw new IndexOutOfBoundsException("Index (" + i + ", " + j + ")越界：" + size);
        }
        return this.upperBoundMatrix[i][j];
    }

    /**
     * 获取指定索引位置的约束是否为闭合 (<=).
     * @param i 行索引.
     * @param j 列索引.
     * @return 如果是 <= 返回 true, 如果是 < 返回 false.
     */
    public boolean getClosed(int i, int j) {
        if (i < 0 || i >= size || j < 0 || j >= size) {
            throw new IndexOutOfBoundsException("Index (" + i + ", " + j + ")越界：" + size);
        }
        return this.closedMatrix[i][j];
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        int maxClockNameWidth = 0;
        for (Clock clock : clockList) {
            maxClockNameWidth = Math.max(maxClockNameWidth, clock.toString().length());
        }
        int elementWidth = 5;

        // 列标题 时钟名
        sb.append(String.format("%" + maxClockNameWidth + "s |", ""));
        for (Clock clock : clockList) {
            sb.append(String.format(" %-" + elementWidth + "s", clock.toString()));
        }
        sb.append("\n");

        // 分隔线
        sb.append("-".repeat(maxClockNameWidth + 1));
        sb.append("+");
        sb.append("-".repeat((elementWidth + 1) * size));
        sb.append("\n");

        // 行
        for (int i = 0; i < size; i++) {
            Clock clock_i = this.clockList.get(i);
            sb.append(String.format("%" + maxClockNameWidth + "s |", clock_i.toString())); // 行标题 时钟名
            for (int j = 0; j < size; j++) {
                String symbol = closedMatrix[i][j] ? "<=" : "< ";
                String valueStr = (upperBoundMatrix[i][j] == Rational.INFINITY) ? "inf" : upperBoundMatrix[i][j].toString();
                String element = String.format("(%s,%s)", symbol, valueStr);
                sb.append(String.format(" %-" + elementWidth + "s", element));
            }
            sb.append('\n');
        }
        return sb.toString();
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DBM dbm = (DBM) o;
        if (size != dbm.size) {
            return false;
        }
        if (!clockList.equals(dbm.clockList)) {
            return false;
        }

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (closedMatrix[i][j] != dbm.closedMatrix[i][j]) {
                    return false;
                }
                if (!Objects.equals(upperBoundMatrix[i][j], dbm.upperBoundMatrix[i][j])) {
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(clockList, size);
        for(int i = 0; i < size; ++i){
            for (int j = 0; j < size; j++) {
                result = 31 * result + Objects.hashCode(upperBoundMatrix[i][j]);
                result = 31 * result + Boolean.hashCode(closedMatrix[i][j]);
            }
        }
        return result;
    }
}
