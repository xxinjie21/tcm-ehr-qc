package com.tcm.ehr;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.tcm.ehr.mapper")
public class TcmEhrApplication {

    public static void main(String[] args) {
        SpringApplication.run(TcmEhrApplication.class, args);
    }
}
