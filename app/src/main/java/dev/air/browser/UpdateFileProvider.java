package dev.air.browser;

import androidx.core.content.FileProvider;

/** A dedicated provider also avoids OEM issues with directly registering FileProvider. */
public final class UpdateFileProvider extends FileProvider {
    public UpdateFileProvider() { super(R.xml.update_paths); }
}
