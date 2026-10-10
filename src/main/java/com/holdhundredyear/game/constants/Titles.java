package com.holdhundredyear.game.constants;

import java.util.List;

/**
 * 玩家成就称号/徽章静态档案（spec §7.7 段位与徽章 / 交接 §5 Task 10.1）。
 * code 为稳定小写 slug 存入 user_title.title_code，name 为玩家可见中文名，
 * kind 区分 "TITLE"（称号）与 "BADGE"（徽章）。
 */
public final class Titles {

    /** 所有徽章/称号（按声明顺序）。 */
    public static final List<Title> ALL = List.of(
            new Title("first-trade", "初入股市", "TITLE"),
            new Title("dodge-limit-down-all", "空仓躲过千股跌停", "BADGE"),
            new Title("five-streak", "抓住 5 连板", "BADGE"),
            new Title("ipo-lottery", "打新中签", "BADGE"),
            new Title("idle-ten-days", "连续10天不操作", "BADGE")
    );

    private Titles() {
    }

    /** 单个徽章/称号定义。 */
    public record Title(String code, String name, String kind) {
    }
}