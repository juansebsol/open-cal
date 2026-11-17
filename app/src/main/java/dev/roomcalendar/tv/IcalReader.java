package dev.roomcalendar.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Read-only Google Calendar ICS parser, including common RFC5545 recurring rules. */
final class IcalReader {
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT);
    private static final int MAX_DOWNLOAD = 8 * 1024 * 1024;
    private static final int DAYS_BEHIND = 7, DAYS_AHEAD = 45;

    static JSONArray download(String feedUrl) throws Exception {
        byte[] downloaded = fetch(feedUrl);
        String ics = new String(downloaded, StandardCharsets.UTF_8);
        return parse(ics, ZoneId.systemDefault(), LocalDate.now());
    }

    static byte[] fetch(String originalUrl) throws Exception {
        String current = originalUrl;
        for (int redirect = 0; redirect < 4; redirect++) {
            if (!MainActivity.isValidFeedUrl(current))
                throw new IllegalArgumentException("Only secure Google Calendar iCal URLs are accepted");
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(25_000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", "text/calendar, text/plain;q=0.7");
            conn.setRequestProperty("User-Agent", "RoomCalendarTV/0.1");
            try {
                int status = conn.getResponseCode();
                if (status >= 300 && status < 400) {
                    String target = conn.getHeaderField("Location");
                    if (target == null) throw new Exception("Google returned a redirect without a destination");
                    current = new URL(new URL(current), target).toString();
                    continue;
                }
                if (status != 200) throw new Exception("Google Calendar returned HTTP " + status + ". Check or reset your secret iCal link.");
                try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        if (out.size() + n > MAX_DOWNLOAD) throw new Exception("Calendar feed is over 8 MB. Reduce its history or use a smaller calendar.");
                        out.write(chunk, 0, n);
                    }
                    if (out.size() == 0) throw new Exception("The calendar returned an empty file.");
                    return out.toByteArray();
                }
            } finally { conn.disconnect(); }
        }
        throw new Exception("Too many calendar redirects");
    }

    static JSONArray parse(String ics, ZoneId fallback, LocalDate today) throws Exception {
        if (!ics.contains("BEGIN:VCALENDAR"))
            throw new Exception("This doesn't appear to be an iCal calendar (.ics).");
        List<String> lines = unfold(ics.replace("\r\n", "\n").replace('\r','\n'));
        List<Event> originals = new ArrayList<>();
        Event e = null;
        boolean inAlarm = false;
        for (String line : lines) {
            if (line.equals("BEGIN:VEVENT")) { e = new Event(); continue; }
            if (line.equals("END:VEVENT")) { if (e != null) originals.add(e); e = null; continue; }
            if (e == null) continue;
            if (line.equals("BEGIN:VALARM")) { inAlarm = true; continue; }
            if (line.equals("END:VALARM")) { inAlarm = false; continue; }
            if (inAlarm) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String nameParams = line.substring(0, colon);
            String key = nameParams.split(";",2)[0].toUpperCase(Locale.ROOT);
            e.values.computeIfAbsent(key,k->new ArrayList<>()).add(new Property(nameParams, line.substring(colon+1)));
        }

        long rangeStart = today.minusDays(DAYS_BEHIND).atStartOfDay(fallback).toInstant().toEpochMilli();
        long rangeEnd = today.plusDays(DAYS_AHEAD).atStartOfDay(fallback).toInstant().toEpochMilli();
        Map<String,Map<Long,Event>> overrides = new HashMap<>();
        for (Event original : originals) {
            original.prepare(fallback);
            Property recurrenceId = original.first("RECURRENCE-ID");
            if (recurrenceId != null && original.start != null) {
                Long key = parseDate(recurrenceId, fallback);
                if (key != null) overrides.computeIfAbsent(original.uid,k->new HashMap<>()).put(key, original);
            }
        }
        List<JSONObject> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Event original : originals) {
            if (original.start == null || original.first("RECURRENCE-ID") != null) continue;
            if ("CANCELLED".equalsIgnoreCase(original.text("STATUS", ""))) continue;
            Map<Long,Event> moved = overrides.getOrDefault(original.uid, Collections.emptyMap());
            Set<Long> excluded = new HashSet<>();
            for (Property p : original.values.getOrDefault("EXDATE", Collections.emptyList())) {
                for (String part : p.value.split(",")) {
                    Long v = parseDate(new Property(p.label,part), fallback);
                    if (v != null) excluded.add(v);
                }
            }
            Set<Long> starts = new HashSet<>();
            starts.add(original.start);
            Property rrule = original.first("RRULE");
            if (rrule != null) starts.addAll(expand(original, rrule.value, rangeEnd));
            for (Property p : original.values.getOrDefault("RDATE", Collections.emptyList())) {
                for (String part : p.value.split(",")) {
                    Long v = parseDate(new Property(p.label,part), fallback);
                    if (v != null) starts.add(v);
                }
            }
            for (long start : starts) {
                if (excluded.contains(start)) continue;
                Event o = moved.get(start);
                if (o != null && "CANCELLED".equalsIgnoreCase(o.text("STATUS", ""))) continue;
                Event actual = o == null ? original : o;
                long eventStart = o == null ? start : o.start;
                long eventEnd = eventStart + Math.max(1, actual.end - actual.start);
                if (eventEnd <= rangeStart || eventStart >= rangeEnd) continue;
                String unique = original.uid + ":" + start;
                if (seen.add(unique)) result.add(toJson(actual, eventStart, eventEnd));
            }
        }
        // An overridden occurrence may move into view from outside the expanded date range.
        for (Map.Entry<String,Map<Long,Event>> entry : overrides.entrySet()) {
            for (Map.Entry<Long,Event> override : entry.getValue().entrySet()) {
                Event actual = override.getValue();
                if (actual.start == null || "CANCELLED".equalsIgnoreCase(actual.text("STATUS", ""))) continue;
                String unique = entry.getKey() + ":" + override.getKey();
                if (seen.contains(unique)) continue;
                long st = actual.start, end = actual.end;
                if (end <= rangeStart || st >= rangeEnd) continue;
                seen.add(unique);
                result.add(toJson(actual, st, end));
            }
        }
        result.sort(Comparator.comparingLong(a -> a.optLong("start")));
        JSONArray array = new JSONArray();
        for (JSONObject item : result) array.put(item);
        return array;
    }

    private static JSONObject toJson(Event e, long start, long end) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", e.uid + ":" + start);
        item.put("title", clean(e.text("SUMMARY", "Untitled event")));
        item.put("start", start);
        item.put("end", end);
        item.put("allDay", e.allDay);
        item.put("location", clean(e.text("LOCATION", "")));
        return item;
    }

    private static List<Long> expand(Event e, String rule, long endOfRange) {
        Map<String,String> kv = new HashMap<>();
        for (String part : rule.split(";")) {
            String[] entry = part.split("=",2);
            if (entry.length == 2) kv.put(entry[0].toUpperCase(Locale.ROOT),entry[1].toUpperCase(Locale.ROOT));
        }
        String freq = kv.getOrDefault("FREQ", "");
        if (!freq.equals("DAILY") && !freq.equals("WEEKLY") && !freq.equals("MONTHLY") && !freq.equals("YEARLY"))
            return Collections.emptyList();
        int interval = Math.max(1, intValue(kv.get("INTERVAL"),1));
        int countLimit = Math.min(100000, Math.max(1, intValue(kv.get("COUNT"),100000)));
        Long until = kv.containsKey("UNTIL") ? parseDate(new Property("UNTIL",kv.get("UNTIL")), e.zone) : null;
        if (until != null && kv.get("UNTIL").length() == 8) until += 86399_999L;
        LocalDate original = Instant.ofEpochMilli(e.start).atZone(e.zone).toLocalDate();
        LocalTime originalTime = Instant.ofEpochMilli(e.start).atZone(e.zone).toLocalTime();
        LocalDate last = Instant.ofEpochMilli(endOfRange).atZone(e.zone).toLocalDate();
        if (last.isBefore(original)) return Collections.emptyList();
        List<Long> result = new ArrayList<>();
        int emitted = 0, iterations = 0;
        for (LocalDate current = original; !current.isAfter(last) && iterations < 50000;
             current = current.plusDays(1), iterations++) {
            if (!matches(original,current,freq,interval,kv)) continue;
            long ms = ZonedDateTime.of(current,originalTime,e.zone).toInstant().toEpochMilli();
            if (ms < e.start) continue;
            if (until != null && ms > until) break;
            emitted++;
            if (emitted > countLimit) break;
            result.add(ms);
        }
        return result;
    }

    private static boolean matches(LocalDate initial, LocalDate day, String freq,
                                   int interval, Map<String,String> kv) {
        long days = ChronoUnit.DAYS.between(initial,day);
        long weeks = ChronoUnit.WEEKS.between(initial.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                                             day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
        long months = ChronoUnit.MONTHS.between(initial.withDayOfMonth(1),day.withDayOfMonth(1));
        long years = day.getYear() - initial.getYear();
        if (freq.equals("DAILY") && days % interval != 0) return false;
        if (freq.equals("WEEKLY") && weeks % interval != 0) return false;
        if (freq.equals("MONTHLY") && months % interval != 0) return false;
        if (freq.equals("YEARLY") && years % interval != 0) return false;
        if (freq.equals("WEEKLY") && !kv.containsKey("BYDAY") && day.getDayOfWeek() != initial.getDayOfWeek()) return false;
        if (freq.equals("MONTHLY") && !kv.containsKey("BYDAY") && !kv.containsKey("BYMONTHDAY") && day.getDayOfMonth() != initial.getDayOfMonth()) return false;
        if (freq.equals("YEARLY") && !kv.containsKey("BYMONTH") && day.getMonthValue() != initial.getMonthValue()) return false;
        if (freq.equals("YEARLY") && !kv.containsKey("BYMONTHDAY") && !kv.containsKey("BYDAY") && day.getDayOfMonth() != initial.getDayOfMonth()) return false;
        if (kv.containsKey("BYMONTH") && !matchesNumbers(kv.get("BYMONTH"), day.getMonthValue(),12)) return false;
        if (kv.containsKey("BYMONTHDAY") && !matchesMonthDay(kv.get("BYMONTHDAY"),day)) return false;
        if (kv.containsKey("BYDAY") && !matchesDay(kv.get("BYDAY"),day)) return false;
        return true;
    }

    private static boolean matchesNumbers(String csv, int n, int max) {
        for (String v : csv.split(",")) if (intValue(v,-1)==n) return true;
        return false;
    }
    private static boolean matchesMonthDay(String csv, LocalDate day) {
        for (String v : csv.split(",")) {
            int n = intValue(v,0);
            if (n == day.getDayOfMonth() || (n < 0 && day.lengthOfMonth()+n+1 == day.getDayOfMonth()))
                return true;
        }
        return false;
    }
    private static boolean matchesDay(String csv, LocalDate day) {
        for (String v : csv.split(",")) {
            if (v.length() < 2) continue;
            String suffix = v.substring(v.length()-2);
            int target = switch(suffix) {
                case "MO" -> 1; case "TU" -> 2; case "WE" -> 3; case "TH" -> 4;
                case "FR" -> 5; case "SA" -> 6; case "SU" -> 7; default -> 0;
            };
            if (day.getDayOfWeek().getValue() != target) continue;
            String ordinal = v.substring(0,v.length()-2);
            if (ordinal.isEmpty()) return true;
            int nth = intValue(ordinal,0);
            if (nth > 0 && ((day.getDayOfMonth()-1)/7+1) == nth) return true;
            if (nth < 0 && ((day.lengthOfMonth()-day.getDayOfMonth())/7+1) == -nth) return true;
        }
        return false;
    }

    private static class Property {
        String label, value;
        Property(String label,String value) { this.label=label;this.value=value; }
    }
    private static class Event {
        Map<String,List<Property>> values = new HashMap<>();
        String uid = "event-" + System.nanoTime();
        Long start, end;
        boolean allDay;
        ZoneId zone;
        Property first(String key) {
            List<Property> p = values.get(key);
            return p == null || p.isEmpty() ? null : p.get(0);
        }
        String text(String key,String fallback) {
            Property p = first(key); return p == null ? fallback : p.value;
        }
        void prepare(ZoneId fallback) {
            uid = text("UID",uid);
            Property p = first("DTSTART");
            if (p == null) return;
            zone = zoneFor(p,fallback);
            allDay = p.label.toUpperCase(Locale.ROOT).contains("VALUE=DATE") || p.value.length()==8;
            start = parseDate(p, fallback);
            Property en = first("DTEND");
            end = en == null ? null : parseDate(en,zone);
            if (start == null) return;
            if (end == null || end <= start) end = start + (allDay ? 86400_000L : 3600_000L);
        }
    }

    private static ZoneId zoneFor(Property p, ZoneId fallback) {
        for (String param : p.label.split(";")) {
            if (param.toUpperCase(Locale.ROOT).startsWith("TZID=")) {
                String id = param.substring(5).replace("\"", "");
                try { return ZoneId.of(id); } catch (Exception ignored) { }
            }
        }
        return fallback;
    }

    private static Long parseDate(Property p, ZoneId zone) {
        try {
            String s = p.value.trim();
            if (s.length()==8) return LocalDate.parse(s,DAY).atStartOfDay(zone).toInstant().toEpochMilli();
            boolean utc = s.endsWith("Z");
            if (utc) { s=s.substring(0,s.length()-1); zone=ZoneId.of("UTC"); }
            if (s.length()==13) s += "00";
            return LocalDateTime.parse(s,DATE_TIME).atZone(zoneFor(p,zone)).toInstant().toEpochMilli();
        } catch (Exception ignored) { return null; }
    }
    private static int intValue(String value, int fallback) {
        try { return Integer.parseInt(value); } catch(Exception e){ return fallback; }
    }
    private static String clean(String str) {
        return str.replace("\\n"," ").replace("\\N"," ").replace("\\,",",")
            .replace("\\;",";").replace("\\\\","\\").trim();
    }
    private static List<String> unfold(String text) {
        List<String> result = new ArrayList<>();
        for (String line : text.split("\n",-1)) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && !result.isEmpty()) {
                int last = result.size()-1;
                result.set(last,result.get(last)+line.substring(1));
            } else result.add(line);
        }
        return result;
    }
}
