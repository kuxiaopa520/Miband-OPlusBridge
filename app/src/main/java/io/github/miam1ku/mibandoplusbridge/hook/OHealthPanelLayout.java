// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import io.github.miam1ku.mibandoplusbridge.hook.xposed.XposedHelpers;

/** Keep the native panel, animation and outside-tap dismissal; measure the actual remaining rows. */
final class OHealthPanelLayout {
    private OHealthPanelLayout() {}

    static void compact(ViewGroup sheet, LinearLayout content, View listHost, View list) {
        ViewGroup.LayoutParams layout = sheet.getLayoutParams();
        layout.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        if (layout instanceof ViewGroup.MarginLayoutParams margins) margins.topMargin = 0;
        try { XposedHelpers.findField(layout.getClass(), "gravity").setInt(layout, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); }
        catch (IllegalAccessException unavailable) { throw new IllegalStateException(unavailable); }
        sheet.setLayoutParams(layout);
        ViewGroup.LayoutParams body = content.getLayoutParams();
        body.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        content.setLayoutParams(body);
        for (View view : new View[]{listHost, list}) {
            if (view == null) continue;
            ViewGroup.LayoutParams params = view.getLayoutParams();
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            if (params instanceof LinearLayout.LayoutParams weighted) weighted.weight = 0;
            view.setLayoutParams(params);
        }
        sheet.requestLayout();
    }
}
