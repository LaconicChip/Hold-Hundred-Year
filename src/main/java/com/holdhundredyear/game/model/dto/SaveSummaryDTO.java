package com.holdhundredyear.game.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 存档架上的一份存档（GET /api/saves 的一行）。
 *
 * <p>这一档没有账号系统：存档就是"一局游戏世界"的全部，玩家从入口页选一份继续玩。
 * 字段全部是给玩家看的，不参与引擎计算。
 */
@Data
public class SaveSummaryDTO {

    /** 存档标识（内部就是 sys_user.id，引擎整套数据都以它为键）。 */
    private long id;

    /** 存档名（sys_user.username）：自动生成"存档 N"，不是登录名。 */
    private String name;

    /** 本局进行到第几个交易日。 */
    private int day;

    /** 本局总交易日数（标准 72 / 快速 36）。 */
    private int totalDays;

    /** 本局模式的可读名（标准模式 / 快速模式）。 */
    private String modeLabel;

    /** 行情状态机原值（NOT_STARTED / MORNING_OPEN / FINISHED …），前端据此决定落地页。 */
    private String marketState;

    /** 本局是否已经终局（通关或爆仓）：终局存档点"继续"会直接进结算页。 */
    private boolean finished;

    /** 最近一次结算的净资产（元）；还没结算过就是初始本金。 */
    private double netAsset;

    /** 最近一次落库时间，存档架按它倒序（最近玩的排最前）。 */
    private LocalDateTime lastPlayedAt;
}
