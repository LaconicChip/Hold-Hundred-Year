package com.holdhundredyear.world;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.common.JwtUtil;
import com.holdhundredyear.game.model.dto.LocalSaveDTO;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 便携版"没有账号系统"那条路（portable 档，H2 文件库落在 target 下，不碰 ./data 的真存档）：
 * <ul>
 *   <li>新机器上 status 报"没有存档"，此时 resume 只能报 NOT_FOUND（前端把按钮置灰）；</li>
 *   <li>创建新游戏会建出唯一的本地玩家，status 立刻看得见进度与模式；</li>
 *   <li>进行中的存档也能被"创建新游戏"清空重播——在线档的 restart 在这里会拒绝，
 *       单机档必须放行，否则玩家点新建只会看到一个报错；</li>
 *   <li>继续游戏发的令牌能解回同一个 userId（免密，但不是没有身份）。</li>
 * </ul>
 * 方法级事务自动回滚，不留存档行。
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:file:./target/hhy-local-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("portable")
@Transactional
class LocalPlayerServiceTest {

    @Autowired
    private LocalPlayerService localPlayerService;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private UserGameStateMapper userGameStateMapper;
    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void freshMachineHasNoSaveAndCannotBeResumed() {
        LocalSaveDTO status = localPlayerService.status();
        assertTrue(status.isLocal(), "portable profile must expose the local entry");
        assertFalse(status.isHasSave(), "a brand new machine has no save yet");
        BizException e = assertThrows(BizException.class, () -> localPlayerService.resume());
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode(), "resume without a save must be NOT_FOUND");
    }

    @Test
    void newGameCreatesTheSingleLocalPlayerAndStatusSeesIt() {
        Map<String, Object> session = localPlayerService.newGame();
        assertNotNull(session.get("token"), "new game must hand back a token");

        LocalSaveDTO status = localPlayerService.status();
        assertTrue(status.isHasSave(), "after creating, the save must be visible");
        assertEquals(1, status.getDay(), "a fresh world starts at day 1");
        assertEquals(GameMode.STANDARD.totalDays(), status.getTotalDays());
        assertEquals(GameMode.STANDARD.getLabel(), status.getModeLabel());
        assertFalse(status.isFinished());
        assertEquals(100000.0, status.getNetAsset(), 0.01, "fresh save shows the initial capital");

        SysUser player = localPlayer();
        assertEquals(session.get("username"), player.getUsername());
        assertEquals(LocalPlayerService.LOCAL_USERNAME, player.getUsername());
    }

    @Test
    void newGameWipesASaveThatIsStillInProgress() {
        Map<String, Object> first = localPlayerService.newGame();
        long userId = ((Number) first.get("userId")).longValue();
        // 把存档推到"进行中"：第 5 日、已收盘。在线档的 restart 在这种状态下会拒绝。
        UserGameState gs = gameState(userId);
        gs.setCurrentDay(5);
        gs.setMarketState("CLOSED");
        userGameStateMapper.updateById(gs);

        Map<String, Object> second = localPlayerService.newGame();
        assertEquals(userId, ((Number) second.get("userId")).longValue(),
                "the portable build keeps ONE local player; a new game reuses that row");

        LocalSaveDTO status = localPlayerService.status();
        assertEquals(1, status.getDay(), "creating a new game must wipe the in-progress save");
        assertTrue(status.isHasSave());
    }

    @Test
    void resumeIssuesATokenThatResolvesBackToTheLocalPlayer() {
        long userId = ((Number) localPlayerService.newGame().get("userId")).longValue();
        Map<String, Object> session = localPlayerService.resume();
        assertEquals(userId, ((Number) session.get("userId")).longValue());
        assertEquals(userId, jwtUtil.parseUserId((String) session.get("token")),
                "the password-less token must carry the same identity the engine keys on");
    }

    private SysUser localPlayer() {
        return sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, LocalPlayerService.LOCAL_USERNAME));
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }
}
