package com.holdhundredyear.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhundredyear.common.ErrorCode;
import com.holdhundredyear.common.JwtUtil;
import com.holdhundredyear.market.entity.UserDailySnapshot;
import com.holdhundredyear.market.entity.UserGameState;
import com.holdhundredyear.market.mapper.SysUserMapper;
import com.holdhundredyear.market.mapper.UserDailySnapshotMapper;
import com.holdhundredyear.market.mapper.UserGameStateMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 存档架全链路集成测试（hold_year_test 库，方法级事务自动回滚）。
 *
 * <p>这一版没有账号系统：入口只有"列存档 / 新建 / 进入 / 删除"。这里钉住四件事：
 * 存档架匿名可达；新建不会覆盖旧存档；进入发的是同一份存档的令牌；删除会连同该存档的
 * 全部游戏数据一起清掉，旧令牌当场失效（401）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SaveFlowTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private UserGameStateMapper userGameStateMapper;

    @Autowired
    private UserDailySnapshotMapper userDailySnapshotMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void saveRackAnnouncesNewSave() throws Exception {
        long saveId = createSave(null);

        JsonNode body = json(mockMvc.perform(get("/api/saves")).andExpect(status().isOk()));
        assertEquals(0, body.path("code").asInt(), "save rack must succeed, body=" + body);

        JsonNode row = findSave(body.path("data"), saveId);
        assertNotNull(row, "the new save must appear on the rack, body=" + body);
        assertTrue(row.path("name").asString().startsWith("存档 "),
                "auto-generated name, body=" + row);
        assertEquals(1, row.path("day").asInt());
        assertEquals(72, row.path("totalDays").asInt());
        assertEquals(100000.0, row.path("netAsset").asDouble(), 0.01);
        assertFalse(row.path("finished").asBoolean());
    }

    @Test
    void creatingAgainKeepsTheOldSave() throws Exception {
        long first = createSave(null);
        long second = createSave(null);
        assertTrue(first != second, "each new save is a separate world");

        JsonNode data = json(mockMvc.perform(get("/api/saves"))).path("data");
        assertNotNull(findSave(data, first), "the old save must stay, body=" + data);
        assertNotNull(findSave(data, second), "the new save must be there, body=" + data);
    }

    @Test
    void openIssuesATokenForTheChosenSave() throws Exception {
        long saveId = createSave(null);

        JsonNode body = json(mockMvc.perform(post("/api/saves/" + saveId + "/open"))
                .andExpect(status().isOk()));
        assertEquals(0, body.path("code").asInt(), "open must succeed, body=" + body);
        String token = body.path("data").path("token").asString();
        assertEquals(saveId, jwtUtil.parseUserId(token), "token must carry the chosen save");

        JsonNode me = json(mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));
        assertEquals(saveId, me.path("data").path("userId").asLong());
    }

    @Test
    void openWithQuickModeReportsThirtySixDays() throws Exception {
        long saveId = createSave("QUICK");

        JsonNode body = json(mockMvc.perform(post("/api/saves/" + saveId + "/open"))
                .andExpect(status().isOk()));
        String token = body.path("data").path("token").asString();

        JsonNode state = json(mockMvc.perform(get("/api/market/state")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));
        assertEquals(36, state.path("data").path("totalDays").asInt(),
                "quick mode save must carry its own rhythm, body=" + state);
    }

    @Test
    void deleteWipesTheSaveAndAllItsWorldData() throws Exception {
        long saveId = createSave(null);

        json(mockMvc.perform(delete("/api/saves/" + saveId)).andExpect(status().isOk()));

        assertNull(sysUserMapper.selectById(saveId), "the save row must be gone");
        UserGameState gs = userGameStateMapper.selectOne(new LambdaQueryWrapper<UserGameState>()
                .eq(UserGameState::getUserId, saveId));
        assertNull(gs, "the world clock must be gone");
        Long snapshots = userDailySnapshotMapper.selectCount(new LambdaQueryWrapper<UserDailySnapshot>()
                .eq(UserDailySnapshot::getUserId, saveId));
        assertEquals(0L, snapshots.longValue(), "daily snapshots must be gone");
        JsonNode data = json(mockMvc.perform(get("/api/saves"))).path("data");
        assertNull(findSave(data, saveId), "the deleted save must leave the rack, body=" + data);
    }

    @Test
    void deleteMissingSaveReturnsNotFound() throws Exception {
        JsonNode body = json(mockMvc.perform(delete("/api/saves/99999999"))
                .andExpect(status().isBadRequest()));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), body.path("code").asInt(), "body=" + body);
    }

    @Test
    void accessMeWithoutTokenReturnsUnauthorized() throws Exception {
        JsonNode body = json(mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized()));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), body.path("code").asInt(), "body=" + body);
    }

    @Test
    void tokenOfDeletedSaveIsRejectedAsUnauthorized() throws Exception {
        long saveId = createSave(null);
        String token = json(mockMvc.perform(post("/api/saves/" + saveId + "/open")))
                .path("data").path("token").asString();

        json(mockMvc.perform(delete("/api/saves/" + saveId)).andExpect(status().isOk()));

        JsonNode body = json(mockMvc.perform(get("/api/stocks")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), body.path("code").asInt(),
                "a token of a deleted save must be UNAUTHORIZED, body=" + body);
    }

    private long createSave(String mode) throws Exception {
        String payload = mode == null ? "{}" : MAPPER.writeValueAsString(java.util.Map.of("mode", mode));
        JsonNode body = json(mockMvc.perform(post("/api/saves")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk()));
        assertEquals(0, body.path("code").asInt(), "create must succeed, body=" + body);
        long saveId = body.path("data").path("userId").asLong();
        assertTrue(saveId > 0, "save id must be positive, body=" + body);
        return saveId;
    }

    private static JsonNode findSave(JsonNode data, long saveId) {
        for (JsonNode row : data) {
            if (row.path("id").asLong() == saveId) {
                return row;
            }
        }
        return null;
    }

    private static JsonNode json(ResultActions actions) throws Exception {
        return MAPPER.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
