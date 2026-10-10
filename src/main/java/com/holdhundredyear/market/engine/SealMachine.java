package com.holdhundredyear.market.engine;

import com.holdhundredyear.game.constants.GameConstants;
import com.holdhundredyear.game.model.enums.SealState;
import com.holdhundredyear.market.quote.Quote;

import java.util.Random;

/**
 * 涨跌停磁吸/封板/炸板状态机（spec §5 磁吸与封板机制）：未封板且 |dayChange|>=6.5% 时
 * 向该方向磁吸漂移并逐跳掷骰封板；封板后价格钉死涨/跌停价，到期掷骰炸板（封单被吃，
 * 瞬间反向 2%~4% ONCE 冲击入队，天地板/地天板由此产生）或续封到收盘；一字板
 * （隔夜 |gap|>=10%）开盘首跳锁定。磁吸漂移直接作用于收益（不经 ShockQueue）。
 *
 * <p>纯计算、无状态、不依赖 Spring：随机源与冲击队列由调用方传入（P6 确定性）。
 * halted 股票由调用方跳过（价格冻结），本类不检查。
 */
public class SealMachine {

    /** 未封板时的磁吸漂移（无随机）：|dayChange|>=MAGNET_TRIGGER 时朝触发方向漂移。
     *  幅度 = MAGNET_DRIFT × (1 + STREAK_MAGNET_BONUS × min(limitUpStreak, STREAK_MAGNET_CAP))，仅涨停方向享连板加成。
     *  dayChange>0 → 正漂移（向上磁吸）；<0 → 负漂移。未触发 → 0。 */
    public double magnetDrift(Quote q) {
        double chg = q.dayChangePct();
        if (Math.abs(chg) < GameConstants.MAGNET_TRIGGER) {
            return 0.0;
        }
        if (chg > 0) {
            double streakBonus = GameConstants.STREAK_MAGNET_BONUS
                    * Math.min(q.getLimitUpStreak(), GameConstants.STREAK_MAGNET_CAP);
            return GameConstants.MAGNET_DRIFT * (1 + streakBonus);
        }
        return -GameConstants.MAGNET_DRIFT;
    }

    /** 价格更新后调用（tick = 当前跳序 0..89；halted 股票调用方应跳过，本方法不检查）。
     *  sealed!=NONE：钉价 current=涨停/跌停价；sealTicksLeft--；耗尽时掷 BREAK_PROB——
     *    命中：sealed=NONE，入队炸板冲击（UP 方向 -[BREAK_SHOCK_MIN,BREAK_SHOCK_MAX] 均匀随机、DOWN 方向 +），
     *          PriceShock(stockId, false, pct, 1, Decay.ONCE)——下跳由 ShockQueue 消费生效；
     *    未命中：sealTicksLeft = TOTAL_TICKS − tick（续封到收盘）。
     *  sealed==NONE：|dayChange|>=MAGNET_TRIGGER 时掷封板：
     *    prob = SEAL_BASE_PROB + SEAL_STREAK_BONUS×limitUpStreak（仅向上方向） + PROXIMITY_SEAL_SCALE×(|chg|−MAGNET_TRIGGER)
     *    命中：sealed=方向(UP/DOWN)、current=对应限价、sealTicksLeft = SEAL_MIN_TICKS + nextInt(SEAL_MAX_TICKS−SEAL_MIN_TICKS+1)。
     *  随机消费顺序固定：sealed 分支 [到期判定 double → 炸板幅度 double?]；未封分支 [封板判定 double → 时长 int?]。 */
    public void apply(Quote q, int tick, Random rng, ShockQueue shocks) {
        if (q.getSealed() != SealState.NONE) {
            advanceSealed(q, tick, rng, shocks);
            return;
        }
        double chg = q.dayChangePct();
        if (Math.abs(chg) < GameConstants.MAGNET_TRIGGER) {
            return;
        }
        boolean up = chg > 0;
        double prob = GameConstants.SEAL_BASE_PROB
                + GameConstants.PROXIMITY_SEAL_SCALE * (Math.abs(chg) - GameConstants.MAGNET_TRIGGER);
        if (up) {
            prob += GameConstants.SEAL_STREAK_BONUS * q.getLimitUpStreak();
        }
        if (rng.nextDouble() >= prob) {
            return;
        }
        q.setSealed(up ? SealState.UP : SealState.DOWN);
        q.setCurrent(up ? q.limitUpPrice() : q.limitDownPrice());
        q.setSealTicksLeft(GameConstants.SEAL_MIN_TICKS
                + rng.nextInt(GameConstants.SEAL_MAX_TICKS - GameConstants.SEAL_MIN_TICKS + 1));
    }

    private void advanceSealed(Quote q, int tick, Random rng, ShockQueue shocks) {
        boolean up = q.getSealed() == SealState.UP;
        q.setCurrent(up ? q.limitUpPrice() : q.limitDownPrice());
        q.setSealTicksLeft(q.getSealTicksLeft() - 1);
        if (q.getSealTicksLeft() > 0) {
            return;
        }
        if (rng.nextDouble() >= GameConstants.BREAK_PROB) {
            q.setSealTicksLeft(GameConstants.TOTAL_TICKS - tick);
            return;
        }
        q.setSealed(SealState.NONE);
        double magnitude = GameConstants.BREAK_SHOCK_MIN
                + rng.nextDouble() * (GameConstants.BREAK_SHOCK_MAX - GameConstants.BREAK_SHOCK_MIN);
        shocks.enqueue(new PriceShock(q.getStockId(), false, up ? -magnitude : magnitude,
                1, PriceShock.Decay.ONCE));
    }

    /** 开盘跳空落地：newPrice = clamp(round2(prevClose×(1+gapPct)), limitDown, limitUp)；open=high=low=current=newPrice；
     *  |gapPct|>=ONE_WORD_GAP(0.10) → 直接 sealed=UP/DOWN、sealTicksLeft = SEAL_MIN_TICKS + nextInt(26)（一字板首跳锁定）。 */
    public void applyOpenGap(Quote q, double gapPct, Random rng) {
        double newPrice = Math.max(q.limitDownPrice(),
                Math.min(q.limitUpPrice(), Quote.round2(q.getPrevClose() * (1 + gapPct))));
        q.setOpen(newPrice);
        q.setHigh(newPrice);
        q.setLow(newPrice);
        q.setCurrent(newPrice);
        if (Math.abs(gapPct) >= GameConstants.ONE_WORD_GAP) {
            q.setSealed(gapPct > 0 ? SealState.UP : SealState.DOWN);
            q.setSealTicksLeft(GameConstants.SEAL_MIN_TICKS
                    + rng.nextInt(GameConstants.SEAL_MAX_TICKS - GameConstants.SEAL_MIN_TICKS + 1));
        }
    }
}
