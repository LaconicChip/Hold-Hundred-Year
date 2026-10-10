package com.holdhundredyear.world;

import com.holdhundredyear.game.constants.EraDefs;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.game.model.enums.EraCode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 纪元掷骰器：给定 seed 生成全剧（{@code GameMode#totalDays()} 天）的 11 个纪元
 * 时间表与强度参数（纯函数、无状态）。分界点写在"36 交易日/年"的基准日历里，
 * 掷点时按当前 {@code DAYS_PER_YEAR} 等比映射，改年长只需动 GameConstants 一个数。
 * 随机数消费顺序固定：年分界点（年1 b1,b2,b3 → 年2 len5,b → 年3 c1,c2,c3），
 * 再按纪元 1..11 依次掷 muMkt 与各板块系数（按 EraDefs 声明顺序）。
 */
public class EraScheduler {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 基准日历（EraDefs 纪元表所用）的一年天数与其三年起点。 */
    private static final int BASE_YEAR_DAYS = 36;
    private static final int BASE_YEAR2_START = 37;
    private static final int BASE_YEAR3_START = 73;

    /**
     * 纪元 3（股灾降临）的专属长度下限 = 4 天：股灾戏码要排"≥2 天千股跌停 → 救市 → 次日千股涨停"，
     * 少一天就会出两种难看结果：要么涨停漏到下一个纪元的第一格（玩家在"灾后震荡"开局看到千股涨停），
     * 要么只剩一天跌停、股灾演成一日游。快速模式年长减半到 12 天，这条下限尤其要守住
     * （年1 四段 2+2+4+2 = 10 ≤ 12，抖动仍有 2 天余量）。
     */
    private static final int CRASH_CHAIN_MIN_ERA_LEN = 4;


    public record EraInstance(int eraIndex, EraCode code, int startDay, int endDay,
                              double muMkt, Map<String, Double> sectorCoefs) {

        public String paramsJson() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("muMkt", muMkt);
            params.put("sectorCoefs", sectorCoefs);
            return MAPPER.writeValueAsString(params);
        }
    }

    public List<EraInstance> roll(long seed) {
        return roll(seed, GameMode.STANDARD);
    }

    /** 按本局模式掷纪元排期：快速模式年长减半，11 个纪元仍按序全覆盖。 */
    public List<EraInstance> roll(long seed, GameMode mode) {
        Random r = WorldRandom.of(seed, "era");

        GameMode gameMode = mode == null ? GameMode.STANDARD : mode;
        int dyp = gameMode.getDaysPerYear();
        int year1Start = 1, year1End = dyp;
        int year2Start = dyp + 1, year2End = dyp * 2;
        int year3Start = dyp * 2 + 1, year3End = gameMode.totalDays();

        // 年1 分界基准 11/21/29，抖动 ±3/±2/±2（掷点形状不变，只把结果日等比映射）
        int minEraLen = gameMode.getMinEraLen();
        // 每段的长度下限：年1 第三段（纪元3）要装下三天连戏，单独抬高
        int[] y1Min = {minEraLen, minEraLen, CRASH_CHAIN_MIN_ERA_LEN, minEraLen};
        int[] y2Min = {minEraLen, minEraLen, minEraLen};
        int[] y3Min = {minEraLen, minEraLen, minEraLen, minEraLen};
        int[] y1 = clampBoundaries(year1Start, year1End, y1Min,
                scale(11 + r.nextInt(7) - 3, gameMode),
                scale(21 + r.nextInt(5) - 2, gameMode),
                scale(29 + r.nextInt(5) - 2, gameMode));
        // 年2：熔断纪元长度 3~5 天（不随年长缩放，它本就是"几天"）；蓝筹/贸易战分界基准 56 抖动 ±3
        int len5 = 3 + r.nextInt(3);
        int[] y2 = clampBoundaries(year2Start, year2End, y2Min,
                year2Start + len5,
                scale(56 + r.nextInt(7) - 3, gameMode));
        // 年3 分界基准 84/96/104，抖动 ±2/±2/±1
        int[] y3 = clampBoundaries(year3Start, year3End, y3Min,
                scale(84 + r.nextInt(5) - 2, gameMode),
                scale(96 + r.nextInt(5) - 2, gameMode),
                scale(104 + r.nextInt(3) - 1, gameMode));

        int[][] spans = {
                {year1Start, y1[0] - 1},
                {y1[0], y1[1] - 1},
                {y1[1], y1[2] - 1},
                {y1[2], year1End},
                {year2Start, y2[0] - 1},
                {y2[0], y2[1] - 1},
                {y2[1], year2End},
                {year3Start, y3[0] - 1},
                {y3[0], y3[1] - 1},
                {y3[1], y3[2] - 1},
                {y3[2], year3End}};

        List<EraInstance> eras = new ArrayList<>(11);
        for (int i = 0; i < spans.length; i++) {
            EraDefs.EraDef def = EraDefs.ALL.get(i);
            double muMkt = uniform(r, def.muMin(), def.muMax());
            Map<String, Double> sectorCoefs = new LinkedHashMap<>();
            for (Map.Entry<String, EraDefs.SectorRange> e : def.sectorRanges().entrySet()) {
                sectorCoefs.put(e.getKey(), uniform(r, e.getValue().min(), e.getValue().max()));
            }
            eras.add(new EraInstance(def.eraIndex(), def.code(), spans[i][0], spans[i][1], muMkt, sectorCoefs));
        }
        return eras;
    }

    /**
     * 安全网：按升序裁剪分界点，保证每段长度 >= 本局最短纪元长度且末段同样满足。
     * 在当前抖动幅度下数学上恒为恒等变换，仅作防御。
     */
    /**
     * 把基准日历（{@value #BASE_YEAR_DAYS} 交易日/年）里的某一天，等比映射到当前年长
     * 日历中同一相对位置。抖动在映射前叠加，故随机数消费顺序与基准版本完全一致。
     */
    private static int scale(int baseDay, GameMode mode) {
        int dyp = mode.getDaysPerYear();
        int baseYearStart, yearStart;
        if (baseDay < BASE_YEAR2_START) {
            baseYearStart = 1;
            yearStart = 1;
        } else if (baseDay < BASE_YEAR3_START) {
            baseYearStart = BASE_YEAR2_START;
            yearStart = dyp + 1;
        } else {
            baseYearStart = BASE_YEAR3_START;
            yearStart = dyp * 2 + 1;
        }
        double fraction = (baseDay - baseYearStart) / (double) (BASE_YEAR_DAYS - 1);
        return yearStart + (int) Math.round(fraction * (dyp - 1));
    }

    /**
     * 安全网：按升序裁剪分界点。每段长度不得小于该段自己的下限（{@code minLens} 逐段给出，
     * 段数 = 分界点数 + 1，末段也算），并且每挪一个分界点都要给后面所有段留够总天数。
     * 在当前抖动幅度下多数情况是恒等变换，仅作防御——纪元3 的三天连戏下限就靠它兜住。
     */
    private static int[] clampBoundaries(int yearStart, int yearEnd, int[] minLens, int... raw) {
        int[] boundaries = new int[raw.length];
        int prev = yearStart;
        for (int seg = 0; seg < raw.length; seg++) {
            int tail = 0;
            for (int j = seg + 1; j < minLens.length; j++) {
                tail += minLens[j];
            }
            int b = Math.min(Math.max(raw[seg], prev + minLens[seg]), yearEnd - tail + 1);
            boundaries[seg] = b;
            prev = b;
        }
        return boundaries;
    }

    private static double uniform(Random r, double min, double max) {
        return min + r.nextDouble() * (max - min);
    }
}
