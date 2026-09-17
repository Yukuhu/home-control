package dev.andre.homecontrol.adapters.bluetooth.player;

import java.util.regex.Pattern;

/** Stream URLs can carry credentials; everything mpv prints passes through here before it is kept or shown. */
public final class StreamRedaction {

    private static final Pattern URL_QUERY = Pattern.compile("((?:https?|rtsp|rtmp)://[^\\s?#'\"]*)\\?[^\\s'\"]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_USERINFO = Pattern.compile("//[^\\s/@'\"]+@");
    private static final Pattern LOOSE_KEY = Pattern.compile("\\b(api_?key|token)=[^\\s&'\"]+", Pattern.CASE_INSENSITIVE);

    private StreamRedaction() {
    }

    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        String withoutQueries = URL_QUERY.matcher(text).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(match.group(1)) + "?…");
        String withoutUserinfo = URL_USERINFO.matcher(withoutQueries).replaceAll("//…@");
        return LOOSE_KEY.matcher(withoutUserinfo).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(match.group(1)) + "=…");
    }
}
