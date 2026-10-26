package com.stockcat;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.stockcat.mapper")
public class StockCatApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockCatApplication.class, args);
    }
}
