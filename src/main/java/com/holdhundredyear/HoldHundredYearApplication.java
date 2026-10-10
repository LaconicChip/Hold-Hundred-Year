package com.holdhundredyear;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.holdhundredyear.market.mapper")
public class HoldHundredYearApplication {

    public static void main(String[] args) {
        SpringApplication.run(HoldHundredYearApplication.class, args);
    }

}
