package com.mall.module.order.service;

import com.mall.module.order.entity.po.Refund;
import com.mall.module.order.entity.vo.RefundEligibilityVO;
import com.mall.module.order.mapper.RefundMapper;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only delegates. Every database read/insert still reaches the real bean. */
@TestConfiguration(proxyBeanMethods = false)
public class EvaluationProbeConfiguration {

    @Bean
    static ProbeControl evaluationProbeControl() {
        return new ProbeControl();
    }

    @Bean
    static BeanPostProcessor evaluationDelegates(ProbeControl control) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof RefundMapper) {
                    return Proxy.newProxyInstance(RefundMapper.class.getClassLoader(),
                            new Class<?>[]{RefundMapper.class}, (proxy, method, arguments) -> {
                                Object result;
                                try {
                                    result = method.invoke(bean, arguments);
                                } catch (InvocationTargetException error) {
                                    throw error.getCause();
                                }
                                if ("insert".equals(method.getName()) && arguments != null && arguments.length == 1
                                        && arguments[0] instanceof Refund refund && control.rollbackOrder != null
                                        && control.rollbackOrder.equals(refund.getOrderId())) {
                                    if (!TransactionSynchronizationManager.isActualTransactionActive()
                                            || !(result instanceof Integer count) || count != 1) {
                                        throw new ProbeSafetyException();
                                    }
                                    control.realInsertObserved.set(true);
                                    // Throw only AFTER the actual insert returned successfully.
                                    throw new ProbeRollbackException();
                                }
                                return result;
                            });
                }
                if (bean instanceof RefundEligibilityService) {
                    return Proxy.newProxyInstance(RefundEligibilityService.class.getClassLoader(),
                            new Class<?>[]{RefundEligibilityService.class}, (proxy, method, arguments) -> {
                                Object result;
                                try {
                                    result = method.invoke(bean, arguments);
                                } catch (InvocationTargetException error) {
                                    throw error.getCause();
                                }
                                if ("check".equals(method.getName()) && arguments != null && arguments.length == 1
                                        && control.concurrentOrder != null && control.concurrentOrder.equals(arguments[0])) {
                                    if (!(result instanceof RefundEligibilityVO receipt) || !receipt.isEligible()
                                            || receipt.isRefundExists()
                                            || !TransactionSynchronizationManager.isActualTransactionActive()) {
                                        throw new ProbeSafetyException();
                                    }
                                    control.qualifiedReads.incrementAndGet();
                                    // execute() calls this ordinary read BEFORE selectByIdForUpdate.
                                    // Both real repeatable-read views exist before either lock is taken.
                                    control.barrier.await(20, TimeUnit.SECONDS);
                                }
                                return result;
                            });
                }
                return bean;
            }
        };
    }

    static final class ProbeControl {
        volatile Long rollbackOrder;
        volatile Long concurrentOrder;
        volatile CyclicBarrier barrier;
        final AtomicBoolean realInsertObserved = new AtomicBoolean();
        final AtomicInteger qualifiedReads = new AtomicInteger();

        void rollback(Long orderId) {
            rollbackOrder = orderId;
        }

        void concurrent(Long orderId) {
            barrier = new CyclicBarrier(2);
            concurrentOrder = orderId;
        }

        void clear() {
            rollbackOrder = null;
            concurrentOrder = null;
            barrier = null;
        }
    }

    static final class ProbeRollbackException extends RuntimeException { }
    static final class ProbeSafetyException extends RuntimeException { }
}
