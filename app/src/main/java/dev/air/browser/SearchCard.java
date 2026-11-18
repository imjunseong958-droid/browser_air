package dev.air.browser;

import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDialog;
import dev.air.browser.databinding.DialogSearchBinding;
import dev.air.navigation.NavigationPolicy;
import java.util.function.Predicate;

/**
 * The spotlight search card over a blurred screen. One typed query opens Google's SafeSearch text results from English
 * pages; there is no suggestion list or history.
 */
final class SearchCard {
    private SearchCard() {}

    /** Shows the card; {@code open} gets the results address and says whether it opened, which closes the card. */
    static AppCompatDialog show(AppCompatActivity activity, Predicate<String> open) {
        DialogSearchBinding search = DialogSearchBinding.inflate(activity.getLayoutInflater());
        AppCompatDialog dialog = new AppCompatDialog(activity, R.style.Air_Spotlight);
        dialog.setContentView(search.getRoot());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.setBlurBehindRadius(Math.round(28 * activity.getResources().getDisplayMetrics().density));
                window.setAttributes(attributes);
            }
        }
        search.root.setOnClickListener(view -> dialog.dismiss());
        search.terms.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                String terms = text.toString().strip();
                search.results.setVisibility(terms.isEmpty() ? View.GONE : View.VISIBLE);
                search.clearButton.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
                search.googleQuery.setText(terms);
            }
        });
        search.clearButton.setOnClickListener(view -> search.terms.setText(""));
        search.googleButton.setOnClickListener(view -> submit(search, dialog, open));
        search.terms.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submit(search, dialog, open);
            return true;
        });
        dialog.show();
        search.terms.requestFocus();
        return dialog;
    }

    private static void submit(DialogSearchBinding search, AppCompatDialog dialog, Predicate<String> open) {
        String url = NavigationPolicy.searchUrl(search.terms.getText().toString());
        if (url != null && open.test(url)) dialog.dismiss();
    }
}
