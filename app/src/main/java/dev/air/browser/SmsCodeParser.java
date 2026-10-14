package dev.air.browser;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses a newly received message locally; never reads an SMS inbox. */
final class SmsCodeParser {
    private static final String CODE = "(?<![A-Za-z0-9])([A-Za-z0-9]{4,10})(?![A-Za-z0-9])";
    private static final String MARKER = "(?:인증\\s*(?:번호|코드)|확인\\s*(?:번호|코드)|"
            + "(?<![A-Za-z])(?:verification\\s*code|security\\s*code|authentication\\s*code|"
            + "login\\s*code|one[ -]?time\\s*(?:password|code)|otp|passcode)(?![A-Za-z]))";
    private static final Pattern AFTER = Pattern.compile(MARKER
            + "(?:\\s*(?:is|는|은))?[\\s:：#=\\[({<\"'·-]{0,16}" + CODE, Pattern.CASE_INSENSITIVE);
    private static final Pattern BEFORE = Pattern.compile(CODE
            + "[\\s\\])}>\"':：-]{0,16}(?:is\\s+(?:your\\s+)?)?" + MARKER, Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKENS = Pattern.compile(CODE);
    private static final Pattern BOUND = Pattern.compile(
            "(?m)^@([A-Za-z0-9.-]+)\\s+#([A-Za-z0-9]{4,10})(?:\\s+@([A-Za-z0-9.-]+))?\\s*$");

    record Codes(List<String> values, boolean unambiguous) {}

    static Codes parse(String message, String host) {
        if (message == null || message.length() > 8192 || host == null) return empty();
        String text = Normalizer.normalize(message, Normalizer.Form.NFKC);
        int boundLines = 0;
        for (String line : text.split("[\\r\\n\\u2028\\u2029]+")) {
            if (line.trim().startsWith("@") && line.contains("#")) boundLines++;
        }
        var bound = BOUND.matcher(text);
        if (bound.find()) {
            String code = bound.group(2);
            // Embedded-frame codes are outside this feature's top-level-page scope.
            if (!bound.group(1).equalsIgnoreCase(host) || bound.group(3) != null
                    || !valid(code) || boundLines != 1 || bound.find()) return empty();
            return new Codes(List.of(code), true);
        }
        if (boundLines != 0) return empty();
        Set<String> labelled = new LinkedHashSet<>();
        for (Pattern pattern : List.of(AFTER, BEFORE)) {
            var match = pattern.matcher(text);
            while (match.find()) {
                String code = match.group(1);
                if (valid(code) && !numberFragment(text, match.start(1), match.end(1))) labelled.add(code);
                if (labelled.size() > 4) return empty();
            }
        }
        if (!labelled.isEmpty()) return new Codes(List.copyOf(labelled), labelled.size() == 1);
        Set<String> candidates = new LinkedHashSet<>();
        var tokens = TOKENS.matcher(text);
        while (tokens.find()) {
            String code = tokens.group(1);
            // Unlabelled words/domains with digits are not plausible code suggestions.
            if (code.matches("[0-9]{4,10}") && !numberFragment(text, tokens.start(1), tokens.end(1))) {
                candidates.add(code);
                if (candidates.size() > 4) return empty();
            }
        }
        return new Codes(List.copyOf(candidates), false);
    }

    static boolean valid(String code) {
        return code != null && code.matches("[A-Za-z0-9]{4,10}")
                && code.chars().anyMatch(c -> c >= '0' && c <= '9');
    }

    private static boolean numberFragment(String text, int start, int end) {
        // Exclude pieces of telephone numbers, dates, decimal amounts and URLs.
        String before = text.substring(Math.max(0, start - 2), start);
        String after = text.substring(end, Math.min(text.length(), end + 2));
        return before.matches(".*[0-9][./,-]") || after.matches("[./,-][0-9].*")
                || (end < text.length() && "년월원".indexOf(text.charAt(end)) >= 0)
                || before.endsWith("/") || before.endsWith("@") || after.matches("\\.[A-Za-z0-9].*");
    }

    private static Codes empty() { return new Codes(List.of(), false); }
}
