package com.holdhundredyear.world;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.common.JwtUtil;
import com.holdhundredyear.game.model.dto.LocalSaveDTO;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.game.model.enums.MarketState;
import com.holdhundredyear.market.GameEndService;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.UserDailySnapshot;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.UserDailySnapshotMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 便携版（"解压即玩"）的本地玩家。<b>这一档没有账号系统</b>：一台机器 = 一个人 = 一份存档，
 * 所以入口只有两个动作——继续游戏、创建新游戏。
 *
 * <p>为什么还留着 sys_user 一行：整套游戏数据都以 user_id 为键（持仓/行情状态/快照/日K…），
 * 引擎与结算不关心这个人是怎么进来的。所以本地玩家就是一行固定用户名
 * （{@value #LOCAL_USERNAME}）+ 一个随机不可知的密码散列——<b>任何人都无法用密码登录他</b>，
 * 入口只发令牌，不校验凭据。账号那套代码（注册/登录/多人排行榜）原样保留在默认档里，
 * 将来要上线部署时把 portable 档关掉就复活，两个版本共用一份引擎。
 *
 * <p>只在 portable 档存在：默认档（MySQL / 局域网多人）里这个 Bean 根本不会注册，
 * {@code /api/auth/local/**} 也就跟着 404，不会给线上环境多开一个"免密登录"的口子。
 */
@Service
@Profile("portable")
public class LocalPlayerService {

    private static final Logger log = LoggerFactory.getLogger(LocalPlayerService.class);

    /** 本地存档的固定用户名：只占住 sys_user 唯一索引并给侧栏显示用，不是登录名。 */
    static final String LOCAL_USERNAME = "单机玩家";

    private final SysUserMapper sysUserMapper;
    private final UserGameStateMapper userGameStateMapper;
    private final UserDailySnapshotMapper userDailySnapshotMapper;
    private final WorldSeeder worldSeeder;
    private final GameEndService gameEndService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public LocalPlayerService(SysUserMapper sysUserMapper,
                              UserGameStateMapper userGameStateMapper,
                              UserDailySnapshotMapper userDailySnapshotMapper,
                              WorldSeeder worldSeeder,
                              GameEndService gameEndService,
                              PasswordEncoder passwordEncoder,
                              JwtUtil jwtUtil) {
        this.sysUserMapper = sysUserMapper;
        this.userGameStateMapper = userGameStateMapper;
        this.userDailySnapshotMapper = userDailySnapshotMapper;
        this.worldSeeder = worldSeeder;
        this.gameEndService = gameEndService;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /** 入口页探测：这台机器上有没有存档、打到第几日、净资产多少、本局什么模式。 */
    public LocalSaveDTO status() {
        LocalSaveDTO dto = new LocalSaveDTO();
        dto.setLocal(true);
        SysUser user = localUser();
        UserGameState gs = user == null ? null : gameState(user.getId());
        if (gs == null) {
            dto.setHasSave(false);
            return dto;
        }
        GameMode mode = GameMode.of(gs.getGameMode());
        MarketState state = MarketState.valueOf(gs.getMarketState());
        dto.setHasSave(true);
        dto.setDay(gs.getCurrentDay());
        dto.setTotalDays(mode.totalDays());
        dto.setModeLabel(mode.getLabel());
        dto.setMarketState(gs.getMarketState());
        dto.setFinished(state == MarketState.FINISHED || state == MarketState.BANKRUPT);
        dto.setNetAsset(netAsset(user, gs.getCurrentDay()));
        return dto;
    }

    /** 继续游戏：为本地存档签发令牌。没有存档时报 NOT_FOUND，由前端把按钮置灰兜住。 */
    public Map<String, Object> resume() {
        SysUser user = localUser();
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return session(user);
    }

    /**
     * 创建新游戏：这台机器第一次打开就建一个本地玩家；已经有存档就<b>清空重播</b>。
     *
     * <p>清空是玩家在一个写清了"第几日、净资产多少、不可恢复"的警告框里点过确认之后才走到
     * 这里的，所以这里允许"进行中也照开"（{@code allowInProgress}）。模式沿用上一局的档案，
     * 玩家落地后仍能在第 1 天的状态栏改（模式是一局一选的既有约定，不在入口再问一遍）。
     */
    public Map<String, Object> newGame() {
        SysUser user = localUser();
        if (user == null) {
            long id = worldSeeder.seedUser(LOCAL_USERNAME, unusablePassword());
            user = sysUserMapper.selectById(id);
            log.info("local player created (userId={})", id);
        } else {
            gameEndService.restart(user.getId(), null, true);
            log.info("local save wiped and reseeded (userId={})", user.getId());
        }
        return session(user);
    }

    private Map<String, Object> session(SysUser user) {
        return Map.of("token", jwtUtil.generate(user.getId()),
                "userId", user.getId(),
                "username", user.getUsername());
    }

    /** 本地玩家不设密码：写一个随机 BCrypt 串占位，密码登录这条路对他永远走不通。 */
    private String unusablePassword() {
        return passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** 最近一次结算的净资产；还没结算过（第 1 天）就是账户余额。 */
    private double netAsset(SysUser user, int currentDay) {
        UserDailySnapshot snapshot = userDailySnapshotMapper.selectOne(
                new LambdaQueryWrapper<UserDailySnapshot>()
                        .eq(UserDailySnapshot::getUserId, user.getId())
                        .le(UserDailySnapshot::getGameDay, currentDay)
                        .orderByDesc(UserDailySnapshot::getGameDay)
                        .last("LIMIT 1"));
        if (snapshot != null && snapshot.getNetAsset() != null) {
            return snapshot.getNetAsset().doubleValue();
        }
        return user.getBalance() == null ? 0.0 : user.getBalance().doubleValue();
    }

    private SysUser localUser() {
        return sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, LOCAL_USERNAME)
                .last("LIMIT 1"));
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }
}
