package com.holdhundredyear.market.engine;

import com.holdhundredyear.game.constants.GameConstants;
import com.holdhundredyear.game.constants.StockDefs;
import com.holdhundredyear.game.constants.StockDefs.StockDef;
import com.holdhundredyear.game.model.enums.VolRegime;
import com.holdhundredyear.market.quote.Quote;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * 三层因子波动引擎（spec §5.2）：市场因子每跳全市场共享一次，板块因子每跳每板块一次，
 * 个股残差每股每跳一次；叠加 GARCH 波动放大、稀疏跳跃与日内 U 型。
 *
 * <p>纯计算、无状态、不依赖 Spring/DB：所有随机源由调用方传入（P6 确定性，
 * Phase 5 由 WorldRandom.of(seed, salt) 派生）。rawReturn 只产原始收益，
 * 封板/磁吸/冲击（Phase 6）由上游叠加。
 */
public class VolatilityEngine {

    /** 每跳一次：市场因子 z + 各板块因子 z（板块按 StockDefs 声明顺序的 distinct，LinkedHashMap 保序）。 */
    public record TickContext(double zMkt, Map<String, Double> zSector) {
    }

    /** 每跳一次：抽取市场因子与各板块因子（随机消费顺序固定：zMkt → 各板块按声明顺序）。 */
    public TickContext beginTick(Random rng) {
        double zMkt = rng.nextGaussian();
        Map<String, Double> zSec = new LinkedHashMap<>();
        for (String sector : StockDefs.ALL_SECTORS) {
            zSec.put(sector, rng.nextGaussian());
        }
        return new TickContext(zMkt, Collections.unmodifiableMap(zSec));
    }

    /**
     * 每股每跳一次：返回不含封板/磁吸/冲击的原始收益。
     * 随机消费顺序固定：zI → jump 判定(double) → [jumpK(int) → jumpZ(gaussian)]。
     */
    public double rawReturn(Quote q, StockDef def, double muMkt, Map<String, Double> sectorCoefs,
                            VolRegime regime, int tick, TickContext ctx, Random rng) {
        double u = uShapeFactor(tick);
        double k = GameConstants.VOL_REGIME.get(regime);
        double rMkt = muMkt + GameConstants.SIGMA_MKT * k * u * ctx.zMkt();
        double coef = sectorCoefs.getOrDefault(def.sector(), 0.0);
        double rSec = coef + GameConstants.SIGMA_SEC * k * u * ctx.zSector().getOrDefault(def.sector(), 0.0);
        double zI = rng.nextGaussian();
        double r = def.beta() * rMkt + rSec + GameConstants.SIGMA_STOCK * k * u * q.getGarchK() * zI;
        if (rng.nextDouble() < GameConstants.JUMP_PROB) {
            int jumpK = GameConstants.JUMP_K_MIN
                    + rng.nextInt(GameConstants.JUMP_K_MAX - GameConstants.JUMP_K_MIN + 1);
            r += GameConstants.SIGMA_STOCK * jumpK * rng.nextGaussian();
        }
        return r;
    }

    /** 跳后更新 GARCH 系数：k' = 0.85k + 0.15·min(|realizedReturn|/SIGMA_STOCK, 4)。 */
    public void updateGarch(Quote q, double realizedReturn) {
        double ratio = Math.min(Math.abs(realizedReturn) / GameConstants.SIGMA_STOCK, GameConstants.GARCH_CAP);
        q.setGarchK(GameConstants.GARCH_DECAY * q.getGarchK() + GameConstants.GARCH_WEIGHT * ratio);
    }

    /**
     * 日内 U 型：tick 0..10 由 U_OPEN 线性过渡到 U_OPEN_END；11..79 恒 U_MID；
     * 80..89 恒 U_CLOSE；越界按最近段处理（tick&lt;0 取开盘值，tick&gt;89 取尾盘值）。
     */
    public static double uShapeFactor(int tick) {
        if (tick <= 0) {
            return GameConstants.U_OPEN;
        }
        if (tick <= 10) {
            return GameConstants.U_OPEN
                    + (GameConstants.U_OPEN_END - GameConstants.U_OPEN) * tick / 10.0;
        }
        if (tick < GameConstants.TOTAL_TICKS - 10) {
            return GameConstants.U_MID;
        }
        return GameConstants.U_CLOSE;
    }
}
