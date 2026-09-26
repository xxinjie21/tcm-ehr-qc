package com.tcm.ehr;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.tcm.ehr.mapper")
/**
 * 应用入口：Spring Boot 启动类，同时开启 Mapper 扫描（com.tcm.ehr.mapper）。
 */
public class TcmEhrApplication {

    /** 启动 Spring 容器；业务逻辑一律不写在这里。 */
    public static void main(String[] args) {
        SpringApplication.run(TcmEhrApplication.class, args);
    }
}
