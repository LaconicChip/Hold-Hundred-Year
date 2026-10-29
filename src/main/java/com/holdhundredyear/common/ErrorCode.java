package com.holdhundredyear.common;

import lombok.Getter;

/**
 * 业务错误码。编码从 1001 开始，成功 code=0。
 *
 * <p>1014（用户名已存在）与 1017（登录失败）随着账号系统一起删除，空号保留不复用——
 * 老客户端/旧日志里仍可能出现这两个数字，复用会让它们被误读成另一件事。
 */
@Getter
public enum ErrorCode {

    UNAUTHORIZED(1001, "未登录或登录已过期"),
    MARKET_CLOSED(1002, "休市中，无法下单"),
    NOT_TRADING_TIME(1003, "当前时段不可交易"),
    NOT_LOT(1004, "委托数量必须为100股的整数倍"),
    LIMIT_UP_CANNOT_BUY(1005, "涨停封死，买单排不上"),
    LIMIT_DOWN_CANNOT_SELL(1006, "跌停封死，卖不出去"),
    HALTED(1007, "该股票正在停牌核查中"),
    INSUFFICIENT_CASH(1008, "资金不足"),
    INSUFFICIENT_SHARES(1009, "可卖股数不足"),
    STOCK_NOT_FOUND(1010, "股票不存在"),
    MARGIN_EXCEED(1011, "融资额度不足，融资负债不能超过净资产"),
    REPAY_EXCEED(1012, "还款金额超过负债"),
    FINISHED_STATE(1013, "本局已结束，无法操作"),
    PARAM_ERROR(1015, "请求参数错误"),
    INTERNAL_ERROR(1016, "系统开小差了，请稍后重试"),
    NOT_FOUND(1018, "资源不存在"),
    GAME_NOT_FINISHED(1019, "本局尚未结束"),
    SHARK_EXCEED(1020, "高利贷额度不足，已超过可借上限"),
    /** 打新申购日已过（2026-10-25）：旧通知重开申购窗时给一句人话，而不是笼统的"请求参数错误"。 */
    IPO_EXPIRED(1021, "这只新股的申购日已过，今天不能再申购");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
