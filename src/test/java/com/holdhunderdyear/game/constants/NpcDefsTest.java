package com.holdhunderdyear.game.constants;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NPC 策略画像（Task 8.4）纯常量校验：CONFIG 键与花名册 code 一一对应、无缺无多；
 * 只在黑7月销户的 NPC 恰为 LAOWANG/XINJIMIN；每画像数值在合理带内。
 */
class NpcDefsTest {

    @Test
    void configKeysCoverEveryNpcDefsCodeExactly() {
        Set<String> codes = NpcDefs.ALL.stream().map(NpcDefs.Npc::code).collect(Collectors.toSet());
        assertEquals(codes, NpcDefs.CONFIG.keySet(),
                "CONFIG keys must match NpcDefs.ALL codes without gap or surplus");
    }

    @Test
    void onlyLaowangAndXinjiminWithdrawInBlackJuly() {
        for (NpcDefs.Npc npc : NpcDefs.ALL) {
            boolean expected = "LAOWANG".equals(npc.code()) || "XINJIMIN".equals(npc.code());
            assertEquals(expected,
                    NpcDefs.CONFIG.get(npc.code()).withdrawInBlackJuly(),
                    "withdrawInBlackJuly flag mismatch for " + npc.code());
        }
    }

    @Test
    void everyConfigBetaAndStyleVolInBand() {
        for (NpcDefs.NpcConfig config : NpcDefs.CONFIG.values()) {
            assertTrue(config.beta() >= 0 && config.beta() <= 2,
                    "beta must be in [0,2], got " + config.beta());
            assertTrue(config.styleVol() > 0,
                    "styleVol must be positive, got " + config.styleVol());
        }
    }
}