package com.nikonpreset.usbprobe.album;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.RandomAccess;
import java.util.TimeZone;

/** Pure grouping checks; explicit timestamps make any attempted disk read a test failure. */
public final class PhotoDateGroupsTest {
    private static int checks;
    public static void main(String[] args) {
        labelsAndLocalDayBoundaries();
        daylightSavingAndYearBoundaries();
        stableSortingAndUnknownDates();
        immutableLazyRowsCoverEveryPhoto();
        invalidInputsAreExplicit();
        System.out.println("PASS: " + checks + " photo date grouping checks");
    }
    private static void labelsAndLocalDayBoundaries() {
        long now = stamp("2026-10-17T00:30:00Z");
        DownloadedPhoto beforeMidnight = photo("midnight", "2026-10-16T16:00:00Z");
        DownloadedPhoto yesterday = photo("yesterday", "2026-10-16T15:59:59Z");
        DownloadedPhoto earlier = photo("earlier", "2026-10-14T04:00:00Z");
        DownloadedPhoto oldYear = photo("old-year", "2025-12-31T04:00:00Z");
        List<PhotoDateGroups.Group> groups = PhotoDateGroups.group(Arrays.asList(oldYear, earlier, yesterday, beforeMidnight), now, zone("Asia/Shanghai"));
        equal(4, groups.size()); equal("今天", groups.get(0).label); equal("2026-10-17", groups.get(0).dateKey);
        equal("昨天", groups.get(1).label); equal("10月14日", groups.get(2).label); equal("2025年12月31日", groups.get(3).label);
        equal("2026-10-16", PhotoDateGroups.group(List.of(beforeMidnight), now, zone("UTC")).get(0).dateKey);
        long internationalNow = stamp("2026-01-01T00:30:00Z");
        equal("2025-12-31", PhotoDateGroups.group(List.of(photo("date-line", "2026-01-01T00:00:00Z")), internationalNow, zone("Pacific/Honolulu")).get(0).dateKey);
        equal("2026-01-01", PhotoDateGroups.group(List.of(photo("date-line", "2026-01-01T00:00:00Z")), internationalNow, zone("Pacific/Kiritimati")).get(0).dateKey);
    }
    private static void daylightSavingAndYearBoundaries() {
        List<PhotoDateGroups.Group> spring = PhotoDateGroups.group(List.of(photo("23-hour-day", "2026-03-08T05:30:00Z")), stamp("2026-03-09T04:15:00Z"), zone("America/New_York"));
        equal("昨天", spring.get(0).label); equal("2026-03-08", spring.get(0).dateKey);
        List<PhotoDateGroups.Group> autumn = PhotoDateGroups.group(List.of(photo("25-hour-day", "2026-11-01T04:00:00Z")), stamp("2026-11-02T05:30:00Z"), zone("America/New_York"));
        equal("昨天", autumn.get(0).label);
        List<PhotoDateGroups.Group> newYear = PhotoDateGroups.group(List.of(photo("year-end", "2025-12-31T12:00:00Z"), photo("two-years", "2024-12-31T12:00:00Z")), stamp("2026-01-01T12:00:00Z"), zone("UTC"));
        equal("昨天", newYear.get(0).label); equal("2024年12月31日", newYear.get(1).label);
        List<PhotoDateGroups.Group> leap = PhotoDateGroups.group(List.of(photo("leap", "2024-02-29T12:00:00Z")), stamp("2024-03-01T12:00:00Z"), zone("UTC"));
        equal("昨天", leap.get(0).label);
    }
    private static void stableSortingAndUnknownDates() {
        DownloadedPhoto first = photo("first-equal", "2026-10-17T01:00:00Z"), second = photo("second-equal", "2026-10-17T01:00:00Z");
        DownloadedPhoto unknown = photo("unknown", 0), invalid = photo("invalid", -1);
        List<DownloadedPhoto> input = new ArrayList<>(List.of(unknown, first, invalid, second, photo("newest", "2026-10-17T23:00:00Z")));
        List<PhotoDateGroups.Group> groups = PhotoDateGroups.group(input, stamp("2026-10-17T12:00:00Z"), zone("UTC"));
        equal(2, groups.size()); equal("newest", groups.get(0).photos.get(0).filename());
        yes(groups.get(0).photos.get(1) == first); yes(groups.get(0).photos.get(2) == second);
        equal("unknown", groups.get(1).dateKey); equal("日期未知", groups.get(1).label);
        equal(List.of(unknown, invalid), groups.get(1).photos); equal(3, groups.get(0).photoCount); equal(2, groups.get(1).photoCount);
        input.clear(); equal(3, groups.get(0).photos.size());
        equal("2027年1月1日", PhotoDateGroups.group(List.of(photo("future", "2027-01-01T12:00:00Z")), stamp("2026-10-17T12:00:00Z"), zone("UTC")).get(0).label);
        equal(0, PhotoDateGroups.group(Collections.emptyList(), 0, zone("UTC")).size());
        throwsType(UnsupportedOperationException.class, () -> groups.clear());
        throwsType(UnsupportedOperationException.class, () -> groups.get(0).photos.clear());
    }
    private static void immutableLazyRowsCoverEveryPhoto() {
        List<DownloadedPhoto> photos = new ArrayList<>(); long day = stamp("2026-10-17T12:00:00Z");
        for (int i = 0; i < 5000; i++) photos.add(photo("p-" + i, day - i));
        photos.add(photo("older", "2026-10-16T12:00:00Z"));
        List<PhotoDateGroups.Group> groups = PhotoDateGroups.group(photos, day, zone("UTC"));
        List<PhotoDateGroups.Row> rows = PhotoDateGroups.rows(groups, 3);
        yes(rows instanceof RandomAccess); equal(1670, rows.size());
        yes(rows.get(0).header); equal(5000, rows.get(0).group.photoCount); equal(0, rows.get(0).photos.size());
        equal(3, rows.get(1).photos.size()); equal("p-0", rows.get(1).photos.get(0).filename());
        equal(2, rows.get(1667).photos.size()); yes(rows.get(1668).header); equal("昨天", rows.get(1668).group.label);
        equal("older", rows.get(1669).photos.get(0).filename());
        int visited = 0, headers = 0;
        for (PhotoDateGroups.Row row : rows) { if (row.header) headers++; else visited += row.photos.size(); }
        equal(5001, visited); equal(2, headers);
        equal(5003, PhotoDateGroups.rows(groups, 1).size());
        equal(4, PhotoDateGroups.rows(groups, Integer.MAX_VALUE).size());
        throwsType(UnsupportedOperationException.class, () -> rows.clear());
        throwsType(UnsupportedOperationException.class, () -> rows.get(1).photos.clear());
        equal(0, PhotoDateGroups.rows(Collections.emptyList(), 3).size());
        throwsType(IndexOutOfBoundsException.class, () -> rows.get(-1)); throwsType(IndexOutOfBoundsException.class, () -> rows.get(rows.size()));
    }
    private static void invalidInputsAreExplicit() {
        throwsType(NullPointerException.class, () -> PhotoDateGroups.group(null, 0, zone("UTC")));
        throwsType(NullPointerException.class, () -> PhotoDateGroups.group(Collections.singletonList(null), 0, zone("UTC")));
        throwsType(NullPointerException.class, () -> PhotoDateGroups.group(Collections.emptyList(), 0, null));
        throwsType(IllegalArgumentException.class, () -> PhotoDateGroups.rows(Collections.emptyList(), 0));
        throwsType(IllegalArgumentException.class, () -> PhotoDateGroups.rows(Collections.emptyList(), -1));
        throwsType(NullPointerException.class, () -> PhotoDateGroups.rows(Collections.singletonList(null), 3));
    }
    private static DownloadedPhoto photo(String name, String date) { return photo(name, stamp(date)); }
    private static DownloadedPhoto photo(String name, long date) {
        File noDisk = new File(name) { @Override public long lastModified() { throw new AssertionError("grouping read disk"); } };
        return new DownloadedPhoto(noDisk, name, "project", "Project", date);
    }
    private static long stamp(String value) { return Instant.parse(value).toEpochMilli(); }
    private static TimeZone zone(String value) { return TimeZone.getTimeZone(value); }
    private static void equal(Object expected, Object actual) { checks++; if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual); }
    private static void yes(boolean value) { checks++; if (!value) throw new AssertionError("expected true"); }
    private static void throwsType(Class<? extends Throwable> type, Runnable action) {
        checks++; try { action.run(); } catch (Throwable failure) { if (type.isInstance(failure)) return; throw new AssertionError(failure); } throw new AssertionError("expected " + type);
    }
}
