import http from 'k6/http';
import { sleep } from 'k6';
import { Trend } from 'k6/metrics';

// 테스트 중 앱 지표(/actuator/prometheus)를 k6가 직접 읽어 단계별로 summary에 남긴다
// 따로 기록 스크립트를 켜고 끌 필요가 없고 테스트가 끝나면 같이 멈춘다 (기록기를 못 꺼서 CSV에 빈 줄이 계속 쌓이는 일이 없음)
// 단계는 시나리오 tags의 stage로 나눈다. 지표를 읽는 요청은 kind 태그가 없어서 도메인 측정에 안 섞인다
//   import { monitor, monitorScenario, serverMetricThresholds } from '../common/server-monitor.js';
//   scenarios: { x10: {...요청, tags: { stage: 'x10' } }, x10_monitor: monitorScenario({ stage: 'x10', startTime, duration }) }
//   thresholds: { ...serverMetricThresholds('x10') }
//   export { monitor };
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// 앱 지표 포트는 9292. k6를 도커로 돌리면 BASE_URL이 host.docker.internal이라 같은 호스트로 맞춘다
export const METRICS_URL = __ENV.METRICS_URL || `${BASE_URL.replace(/:\d+$/, '')}:9292/actuator/prometheus`;
const INTERVAL_SECONDS = Number(__ENV.MONITOR_INTERVAL || 2);

// 이름 → 읽을 줄. system_cpu는 PC 전체 CPU라 k6·DB·Redis가 같은 PC에서 CPU를 쓰는 것도 보인다
const SOURCES = {
    tomcat_busy: /^tomcat_threads_busy_threads\{[^}]*name="http-nio-8080"[^}]*\} (\S+)/m,
    hikari_active: /^hikaricp_connections_active\{[^}]*\} (\S+)/m,
    hikari_pending: /^hikaricp_connections_pending\{[^}]*\} (\S+)/m,
    process_cpu: /^process_cpu_usage\{[^}]*\} (\S+)/m,
    system_cpu: /^system_cpu_usage\{[^}]*\} (\S+)/m,
};
const TRENDS = Object.fromEntries(Object.keys(SOURCES).map((key) => [key, new Trend(`server_${key}`)]));

export function monitor() {
    const res = http.get(METRICS_URL, { tags: { name: 'serverMetrics' }, timeout: '3s' });
    if (res.status === 200) {
        Object.entries(SOURCES).forEach(([key, pattern]) => {
            const match = pattern.exec(res.body);
            if (match) {
                TRENDS[key].add(Number(match[1]));
            }
        });
    }
    sleep(INTERVAL_SECONDS);
}

// 요청 시나리오와 같은 시간에 도는 지표 읽기 시나리오
export function monitorScenario({ stage, startTime = '0s', duration }) {
    return { executor: 'constant-vus', vus: 1, startTime, duration, exec: 'monitor', gracefulStop: '0s', tags: { stage } };
}

// 단계별 서버 지표(평균·최대)를 summary에 남기려고 항상 통과하는 기준을 둔다
export function serverMetricThresholds(stage) {
    return Object.fromEntries(Object.keys(SOURCES).map((key) => [`server_${key}{stage:${stage}}`, ['max>=0']]));
}
