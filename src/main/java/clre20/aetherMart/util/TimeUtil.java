package clre20.aetherMart.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 時間長度解析（30m、12h、2d、1d12h）與時間格式。 */
public final class TimeUtil {

    private static final Pattern PART = Pattern.compile("(\\d{1,4})([dhms])");
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    public static final DateTimeFormatter DATE_TIME_SHORT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private TimeUtil() {
    }

    /** 解析時間長度；格式錯誤或為 0 時回傳 -1。最長 3650 天。 */
    public static long parseDurationMillis(String input) {
        if (input == null) {
            return -1;
        }
        String s = input.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.isEmpty() || s.length() > 24) {
            return -1;
        }
        Matcher m = PART.matcher(s);
        int pos = 0;
        long total = 0;
        while (m.find()) {
            if (m.start() != pos) {
                return -1;
            }
            long n = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "d" -> n * 86_400_000L;
                case "h" -> n * 3_600_000L;
                case "m" -> n * 60_000L;
                default -> n * 1000L;
            };
            pos = m.end();
        }
        if (pos != s.length() || total <= 0 || total > 3650L * 86_400_000L) {
            return -1;
        }
        return total;
    }

    /** 解析 yyyy-MM-dd HH:mm；錯誤時回傳 -1。 */
    public static long parseDateTime(String input, ZoneId zone) {
        try {
            return LocalDateTime.parse(input.trim(), INPUT).atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException | NullPointerException e) {
            return -1;
        }
    }

    private static final Pattern FLEX_DATE = Pattern.compile(
            "(?:(\\d{4})[-/.])?(\\d{1,2})[-/.](\\d{1,2})(?:\\s+(\\d{1,2}):(\\d{2}))?");
    private static final Pattern FLEX_TIME = Pattern.compile("(\\d{1,2}):(\\d{2})");

    /**
     * 寬鬆的日期時間輸入，例如 2026-11-01 20:00、2026/11/1、11/1 20:00、11/1、11月1日、20:00、現在。
     * 全形數字與符號會先轉成半形。沒寫年份 = 今年（已經過去超過一天就算明年）；只寫時間 = 今天。
     * 只寫日期時，endOfDay = true 取 23:59（結束時間「到 11/1」包含當天），否則取 00:00。
     * 格式錯誤回傳 -1。
     */
    public static long parseFlexible(String input, ZoneId zone, long now, boolean endOfDay) {
        if (input == null) {
            return -1;
        }
        String s = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFKC).trim()
                .replace("年", "/").replace("月", "/").replace("日", "").replace("號", "").replace("号", "")
                .replaceAll("\\s+", " ");
        if (s.isEmpty() || s.length() > 24) {
            return -1;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.equals("now") || s.equals("現在") || s.equals("现在")) {
            return now - now % 60_000L;
        }
        LocalDateTime today = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone);
        try {
            Matcher t = FLEX_TIME.matcher(s);
            if (t.matches()) {
                return today.toLocalDate().atTime(Integer.parseInt(t.group(1)), Integer.parseInt(t.group(2)))
                        .atZone(zone).toInstant().toEpochMilli();
            }
            Matcher m = FLEX_DATE.matcher(s);
            if (!m.matches()) {
                return -1;
            }
            boolean hasYear = m.group(1) != null;
            int year = hasYear ? Integer.parseInt(m.group(1)) : today.getYear();
            int month = Integer.parseInt(m.group(2));
            int day = Integer.parseInt(m.group(3));
            int hour = endOfDay ? 23 : 0;
            int minute = endOfDay ? 59 : 0;
            if (m.group(4) != null) {
                hour = Integer.parseInt(m.group(4));
                minute = Integer.parseInt(m.group(5));
            }
            LocalDateTime dt = LocalDateTime.of(year, month, day, hour, minute);
            if (!hasYear && dt.isBefore(today.minusDays(1))) {
                dt = dt.plusYears(1);
            }
            return dt.atZone(zone).toInstant().toEpochMilli();
        } catch (java.time.DateTimeException | NumberFormatException e) {
            return -1;
        }
    }

    /** 短的時間長度文字，例如 6小時、3天、1天 12小時。 */
    public static String formatShort(long millis, String day, String hour, String minute) {
        long minutes = Math.max(0, millis / 60_000L);
        long d = minutes / 1440;
        long h = minutes % 1440 / 60;
        long m = minutes % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append(day).append(' ');
        }
        if (h > 0) {
            sb.append(h).append(hour).append(' ');
        }
        if (m > 0 || sb.length() == 0) {
            sb.append(m).append(minute);
        }
        return sb.toString().trim();
    }

    public static String format(long epochMillis, ZoneId zone, DateTimeFormatter f) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone).format(f);
    }

    /** 剩餘時間文字，例如 1天 2小時、5分 3秒；單位文字由呼叫端提供。 */
    public static String formatDuration(long millis, String day, String hour, String minute, String second) {
        if (millis < 0) {
            millis = 0;
        }
        Duration d = Duration.ofMillis(millis);
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append(day).append(' ');
            sb.append(hours).append(hour);
        } else if (hours > 0) {
            sb.append(hours).append(hour).append(' ').append(minutes).append(minute);
        } else if (minutes > 0) {
            sb.append(minutes).append(minute).append(' ').append(seconds).append(second);
        } else {
            sb.append(seconds).append(second);
        }
        return sb.toString().trim();
    }

    /**
     * 讀取「HH:mm」設定值。YAML 1.1 會把沒加引號的 15:30 讀成 60 進位整數 930，這裡一併轉回時與分。
     */
    public static int[] parseClock(Object value) {
        if (value instanceof Number n) {
            long v = n.longValue();
            if (v < 0 || v >= 24 * 60) {
                return null;
            }
            return new int[]{(int) (v / 60), (int) (v % 60)};
        }
        return value == null ? null : parseClock(value.toString());
    }

    public static int[] parseClock(String s) {
        if (s == null) {
            return null;
        }
        String[] p = s.trim().split(":");
        if (p.length != 2) {
            return null;
        }
        try {
            int h = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return null;
            }
            return new int[]{h, m};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
