package com.holdhunderdyear;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.holdhunderdyear.market.mapper")
public class HoldHunderdYearApplication {

    public static void main(String[] args) {
        SpringApplication.run(HoldHunderdYearApplication.class, args);
    }

}
