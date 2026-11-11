package com.server.zelo.global.config;

import org.springframework.http.HttpMethod;
import com.server.zelo.global.security.JwtAccessDeniedHandler;
import com.server.zelo.global.security.JwtAuthenticationEntryPoint;
import com.server.zelo.global.security.JwtFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 회원가입, 로그인, 패스워드 초기화 허용
                        .requestMatchers("/api/users", "/api/users/login" ,"/api/auth/**", "/api/users/password-reset").permitAll()
                        // 카테고리, 상품 조회 허용
                        .requestMatchers("/api/categories", "/api/products").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products/*").permitAll()
                        // 웹훅 수신
                        .requestMatchers(HttpMethod.POST, "/api/payments/webhook").permitAll()
                        // 프로메테우스, 헬스체크
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
