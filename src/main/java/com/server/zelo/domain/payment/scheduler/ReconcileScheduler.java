package com.server.zelo.domain.payment.scheduler;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.common.monitoring.BusinessMetric;
import com.server.zelo.common.monitoring.BusinessMetrics;
import com.server.zelo.domain.payment.dto.response.PaymentFindTarget;
import com.server.zelo.domain.payment.entity.PaymentStatus;
import com.server.zelo.domain.payment.service.PaymentFacade;
import com.server.zelo.domain.payment.service.PaymentService;
import com.server.zelo.domain.refund.dto.response.RefundFindTarget;
import com.server.zelo.domain.refund.service.RefundFacade;
import com.server.zelo.domain.refund.service.RefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongFunction;

// 웹훅이 오지 않아 결과를 모른 채 남은 결제·환불을 포트원에 다시 조회해 맞춘다
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "zelo.reconciliation.scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReconcileScheduler {
    // 늦은 웹훅을 기다리되 주문 만료(30분) 전에 정리되도록 짧게 둔다
    private static final int STALE_MINUTES = 10;
    private long paymentCursor;
    private long refundCursor;
    private long readyCursor;

    private final RefundFacade refundFacade;
    private final PaymentFacade paymentFacade;
    private final RefundService refundService;
    private final PaymentService paymentService;
    private final BusinessMetrics businessMetrics;

    @Scheduled(fixedDelayString = "${zelo.reconciliation.scheduler.fixed-delay:60000}")
    public void reconcile() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STALE_MINUTES);
        // 포트원이 응답하지 않으면 남은 대사도 같은 결과라 건너뛴다
        if (reconcileConfirmPending(cutoff) && reconcileReady(cutoff)) {
            reconcileRefunds(cutoff);
        }
    }

    private boolean reconcileConfirmPending(LocalDateTime cutoff) {
        PaymentRun run = reconcilePayments("PAYMENT", paymentCursor,
                cursor -> paymentService.findConfirmPendingPayments(cutoff, cursor), paymentFacade::closeConfirmPending);
        paymentCursor = run.cursor();
        return run.completed();
    }

    // 주문 만료는 READY 결제를 포트원에 묻지 않아서 만료 전에 확인한다
    private boolean reconcileReady(LocalDateTime cutoff) {
        PaymentRun run = reconcilePayments("PAYMENT_READY", readyCursor,
                cursor -> paymentService.findReadyPayments(cutoff, cursor), paymentFacade::closeReady);
        readyCursor = run.cursor();
        return run.completed();
    }

    // 포트원 타임아웃으로 중간에 멈추면 completed가 false다
    private PaymentRun reconcilePayments(String target, long cursor,
                                         LongFunction<List<PaymentFindTarget>> finder,
                                         Function<String, PaymentStatus> closer) {
        long start = System.nanoTime();
        List<PaymentFindTarget> payments = finder.apply(cursor);
        // 끝까지 봤으면 처음부터 다시 본다
        if (payments.isEmpty() && cursor > 0) {
            cursor = 0;
            payments = finder.apply(cursor);
        }
        boolean completed = true;
        for (PaymentFindTarget payment : payments) {
            try {
                countResult(target, closer.apply(payment.portoneId()).name());
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.PG_TIMEOUT) {
                    log.warn("포트원 응답 지연으로 정합성 대사를 다음 실행으로 미룸 target={} portoneId={}", target, payment.portoneId());
                    completed = false;
                    break;
                }
                log.warn("결제 정합성 대사 실패 target={} portoneId={} code={}", target, payment.portoneId(), e.getErrorCode().getCode());
                countResult(target, e.getErrorCode().name());
            } catch (RuntimeException e) {
                // 한 건의 실패가 나머지 정리를 막지 않도록 함
                log.error("결제 정합성 대사 실패 target={} portoneId={}", target, payment.portoneId(), e);
                countResult(target, "ERROR");
            }
            cursor = payment.paymentId();
        }
        recordRun(target, payments.size(), start);
        return new PaymentRun(cursor, completed);
    }

    private void countResult(String target, String result) {
        businessMetrics.count(BusinessMetric.RECONCILE_RESULT, "target", target, "result", result);
    }

    private record PaymentRun(long cursor, boolean completed) {
    }

    private void reconcileRefunds(LocalDateTime cutoff) {
        long start = System.nanoTime();
        List<RefundFindTarget> targets = refundService.findRequestedRefunds(cutoff, refundCursor);
        if (targets.isEmpty() && refundCursor > 0) {
            refundCursor = 0;
            targets = refundService.findRequestedRefunds(cutoff, refundCursor);
        }
        for (RefundFindTarget target : targets) {
            try {
                refundFacade.syncCancellation(target.orderId(), target.portoneId());
                businessMetrics.count(BusinessMetric.RECONCILE_RESULT, "target", "REFUND", "result", "CHECKED");
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.PG_TIMEOUT) {
                    log.warn("포트원 응답 지연으로 정합성 대사를 다음 실행으로 미룸 portoneId={}", target.portoneId());
                    break;
                }
                log.warn("환불 정합성 대사 실패 portoneId={} code={}", target.portoneId(), e.getErrorCode().getCode());
                businessMetrics.count(BusinessMetric.RECONCILE_RESULT, "target", "REFUND", "result", e.getErrorCode().name());
            } catch (RuntimeException e) {
                log.error("환불 정합성 대사 실패 portoneId={}", target.portoneId(), e);
                businessMetrics.count(BusinessMetric.RECONCILE_RESULT, "target", "REFUND", "result", "ERROR");
            }
            refundCursor = target.refundId();
        }
        recordRun("REFUND", targets.size(), start);
    }

    // 한 번에 처리할 양(20건)이 실행 간격 안에 끝나는지 확인하려고 실행 시간을 남긴다
    private void recordRun(String target, int size, long start) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        businessMetrics.record(BusinessMetric.RECONCILE_RUN, elapsed, "target", target);
        if (size > 0) {
            log.info("정합성 대사 실행 target={} size={} elapsedMs={}", target, size, elapsed.toMillis());
        }
    }
}