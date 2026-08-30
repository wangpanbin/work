package com.cinema.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI cinemaOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("影院抢票选座系统 API")
                .description("骨架阶段: 认证/影片/场次; W2-W3: 座位图/锁座/支付/超时/推送")
                .version("v0.1.0"));
    }
}
