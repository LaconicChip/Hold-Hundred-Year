package com.holdhunderdyear.game.model.dto;

import lombok.Data;

/**
 * 单条打新记录（spec §11 GET /api/me/ipo-records 行结构）。status 为打新状态机原文
 * SUBSCRIBED/PAID/LOST/ABANDONED/SETTLED；profit 仅 SETTLED 有值，未结算返回 0
 * （是否已结算看 status，不靠 profit 区分）。
 */
@Data
public class IpoRecordItemDTO {

    private long id;

    private String time;

    private String ipoName;

    private double issuePrice;

    private String status;

    private double profit;

    private int gameDay;
}
