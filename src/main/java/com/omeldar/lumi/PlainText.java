package com.omeldar.lumi;

import java.util.regex.Pattern;

/**
 * The safety net under the reply style: Minecraft chat shows plain text, so any markdown that
 * slips past the instructions is taken out before it reaches the player, rather than shown as
 * stray asterisks, hashes and backticks.
 *
 * <p>It only cleans a line; it never drops one. A bullet or a numbered step becomes the plain
 * sentence it introduced, a heading becomes its words, emphasis markers go.
 */
public final class PlainText {
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+");
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*+•]|\\d{1,3}[.)])\\s+");
    private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?");
    private static final Pattern RULE = Pattern.compile("^\\s*(?:[-*_]\\s*){3,}$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");
    /**
     * Asterisks only, and only at word edges. Underscores are left alone on purpose: block ids
     * like polished_blackstone_bricks are full of them, and Lumi names blocks all the time.
     * The word-edge rule also leaves arithmetic like 2*3*4 alone.
     */
    private static final Pattern EMPHASIS = Pattern.compile("(?<![\\w*])(\\*\\*|\\*)(?=[^\\s*])(.+?)(?<=[^\\s*])\\1(?![\\w*])");
    private static final Pattern CODE = Pattern.compile("`+([^`]*)`+");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)]+)\\)");

    private PlainText() {}

    public static String line(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        if (RULE.matcher(s).matches() || TABLE_SEPARATOR.matcher(s).matches()) return "";
        s = HEADING.matcher(s).replaceFirst("");
        s = QUOTE.matcher(s).replaceFirst("");
        s = BULLET.matcher(s).replaceFirst("");
        if (s.startsWith("|") && s.endsWith("|") && s.length() > 1) {
            // A table row: keep the cells as one readable line.
            s = String.join(", ", java.util.Arrays.stream(s.substring(1, s.length() - 1).split("\\|"))
                    .map(String::strip).filter(c -> !c.isEmpty()).toList());
        }
        s = LINK.matcher(s).replaceAll("$1 ($2)");
        s = CODE.matcher(s).replaceAll("$1");
        // Twice, so **bold *and italic*** both come off.
        s = EMPHASIS.matcher(s).replaceAll("$2");
        s = EMPHASIS.matcher(s).replaceAll("$2");
        return s.strip();
    }
}
