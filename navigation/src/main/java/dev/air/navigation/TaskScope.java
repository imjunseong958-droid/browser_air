package dev.air.navigation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A known document/form stays scoped to that resource, including its submit and completion pages. */
final class TaskScope {
    private static final Pattern GOOGLE_DOCUMENT = Pattern.compile(
            "^/(forms|document|spreadsheets|presentation|drawings)/(?:u/\\d+/)?d/(e/)?([A-Za-z0-9_-]+)(?:/.*)?$");
    private static final Pattern DRIVE_FILE = Pattern.compile("^/file/d/([A-Za-z0-9_-]+)(?:/.*)?$");
    final WebAddress entry;
    private final String resource;

    TaskScope(WebAddress entry) {
        this.entry = entry;
        resource = resource(entry);
    }

    boolean allows(WebAddress target) {
        return entry.sameSite(target) && (resource == null || resource.equals(resource(target)));
    }

    boolean isDocument() { return resource != null; }

    private static String resource(WebAddress address) {
        if (address == null) return null;
        if (address.host().equals("docs.google.com")) {
            Matcher match = GOOGLE_DOCUMENT.matcher(address.normalizedPath());
            if (match.matches()) return match.group(1) + ":" + (match.group(2) == null ? "" : "e:") + match.group(3);
        }
        if (address.host().equals("drive.google.com")) {
            Matcher match = DRIVE_FILE.matcher(address.normalizedPath());
            if (match.matches()) return "file:" + match.group(1);
            if (address.normalizedPath().equals("/open")) {
                String id = address.parameters().get("id");
                if (id != null && id.matches("[A-Za-z0-9_-]+")) return "file:" + id;
            }
        }
        return null;
    }
}
