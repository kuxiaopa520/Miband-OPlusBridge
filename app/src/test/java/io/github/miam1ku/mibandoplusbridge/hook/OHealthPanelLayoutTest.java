// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class OHealthPanelLayoutTest {
    @Test public void theRemainingNativeRowsMeasureAsABottomPanelInsteadOfFillingTheScreen() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout screen = new FrameLayout(context), sheet = new FrameLayout(context);
        screen.addView(sheet, new FrameLayout.LayoutParams(-1, -1, Gravity.TOP));
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(content, new FrameLayout.LayoutParams(-1, -1));
        View toolbar = new View(context); content.addView(toolbar, new LinearLayout.LayoutParams(-1, 72));
        FrameLayout listHost = new FrameLayout(context); content.addView(listHost, new LinearLayout.LayoutParams(-1, 0, 1));
        var list = new androidx.recyclerview.widget.RecyclerView(context);
        list.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(context));
        list.setAdapter(new androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            @Override public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                View row = new View(context);
                row.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, 320));
                return new androidx.recyclerview.widget.RecyclerView.ViewHolder(row) {};
            }
            @Override public void onBindViewHolder(androidx.recyclerview.widget.RecyclerView.ViewHolder holder, int position) {}
            @Override public int getItemCount() { return 1; }
        });
        listHost.addView(list, new FrameLayout.LayoutParams(-1, -1));
        OHealthPanelLayout.compact(sheet, content, listHost, list);
        screen.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
        screen.layout(0, 0, 500, 900);
        assertEquals(392, sheet.getMeasuredHeight());
        assertEquals(900, sheet.getBottom());
        assertEquals(508, sheet.getTop());
        assertEquals(0, ((LinearLayout.LayoutParams) listHost.getLayoutParams()).weight, 0);
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, content.getLayoutParams().height);
    }
}
