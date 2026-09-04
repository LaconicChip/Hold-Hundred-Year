package com.holdhunderdyear.game.model.enums;

/**
 * 波动率状态（spec §5.2）：CALM 低波 / NORMAL 常态 / WILD 高波。
 * 对应乘子见 GameConstants.VOL_REGIME（0.6 / 1.0 / 2.0），由剧本事件（Phase 8）切换。
 */
public enum VolRegime {

    CALM,
    NORMAL,
    WILD
}
