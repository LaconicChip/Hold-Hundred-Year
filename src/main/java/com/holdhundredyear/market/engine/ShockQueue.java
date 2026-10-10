package com.holdhundredyear.market.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 价格冲击队列（P4）：冲击只入队，引擎每跳按下述协议消费，多冲击源叠加天然为求和，
 * 避免 if-else 地狱。事件七种反应模式（Phase 8）、剧本日定向冲击、炸板反向冲击均经此
 * 队列下发；磁吸漂移不做 shock（SealMachine 直接加在收益上）。
 *
 * <p>调用协议：引擎每跳先调用一次 {@link #consumeMarket()}（推进全部全市场冲击并返回
 * 其本跳幅度之和，同值作用于所有股票），随后对每只股票各调用一次
 * {@link #consumeStock(long)}（仅推进并返回该股票专属冲击，不含全市场）。
 * 每只股票本跳承受的总冲击 = consumeMarket() + consumeStock(stockId)。
 * 全市场冲击每跳只被 consumeMarket 推进一次（而非每股推进一次），
 * 保证所有股票看到完整且相同的衰减曲线；个股冲击只被其所属股票的 consumeStock 推进。
 *
 * <p>非线程安全：实例存活于 QuoteBoard 内部、由 per-user 世界锁保护（Phase 5），无内部同步。
 */
public class ShockQueue {

    /** 规模极小（同屏活跃冲击个位数），线性扫描即可。 */
    private final List<PriceShock> shocks = new ArrayList<>();

    public void enqueue(PriceShock shock) {
        shocks.add(shock);
    }

    /**
     * 每跳调用一次：推进并返回全部 marketWide 冲击的本跳幅度之和（同值作用于所有股票）。
     * 命中的 shock 各自 ticksLeft--，耗尽即从队列移除。
     */
    public double consumeMarket() {
        double total = 0.0;
        Iterator<PriceShock> it = shocks.iterator();
        while (it.hasNext()) {
            PriceShock shock = it.next();
            if (shock.marketWide()) {
                total += shock.consume();
                if (shock.exhausted()) {
                    it.remove();
                }
            }
        }
        return total;
    }

    /**
     * 每只股票每跳调用一次：推进并返回该股票专属冲击的本跳幅度
     * （不含 marketWide，也不推进它们）。命中的 shock 各自 ticksLeft--，耗尽即从队列移除。
     */
    public double consumeStock(long stockId) {
        double total = 0.0;
        Iterator<PriceShock> it = shocks.iterator();
        while (it.hasNext()) {
            PriceShock shock = it.next();
            if (!shock.marketWide() && shock.stockId() == stockId) {
                total += shock.consume();
                if (shock.exhausted()) {
                    it.remove();
                }
            }
        }
        return total;
    }

    /** 当前活跃（未耗尽）shock 数，测试/调试用。 */
    public int activeCount() {
        return shocks.size();
    }

    public void clear() {
        shocks.clear();
    }
}
