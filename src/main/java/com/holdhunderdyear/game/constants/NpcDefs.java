package com.holdhunderdyear.game.constants;

import java.util.List;

/**
 * NPC 花名册（spec §5）。code 为稳定标识，style 仅作展示；
 * 策略画像（Task 8.4）后续基于 code 扩展。
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

    private NpcDefs() {
    }
}
