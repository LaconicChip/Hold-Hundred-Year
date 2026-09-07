package com.holdhunderdyear.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.game.model.dto.AssetsDTO;
import com.holdhunderdyear.game.model.dto.PositionDTO;
import com.holdhunderdyear.game.model.dto.TitleDTO;
import com.holdhunderdyear.game.model.dto.TradeItemDTO;
import com.holdhunderdyear.common.PageResult;
import com.holdhunderdyear.game.model.dto.TradeDTO;
import com.holdhunderdyear.market.MarketStateService;
import com.holdhunderdyear.market.entity.SysUser;
import com.holdhunderdyear.market.entity.UserTitle;
import com.holdhunderdyear.market.mapper.SysUserMapper;
import com.holdhunderdyear.market.mapper.UserTitleMapper;
import com.holdhunderdyear.world.WorldSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 个人资产/持仓/流水/称号查询集成测试（hold_year_test 库，方法级事务自动回滚）。
 * 锁定 spec §11 GET /api/me/{assets,positions,trades,titles} 契约——此前这四端点缺失、
 * 前端被迫走 mock 假数据，本测试防回归。
 */
@SpringBootTest
@Transactional
class MeQueryServiceTest {

    private static final String BCRYPT_HASH = "$2a$10$bcrypthash";

    @Autowired
    private WorldSeeder worldSeeder;

    @Autowired
    private MarketStateService marketStateService;

    @Autowired
    private TradeService tradeService;

    @Autowired
    private MeQueryService meQueryService;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private UserTitleMapper userTitleMapper;

    @Test
    void freshWorldAssetsAreInitialBalanceOnly() {
        long userId = worldSeeder.seedUser("me_a1", BCRYPT_HASH);

        AssetsDTO assets = meQueryService.assets(userId);

        assertEquals(100000.00, assets.getCash(), 1e-9);
        assertEquals(0.0, assets.getMarketValue(), 1e-9);
        assertEquals(0.0, assets.getDebt(), 1e-9);
        assertEquals(100000.00, assets.getNetAsset(), 1e-9);
        assertEquals(0.0, assets.getGuardRatio(), 1e-9, "no debt means guardRatio 0");
    }

    @Test
    void assetsReflectCashDebtAndMarkedToMarketPositions() {
        long userId = worldSeeder.seedUser("me_a2", BCRYPT_HASH);
        marketStateService.startDay(userId);
        buy(userId, 8L, 200);
        setDebt(userId, 10000.00);

        AssetsDTO assets = meQueryService.assets(userId);

        // 200 股 × 18.00 = 3600.00，佣金 5.00（万 3 不足 5 取最低）
        assertEquals(100000.00 - 3605.00, assets.getCash(), 1e-9);
        assertEquals(3600.00, assets.getMarketValue(), 1e-9, "positions marked at board current");
        assertEquals(10000.00, assets.getDebt(), 1e-9);
        assertEquals(assets.getCash() + 3600.00 - 10000.00, assets.getNetAsset(), 1e-9);
        assertEquals((assets.getCash() + 3600.00) / 10000.00, assets.getGuardRatio(), 1e-9);
    }

    @Test
    void positionsListMirrorsHoldingsWithT1Freeze() {
        long userId = worldSeeder.seedUser("me_p1", BCRYPT_HASH);
        marketStateService.startDay(userId);
        buy(userId, 8L, 200);

        List<PositionDTO> rows = meQueryService.positions(userId);

        assertEquals(1, rows.size());
        PositionDTO row = rows.get(0);
        assertEquals(8L, row.getStockId());
        assertEquals("600008", row.getCode());
        assertEquals("牛途证券", row.getName());
        assertEquals(200, row.getShares());
        assertEquals(0, row.getAvailableShares(), "T+1: bought shares are frozen until close");
        assertEquals(18.03, row.getCostPrice(), 1e-9, "avg cost = 3605/200 HALF_UP to 2 decimals");
        assertEquals(18.00, row.getCurrent(), 1e-9);
        assertEquals(3600.00, row.getMarketValue(), 1e-9);
        assertEquals(-6.00, row.getProfit(), 1e-6, "(18.00 - 18.03) x 200 with rounded cost");
    }

    @Test
    void tradesReturnNewestRecordsWithBuySide() {
        long userId = worldSeeder.seedUser("me_t1", BCRYPT_HASH);
        marketStateService.startDay(userId);
        buy(userId, 8L, 100);

        PageResult<TradeItemDTO> page = meQueryService.trades(userId);

        assertEquals(1, page.getTotal());
        TradeItemDTO row = page.getRecords().get(0);
        assertEquals("buy", row.getSide(), "side is lowercase buy/sell");
        assertEquals(8L, row.getStockId());
        assertEquals(100, row.getShares());
        assertEquals(18.00, row.getPrice(), 1e-9);
        assertEquals(1800.00, row.getAmount(), 1e-9);
        assertEquals(5.00, row.getCommission(), 1e-9);
        assertEquals(0.0, row.getTax(), 1e-9, "buy has no stamp tax");
        assertNotNull(row.getTime());
    }

    @Test
    void titlesListUnlockedOnlyWithStaticNames() {
        long userId = worldSeeder.seedUser("me_title1", BCRYPT_HASH);
        UserTitle title = new UserTitle();
        title.setUserId(userId);
        title.setTitleCode("first-trade");
        title.setTitleName("初入股市");
        title.setKind("TITLE");
        title.setGameDay(1);
        userTitleMapper.insert(title);

        List<TitleDTO> rows = meQueryService.titles(userId);

        assertEquals(1, rows.size(), "only unlocked rows are stored, so list mirrors them");
        TitleDTO row = rows.get(0);
        assertEquals("first-trade", row.getId());
        assertEquals("初入股市", row.getName());
        assertTrue(row.isUnlocked());
        assertFalse(row.getName().isBlank());
    }

    private void buy(long userId, long stockId, int shares) {
        TradeDTO dto = new TradeDTO();
        dto.setStockId(stockId);
        dto.setShares(shares);
        tradeService.buy(userId, dto);
    }

    private void setDebt(long userId, double debt) {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getId, userId));
        user.setDebt(BigDecimal.valueOf(debt));
        sysUserMapper.updateById(user);
    }
}
