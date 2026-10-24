package com.holdhundredyear.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 便携包单实例闸门的纯函数与探测逻辑：重复双击不能再把 H2 锁失败包装成
 * "Failed to launch JVM"。
 */
class PortableInstanceGuardTest {

    @Test
    void portableProfileIsDetectedFromEitherSystemPropertyOrEnvStyleValue() {
        assertTrue(PortableInstanceGuard.isPortable("portable"));
        assertTrue(PortableInstanceGuard.isPortable("portable,dev"));
        assertFalse(PortableInstanceGuard.isPortable(""));
        assertFalse(PortableInstanceGuard.isPortable(null));
    }

    @Test
    void portFallsBackTo8080WhenEnvIsMissingOrInvalid() {
        assertEquals(8198, PortableInstanceGuard.portOf("8198"));
        assertEquals(8080, PortableInstanceGuard.portOf(null));
        assertEquals(8080, PortableInstanceGuard.portOf("  "));
        assertEquals(8080, PortableInstanceGuard.portOf("abc"));
        assertEquals(8080, PortableInstanceGuard.portOf("70000"));
        assertEquals(8080, PortableInstanceGuard.portOf("0"));
    }

    @Test
    void lockFileLivesNextToTheH2File() {
        assertEquals(Path.of("C:/game/data").normalize(),
                PortableInstanceGuard.dataDirOf("C:/game/data/hold_year").normalize());
        assertEquals(Path.of("./data").normalize(),
                PortableInstanceGuard.dataDirOf(null).normalize());
    }

    /** 探测只认自家运行时信息；端口上的陌生网页不能被当成"游戏已经在跑"。 */
    @Test
    void serverProbeRequiresTheGameRuntimeInfo() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/runtime/info", exchange -> {
            byte[] body = "{\"code\":0,\"data\":{\"port\":8080,\"portable\":true,\"lanUrls\":[]}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            assertTrue(PortableInstanceGuard.serverUp(port), "a live game server must be detected");
        } finally {
            server.stop(0);
        }
        assertFalse(PortableInstanceGuard.serverUp(port), "a stopped server must not be reported as running");

        HttpServer foreign = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        foreign.createContext("/api/runtime/info", exchange -> {
            byte[] body = "<html>another app</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        foreign.start();
        try {
            assertFalse(PortableInstanceGuard.serverUp(foreign.getAddress().getPort()),
                    "a foreign server on the same port must not be mistaken for the game");
        } finally {
            foreign.stop(0);
        }
    }
}
