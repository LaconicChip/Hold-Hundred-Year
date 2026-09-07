package com.holdhunderdyear.game.model.dto;

import lombok.Data;

/**
 * 单条已解锁称号/徽章（spec §11 GET /api/me/titles 行结构）。
 * id 即 title_code；unlocked 恒为 true（未解锁不落库、不返回）。
 */
@Data
public class TitleDTO {

    private String id;

    private String name;

    /** 展示用描述（按 kind 生成："成就称号"/"阶段挑战徽章"）。 */
    private String desc;

    /** TITLE 称号 / BADGE 阶段挑战徽章。 */
    private String kind;

    private int gameDay;

    private boolean unlocked;
}
