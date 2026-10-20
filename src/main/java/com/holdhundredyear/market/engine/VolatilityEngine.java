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
 * 个股残差每股每跳一次；叠加 GARCH 波动放大、稀疏跳跃、日内 U 型与均值回归。
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
     * 均值回归项是确定性的（只读行情板的当前价与当日均价），不消费随机数，同种子仍同结果。
     *
     * <p><b>2026-10-20 引擎大改</b>：个股特质项（σ、跳跃幅度、日内活跃相位）全部按
     * {@code StockDef} 的个股特征走，市场因子与板块因子仍是全市场共性——
     * "大盘齐涨齐跌"保留，但"哪只票在什么时候动"由每只票自己决定。
     */
    public double rawReturn(Quote q, StockDef def, double muMkt, Map<String, Double> sectorCoefs,
                            VolRegime regime, int tick, TickContext ctx, Random rng) {
        double uMkt = uShapeFactor(tick);
        // 个股自己的日内活跃包络：相位由档案给定，24 只票错开 → 不再"固定时间集体动"
        double uOwn = uShapeFactor(tick, def);
        double k = GameConstants.VOL_REGIME.get(regime);
        double rMkt = muMkt + GameConstants.SIGMA_MKT * k * uMkt * ctx.zMkt();
        double coef = sectorCoefs.getOrDefault(def.sector(), 0.0);
        double rSec = coef + GameConstants.SIGMA_SEC * k * uMkt * ctx.zSector().getOrDefault(def.sector(), 0.0);
        double zI = rng.nextGaussian();
        // 本股当跳 σ：基准 × 个股特质倍数 × 波动体制 × 自身日内包络 × GARCH 系数
        // （ST 票在 sigmaScale 里再收一档：±5% 的板很近，不收窄就会天天顶在板上）
        double sigmaI = conditionalSigma(q, def, regime, tick);
        double r = def.beta() * rMkt + rSec + sigmaI * zI;
        // 日内均值回归：往当日均价回拉。缺这一项时价格只会一路随机游走，分时图像一条斜线而不是
        // 一团抖动；有了它，涨多了要回、跌多了要抽，日收益方差也顺带被压住（"抖而不跑"）。
        // 开盘首跳还没有均价（tickCount == 0）不回归；均价 <= 0 只可能是脏数据，同样跳过。
        if (q.getTickCount() > 0 && q.getDayAvg() > 0) {
            r -= GameConstants.OU_KAPPA * (q.getCurrent() - q.getDayAvg()) / q.getDayAvg();
        }
        if (rng.nextDouble() < GameConstants.JUMP_PROB) {
            int jumpK = GameConstants.JUMP_K_MIN
                    + rng.nextInt(GameConstants.JUMP_K_MAX - GameConstants.JUMP_K_MIN + 1);
            // 跳跃跟本股自己的 σ 走：银行跳得轻、妖股跳得重（不是全局同一个数）
            r += sigmaI * jumpK * rng.nextGaussian();
        }
        return r;
    }

    /**
     * 本股<b>当前条件 σ</b>（当跳该有的波动尺度）：基准 × 个股特质倍数 × 波动体制
     * × 自身日内包络 × GARCH 系数。
     *
     * <p>两处必须用同一个值：① {@link #rawReturn} 里乘在特质收益上；② {@link #updateGarch}
     * 里当比率的分母。分母若用不带 k 的"基准尺度"，GARCH 会自我压制（实测 k 被压到 0.05，
     * 个股波动倍数被整段抵消）；分母若不带自身日内包络，活跃时段的 k 会被反向拉低。
     */
    public static double conditionalSigma(Quote q, StockDef def, VolRegime regime, int tick) {
        double regimeK = GameConstants.VOL_REGIME.get(regime);
        return sigmaScale(q, def) * regimeK * uShapeFactor(tick, def) * q.getGarchK();
    }

    /**
     * 跳后更新 GARCH 系数：k' = 0.85k + 0.15·min(|r|/sigmaCond, CAP)²。
     *
     * <p><b>为什么用平方</b>（2026-10-20 引擎大改）：旧版用<b>线性</b>比率，而分母是"基准 σ"，
     * 实际波动又乘了 k——于是 k 的不动点是"自洽方程 k = 0.8·k"这类退化解，k 会被一路压到
     * 0.2~0.4（实测），等于把个股波动倍数整段抵消掉。改成平方后不动点是 k ≈ 1
     * （E[(ε/σ)²] = 1），k 只反映"这一跳是不是特别大"，不会系统性压低或抬高任何一只票的波动。
     *
     * <p>分母 sigmaCond 必须是<b>当前条件 σ</b>（见 {@link #conditionalSigma}）：用不带 k 的
     * "基准尺度"会让 k 自我压制（实测塌到 0.05），用不带日内包络的尺度会让活跃时段的 k 反向走低。
     */
    public void updateGarch(Quote q, double realizedReturn, double sigmaCond) {
        double ratio = Math.min(Math.abs(realizedReturn) / sigmaCond, GameConstants.GARCH_CAP);
        q.setGarchK(GameConstants.GARCH_DECAY * q.getGarchK() + GameConstants.GARCH_WEIGHT * ratio * ratio);
    }

    /**
     * 该股的基准 σ 尺度（不含日内包络与 GARCH 系数）：GARCH 比率的分母。
     *
     * <p>必须用<b>本股自己的</b>尺度，不能用全局 SIGMA_STOCK——否则低波动票（银行）的
     * 实际波动只有基准的 0.55 倍，比值恒大于 1，GARCH 会把它顶到上限 1.8，
     * 个股特质倍数就被 GARCH 抵消了。ST 票同理（板窄、波动再收一档）。
     */
    public static double sigmaScale(Quote q, StockDef def) {
        double volMult = q.isSt() ? def.volMult() * GameConstants.ST_VOL_FACTOR : def.volMult();
        return GameConstants.SIGMA_STOCK * volMult;
    }

    /**
     * 日内 U 型（全市场共性）：tick 0..10 由 U_OPEN 线性过渡到 U_OPEN_END；11..79 恒 U_MID；
     * 80..89 恒 U_CLOSE；越界按最近段处理（tick&lt;0 取开盘值，tick&gt;89 取尾盘值）。
     *
     * <p>这条曲线只作用于市场因子与板块因子（所有票共享）。个股特质项另走
     * {@link #uShapeFactor(int, StockDef)} 的相位调制。
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

    /**
     * 个股日内活跃包络 = 全市场共性 U 型 × 本股相位余弦（2026-10-20 引擎大改）。
     *
     * <p>余弦项让每只票一天里有一条自己的节奏：σ 在 {@code volPhase} 处最高（1+amp）、
     * 45 跳之后最低（1−amp），24 只票相位互不相同。旧版 24 只票共用同一条曲线，
     * 于是"开盘/尾盘"必然是全市场一起放大的固定时刻——玩家看到的"都在固定时间突然大涨
     * 或大跌一轮"就是这么来的。相位项以 90 跳为周期、首尾连续，跨日不跳变。
     */
    public static double uShapeFactor(int tick, StockDef def) {
        double own = 1.0 + GameConstants.U_PHASE_AMP
                * Math.cos(2 * Math.PI * (tick - def.volPhase()) / GameConstants.TOTAL_TICKS);
        return uShapeFactor(tick) * own;
    }
}
