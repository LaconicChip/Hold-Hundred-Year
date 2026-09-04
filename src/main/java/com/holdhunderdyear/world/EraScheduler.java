package com.holdhunderdyear.world;

import com.holdhunderdyear.game.constants.EraDefs;
import com.holdhunderdyear.game.model.enums.EraCode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 纪元掷骰器：给定 seed 生成 108 天的 11 个纪元时间表与强度参数（纯函数、无状态）。
 * 随机数消费顺序固定：年分界点（年1 b1,b2,b3 → 年2 len5,b → 年3 c1,c2,c3），
 * 再按纪元 1..11 依次掷 muMkt 与各板块系数（按 EraDefs 声明顺序）。
 */
public class EraScheduler {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final int YEAR1_END = 36;
    private static final int YEAR2_START = 37;
    private static final int YEAR2_END = 72;
    private static final int YEAR3_START = 73;
    private static final int YEAR3_END = 108;
    private static final int MIN_ERA_LEN = 3;

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
        Random r = WorldRandom.of(seed, "era");

        // 年1 分界基准 11/21/29，抖动 ±3/±2/±2
        int[] y1 = clampBoundaries(1, YEAR1_END,
                11 + r.nextInt(7) - 3,
                21 + r.nextInt(5) - 2,
                29 + r.nextInt(5) - 2);
        // 年2：熔断固定 start=37、长度 3~5；蓝筹/贸易战分界基准 56 抖动 ±3
        int len5 = 3 + r.nextInt(3);
        int[] y2 = clampBoundaries(YEAR2_START, YEAR2_END,
                YEAR2_START + len5,
                56 + r.nextInt(7) - 3);
        // 年3 分界基准 84/96/104，抖动 ±2/±2/±1
        int[] y3 = clampBoundaries(YEAR3_START, YEAR3_END,
                84 + r.nextInt(5) - 2,
                96 + r.nextInt(5) - 2,
                104 + r.nextInt(3) - 1);

        int[][] spans = {
                {1, y1[0] - 1},
                {y1[0], y1[1] - 1},
                {y1[1], y1[2] - 1},
                {y1[2], YEAR1_END},
                {YEAR2_START, y2[0] - 1},
                {y2[0], y2[1] - 1},
                {y2[1], YEAR2_END},
                {YEAR3_START, y3[0] - 1},
                {y3[0], y3[1] - 1},
                {y3[1], y3[2] - 1},
                {y3[2], YEAR3_END}};

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
     * 安全网：按升序裁剪分界点，保证每段长度 >= MIN_ERA_LEN 且末段也 >= MIN_ERA_LEN。
     * 在当前抖动幅度下数学上恒为恒等变换，仅作防御。
     */
    private static int[] clampBoundaries(int yearStart, int yearEnd, int... raw) {
        int[] boundaries = new int[raw.length];
        int prev = yearStart;
        for (int i = 0; i < raw.length; i++) {
            int b = Math.min(Math.max(raw[i], prev + MIN_ERA_LEN), yearEnd - MIN_ERA_LEN + 1);
            boundaries[i] = b;
            prev = b;
        }
        return boundaries;
    }

    private static double uniform(Random r, double min, double max) {
        return min + r.nextDouble() * (max - min);
    }
}
