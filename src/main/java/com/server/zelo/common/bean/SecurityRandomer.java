package com.server.zelo.common.bean;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;

@Configuration
public class SecurityRandomer {

    @Bean
    public SecureRandom secureRandom() {
        return new SecureRandom();
    }

}
