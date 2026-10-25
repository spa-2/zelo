package com.server.zelo.domain.order.service;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.List;

// DB를 사용하지 않고 실제 TransactionTemplate의 경계와 동기화 콜백을 확인한다.
class OrderTestTransactionManager extends AbstractPlatformTransactionManager {

    final List<String> events = new ArrayList<>();

    @Override
    protected Object doGetTransaction() {
        return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        events.add("begin");
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        events.add("commit");
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        events.add("rollback");
    }
}
