package dev.air.browser;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;

/** Implements web form UI without adding any browsing controls. */
final class BrowserPrompts implements PromptDelegate {
    private final BrowserActivity activity;
    private final Map<GeckoResult<PromptResponse>, BasePrompt> pending = new LinkedHashMap<>();
    private final List<AlertDialog> dialogs = new ArrayList<>();
    private final ActivityResultLauncher<Intent> filePicker;
    private FilePrompt filePrompt;
    private GeckoResult<PromptResponse> fileResult;

    BrowserPrompts(BrowserActivity activity) {
        this.activity = activity;
        filePicker = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), returned -> {
            if (filePrompt == null || fileResult == null) return;
            FilePrompt prompt = filePrompt;
            GeckoResult<PromptResponse> result = fileResult;
            filePrompt = null; fileResult = null;
            Intent data = returned.getData();
            if (returned.getResultCode() != android.app.Activity.RESULT_OK || data == null) {
                complete(result, prompt.dismiss());
                return;
            }
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) uris.add(data.getData());
            if (uris.isEmpty()) complete(result, prompt.dismiss());
            else complete(result, prompt.confirm(activity, uris.toArray(new Uri[0])));
        });
    }

    private GeckoResult<PromptResponse> track(BasePrompt prompt) {
        GeckoResult<PromptResponse> result = new GeckoResult<>();
        pending.put(result, prompt);
        return result;
    }
    private void complete(GeckoResult<PromptResponse> result, PromptResponse response) {
        if (pending.remove(result) != null) result.complete(response);
    }
    private AlertDialog.Builder builder(BasePrompt prompt, GeckoResult<PromptResponse> result) {
        return new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.website_prompt) + " · " + activity.visibleHost())
                .setOnCancelListener(dialog -> { if (!prompt.isComplete()) complete(result, prompt.dismiss()); });
    }
    private void show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialogs.add(dialog);
        dialog.setOnDismissListener(ignored -> dialogs.remove(dialog));
        dialog.show();
    }

    void cancelPending() {
        for (var entry : new ArrayList<>(pending.entrySet())) {
            if (!entry.getValue().isComplete()) complete(entry.getKey(), entry.getValue().dismiss());
        }
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        dialogs.clear();
        filePrompt = null; fileResult = null;
    }

    @Override public @Nullable GeckoResult<PromptResponse> onAlertPrompt(@NonNull GeckoSession session, @NonNull AlertPrompt prompt) {
        GeckoResult<PromptResponse> result = track(prompt);
        show(builder(prompt, result).setMessage(prompt.message)
                .setPositiveButton(R.string.okay, (dialog, which) -> complete(result, prompt.dismiss())));
        return result;
    }

    @Override public @Nullable GeckoResult<PromptResponse> onButtonPrompt(@NonNull GeckoSession session, @NonNull ButtonPrompt prompt) {
        GeckoResult<PromptResponse> result = track(prompt);
        show(builder(prompt, result).setMessage(prompt.message)
                .setPositiveButton(R.string.okay, (dialog, which) -> complete(result, prompt.confirm(ButtonPrompt.Type.POSITIVE)))
                .setNegativeButton(R.string.cancel, (dialog, which) -> complete(result, prompt.confirm(ButtonPrompt.Type.NEGATIVE))));
        return result;
    }

    @Override public @Nullable GeckoResult<PromptResponse> onTextPrompt(@NonNull GeckoSession session, @NonNull TextPrompt prompt) {
        GeckoResult<PromptResponse> result = track(prompt);
        EditText input = input(prompt.defaultValue, InputType.TYPE_CLASS_TEXT);
        show(builder(prompt, result).setMessage(prompt.message).setView(input)
                .setPositiveButton(R.string.okay, (dialog, which) -> complete(result, prompt.confirm(input.getText().toString())))
                .setNegativeButton(R.string.cancel, (dialog, which) -> complete(result, prompt.dismiss())));
        return result;
    }

    @Override public @Nullable GeckoResult<PromptResponse> onAuthPrompt(@NonNull GeckoSession session, @NonNull AuthPrompt prompt) {
        GeckoResult<PromptResponse> result = track(prompt);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, 0, padding, 0);
        EditText username = input(prompt.authOptions.username, InputType.TYPE_CLASS_TEXT);
        EditText password = input("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        username.setHint(R.string.username); password.setHint(R.string.password);
        layout.addView(username); layout.addView(password);
        show(builder(prompt, result).setMessage(prompt.message).setView(layout)
                .setPositiveButton(R.string.continue_action, (dialog, which) -> complete(result,
                        prompt.confirm(username.getText().toString(), password.getText().toString())))
                .setNegativeButton(R.string.cancel, (dialog, which) -> complete(result, prompt.dismiss())));
        return result;
    }

    @Override public @Nullable GeckoResult<PromptResponse> onChoicePrompt(@NonNull GeckoSession session, @NonNull ChoicePrompt prompt) {
        GeckoResult<PromptResponse> result = track(prompt);
        List<ChoicePrompt.Choice> choices = new ArrayList<>();
        flatten(prompt.choices, choices);
        String[] labels = choices.stream().map(choice -> choice.label).toArray(String[]::new);
        AlertDialog.Builder dialog = builder(prompt, result).setNegativeButton(R.string.cancel, (d, which) -> complete(result, prompt.dismiss()));
        if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
            boolean[] selected = new boolean[choices.size()];
            for (int i = 0; i < choices.size(); i++) selected[i] = choices.get(i).selected;
            dialog.setMultiChoiceItems(labels, selected, (d, which, checked) -> selected[which] = checked)
                    .setPositiveButton(R.string.okay, (d, which) -> {
                        List<String> ids = new ArrayList<>();
                        for (int i = 0; i < choices.size(); i++) if (selected[i]) ids.add(choices.get(i).id);
                        complete(result, prompt.confirm(ids.toArray(new String[0])));
                    });
        } else {
            dialog.setItems(labels, (d, which) -> complete(result, prompt.confirm(choices.get(which))));
        }
        show(dialog);
        return result;
    }

    private static void flatten(ChoicePrompt.Choice[] source, List<ChoicePrompt.Choice> result) {
        for (ChoicePrompt.Choice choice : source) {
            if (choice.items != null) flatten(choice.items, result);
            else if (!choice.disabled && !choice.separator) result.add(choice);
        }
    }

    @Override public @Nullable GeckoResult<PromptResponse> onDateTimePrompt(@NonNull GeckoSession session, @NonNull DateTimePrompt prompt) {
        GeckoResult<PromptResponse> result = track(prompt);
        String format = switch (prompt.type) {
            case DateTimePrompt.Type.TIME -> "HH:mm";
            case DateTimePrompt.Type.MONTH -> "YYYY-MM";
            case DateTimePrompt.Type.WEEK -> "YYYY-Www";
            case DateTimePrompt.Type.DATETIME_LOCAL -> "YYYY-MM-DDTHH:mm";
            default -> "YYYY-MM-DD";
        };
        EditText input = input(prompt.defaultValue, InputType.TYPE_CLASS_DATETIME);
        input.setHint(format);
        show(builder(prompt, result).setView(input)
                .setPositiveButton(R.string.okay, (dialog, which) -> complete(result, prompt.confirm(input.getText().toString())))
                .setNegativeButton(R.string.cancel, (dialog, which) -> complete(result, prompt.dismiss())));
        return result;
    }

    @Override public @Nullable GeckoResult<PromptResponse> onFilePrompt(@NonNull GeckoSession session, @NonNull FilePrompt prompt) {
        if (filePrompt != null) return GeckoResult.fromValue(prompt.dismiss());
        GeckoResult<PromptResponse> result = track(prompt);
        filePrompt = prompt; fileResult = result;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        if (prompt.mimeTypes != null && prompt.mimeTypes.length > 0) intent.putExtra(Intent.EXTRA_MIME_TYPES, prompt.mimeTypes);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, prompt.type == FilePrompt.Type.MULTIPLE);
        try { filePicker.launch(intent); }
        catch (ActivityNotFoundException ignored) { filePrompt = null; fileResult = null; complete(result, prompt.dismiss()); }
        return result;
    }

    @Override public @Nullable GeckoResult<PromptResponse> onBeforeUnloadPrompt(@NonNull GeckoSession session, @NonNull BeforeUnloadPrompt prompt) {
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW));
    }

    @Override public @Nullable GeckoResult<PromptResponse> onRepostConfirmPrompt(@NonNull GeckoSession session, @NonNull RepostConfirmPrompt prompt) {
        GeckoResult<PromptResponse> result = track(prompt);
        show(builder(prompt, result).setMessage(R.string.resubmit_form)
                .setPositiveButton(R.string.continue_action, (dialog, which) -> complete(result, prompt.confirm(AllowOrDeny.ALLOW)))
                .setNegativeButton(R.string.cancel, (dialog, which) -> complete(result, prompt.confirm(AllowOrDeny.DENY))));
        return result;
    }

    @Override public @Nullable GeckoResult<PromptResponse> onSelectIdentityCredentialProvider(@NonNull GeckoSession session,
            @NonNull IdentityCredential.ProviderSelectorPrompt prompt) {
        GeckoResult<PromptResponse> result = track(prompt);
        String[] labels = new String[prompt.providers.length];
        for (int i = 0; i < labels.length; i++) labels[i] = prompt.providers[i].name + " · " + prompt.providers[i].domain;
        show(builder(prompt, result).setItems(labels, (dialog, which) -> complete(result, prompt.confirm(which)))
                .setNegativeButton(R.string.cancel, (dialog, which) -> complete(result, prompt.dismiss())));
        return result;
    }

    @Override public @Nullable GeckoResult<PromptResponse> onSelectIdentityCredentialAccount(@NonNull GeckoSession session,
            @NonNull IdentityCredential.AccountSelectorPrompt prompt) {
        GeckoResult<PromptResponse> result = track(prompt);
        String[] labels = new String[prompt.accounts.length];
        for (int i = 0; i < labels.length; i++) labels[i] = prompt.accounts[i].email;
        show(builder(prompt, result).setItems(labels, (dialog, which) -> complete(result, prompt.confirm(which)))
                .setNegativeButton(R.string.cancel, (dialog, which) -> complete(result, prompt.dismiss())));
        return result;
    }

    @Override public @Nullable GeckoResult<PromptResponse> onShowPrivacyPolicyIdentityCredential(@NonNull GeckoSession session,
            @NonNull IdentityCredential.PrivacyPolicyPrompt prompt) {
        GeckoResult<PromptResponse> result = track(prompt);
        show(builder(prompt, result).setMessage(prompt.providerDomain + "\n\n" + prompt.privacyPolicyUrl + "\n\n" + prompt.termsOfServiceUrl)
                .setPositiveButton(R.string.continue_action, (dialog, which) -> complete(result, prompt.confirm(true)))
                .setNegativeButton(R.string.cancel, (dialog, which) -> complete(result, prompt.confirm(false))));
        return result;
    }

    private EditText input(String value, int type) {
        EditText input = new EditText(activity);
        input.setInputType(type);
        input.setText(value == null ? "" : value);
        input.setSingleLine(true);
        return input;
    }
}
