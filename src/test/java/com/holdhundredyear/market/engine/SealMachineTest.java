package com.holdhundredyear.market.engine;

import com.holdhundredyear.game.constants.GameConstants;
import com.holdhundredyear.game.model.enums.SealState;
import com.holdhundredyear.market.engine.PriceShock.Decay;
import com.holdhundredyear.market.quote.Quote;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 磁吸/封板/炸板状态机验证（P6 确定性：统计断言覆盖 seed 1..5 全过、不挑种子；
 * 确定性断言用桩随机源精确到值）。
 *
 * <p>统计效应量（prevClose=100，20 跳 apply，每种子 400 试验）：
 * +7% 处单跳封板概率 = 0.06 + 2.0×0.005 = 0.07，20 跳累计约 77%，远高于 50% 阈值；
 * streak=2 单跳 0.11、20 跳约 90%，对 streak=0 的差距约 13pp，逐种子稳定。
 * 5%（&lt;6.5%）不进入掷骰分支，封板率恒为 0。跌停方向全对称。
 */
class SealMachineTest {

    private static final long[] SEEDS = {1, 2, 3, 4, 5};
    private static final int TICKS = 20;
    private static final int TRIALS = 400;

    private final SealMachine machine = new SealMachine();

    // ---------- 造数与桩 ----------

    /** prevClose=100 的普通股（涨停 110.00 / 跌停 90.00），stockId 固定 42。 */
    private Quote quote(double current) {
        Quote q = new Quote();
        q.setStockId(42L);
        q.setPrevClose(100.0);
        q.setCurrent(current);
        q.setOpen(100.0);
        q.setHigh(Math.max(100.0, current));
        q.setLow(Math.min(100.0, current));
        return q;
    }

    private Quote quote(double current, int limitUpStreak) {
        Quote q = quote(current);
        q.setLimitUpStreak(limitUpStreak);
        return q;
    }

    /** 每种子一条 Random 流跑 trials 个独立试验（各 20 跳 apply），返回发生过封板的试验数。 */
    private int countSealedWithinTicks(double current, int limitUpStreak, long seed, int trials) {
        Random rng = new Random(seed * 1_000 + 1);
        int sealedCount = 0;
        for (int trial = 0; trial < trials; trial++) {
            Quote q = quote(current, limitUpStreak);
            boolean everSealed = false;
            for (int tick = 0; tick < TICKS; tick++) {
                machine.apply(q, tick, rng, new ShockQueue());
                if (q.getSealed() != SealState.NONE) {
                    everSealed = true;
                }
            }
            if (everSealed) {
                sealedCount++;
            }
        }
        return sealedCount;
    }

    /** 按序吐出预设 double 的桩随机源（耗尽后重复最后一个）；nextInt 被消费即失败。 */
    private static final class StubRandom extends Random {
        private final double[] doubles;
        private int next;

        StubRandom(double... doubles) {
            this.doubles = doubles;
        }

        @Override
        public double nextDouble() {
            return doubles[Math.min(next++, doubles.length - 1)];
        }

        @Override
        public int nextInt(int bound) {
            throw new AssertionError("本测试不应消费 nextInt");
        }
    }

    /** 消费任何随机数即失败的随机源：验证“无掷骰”路径。 */
    private Random noDice() {
        return new Random() {
            @Override
            public double nextDouble() {
                throw new AssertionError("|chg|<6.5% 未触发磁吸，不得掷骰");
            }

            @Override
            public int nextInt(int bound) {
                throw new AssertionError("|chg|<6.5% 未触发磁吸，不得掷骰");
            }
        };
    }

    /** 捕获入队 shock 的队列：断言炸板冲击的精确参数用。 */
    private static final class CapturingQueue extends ShockQueue {
        PriceShock captured;

        @Override
        public void enqueue(PriceShock shock) {
            captured = shock;
            super.enqueue(shock);
        }
    }

    // ---------- 1. 磁吸触发封板（统计） + 7. 跌停方向对称 ----------

    @Test
    void sevenPercentUpSealsMoreThanHalfWithinTwentyTicks() {
        for (long seed : SEEDS) {
            int sealed = countSealedWithinTicks(107.0, 0, seed, TRIALS);
            assertTrue(sealed > TRIALS / 2,
                    "seed " + seed + ": +7% 处 20 跳封板率 " + (sealed / (double) TRIALS) + " 必须 > 50%");
        }
    }

    @Test
    void sevenPercentDownSealsMoreThanHalfWithinTwentyTicks() {
        for (long seed : SEEDS) {
            int sealed = countSealedWithinTicks(93.0, 0, seed, TRIALS);
            assertTrue(sealed > TRIALS / 2,
                    "seed " + seed + ": -7% 处 20 跳封板率 " + (sealed / (double) TRIALS)
                            + " 必须 > 50%（跌停方向对称）");
        }
    }

    @Test
    void fivePercentNeverRollsSealDiceInEitherDirection() {
        assertEquals(0, countSealedWithNoDice(105.0), "+5% < 6.5% 磁吸阈值：封板率必须恰为 0 且不掷骰");
        assertEquals(0, countSealedWithNoDice(95.0), "-5% < 6.5% 磁吸阈值：封板率必须恰为 0 且不掷骰（对称）");
    }

    private int countSealedWithNoDice(double current) {
        Random rng = noDice();
        int sealed = 0;
        for (int trial = 0; trial < 20; trial++) {
            Quote q = quote(current);
            for (int tick = 0; tick < TICKS; tick++) {
                machine.apply(q, tick, rng, new ShockQueue());
                if (q.getSealed() != SealState.NONE) {
                    sealed++;
                }
            }
        }
        return sealed;
    }

    // ---------- 2. 封板钉价 ----------

    @Test
    void sealedUpPinsLimitPriceAndBlocksBuying() {
        Quote q = quote(107.0);
        q.setSealed(SealState.UP);
        q.setSealTicksLeft(30);
        for (int tick = 0; tick < 5; tick++) {
            machine.apply(q, tick, new Random(1), new ShockQueue());
            assertEquals(110.0, q.getCurrent(), 1e-9, "sealed=UP 后每跳钉死涨停价");
            assertEquals(SealState.UP, q.getSealed());
            assertFalse(q.canBuy(), "钉死涨停价后 canBuy 必须为 false（涨停封死买不进）");
        }
    }

    @Test
    void sealedDownPinsLimitPriceAndBlocksSelling() {
        Quote q = quote(93.0);
        q.setSealed(SealState.DOWN);
        q.setSealTicksLeft(30);
        for (int tick = 0; tick < 5; tick++) {
            machine.apply(q, tick, new Random(1), new ShockQueue());
            assertEquals(90.0, q.getCurrent(), 1e-9, "sealed=DOWN 后每跳钉死跌停价");
            assertEquals(SealState.DOWN, q.getSealed());
            assertFalse(q.canSell(), "钉死跌停价后 canSell 必须为 false（跌停封死卖不出）");
        }
    }

    // ---------- 3. 炸板 ----------

    @Test
    void exhaustedUpSealBreaksWithNegativeOnceShock() {
        Quote q = quote(110.0);
        q.setSealed(SealState.UP);
        q.setSealTicksLeft(1);
        CapturingQueue queue = new CapturingQueue();
        // 随机消费顺序：到期判定 0.10（<0.35 命中炸板）→ 幅度 0.5 → -(0.02+0.5×0.02)=-0.03
        machine.apply(q, 10, new StubRandom(0.10, 0.50), queue);

        assertEquals(SealState.NONE, q.getSealed(), "炸板命中后 sealed 回到 NONE");
        assertNotNull(queue.captured, "炸板必须入队一个 PriceShock");
        assertEquals(42L, queue.captured.stockId());
        assertFalse(queue.captured.marketWide(), "炸板是个股冲击，不是全市场");
        assertEquals(-0.03, queue.captured.totalPct(), 1e-12, "UP 方向炸板为负冲击 -[2%,4%] 均匀随机");
        assertEquals(1, queue.captured.totalTicks(), "炸板冲击 1 跳生效");
        assertEquals(Decay.ONCE, queue.captured.decay(), "炸板冲击一次性给足");
        assertEquals(-0.03, queue.consumeStock(42L), 1e-12, "ONCE 冲击首跳（下跳）交付全部幅度");
        assertEquals(0.0, queue.consumeStock(42L), 1e-12, "之后不再有残余");
        assertEquals(0, queue.activeCount());
    }

    @Test
    void exhaustedDownSealBreaksWithPositiveOnceShock() {
        Quote q = quote(90.0);
        q.setSealed(SealState.DOWN);
        q.setSealTicksLeft(1);
        CapturingQueue queue = new CapturingQueue();
        machine.apply(q, 10, new StubRandom(0.10, 0.50), queue);

        assertEquals(SealState.NONE, q.getSealed());
        assertNotNull(queue.captured);
        assertEquals(0.03, queue.captured.totalPct(), 1e-12, "DOWN 方向炸板为正冲击 +[2%,4%]（地天板由此产生）");
        assertEquals(1, queue.captured.totalTicks());
        assertEquals(Decay.ONCE, queue.captured.decay());
    }

    @Test
    void unbrokenSealExtendsToClose() {
        Quote q = quote(110.0);
        q.setSealed(SealState.UP);
        q.setSealTicksLeft(1);
        ShockQueue queue = new ShockQueue();
        machine.apply(q, 10, new StubRandom(0.90), queue); // 0.90 >= 0.35 未命中炸板

        assertEquals(SealState.UP, q.getSealed(), "未炸板则续封");
        assertEquals(GameConstants.TOTAL_TICKS - 10, q.getSealTicksLeft(),
                "续封到收盘：sealTicksLeft = TOTAL_TICKS - tick");
        assertEquals(110.0, q.getCurrent(), 1e-9);
        assertEquals(0, queue.activeCount(), "未炸板不得入队冲击");
    }

    // ---------- 4. 连板加成 ----------

    @Test
    void streakTwoSealsMoreThanStreakZeroWithinTwentyTicks() {
        for (long seed : SEEDS) {
            int plain = countSealedWithinTicks(107.0, 0, seed, TRIALS);
            int hot = countSealedWithinTicks(107.0, 2, seed, TRIALS);
            assertTrue(hot > plain,
                    "seed " + seed + ": streak=2 封板率 " + (hot / (double) TRIALS)
                            + " 必须高于 streak=0 的 " + (plain / (double) TRIALS));
        }
    }

    @Test
    void magnetDriftScalesWithCappedStreakBonusUpOnly() {
        assertEquals(0.0015, machine.magnetDrift(quote(107.0, 0)), 1e-12,
                "streak=0 → 基础磁吸 MAGNET_DRIFT");
        assertEquals(0.0030, machine.magnetDrift(quote(107.0, 2)), 1e-12,
                "streak=2 → MAGNET_DRIFT×(1+0.5×2) = 0.0015×2");
        assertEquals(0.0045, machine.magnetDrift(quote(107.0, 4)), 1e-12,
                "streak=4 → MAGNET_DRIFT×(1+0.5×4) = 0.0015×3");
        assertEquals(0.0045, machine.magnetDrift(quote(107.0, 6)), 1e-12,
                "streak=6 连板数封顶于 STREAK_MAGNET_CAP=4，与 streak=4 同值");
        assertEquals(-0.0015, machine.magnetDrift(quote(93.0, 4)), 1e-12,
                "跌停方向不享连板加成，恒为 -MAGNET_DRIFT");
    }

    // ---------- 5. 一字板 ----------

    @Test
    void openGapTenPercentLocksOneWordBoardAtLimit() {
        Quote up = quote(100.0);
        machine.applyOpenGap(up, 0.10, new Random(1));
        assertEquals(SealState.UP, up.getSealed(), "|gap|>=10% 开盘即封涨停（一字板）");
        assertEquals(110.0, up.getCurrent(), 1e-9);
        assertEquals(up.limitUpPrice(), up.getCurrent(), 1e-9, "钉死涨停价");
        assertEquals(110.0, up.getOpen(), 1e-9);
        assertEquals(110.0, up.getHigh(), 1e-9);
        assertEquals(110.0, up.getLow(), 1e-9);
        assertTrue(up.getSealTicksLeft() >= GameConstants.SEAL_MIN_TICKS
                        && up.getSealTicksLeft() <= GameConstants.SEAL_MAX_TICKS,
                "一字板封板时长在 " + GameConstants.SEAL_MIN_TICKS + "~" + GameConstants.SEAL_MAX_TICKS + " 跳");
        assertFalse(up.canBuy());

        Quote down = quote(100.0);
        machine.applyOpenGap(down, -0.10, new Random(2));
        assertEquals(SealState.DOWN, down.getSealed(), "|gap|>=10% 开盘即封跌停（一字跌停）");
        assertEquals(down.limitDownPrice(), down.getCurrent(), 1e-9, "钉死跌停价");
        assertEquals(90.0, down.getCurrent(), 1e-9);
        assertFalse(down.canSell());

        Quote beyond = quote(100.0);
        machine.applyOpenGap(beyond, 0.15, new Random(3));
        assertEquals(SealState.UP, beyond.getSealed());
        assertEquals(beyond.limitUpPrice(), beyond.getCurrent(), 1e-9, "超出涨停的跳空夹逼到涨停价");
    }

    @Test
    void openGapFivePercentStaysUnsealedAtClampedPrice() {
        Quote q = quote(100.0);
        machine.applyOpenGap(q, 0.05, noDice());
        assertEquals(SealState.NONE, q.getSealed(), "|gap|<10% 不封板");
        assertEquals(105.0, q.getCurrent(), 1e-9, "clamp 后落在 round2(100×1.05)=105");
        assertEquals(105.0, q.getOpen(), 1e-9);
        assertEquals(105.0, q.getHigh(), 1e-9);
        assertEquals(105.0, q.getLow(), 1e-9);
        assertTrue(q.canBuy());
        assertTrue(q.canSell());
    }

    // ---------- 6. magnetDrift 符号与边界 ----------

    @Test
    void magnetDriftSignAndTriggerBoundary() {
        assertEquals(0.0015, machine.magnetDrift(quote(107.0, 0)), 1e-12, "chg=+7% → +0.0015");
        assertEquals(-0.0015, machine.magnetDrift(quote(93.0, 0)), 1e-12, "chg=-7% → -0.0015");
        assertEquals(0.0, machine.magnetDrift(quote(105.0, 0)), 1e-12, "|chg|=5% 未触发 → 0");
        assertEquals(0.0, machine.magnetDrift(quote(95.0, 0)), 1e-12, "|chg|=-5% 未触发 → 0");
        assertEquals(0.0015, machine.magnetDrift(quote(106.5, 0)), 1e-12, "chg 恰为 +6.5% → 触发（>= 含等号）");
        assertEquals(-0.0015, machine.magnetDrift(quote(93.5, 0)), 1e-12, "chg 恰为 -6.5% → 触发（>= 含等号）");
    }
}
