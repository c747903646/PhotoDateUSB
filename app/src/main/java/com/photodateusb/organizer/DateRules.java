package com.photodateusb.organizer;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java date rules, intentionally decoupled from Android for easy tests.
 * Never infer a photo's capture time from the transfer date.
 */
public final class DateRules {
    public enum Group { YEAR, MONTH, DAY }
    private static final Pattern EXIF_DATE = Pattern.compile(
        "^\\s*((?:19|20)\\d{2})[:/-](\\d{1,2})[:/-](\\d{1,2})" +
        "(?:[ T](\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?)?.*$");
    private static final Pattern NAME_DATE = Pattern.compile(
        "(?<!\\d)((?:19|20)\\d{2})[-_. ]?(\\d{2})[-_. ]?(\\d{2})" +
        "(?:[-_. ]?(\\d{2})[-_. ]?(\\d{2})[-_. ]?(\\d{2})(?:\\d{1,3})?)?(?!\\d)");
    private static final DateTimeFormatter SHOW =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US);

    private DateRules() { }

    public static LocalDateTime parseExif(String value) {
        if (value == null) return null;
        Matcher m = EXIF_DATE.matcher(value.replace("\u0000", ""));
        if (!m.matches()) return null;
        return parseGroups(m);
    }

    public static LocalDateTime parseFilename(String value) {
        if (value == null) return null;
        Matcher m = NAME_DATE.matcher(value);
        while (m.find()) {
            LocalDateTime dt = parseGroups(m);
            if (dt != null) return dt;
        }
        return null;
    }

    private static LocalDateTime parseGroups(Matcher m) {
        try {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int day = Integer.parseInt(m.group(3));
            int hour = integerOrZero(m.group(4));
            int minute = integerOrZero(m.group(5));
            int second = integerOrZero(m.group(6));
            if (year < 1900 || year > 2100) return null;
            return LocalDateTime.of(year, month, day, hour, minute, second);
        } catch (NumberFormatException | DateTimeException e) {
            return null;
        }
    }

    private static int integerOrZero(String text) {
        return text == null ? 0 : Integer.parseInt(text);
    }

    public static List<String> folders(LocalDateTime dt, Group group) {
        if (dt == null) return Arrays.asList("未识别日期");
        List<String> parts = new ArrayList<>();
        parts.add(String.format(Locale.US, "%04d", dt.getYear()));
        if (group == Group.MONTH || group == Group.DAY) {
            parts.add(String.format(Locale.US, "%02d", dt.getMonthValue()));
        }
        if (group == Group.DAY) {
            parts.add(String.format(Locale.US, "%02d", dt.getDayOfMonth()));
        }
        return parts;
    }

    public static String show(LocalDateTime dt) {
        return dt == null ? "未知" : SHOW.format(dt);
    }

    public static String numberFile(String name, int index) {
        if (index <= 0) return name;
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name + String.format(Locale.US, "_%03d", index);
        return name.substring(0, dot)
            + String.format(Locale.US, "_%03d", index) + name.substring(dot);
    }

    public static boolean imageFile(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".jpe")
            || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".heic")
            || n.endsWith(".heif") || n.endsWith(".avif") || n.endsWith(".tiff")
            || n.endsWith(".tif") || n.endsWith(".dng") || n.endsWith(".bmp")
            || n.endsWith(".gif");
    }

    public static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".jpe")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".heic")) return "image/heic";
        if (n.endsWith(".heif")) return "image/heif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".tif") || n.endsWith(".tiff")) return "image/tiff";
        if (n.endsWith(".avif")) return "image/avif";
        if (n.endsWith(".dng")) return "image/x-adobe-dng";
        return "application/octet-stream";
    }
}
