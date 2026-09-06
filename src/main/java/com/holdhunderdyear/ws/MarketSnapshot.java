package com.holdhunderdyear.ws;

import java.util.List;

/**
 * 每跳行情推送快照（Task 10.2）：轻量只读传输对象，承载推进一瞬的世界视图——tickIndex、
 * 等权指数与 24 只个股的当前价/涨跌幅。由 WorldTickService 在每跳推进完成处组装，交
 * PushService.pushQuotes 下发在线用户，供前端刷新行情面板；不强求与查询 DTO 全对齐。
 * record 值语义、Jackson 可直接序列化。
 */
public record MarketSnapshot(int tickIndex, double indexValue, List<Row> quotes) {

    /** 单只个股行情行。 */
    public record Row(long stockId, double current, double changePct) {
    }
}