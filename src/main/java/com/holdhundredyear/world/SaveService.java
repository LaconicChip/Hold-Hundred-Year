package com.holdhundredyear.world;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.BizException;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.common.JwtUtil;
import com.holdhundredyear.game.model.dto.SaveSummaryDTO;
import com.holdhundredyear.game.model.enums.GameMode;
import com.holdhundredyear.game.model.enums.MarketState;
import com.holdhundredyear.market.GameEndService;
import com.holdhundredyear.market.entity.SysUser;
import com.holdhundredyear.market.entity.UserDailySnapshot;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.UserDailySnapshotMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import com.holdhundredyear.ws.WsSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 存档架：这一版没有账号系统，入口页只做四件事——列存档、建存档、进存档、删存档。
 *
 * <p>为什么仍以 {@code sys_user} 一行代表一份存档：整套游戏数据（持仓、行情状态、快照、
 * 日K、消息…）都以 user_id 为键，引擎与结算不关心玩家是怎么进来的。所以"存档"就是
 * 一行固定/自动命名的 {@code sys_user} + 一个随机不可知的密码散列——<b>没有任何登录入口</b>，
 * 进入只靠 {@link JwtUtil} 签发的令牌。
 *
 * <p>这是<b>单机存档游戏</b>：标准包（MySQL）与便携包（H2）行为完全一致，局域网里的朋友
 * 打开同一个页面会看到同一张存档架。
 */
@Service
public class SaveService {

    private static final Logger log = LoggerFactory.getLogger(SaveService.class);

    /** 自动存档名的前缀：玩家在入口页看到的就是"存档 1 / 存档 2…"。 */
    static final String NAME_PREFIX = "存档 ";

    private final SysUserMapper sysUserMapper;
    private final UserGameStateMapper userGameStateMapper;
    private final UserDailySnapshotMapper userDailySnapshotMapper;
    private final WorldSeeder worldSeeder;
    private final GameEndService gameEndService;
    private final WsSessionRegistry wsSessionRegistry;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public SaveService(SysUserMapper sysUserMapper,
                       UserGameStateMapper userGameStateMapper,
                       UserDailySnapshotMapper userDailySnapshotMapper,
                       WorldSeeder worldSeeder,
                       GameEndService gameEndService,
                       WsSessionRegistry wsSessionRegistry,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil) {
        this.sysUserMapper = sysUserMapper;
        this.userGameStateMapper = userGameStateMapper;
        this.userDailySnapshotMapper = userDailySnapshotMapper;
        this.worldSeeder = worldSeeder;
        this.gameEndService = gameEndService;
        this.wsSessionRegistry = wsSessionRegistry;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /** 存档架：全部存档，最近玩过的排最前。 */
    public List<SaveSummaryDTO> list() {
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .orderByDesc(SysUser::getId));
        List<SaveSummaryDTO> out = new ArrayList<>(users.size());
        for (SysUser user : users) {
            SaveSummaryDTO summary = summaryOf(user);
            if (summary != null) {
                out.add(summary);
            }
        }
        out.sort(Comparator.comparing(SaveSummaryDTO::getLastPlayedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /**
     * 新建一份存档（不覆盖任何旧存档）。模式默认标准档：入口页不逼玩家先做选择，
     * 落地后第 1 天的状态栏仍有模式选择器（一局一选的既有约定）。
     */
    public Map<String, Object> create(GameMode mode) {
        // 局域网里两个人同时点"新建"可能撞同一个自动名：唯一索引兜底，撞了就换下一个编号重试。
        for (int attempt = 0; attempt < 3; attempt++) {
            String name = nextName();
            try {
                long userId = worldSeeder.seedUser(name, unusablePassword(),
                        mode == null ? GameMode.STANDARD : mode);
                SysUser user = sysUserMapper.selectById(userId);
                log.info("save created (userId={}, name={})", userId, name);
                return session(user);
            } catch (DuplicateKeyException e) {
                log.info("save name {} was taken concurrently, retrying", name);
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR);
    }

    /** 进入一份存档：签发令牌。存档不存在时报 NOT_FOUND。 */
    public Map<String, Object> open(long saveId) {
        SysUser user = sysUserMapper.selectById(saveId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return session(user);
    }

    /** 删除一份存档：连同该存档的全部游戏数据一起清掉（不可恢复）。 */
    public void delete(long saveId) {
        gameEndService.purgeSave(saveId);
        // 存档被删后调度器不能再推进它：先从在线集合摘掉，否则每 100ms 一拍都会
        // getOrLoad 抛 NOT_FOUND、日志刷屏（前端下一次请求会 401 回存档架）。
        wsSessionRegistry.unregister(saveId);
        log.info("save deleted (userId={})", saveId);
    }

    private SaveSummaryDTO summaryOf(SysUser user) {
        UserGameState gs = gameState(user.getId());
        if (gs == null) {
            return null;
        }
        GameMode mode = GameMode.of(gs.getGameMode());
        MarketState state = MarketState.valueOf(gs.getMarketState());
        SaveSummaryDTO dto = new SaveSummaryDTO();
        dto.setId(user.getId());
        dto.setName(user.getUsername());
        dto.setDay(gs.getCurrentDay());
        dto.setTotalDays(mode.totalDays());
        dto.setModeLabel(mode.getLabel());
        dto.setMarketState(gs.getMarketState());
        dto.setFinished(state == MarketState.FINISHED || state == MarketState.BANKRUPT);
        dto.setNetAsset(netAsset(user, gs.getCurrentDay()));
        dto.setLastPlayedAt(gs.getUpdatedAt() == null ? user.getCreatedAt() : gs.getUpdatedAt());
        return dto;
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

    /** 存档名：从"存档 1"开始找第一个没被占用的编号。 */
    private String nextName() {
        for (int i = 1; i < Integer.MAX_VALUE; i++) {
            String candidate = NAME_PREFIX + i;
            Long count = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, candidate));
            if (count == null || count == 0) {
                return candidate;
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR);
    }

    private Map<String, Object> session(SysUser user) {
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return Map.of("token", jwtUtil.generate(user.getId()),
                "userId", user.getId(),
                "username", user.getUsername());
    }

    /** 存档不设密码：写一个随机 BCrypt 串占位，密码登录这条路根本不存在。 */
    private String unusablePassword() {
        return passwordEncoder.encode(UUID.randomUUID().toString());
    }

    private UserGameState gameState(long userId) {
        return userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, userId));
    }
}
