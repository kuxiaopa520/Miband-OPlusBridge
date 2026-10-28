// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Await a host Rx result without depending on OHealth's obfuscated SyncObserver implementation. */
final class OHealthObservable {
    private OHealthObservable() {}

    static Object await(ClassLoader loader, Class<?> observerClass, Method subscribe, Method dispose,
            Object observable, long timeoutMs) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> bean = new AtomicReference<>();
        AtomicReference<Object> disposable = new AtomicReference<>();
        AtomicBoolean failed = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        Object observer = Proxy.newProxyInstance(loader, new Class<?>[]{observerClass}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "onSubscribe" -> {
                    disposable.set(args[0]);
                    if (closed.get()) dispose.invoke(args[0]);
                }
                case "onNext" -> { bean.compareAndSet(null, args[0]); done.countDown(); }
                case "onError" -> { failed.set(true); done.countDown(); }
                case "onComplete" -> done.countDown();
                case "equals" -> { return proxy == args[0]; }
                case "hashCode" -> { return System.identityHashCode(proxy); }
                case "toString" -> { return "OplusBandObserver"; }
                default -> throw new UnsupportedOperationException("IMPORT_OBSERVER_METHOD");
            }
            return null;
        });
        try {
            subscribe.invoke(observable, observer);
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS) || failed.get() || bean.get() == null) {
                throw new IllegalStateException("IMPORT_READ_UNCONFIRMED");
            }
            return bean.get();
        } finally {
            closed.set(true);
            Object subscription = disposable.get();
            if (subscription != null) dispose.invoke(subscription);
        }
    }
}
