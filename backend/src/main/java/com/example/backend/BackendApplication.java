package com.example.backend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;

@SpringBootApplication
@MapperScan("com.example.backend.mapper")
public class BackendApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(BackendApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(args);
    }

}
