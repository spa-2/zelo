package com.server.zelo.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.server.zelo.domain.order.entity.ActorType;
import com.server.zelo.domain.order.entity.Order;
import com.server.zelo.domain.order.entity.ShippingAddress;
import com.server.zelo.domain.order.repository.OrderRepository;
import com.server.zelo.domain.payment.dto.response.RetryPaymentResponse;
import com.server.zelo.domain.payment.entity.Payment;
import com.server.zelo.domain.payment.repository.PaymentRepository;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.repository.UserRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

// 여러 스레드가 커밋된 데이터를 함께 봐야 해서 @Transactional 대신 테스트가 만든 데이터를 직접 지운다
@SpringBootTest
@ActiveProfiles("test")
class PaymentConcurrencyTest {

    private static final int THREAD_COUNT = 10;

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long userId;
    private Long orderId;

    @AfterEach
    void tearDown() {
        paymentRepository.deleteAll(findPayments(orderId));
        orderRepository.deleteById(orderId);
        userRepository.deleteById(userId);
    }

    @Test
    void 같은_주문에_재결제를_동시에_요청해도_새_결제는_하나만_생긴다() throws InterruptedException {
        // given
        saveFailedOrder();
        Set<String> portoneIds = ConcurrentHashMap.newKeySet();
        AtomicInteger failCount = new AtomicInteger();

        // when
        runConcurrently(() -> {
            try {
                RetryPaymentResponse response = paymentFacade.retryPayment(userId, orderId);
                portoneIds.add(response.portoneId());
            } catch (RuntimeException e) {
                failCount.incrementAndGet();
            }
        });

        // then
        List<Payment> payments = findPayments(orderId);
        assertThat(failCount).hasValue(0);
        assertThat(payments).hasSize(2);
        assertThat(payments).filteredOn(Payment::isReady).hasSize(1);
        assertThat(portoneIds).hasSize(1);
    }

    // 결제창에서 실패한 주문: 주문은 결제 대기로 남고 결제 하나가 FAILED
    private void saveFailedOrder() {
        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.save(
                    User.of("concurrency-" + token() + "@test.dev", "test-password", "동시성 테스트", "01012345678"));
            Order order = orderRepository.save(Order.of(user, "CONCURRENCY-" + token(), "동시성 테스트 주문",
                    ActorType.USER, null, 3_000L, ShippingAddress.of("홍길동", "01012345678", "12345", "테스트 주소")));
            Payment failed = Payment.of(order.getId(), "payment-concurrency-" + token(), 3_000L);
            failed.fail("테스트 결제 실패");
            paymentRepository.save(failed);
            userId = user.getId();
            orderId = order.getId();
        });
    }

    // 모든 스레드를 준비시킨 뒤 같은 순간에 출발시켜 요청이 최대한 겹치게 한다
    private void runConcurrently(Runnable task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();
    }

    private List<Payment> findPayments(Long orderId) {
        return paymentRepository.findAll().stream()
                .filter(payment -> payment.getOrderId().equals(orderId))
                .toList();
    }

    private static String token() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}