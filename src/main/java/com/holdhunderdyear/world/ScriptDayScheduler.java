package com.holdhunderdyear.world;

import com.holdhunderdyear.game.constants.StockDefs;
import com.holdhunderdyear.game.model.enums.GameMode;
import com.holdhunderdyear.game.model.enums.ScriptType;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 剧本日与 IPO 排期掷骰器（spec §4.4 历史名场面、§6.5 打新）。
 * 给定 seed 与 {@link EraScheduler} 产出的 11 个纪元区间，掷出全局剧本日列表（纯函数、无状态），
 * 输出按 gameDay 升序排列，同日按 {@link ScriptType} 声明顺序（ordinal）排列。
 *
 * <p>随机数全部来自 WorldRandom.of(seed, "script")，消费顺序固定（同 seed 必得同结果）：
 * <ol>
 *   <li>熔断日（无随机）：era5.startDay 与 +1，各 1 天；</li>
 *   <li>千股跌停（era3）：n = 2+nextInt(2) → 救市日 pickDay 于 era3 后半段
 *       [start+len/2, end-1] → 跌停候选池校验（无随机；era3 除去救市日及其次日不足 n 时，
 *       救市日回落为 era3.end-1 并重算）→ first = era3.start+nextInt(2)（与救市日/其次日撞车时
 *       防御性翻转为两天中的另一天，无随机）→ n-1 次 pickDay 于 [era3.start, era3.end]
 *       （避开 first 与救市日/救市次日）→ n 个跌停日按 gameDay 升序逐日掷
 *       sealRatio = 0.85+0.10*nextDouble；</li>
 *   <li>救市日（无随机，params 为空）；</li>
 *   <li>千股涨停：救市次日先掷 sealRatio（1 次 nextDouble）→ m = 1+nextInt(2) →
 *       m 次 pickDay 于 era2 → 按挑选顺序逐日掷 sealRatio；</li>
 *   <li>制裁闪崩（era7）：s = 1+nextInt(2) → s 次 pickDay 于 era7 → 按挑选顺序掷
 *       targetStockId = 科技股池（半导体/算力/通信/传媒，按 StockDefs 声明顺序派生）中
 *       nextInt 取一；</li>
 *   <li>黑7月崩盘（era10）：c = 2+nextInt(2)（按窗口容量夹逼）→ c 次 pickDay 于窗口内
 *       （标准模式窗口 = 前半段 [era10.start, era10.start+(len+1)/2-1]，快速模式 =
 *       整个纪元，见 {@code GameMode#isCrashWindowFullEra()}）→ 按挑选顺序逐日掷 sealRatio；</li>
 *   <li>IPO：12 个候选名 Collections.shuffle 洗牌一次 → 逐年（年1/年2/年3）
 *       k = 2+nextInt(2) → k 次 pickDay 于年内（末段留出 IPO_LIFECYCLE_TAIL 天给 D+2/D+4）→ 按挑选顺序依次取名（不重复）并掷
 *       issuePrice = round2(5+25*nextDouble)。</li>
 * </ol>
 * <p>每类剧本日的请求数都在掷骰后按窗口容量夹逼（千股跌停还要额外扣掉救市日及其次日），
 * pickDay 命中已用日期时的重掷循环同样由该 Random 驱动、天然确定，池子见底直接抛
 * IllegalStateException——年长压到 12 交易日/年、短纪元只剩 2~3 天时，撒点既不会把注册
 * 线程卡死，也不会掷出纪元外的日期。
 *
 * <p>夹逼会丢掷骰结果的地方只剩一处，且是数学上装不下：快速模式纪元 3 恒为 4 天，
 * "2 天千股跌停 → 救市 → 次日千股涨停"正好排满整段，第 3 个跌停日没有格子（一年 12 天里
 * 再给纪元 3 加一天，就会有别的纪元跌破 GameMode.getMinEraLen()）。黑7月的窗口改成按
 * 档案铺满整个纪元，正是为了不再白白丢掉已经掷出来的崩盘日。
 */
public class ScriptDayScheduler {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 局内 IPO 名字池（12 个；总 IPO 数 6~9，故局内名字不重复）。 */
    private static final List<String> IPO_NAME_POOL = List.of(
            "雷霆安防", "银河芯动", "九霄云算", "聚宝盆金服", "盘古智驾", "凤凰光学",
            "鲸落生物", "长城数科", "天权航天", "锦鲤锂业", "貔貅支付", "玄武云储");

    /** 打新生命周期：申购日 → D+2 上市 → D+4 结算（IpoService），收官前要留这么多天。 */
    private static final int IPO_LIFECYCLE_TAIL = 4;

    /** 制裁闪崩可命中的科技板块。 */
    private static final Set<String> TECH_SECTORS = Set.of("半导体", "算力", "通信", "传媒");

    /** 科技股目标池：按 StockDefs 声明顺序过滤板块派生，不硬编码 id。 */
    private static final List<Long> TECH_STOCK_IDS = StockDefs.ALL.stream()
            .filter(s -> TECH_SECTORS.contains(s.sector()))
            .map(StockDefs.StockDef::id)
            .toList();

    /** 单个剧本日：gameDay + 类型 + 参数。params 为不可变 Map，paramsJson 直接序列化该 Map。 */
    public record ScriptDayInstance(int gameDay, ScriptType type, Map<String, Object> params) {

        public String paramsJson() {
            return MAPPER.writeValueAsString(params);
        }
    }

    /** 按标准模式撒点（老调用方与测试沿用）。 */
    public List<ScriptDayInstance> roll(long seed, List<EraScheduler.EraInstance> eras) {
        return roll(seed, eras, GameMode.STANDARD);
    }

    /** 按本局模式撒点：三年区间与收官日都随年长变化，剧本日类型一个不减。 */
    public List<ScriptDayInstance> roll(long seed, List<EraScheduler.EraInstance> eras, GameMode mode) {
        Random r = WorldRandom.of(seed, "script");
        GameMode gameMode = mode == null ? GameMode.STANDARD : mode;
        List<ScriptDayInstance> out = new ArrayList<>();

        EraScheduler.EraInstance era2 = eras.get(1);
        EraScheduler.EraInstance era3 = eras.get(2);
        EraScheduler.EraInstance era5 = eras.get(4);
        EraScheduler.EraInstance era7 = eras.get(6);
        EraScheduler.EraInstance era10 = eras.get(9);

        // 1. 熔断日：纪元5 前 2 个交易日
        out.add(new ScriptDayInstance(era5.startDay(), ScriptType.CIRCUIT_BREAKER, Map.of()));
        out.add(new ScriptDayInstance(era5.startDay() + 1, ScriptType.CIRCUIT_BREAKER, Map.of()));

        // 2. 千股跌停（纪元3）：先定救市日，再掷跌停日，跌停日避开救市日及其次日
        int start3 = era3.startDay();
        int end3 = era3.endDay();
        int len3 = end3 - start3 + 1;
        int n = 2 + r.nextInt(2);
        n = Math.min(n, len3 - 1); // 防御：若未来纪元抖动放宽导致 len3 变小，避免 pickDay 候选耗尽
        // 救市日最多落在纪元3 的倒数第二天：千股涨停是"救市次日"，救市日压在最后一天就会把
        // 涨停甩到下一个纪元的第一格。EraScheduler 保证真实排期里 len3 >= 3（三天连戏下限），
        // 此时下界 start3+len3/2 <= end3-1；万一拿到的是更短的纪元（手工构造/未来改档），
        // 上界退回 end3 让链条溢出一天，也绝不因为窗口反序而抛。
        int rescueLo = start3 + len3 / 2;
        int rescueHi = Math.max(end3 - 1, rescueLo);
        int rescueDay = pickDay(r, rescueLo, rescueHi, new HashSet<>());
        if (len3 - blockedCount(rescueDay, start3, end3) < n) {
            rescueDay = rescueHi;
        }
        Set<Integer> blocked = new HashSet<>();
        blocked.add(rescueDay);
        blocked.add(rescueDay + 1);
        int first = start3 + r.nextInt(2);
        if (first == rescueDay || first == rescueDay + 1) {
            first = start3 + (1 - (first - start3));
        }
        blocked.add(first);
        List<Integer> downDays = new ArrayList<>();
        downDays.add(first);
        // 掷骰后按真实可用天数限量：跌停日数不能超过"纪元3 减去救市日及其次日与首日"的余量
        int downPickable = Math.min(n - 1, availableDays(start3, end3, blocked));
        for (int i = 0; i < downPickable; i++) {
            downDays.add(pickDay(r, start3, end3, blocked));
        }
        Collections.sort(downDays);
        for (int day : downDays) {
            out.add(new ScriptDayInstance(day, ScriptType.LIMIT_DOWN_ALL,
                    params("sealRatio", 0.85 + 0.10 * r.nextDouble())));
        }

        // 3. 救市日
        out.add(new ScriptDayInstance(rescueDay, ScriptType.RESCUE, Map.of()));

        // 4. 千股涨停：救市次日 + 纪元2 内 1~2 天
        out.add(new ScriptDayInstance(rescueDay + 1, ScriptType.LIMIT_UP_ALL,
                params("sealRatio", 0.85 + 0.10 * r.nextDouble())));
        int m = Math.min(1 + r.nextInt(2), era2.endDay() - era2.startDay() + 1);
        Set<Integer> upUsed = new HashSet<>();
        List<Integer> upDays = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            upDays.add(pickDay(r, era2.startDay(), era2.endDay(), upUsed));
        }
        for (int day : upDays) {
            out.add(new ScriptDayInstance(day, ScriptType.LIMIT_UP_ALL,
                    params("sealRatio", 0.85 + 0.10 * r.nextDouble())));
        }

        // 5. 制裁闪崩（纪元7，目标为科技股池）
        int s = Math.min(1 + r.nextInt(2), era7.endDay() - era7.startDay() + 1);
        Set<Integer> sanctionUsed = new HashSet<>();
        List<Integer> sanctionDays = new ArrayList<>();
        for (int i = 0; i < s; i++) {
            sanctionDays.add(pickDay(r, era7.startDay(), era7.endDay(), sanctionUsed));
        }
        for (int day : sanctionDays) {
            out.add(new ScriptDayInstance(day, ScriptType.SANCTION,
                    params("targetStockId", TECH_STOCK_IDS.get(r.nextInt(TECH_STOCK_IDS.size())))));
        }

        // 6. 黑7月崩盘（纪元10）：标准模式崩前半段、后半段阴跌；快速模式整个纪元都是窗口
        int len10 = era10.endDay() - era10.startDay() + 1;
        int crashWindow = gameMode.isCrashWindowFullEra() ? len10 : (len10 + 1) / 2;
        // 崩盘日只能落在窗口内：短纪元（快速模式实测只有 2~3 天）时按窗口容量夹逼
        int c = Math.min(2 + r.nextInt(2), crashWindow);
        Set<Integer> crashUsed = new HashSet<>();
        List<Integer> crashDays = new ArrayList<>();
        for (int i = 0; i < c; i++) {
            crashDays.add(pickDay(r, era10.startDay(), era10.startDay() + crashWindow - 1, crashUsed));
        }
        for (int day : crashDays) {
            out.add(new ScriptDayInstance(day, ScriptType.CRASH_WEEK,
                    params("sealRatio", 0.85 + 0.10 * r.nextDouble())));
        }

        // 7. IPO：每年 2~3 次，名字从洗牌后的池中顺序取
        List<String> names = new ArrayList<>(IPO_NAME_POOL);
        Collections.shuffle(names, r);
        int nameIdx = 0;
        int dyp = gameMode.getDaysPerYear();
        int[][] yearRanges = {{1, dyp}, {dyp + 1, dyp * 2}, {dyp * 2 + 1, gameMode.totalDays()}};
        for (int[] range : yearRanges) {
            // 申购日要留出 D+2 上市、D+4 结算的日子：收官前 IPO_LIFECYCLE_TAIL 天不再排新申购，
            // 否则玩家点完申购就终局，钱冻在一只既没上市也没结算的新股里（短局尤其容易撞上）
            int hi = Math.min(range[1], gameMode.totalDays() - IPO_LIFECYCLE_TAIL);
            int k = Math.min(2 + r.nextInt(2), hi - range[0] + 1);
            Set<Integer> ipoUsed = new HashSet<>();
            List<Integer> ipoDays = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                ipoDays.add(pickDay(r, range[0], hi, ipoUsed));
            }
            for (int day : ipoDays) {
                double issuePrice = Math.round((5 + 25 * r.nextDouble()) * 100.0) / 100.0;
                out.add(new ScriptDayInstance(day, ScriptType.IPO,
                        params("ipoName", names.get(nameIdx++), "issuePrice", issuePrice)));
            }
        }

        out.sort(Comparator.comparingInt(ScriptDayInstance::gameDay)
                .thenComparingInt(d -> d.type().ordinal()));
        return Collections.unmodifiableList(out);
    }

    /** 在 [lo, hi] 内掷一天，命中已用日期则重掷（确定性），最终登记并返回。 */
    private static int pickDay(Random r, int lo, int hi, Set<Integer> used) {
        if (availableDays(lo, hi, used) <= 0) {
            throw new IllegalStateException(
                    "script day pool exhausted: range [" + lo + "," + hi + "] already holds " + used.size() + " used days");
        }
        int day;
        do {
            day = lo + r.nextInt(hi - lo + 1);
        } while (used.contains(day));
        used.add(day);
        return day;
    }

    /** [lo, hi] 内还能掷到几天（总天数减去已用且落在区间内的天数）。 */
    private static int availableDays(int lo, int hi, Set<Integer> used) {
        int free = hi - lo + 1;
        for (int day = lo; day <= hi; day++) {
            if (used.contains(day)) {
                free--;
            }
        }
        return free;
    }

    /** {rescueDay, rescueDay+1} 中落在 [lo, hi] 内的天数。 */
    private static int blockedCount(int rescueDay, int lo, int hi) {
        int count = 0;
        if (rescueDay >= lo && rescueDay <= hi) {
            count++;
        }
        if (rescueDay + 1 >= lo && rescueDay + 1 <= hi) {
            count++;
        }
        return count;
    }

    /** 构建不可变参数 Map（LinkedHashMap 固定 JSON 键序）。 */
    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
