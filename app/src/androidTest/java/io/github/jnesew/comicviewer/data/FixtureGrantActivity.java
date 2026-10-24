package io.github.jnesew.comicviewer.data;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** The fixture's own UID grants access, just as the platform picker does. Test APK only. */
public final class FixtureGrantActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        for (String id : new String[]{"a", "b", "a-series"}) {
            grantUriPermission("io.github.jnesew.comicviewer",
                    DocumentsContract.buildTreeDocumentUri(FixtureDocumentsProvider.AUTHORITY, id),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }
        grantUriPermission("io.github.jnesew.comicviewer",
                DocumentsContract.buildDocumentUri(FixtureDocumentsProvider.AUTHORITY, "m-book"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        finish();
    }
}
