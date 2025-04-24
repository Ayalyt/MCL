package org.example.utils;

import lombok.Getter;
// Removed: import org.apache.commons.math3.fraction.Fraction;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


public final class Rational implements Comparable<Rational> {
    private static final int MAX_CACHE_MAGNITUDE = 1024;
    private static final ConcurrentHashMap<String, Rational> CACHE = new ConcurrentHashMap<>(2048);
    private static final ForkJoinPool PARALLEL_POOL = new ForkJoinPool();

    // BigInteger 常量
    private static final BigInteger BIG_INT_ZERO = BigInteger.ZERO;
    private static final BigInteger BIG_INT_ONE = BigInteger.ONE;
    private static final BigInteger BIG_INT_NEG_ONE = BigInteger.valueOf(-1);
    private static final BigInteger BIG_INT_TEN = BigInteger.TEN;

    @Getter
    private final BigInteger numerator;
    @Getter
    private final BigInteger denominator;

    private volatile int hash;

    // 常用常量
    public static final Rational ZERO = new Rational(BIG_INT_ZERO, BIG_INT_ONE); // 0/1
    public static final Rational ONE = new Rational(BIG_INT_ONE, BIG_INT_ONE);   // 1/1
    public static final Rational HALF = new Rational(BIG_INT_ONE, BigInteger.valueOf(2)); // 1/2
    public static final Rational INFINITY = new Rational(BIG_INT_ONE, BIG_INT_ZERO);      // 1/0
    public static final Rational NEG_INFINITY = new Rational(BIG_INT_NEG_ONE, BIG_INT_ZERO); // -1/0
    public static final Rational NaN = new Rational(BIG_INT_ZERO, BIG_INT_ZERO);         // 0/0
    public static final Rational EPSILON = new Rational(BigInteger.valueOf(1), BigInteger.valueOf(1000000));

    static {
        CACHE.put(ZERO.getCacheKey(), ZERO);
        CACHE.put(ONE.getCacheKey(), ONE);
        CACHE.put(HALF.getCacheKey(), HALF);
        CACHE.put(valueOf(-1).getCacheKey(), valueOf(-1));

        for (int i = -16; i <= 16; i++) {
            if (i != 0 && i != 1 && i != -1) {
                Rational r = new Rational(BigInteger.valueOf(i), BIG_INT_ONE);
                CACHE.put(r.getCacheKey(), r);
            }
        }
        for (int den = 2; den <= 16; den++) {
            for (int num = -den; num <= den; num++) {
                if (num != 0) {
                    Rational r = valueOf(BigInteger.valueOf(num), BigInteger.valueOf(den));
                    if (!CACHE.containsValue(r) && shouldCache(r)) {
                        CACHE.put(r.getCacheKey(), r);
                    }
                }
            }
        }
        CACHE.put(INFINITY.getCacheKey(), INFINITY);
        CACHE.put(NEG_INFINITY.getCacheKey(), NEG_INFINITY);
        CACHE.put(NaN.getCacheKey(), NaN);
    }


    /**
     * 私有构造函数，用于内部创建和常量定义。
     */
    private Rational(BigInteger numerator, BigInteger denominator) {
        this.numerator = numerator;
        this.denominator = denominator;
    }

    /**
     * 规范化有理数：
     * 1. 处理分母为0 (Infinity/NaN)
     * 2. 确保分母为正
     * 3. 约分 (除以GCD)
     * 4. 处理分子为0
     */
    private static Rational normalize(BigInteger num, BigInteger den) {
        if (den.equals(BIG_INT_ZERO)) {
            int signNum = num.signum();
            if (signNum > 0) {
                return INFINITY;
            }
            if (signNum < 0) {
                return NEG_INFINITY;
            }
            return NaN;
        }
        if (num.equals(BIG_INT_ZERO)) {
            return ZERO;
        }

        // 确保分母为正
        if (den.signum() < 0) {
            num = num.negate();
            den = den.negate();
        }

        // 约分
        BigInteger commonDivisor = num.gcd(den);
        if (!commonDivisor.equals(BIG_INT_ONE)) {
            num = num.divide(commonDivisor);
            den = den.divide(commonDivisor);
        }

        if (den.equals(BIG_INT_ONE)) {
            if (num.equals(BIG_INT_ZERO)) {
                return ZERO;
            }
            if (num.equals(BIG_INT_ONE)) {
                return ONE;
            }
        }
        if (num.equals(BIG_INT_ONE) && den.equals(BigInteger.valueOf(2))) {
            return HALF;
        }


        // 返回规范化后的内部表示
        return new Rational(num, den);
    }

    // ========== 工厂方法 ==========

    public static Rational valueOf(BigInteger numerator) {
        return valueOf(numerator, BIG_INT_ONE);
    }

    public static Rational valueOf(long numerator) {
        if (numerator == 0L) {
            return ZERO;
        }
        if (numerator == 1L) {
            return ONE;
        }
        return valueOf(BigInteger.valueOf(numerator), BIG_INT_ONE);
    }

    public static Rational valueOf(int numerator) {
        return valueOf((long) numerator);
    }


    public static Rational valueOf(BigInteger numerator, BigInteger denominator) {
        // 预检查简化情况
        if (denominator.equals(BIG_INT_ZERO)) {
            int signNum = numerator.signum();
            if (signNum > 0) {
                return INFINITY;
            }
            if (signNum < 0) {
                return NEG_INFINITY;
            }
            return NaN;
        }
        if (numerator.equals(BIG_INT_ZERO)) {
            return ZERO;
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        if (numerator.equals(denominator)) {
            return ONE;
        }
        if (denominator.equals(BIG_INT_ONE)) { // 已经是整数
            if (numerator.equals(BIG_INT_ONE)) {
                return ONE;
            }
            // 尝试从缓存获取小整数
            String key = numerator.toString() + "/1";
            Rational cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }


        // 生成缓存键,对于非常大的数可能效率不高
        String key = numerator.toString() + "/" + denominator.toString();
        Rational cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        Rational normalized = normalize(numerator, denominator);

        if (shouldCache(normalized)) {
            String normalizedKey = normalized.getCacheKey();
            Rational finalCached = CACHE.get(normalizedKey);
            if (finalCached != null) {
                return finalCached;
            }
            CACHE.put(normalizedKey, normalized);
        }

        return normalized;
    }

    public static Rational valueOf(long numerator, long denominator) {
        return valueOf(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    public static Rational valueOf(int numerator, int denominator) {
        return valueOf((long) numerator, (long) denominator);
    }


    public static Rational valueOf(double value) {
        if (Double.isNaN(value)) {
            return NaN;
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? INFINITY : NEG_INFINITY;
        }
        if (Math.abs(value) < 1e-15) {
            return ZERO;
        }
        if (Math.abs(value - 1.0) < 1e-15) {
            return ONE;
        }
        if (Math.abs(value - 0.5) < 1e-15) {
            return HALF;
        }
        if (Math.abs(value + 1.0) < 1e-15) {
            return valueOf(1);
        }
        if (Math.abs(value - -1.0) < 1e-15) {
            return valueOf(-1);
        }

        // 使用 BigDecimal 进行精确转换
        BigDecimal bd = new BigDecimal(Double.toString(value));
        int scale = bd.scale();
        BigInteger num;
        BigInteger den;
        if (scale <= 0) {
            // 整数或科学计数法的大数
            num = bd.unscaledValue().multiply(BIG_INT_TEN.pow(-scale));
            den = BIG_INT_ONE;
        } else {
            num = bd.unscaledValue();
            den = BIG_INT_TEN.pow(scale);
        }

        return valueOf(num, den); // Handles normalization and caching
    }

    public static Rational valueOf(String s) {
        if (s == null || s.trim().isEmpty()) {
            throw new NumberFormatException("输入非法");
        }
        s = s.trim();

        // Check cache first
        Rational cached = CACHE.get(s);
        if (cached != null) {
            return cached;
        }


        // 处理特殊的无穷符号
        if ("+".equals(s) || "∞".equals(s) || "Infinity".equalsIgnoreCase(s)) {
            return INFINITY;
        }
//        if ("-".equals(s) || "-Infinity".equalsIgnoreCase(s)) {
//            return NEG_INFINITY;
//        } 暂时先不用
        if (s.equalsIgnoreCase("NaN")) {
            return NaN;
        }


        // 处理分数形式
        if (s.contains("/")) {
            String[] parts = s.split("/", 2);
            if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                throw new NumberFormatException("无效分数格式: " + s);
            }
            try {
                BigInteger num = new BigInteger(parts[0].trim());
                BigInteger den = new BigInteger(parts[1].trim());
                return valueOf(num, den);
            } catch (NumberFormatException e) {
                throw new NumberFormatException("分数" + s + "的数字无效: " + e.getMessage());
            }
        }

        // 处理整数和小数 (使用 BigDecimal for robustness)
        try {
            BigDecimal bd = new BigDecimal(s);
            int scale = bd.scale();
            BigInteger num;
            BigInteger den;
            if (scale <= 0) {
                num = bd.unscaledValue().multiply(BIG_INT_TEN.pow(-scale));
                den = BIG_INT_ONE;
            } else {
                num = bd.unscaledValue();
                den = BIG_INT_TEN.pow(scale);
            }
            return valueOf(num, den);

        } catch (NumberFormatException e) {
            throw new NumberFormatException("无效数字格式: " + s);
        }
    }

    // ========== 基础运算 ==========
    public Rational add(Rational other) {
        if (this.isNaN() || other.isNaN()) {
            return NaN;
        }
        if (this == ZERO) {
            return other;
        }
        if (other == ZERO) {
            return this;
        }

        if (this.isInfinity() || other.isInfinity()) {
            if (this.isInfinity() && other.isInfinity()) {
                if (this.numerator.signum() != other.numerator.signum()) {
                    return NaN;
                }
                return this;
            }
            return this.isInfinity() ? this : other;
        }

        // Finite addition using BigInteger
        BigInteger num1 = this.numerator; BigInteger den1 = this.denominator;
        BigInteger num2 = other.numerator; BigInteger den2 = other.denominator;

        BigInteger newNum = num1.multiply(den2).add(num2.multiply(den1));
        BigInteger newDen = den1.multiply(den2);

        return valueOf(newNum, newDen);
    }

    public Rational subtract(Rational other) {
        if (this.isNaN() || other.isNaN()) {
            return NaN;
        }
        return this.add(other.negate());
    }

    public Rational multiply(Rational other) {
        if (this.isNaN() || other.isNaN()) {
            return NaN;
        }

        int s1 = this.signum();
        int s2 = other.signum();
        if (s1 == 0 || s2 == 0) {
            return ZERO;
        }

        if (this.isInfinity() || other.isInfinity()) {
            return (s1 * s2 > 0) ? INFINITY : NEG_INFINITY;
        }

        // Finite multiplication
        BigInteger num1 = this.numerator; BigInteger den1 = this.denominator;
        BigInteger num2 = other.numerator; BigInteger den2 = other.denominator;

        return valueOf(num1.multiply(num2), den1.multiply(den2));
    }

    public Rational divide(Rational other) {
        if (this.isNaN() || other.isNaN()) {
            return NaN;
        }
        if (other.isZero()) {
            if (this.isZero()) {
                return NaN; // 0 / 0
            }
            return this.signum() > 0 ? INFINITY : NEG_INFINITY; // Finite / 0
        }
        if (other.isInfinity()) {
            return ZERO; // Finite / Inf = 0
        }
        if (this.isInfinity()) {
            // Inf / Finite
            return this.signum() * other.signum() > 0 ? INFINITY : NEG_INFINITY;
        }
        if (other == ONE) {
            return this;
        }

        return this.multiply(other.reciprocal());
    }

    public Rational reciprocal() {
        if (isNaN()) {
            return NaN;
        }
        if (isZero()) {
            return INFINITY; // 1 / 0 -> Infinity (sign handled by factory)
        }
        if (this == INFINITY) {
            return ZERO; // 1 / +Inf -> 0
        }
        if (this == NEG_INFINITY) {
            return ZERO; // 1 / -Inf -> 0
        }
        return valueOf(this.denominator, this.numerator);
    }

    public Rational negate() {
        if (isNaN()) {
            return NaN;
        }
        if (isZero()) {
            return ZERO;
        }
        if (this == INFINITY) {
            return NEG_INFINITY;
        }
        if (this == NEG_INFINITY) {
            return INFINITY;
        }
        return valueOf(this.numerator.negate(), this.denominator);
    }

    public Rational abs() {
        if (isNaN()) {
            return NaN;
        }
        if (this == NEG_INFINITY) {
            return INFINITY;
        }
        if (this.numerator.signum() >= 0) {
            return this;
        }
        return this.negate();
    }

    public static Rational max(Rational a, Rational b) {
        if (a.isNaN() || b.isNaN()) {
            return NaN;
        }
        if (a.isInfinity() || b.isInfinity()) {
            return a.isInfinity()? a : b;
        }
        if (a.isNegativeInfinity() || b.isNegativeInfinity()) {
            return a.isNegativeInfinity()? b : a;
        }
        return a.compareTo(b) >= 0? a : b;
    }

    // ========== 并行流支持 ==========
    public static Rational parallelSum(Collection<Rational> numbers) {
        return PARALLEL_POOL.submit(() ->
                numbers.parallelStream()
                        .reduce(Rational.ZERO, Rational::add)
        ).join();
    }

    public static Rational parallelProduct(Collection<Rational> numbers) {
        return PARALLEL_POOL.submit(() ->
                numbers.parallelStream()
                        .reduce(Rational.ONE, Rational::multiply)
        ).join();
    }

    public static List<Rational> parallelMap(List<Rational> input, UnaryOperator<Rational> mapper) {
        return PARALLEL_POOL.submit(() ->
                input.parallelStream()
                        .map(mapper)
                        .collect(Collectors.toList())
        ).join();
    }

    public static List<Rational> parallelBinaryOperation(
            List<Rational> list1,
            List<Rational> list2,
            BinaryOperator<Rational> op) {
        if (list1.size() != list2.size()) {
            throw new IllegalArgumentException("Lists must have same size");
        }
        return PARALLEL_POOL.submit(() ->
                IntStream.range(0, list1.size())
                        .parallel()
                        .mapToObj(i -> op.apply(list1.get(i), list2.get(i)))
                        .collect(Collectors.toList())
        ).join();
    }

    // ========== 工具方法 ==========

    /**
     * 是否为有限数 (既不是 Infinity, -Infinity, 也不是 NaN)
     * @return 如果是有限数返回 true
     */
    public boolean isFinite() {
        // 有限数的 denominator 必须非零
        return !this.denominator.equals(BIG_INT_ZERO);
    }

    /**
     * 是否为无穷大 (包括正无穷和负无穷)
     * @return 是否为无穷大
     */
    public boolean isInfinity() {
        // 无穷大的 denominator 为 0，numerator 非 0
        return this.denominator.equals(BIG_INT_ZERO) && !this.numerator.equals(BIG_INT_ZERO);
    }

    /**
     * 是否为正无穷大
     * @return 是否为正无穷大
     */
    public boolean isPositiveInfinity() {
        return this == INFINITY;
    }


    /**
     * 是否为负无穷大
     * @return 是否为负无穷大
     */
    public boolean isNegativeInfinity() {
        return this == NEG_INFINITY;
    }

    /**
     * 是否为零
     * @return 是否为零
     */
    public boolean isZero() {
        return this == ZERO;
    }

    /**
     * 是否为NaN (Not a Number)
     * @return 是否为NaN
     */
    public boolean isNaN() {
        return this == NaN;
    }

    /**
     * 获取符号: 1 (正数), -1 (负数), 0 (零),
     * 或者抛出异常 (NaN).
     * 无穷大根据其符号返回 1 或 -1.
     * @return 符号值
     * @throws ArithmeticException 如果是 NaN
     */
    public int signum() {
        if (isNaN()) {
            throw new ArithmeticException("含有NaN");
        }
        // 对于有限数和无穷大，符号由分子决定 (分母规范化为正或零)
        return this.numerator.signum();
    }


    /**
     * 转换为 double 值.
     * 注意：对于非常大或非常小的有理数可能会丢失精度或溢出为 Infinity/-Infinity.
     * @return double 值
     */
    public double doubleValue() {
        if (isNaN()) {
            return Double.NaN;
        }
        if (this == INFINITY) {
            return Double.POSITIVE_INFINITY;
        }
        if (this == NEG_INFINITY) {
            return Double.NEGATIVE_INFINITY;
        }
        if (isZero()) {
            return 0.0;
        }

        // 使用 BigDecimal 进行转换以获得更好的精度控制
        // 设置一个足够大的精度，但避免无限循环
        int precision = Math.max(100, Math.max(numerator.abs().bitLength(), denominator.abs().bitLength()) + 10);
        BigDecimal numBd = new BigDecimal(this.numerator);
        BigDecimal denBd = new BigDecimal(this.denominator);

        try {
            BigDecimal result = numBd.divide(denBd, precision, RoundingMode.HALF_EVEN);
            return result.doubleValue(); // 可能仍然会溢出为 double 的 Infinity
        } catch (ArithmeticException e) {
            // 如果除法导致非终止小数，直接用 double 除法可能更合适（虽然精度较低）
            return this.numerator.doubleValue() / this.denominator.doubleValue();
        }
    }

    /**
     * 转换为 float 值.
     * 注意：精度损失比 double 更严重.
     * @return float 值
     */
    public float floatValue() {
        return (float) doubleValue();
    }

    /**
     * 转换为 long 值 (截断小数部分).
     * @return long 值
     * @throws ArithmeticException 如果数值超出 long 范围或为非有限数
     */
    public long longValue() {
        if (!isFinite()) {
            throw new ArithmeticException("Rational to long: " + this);
        }
        if (isZero()) {
            return 0L;
        }
        // 执行 BigInteger 除法 (截断)
        BigInteger result = this.numerator.divide(this.denominator);
        try {
            return result.longValueExact();
        } catch (ArithmeticException e) {
            throw new ArithmeticException("Rational越界: " + this);
        }
    }

    /**
     * 转换为 int 值 (截断小数部分).
     * @return int 值
     * @throws ArithmeticException 如果数值超出 int 范围或为非有限数
     */
    public int intValue() {
        if (!isFinite()) {
            throw new ArithmeticException("Rational to int: " + this);
        }
        if (isZero()) {
            return 0;
        }
        BigInteger result = this.numerator.divide(this.denominator);
        try {
            return result.intValueExact();
        } catch (ArithmeticException e) {
            throw new ArithmeticException("Rational越界: " + this);
        }
    }

    /**
     *  判断该rational是否是整数
     */
    public boolean isInteger() {
        return this.denominator.equals(BIG_INT_ONE);
    }


    /**
     * 静态比较
     * 处理 Infinity, -Infinity, 和 NaN.
     * NaN 比较都应谨慎处理，遵循 IEEE 754 的部分思想
     * 但 compareTo 通常要求全序关系，NaN 破坏了这一点。
     * 定义 NaN 比所有数都大 (除了它自己)。
     */
    public static int compare(Rational a, Rational b) {
        if (a == b) {
            return 0; // 同一对象或常量
        }

        // NaN > Infinity > finite > -Infinity
        if (a.isNaN()) {
            return b.isNaN() ? 0 : 1;
        }
        if (b.isNaN()) {
            return -1;
        }

        // 处理无穷大
        if (a.isInfinity()) { // a +Inf/-Inf
            if (b.isInfinity()) { // b +Inf/-Inf
                return a.numerator.compareTo(b.numerator); // +Inf vs +Inf = 0, -Inf vs -Inf = 0, +Inf vs -Inf = 1
            }
            return a.numerator.signum(); // a：Inf, b：finite
        }
        if (b.isInfinity()) { // a finite, b Inf
            return -b.numerator.signum();
        }

        // 比较 a/b 和 c/d -> 比较 a*d 和 c*b
        BigInteger ad = a.numerator.multiply(b.denominator);
        BigInteger cb = b.numerator.multiply(a.denominator);
        return ad.compareTo(cb);
    }

    // ========== 对象基础方法 ==========

    @Override
    public int compareTo(Rational other) {
        return compare(this, other);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true; // 同一对象或常量
        }
        if (!(o instanceof Rational that)) {
            return false;
        }
        return this.numerator.equals(that.numerator) && this.denominator.equals(that.denominator);
    }

    @Override
    public int hashCode() {
        int h = hash;
        if (h == 0) {
            h = Objects.hash(numerator, denominator);
            if (h == 0) {
                h = 1; // 避免 hash 为 0 导致重复计算
            }
            hash = h;
        }
        return h;
    }

    /**
     * 获取用于缓存的键。
     * 注意：对于非常大的 BigInteger，toString() 可能很慢。
     * 可以考虑其他键生成策略，但这会使缓存查找复杂化。
     */
    private String getCacheKey() {
        // 对于常量，直接使用它们的标准字符串表示
        if (this == ZERO) {
            return "0";
        }
        if (this == ONE) {
            return "1";
        }
        if (this == HALF) {
            return "1/2";
        }
        if (this == INFINITY) {
            return "∞";
        }
        if (this == NEG_INFINITY) {
            return "-∞";
        }
        if (this == NaN) {
            return "NaN";
        }

        // 对于整数，使用 "num/1" 格式
        if (this.denominator.equals(BIG_INT_ONE)) {
            return this.numerator.toString() + "/1";
        }
        // 对于其他分数
        return this.numerator.toString() + "/" + this.denominator.toString();
    }

    /**
     * 决定一个新创建的 (且未在缓存中的) Rational 是否应该被缓存。
     * 可以基于数值大小、分母大小等因素。
     */
    private static boolean shouldCache(Rational r) {
        if (r.isNaN() || r.isInfinity()) {
            return true;
        }
        try {
            int numBitLength = r.numerator.abs().bitLength();
            int denBitLength = r.denominator.bitLength();
            return (numBitLength + denBitLength) < 64;
        } catch (Exception e) {
            return false;
        }
    }


    @Override
    public String toString() {
        if (isNaN()) {
            return "NaN";
        }
        if (this == INFINITY) {
            return "∞";
        }
        if (this == NEG_INFINITY) {
            return "-∞";
        }
        if (isZero()) {
            return "0";
        }
        if (this.denominator.equals(BIG_INT_ONE)) {
            return this.numerator.toString();
        }
        // 分数
        return this.numerator.toString() + "/" + this.denominator.toString();
    }


}

