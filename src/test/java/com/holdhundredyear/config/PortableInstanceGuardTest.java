package com.holdhundredyear.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
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

    /** 只要 HTTP 有应答就说明服务已经起来；探测失败不能误判成"已在运行"。 */
    @Test
    void serverProbeUsesHttpNotJustThePort() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/saves", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            assertTrue(PortableInstanceGuard.serverUp(port), "a live server must be detected");
        } finally {
            server.stop(0);
        }
        assertFalse(PortableInstanceGuard.serverUp(port), "a stopped server must not be reported as running");
    }
}
