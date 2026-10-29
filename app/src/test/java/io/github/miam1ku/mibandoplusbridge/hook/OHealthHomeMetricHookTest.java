// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class OHealthHomeMetricHookTest {
    public static class Card {
        public View r;
        boolean valid = true;
        int refreshes;
        public boolean i() { return valid; }
        public void o() { refreshes++; ((TextView) r).setText("2800"); }
    }
    public static class StepCard extends Card {}
    public static class FailingCard extends Card {
        @Override public void o() { throw new IllegalStateException("QUERY_UNAVAILABLE"); }
    }

    @After public void clearQueuedRefreshes() {
        android.os.Handler handler = ReflectionHelpers.getStaticField(OHealthHomeMetricHook.class, "main");
        if (handler != null) handler.removeCallbacksAndMessages(null);
        java.util.Map<Object, Boolean> cards = ReflectionHelpers.getStaticField(OHealthHomeMetricHook.class, "CARDS");
        cards.clear();
        ReflectionHelpers.setStaticField(OHealthHomeMetricHook.class, "reloadPosted", false);
        ReflectionHelpers.setStaticField(OHealthHomeMetricHook.class, "modern", false);
        ReflectionHelpers.setStaticField(OHealthHomeMetricHook.class, "main", null);
    }

    @Test public void boundNativeCardReadsAgainAndRedrawsThroughItsOwnRefresh() throws Exception {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            TextView view = new TextView(controller.get()); view.setText("0");
            controller.get().setContentView(view);
            assertTrue(view.isAttachedToWindow());
            Card card = new StepCard(); card.r = view;
            OHealthHomeMetricHook.reload610(card);
            assertEquals(1, card.refreshes);
            assertEquals("2800", view.getText().toString());
        }
    }

    @Test public void detachedViewsAndDestroyedCardsDoNotStartNewQueries() throws Exception {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Card card = new Card(); card.r = new TextView(controller.get());
            OHealthHomeMetricHook.reload610(card);
            assertEquals(0, card.refreshes);
            controller.get().setContentView(card.r);
            card.valid = false;
            OHealthHomeMetricHook.reload610(card);
            assertEquals(0, card.refreshes);
        }
    }

    @Test public void importBurstsCoalesceAndOneFailedCardDoesNotBlockTheRest() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            TextView view = new TextView(controller.get()); controller.get().setContentView(view);
            Card card = new StepCard(); card.r = view;
            Card failed = new FailingCard(); failed.r = view;
            java.util.Map<Object, Boolean> cards = ReflectionHelpers.getStaticField(OHealthHomeMetricHook.class, "CARDS");
            cards.put(card, true); cards.put(failed, true);
            ReflectionHelpers.setStaticField(OHealthHomeMetricHook.class, "modern", true);
            for (int i = 0; i < 20; i++) OHealthHomeMetricHook.requestReload();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(999));
            assertEquals(0, card.refreshes);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1));
            assertEquals(1, card.refreshes);
            OHealthHomeMetricHook.requestReload();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
            assertEquals(2, card.refreshes);
        }
    }
}
