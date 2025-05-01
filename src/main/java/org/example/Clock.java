package org.example;

import lombok.Getter;
import lombok.Setter;
import org.example.region.Region;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger; // 使用原子类保证线程安全

/**
 * 代表一个时钟变量.
 * 包含一个特殊的零时钟 x0.
 * @author Ayalyt
 */
@Getter
public class Clock implements Comparable<Clock> {


    private static final IDgenerator ID_GENERATOR = new IDgenerator();

    private static final Clock ZERO_CLOCK = new Clock(0, "x_0", 0);

    private final Integer id;
    private String name;

    @Setter
    private Integer kappa = 10;

    private Clock(int id, String name, int kappa) {
        this.id = id;
        this.name = name;
        this.kappa = kappa;
    }

    /**da
     * 创建一个新的普通时钟 (ID 由生成器分配).
     * 默认名称为 "x" + id.
     */
    public Clock() {
        this.id = ID_GENERATOR.createId();
        this.name = "x" + this.id;
    }

    /**
     * 创建一个新的普通时钟, 并指定 kappa 值.
     * @param kappa Kappa 值.
     */
    public Clock(int kappa) {
        this();
        this.kappa = kappa;
    }

    /**
     * 创建一个新的普通时钟, 并指定名称.
     * @param name 时钟名称.
     */
    public Clock(String name) {
        this.id = ID_GENERATOR.createId();
        this.name = name;
        this.kappa = 10;
    }

    /**
     * 拷贝构造函数.
     * @param other 要拷贝的时钟.
     */
    public Clock(Clock other) {
        if (other.isZeroClock()){
            this.id = 0;
            this.name = "x0";
            this.kappa = 0;
        }
        else {
            this.id = ID_GENERATOR.createId();
            this.name = other.name;
            this.kappa = other.kappa;
        }
    }

    /**
     * 创建一个新的普通时钟, 指定名称和 kappa 值.
     * @param name 时钟名称.
     * @param kappa Kappa 值.
     */
    public Clock(String name, int kappa) {
        this.id = ID_GENERATOR.createId();
        this.name = name;
        this.kappa = kappa;
    }


    /**
     * 获取零时钟 (x0) 的单例实例.
     * @return 零时钟实例.
     */
    public static Clock getZeroClock() {
        return ZERO_CLOCK;
    }

    /**
     * 检查此时钟是否为零时钟.
     * @return 如果是零时钟则返回 true.
     */
    public boolean isZeroClock() {
        return this == ZERO_CLOCK || this.id == 0;
    }

    public void setName(){
        this.name = getName() + this.id;
    }


    @Override
    public int compareTo(Clock other) {
        return Integer.compare(this.id, other.id);
    }

    @Override
    public String toString() {
        return this.name;
    }

    @Override
    public int hashCode() {
        return this.id;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        Clock clock = (Clock) obj;
        return Objects.equals(this.id, clock.id);
    }

}
