package com.holdhundredyear.game.constants;

import java.util.List;
import java.util.Map;

/**
 * NPC 花名册与策略画像（spec §5 / Task 8.4）。花名册 code 为稳定标识，style 仅作展示；
 * 策略画像在已有 ALL 基础上按 code 追加，勿重建花名册。
 */
public final class NpcDefs {

    public record Npc(String code, String name, String style) {
    }

    public static final List<Npc> ALL = List.of(
            new Npc("DAMA", "张大妈", "追涨杀跌型"),
            new Npc("LAOWANG", "隔壁老王", "高杠杆追热点"),
            new Npc("LAOLI", "保安老李", "定投指数型"),
            new Npc("CHIEF", "首席分析师", "一本正经胡说八道"),
            new Npc("JUSHI", "炒股养家居士", "死捂一只不动"),
            new Npc("LAOQIAN", "拆迁户老钱", "满仓大通银号吃股息"),
            new Npc("XINJIMIN", "95后新基民", "追题材风口"),
            new Npc("CHENGXUYUAN", "辞职炒股的程序猿", "量化幻觉型"));

    /** 单日收益模型：NPC 日收益 = beta·rMkt + driftMean + styleVol·噪声（seeded 可复现）。 */
    public record NpcConfig(double beta, double driftMean, double styleVol,
                            boolean withdrawInBlackJuly) {
    }

    /**
     * 策略画像（Task 8.4）：按 code 全覆盖 8 个 NPC。beta 为对大盘敏感度、driftMean 为
     * 日均风格漂移（负值=常年亏手续费/风格）、styleVol 为每位噪声，withdrawInBlackJuly
     * 仅 LAOWANG/XINJIMIN 在黑7月销户。花名册已存在（Phase 5），本表在其基础上追加。
     */
    public static final Map<String, NpcConfig> CONFIG = Map.of(
            "DAMA", new NpcConfig(1.3, -0.0006, 0.006, false),
            "LAOWANG", new NpcConfig(1.6, -0.0009, 0.010, true),
            "LAOLI", new NpcConfig(0.5, 0.0002, 0.003, false),
            "CHIEF", new NpcConfig(1.2, 0.0, 0.006, false),
            "JUSHI", new NpcConfig(0.6, -0.0001, 0.002, false),
            "LAOQIAN", new NpcConfig(0.5, 0.0004, 0.003, false),
            "XINJIMIN", new NpcConfig(1.5, -0.0008, 0.010, true),
            "CHENGXUYUAN", new NpcConfig(1.3, -0.0007, 0.010, false));

    private NpcDefs() {
    }
}
