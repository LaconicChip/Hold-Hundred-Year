package com.holdhundredyear.game.model.dto;

import lombok.Data;

/**
 * 便携版入口页要显示的本地存档概况（GET /api/auth/local/status）。
 *
 * <p>字段全部是"给玩家看的"，不是给引擎用的：这台机器上只有一个人一份存档，所以这里回答的
 * 问题是"要不要继续这段历史"，而不是"哪个账号在登录"。
 */
@Data
public class LocalSaveDTO {

    /** 后端确实是便携版（不是便携版时这个接口根本不存在，前端收到 404 就走登录/注册）。 */
    private boolean local;

    /** 机器上是否已经有存档：决定"继续游戏"按钮能不能点。 */
    private boolean hasSave;

    /** 存档进行到第几个交易日（无存档时为 0）。 */
    private int day;

    /** 本局总交易日数（用于"第 12 / 72 日"这种展示）。 */
    private int totalDays;

    /** 本局模式的可读名（标准模式 / 快速模式）。 */
    private String modeLabel;

    /** 行情状态机原值（NOT_STARTED / MORNING_OPEN / FINISHED …），前端据此决定落地页。 */
    private String marketState;

    /** 本局是否已经终局（通关或爆仓）：终局的存档点"继续"会直接进结算页。 */
    private boolean finished;

    /** 最近一次结算的净资产（元）；还没结算过就是初始本金。 */
    private double netAsset;
}
