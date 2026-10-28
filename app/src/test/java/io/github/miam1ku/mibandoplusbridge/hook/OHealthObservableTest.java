// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OHealthObservableTest {
    public interface Observer {
        void onSubscribe(Disposable subscription);
        void onNext(Object value);
        void onError(Throwable error);
        void onComplete();
    }
    public static final class Disposable {
        boolean closed;
        public void dispose() { closed = true; }
    }
    public static final class Observable {
        final Disposable subscription = new Disposable();
        final Consumer<Observer> emit;
        Observable(Consumer<Observer> emit) { this.emit = emit; }
        public void subscribe(Observer observer) { observer.onSubscribe(subscription); emit.accept(observer); }
    }

    private Object await(Observable source) throws Exception {
        return OHealthObservable.await(getClass().getClassLoader(), Observer.class,
                Observable.class.getMethod("subscribe", Observer.class), Disposable.class.getMethod("dispose"), source, 30);
    }

    @Test public void readAndWriteResultsAreReturnedAndTheSubscriptionIsReleased() throws Exception {
        Object result = new Object();
        Observable source = new Observable(observer -> observer.onNext(result));
        assertSame(result, await(source));
        assertTrue(source.subscription.closed);
    }

    @Test public void onErrorRetainsThePendingRecordAndReleasesTheSubscription() {
        Observable source = new Observable(observer -> observer.onError(new IllegalStateException("host-error")));
        assertEquals("IMPORT_READ_UNCONFIRMED", assertThrows(IllegalStateException.class, () -> await(source)).getMessage());
        assertTrue(source.subscription.closed);
    }

    @Test public void emptyCompletionIsNotAnAcknowledgement() {
        Observable source = new Observable(Observer::onComplete);
        assertThrows(IllegalStateException.class, () -> await(source));
        assertTrue(source.subscription.closed);
    }

    @Test public void timeoutDisposesTheUnconfirmedHostRequest() {
        Observable source = new Observable(observer -> {});
        assertThrows(IllegalStateException.class, () -> await(source));
        assertTrue(source.subscription.closed);
    }
}
