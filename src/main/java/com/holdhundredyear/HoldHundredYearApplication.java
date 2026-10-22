package com.holdhundredyear;

import com.holdhundredyear.config.PortableInstanceGuard;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.holdhundredyear.market.mapper")
public class HoldHundredYearApplication {

    public static void main(String[] args) {
        // 便携包重复双击时，第二个进程会在 H2 文件锁上失败，玩家只看到 "Failed to launch JVM"。
        // 在 Spring 启动前先判断是否已有实例在跑：有就开窗并安静退出，没有才继续启动。
        if (PortableInstanceGuard.exitIfAlreadyRunning()) {
            return;
        }
        try {
            SpringApplication.run(HoldHundredYearApplication.class, args);
        } catch (Throwable error) {
            // jpackage 的错误框只有一句 Failed to launch JVM，这里留下玩家能看懂的失败说明。
            PortableInstanceGuard.reportStartupFailure(error);
            throw error;
        }
    }

}
