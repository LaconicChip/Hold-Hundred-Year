package com.holdhunderdyear.market.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 价格冲击队列（P4）：冲击只入队，引擎每跳对每只股票统一 {@link #consume(long)} 消费，
 * 多冲击源叠加天然为求和，避免 if-else 地狱。事件七种反应模式（Phase 8）、剧本日定向冲击、
 * 炸板反向冲击均经此队列下发；磁吸漂移不做 shock（SealMachine 直接加在收益上）。
 *
 * <p>consume(stockId) 汇总"该股票专属 + 全市场"活跃 shock 的本跳幅度并求和返回，
 * 同时将这些 shock 各自推进一跳、移除已耗尽者；其他股票的专属 shock 不受影响
 * （等待其所属股票的下一跳消费）。
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
     * 消费一跳：返回该股票本跳承受的冲击总幅（个股专属 + 全市场之和）。
     * 命中的 shock 各自 ticksLeft--，耗尽即从队列移除。
     */
    public double consume(long stockId) {
        double total = 0.0;
        Iterator<PriceShock> it = shocks.iterator();
        while (it.hasNext()) {
            PriceShock shock = it.next();
            if (shock.marketWide() || shock.stockId() == stockId) {
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
