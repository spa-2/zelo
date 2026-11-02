package com.server.zelo.common.monitoring;

import com.server.zelo.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 오류가 처음 난 순간 만들어진 시계열은 Prometheus의 increase()가 첫 증가를 놓친다. TOKEN_REUSED처럼 드문 오류일수록 그 첫 번째가 중요하고,
// 재시작하면 시계열이 사라졌다 다시 생겨 배포 때마다 같은 일이 반복된다. 그래서 앱이 뜰 때 모든 ErrorCode를 0으로 미리 등록한다
@Component
@RequiredArgsConstructor
public class ApiErrorMetricsInitializer {

    private final BusinessMetrics businessMetrics;

    @PostConstruct
    public void registerAll() {
        for (ErrorCode code : ErrorCode.values()) {
            businessMetrics.register(BusinessMetric.API_ERROR, "code", code.getCode());
        }
    }
}
