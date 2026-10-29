// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

/** Quick settings labels. Adding the tile wakes the bridge and leaves the link up. */
public final class BandTileState {
    static final int UNAVAILABLE = 0; // Tile.STATE_UNAVAILABLE
    static final int INACTIVE = 1;    // Tile.STATE_INACTIVE
    static final int ACTIVE = 2;      // Tile.STATE_ACTIVE

    record View(int state, String subtitle) {}

    private BandTileState() {}

    static View of(boolean registered, boolean serviceUp, boolean connected, int battery) {
        return of(registered, serviceUp, connected, battery, false);
    }

    static View of(boolean registered, boolean serviceUp, boolean connected, int battery, boolean coexist) {
        if (registered && coexist) {
            return new View(connected ? ACTIVE : INACTIVE, connected
                    ? io.github.miam1ku.mibandoplusbridge.ui.Copy.tileSubtitle(true, true, true, battery)
                    : "等待小米连接 · 点按查看");
        }
        String subtitle = io.github.miam1ku.mibandoplusbridge.ui.Copy.tileSubtitle(registered, serviceUp, connected, battery);
        if (!registered) return new View(UNAVAILABLE, subtitle);
        if (serviceUp && connected) return new View(ACTIVE, subtitle);
        return new View(INACTIVE, subtitle);
    }

    enum Action { WAKE, OPEN_APP }

    static Action click(boolean mayWake) {
        return mayWake ? Action.WAKE : Action.OPEN_APP;
    }
}
