package com.holdhundredyear.game.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 高利贷板块视图（spec §6.6 / §12 GET /api/loan/shark）：欠多少、今天滚多少息、
 * 哪天到期、还能借多少，外加逐笔台账。借与还的响应同形状，前端一套逻辑吃三处。
 */
@Data
public class SharkLoanDTO {

    /** 未清余额合计（恒等于 sys_user.shark_debt）。 */
    private BigDecimal balance;

    /** 今天一天要滚多少利息（各笔按其当前账龄的日息求和）。 */
    private BigDecimal todayInterest;

    /** 今天按什么年化计息（递增后的值，用来把"越拖越贵"演给玩家看）。 */
    private double annualRate;

    /** 最早一笔的到期日；没有在借的笔时为 null。 */
    private Integer dueDay;

    /** 距最早到期日还剩几个交易日（逾期为负）。 */
    private Integer daysToDue;

    /** 当前还能借多少（净资产 4 倍扣掉已借部分）。 */
    private BigDecimal borrowable;

    /** 规则原样下发（额度倍数、利率档位、升档步长、期限），前端只渲染不复写规则。 */
    private double limitRatio;

    private double baseAnnual;

    private double stepAnnual;

    private int stepDays;

    private double maxAnnual;

    private int termDays;

    /** 本局一个游戏年多少个交易日（利息按它折算，前端不自己抄节奏）。 */
    private int daysPerYear;

    /** 板块告示文案（content/shark-lines.txt 的 @WARNING）。 */
    private List<String> warnings;

    /** 逐笔台账，按到期日升序（还款也按这个顺序冲销）。 */
    private List<Tranche> tranches;

    /** 单笔：本金、余额、起算日、到期日、状态。 */
    @Data
    public static class Tranche {

        private Long id;

        private BigDecimal principal;

        private BigDecimal balance;

        private Integer startDay;

        private Integer dueDay;

        private String status;
    }
}
