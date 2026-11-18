package dev.air.browser;

import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDialog;
import dev.air.browser.databinding.DialogTabsBinding;
import dev.air.browser.databinding.ItemTabBinding;
import java.util.List;

/** The open tabs as a sheet from the bottom of the page: show one, close one, or start a new one. */
final class TabsSheet {
    interface Actions {
        List<BrowserTab> tabs();
        BrowserTab current();
        String label(BrowserTab tab);
        String place(BrowserTab tab);
        void show(BrowserTab tab);
        void close(BrowserTab tab);
        void newTab();
        void openPdf();
    }

    /** About five rows show at once; more scroll. */
    private static final float VISIBLE_ROWS = 5.5f;
    private final AppCompatActivity activity;
    private final Actions actions;
    private AppCompatDialog dialog;
    private DialogTabsBinding ui;

    TabsSheet(AppCompatActivity activity, Actions actions) {
        this.activity = activity;
        this.actions = actions;
    }

    void show() {
        if (dialog != null && dialog.isShowing()) return;
        ui = DialogTabsBinding.inflate(activity.getLayoutInflater());
        dialog = new AppCompatDialog(activity, R.style.Air_Sheet);
        dialog.setContentView(ui.getRoot());
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
        }
        ui.newTabButton.setOnClickListener(view -> { dismiss(); actions.newTab(); });
        ui.openPdfButton.setOnClickListener(view -> { dismiss(); actions.openPdf(); });
        fill();
        dialog.show();
    }

    /** Redraws the list after a tab opens, closes or changes its title while the sheet is showing. */
    void refresh() {
        if (dialog != null && dialog.isShowing()) fill();
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    private void fill() {
        List<BrowserTab> tabs = actions.tabs();
        if (tabs.isEmpty()) { dismiss(); return; }
        ui.tabsTitle.setText(activity.getString(R.string.tabs_count, tabs.size()));
        ui.tabsList.removeAllViews();
        BrowserTab current = actions.current();
        int selected = -1;
        for (BrowserTab tab : tabs) {
            ItemTabBinding row = ItemTabBinding.inflate(activity.getLayoutInflater(), ui.tabsList, false);
            row.tabTitle.setText(actions.label(tab));
            row.tabHost.setText(actions.place(tab));
            row.getRoot().setSelected(tab == current);
            if (tab == current) selected = ui.tabsList.getChildCount();
            row.getRoot().setOnClickListener(view -> { dismiss(); actions.show(tab); });
            row.tabClose.setContentDescription(activity.getString(R.string.close_tab) + " · " + actions.label(tab));
            row.tabClose.setOnClickListener(view -> actions.close(tab));
            ui.tabsList.addView(row.getRoot());
        }
        float density = activity.getResources().getDisplayMetrics().density;
        int rowStep = Math.round(66 * density);
        ViewGroup.LayoutParams params = ui.tabsScroll.getLayoutParams();
        params.height = tabs.size() > VISIBLE_ROWS ? Math.round(VISIBLE_ROWS * rowStep) : ViewGroup.LayoutParams.WRAP_CONTENT;
        ui.tabsScroll.setLayoutParams(params);
        int shown = selected;
        if (shown >= 0) ui.tabsScroll.post(() -> {
            if (ui != null && shown < ui.tabsList.getChildCount()) ui.tabsScroll.scrollTo(0, ui.tabsList.getChildAt(shown).getTop());
        });
    }
}
