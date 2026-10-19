package com.nikonpreset.usbprobe.album;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.TimeZone;

/** Pure metadata grouping. Does not read files, decode photographs or create views. */
public final class PhotoDateGroups {
    private PhotoDateGroups() { }

    public static final class Group {
        public final String dateKey, label;
        public final int photoCount;
        public final List<DownloadedPhoto> photos;
        private Group(LocalDate date, String label, List<DownloadedPhoto> photos) {
            dateKey = date == null ? "unknown" : date.toString(); this.label = label;
            this.photos = Collections.unmodifiableList(new ArrayList<>(photos)); photoCount = this.photos.size();
        }
    }

    /** A full-width group header or at most `columns` photo references for one recycled row. */
    public static final class Row {
        public final boolean header;
        public final Group group;
        public final List<DownloadedPhoto> photos;
        private Row(Group group, int start, int columns) {
            this.group = group; header = start < 0;
            photos = header ? Collections.emptyList() : group.photos.subList(start, (int) Math.min(group.photoCount, start + (long) columns));
        }
    }

    public static List<Group> group(List<DownloadedPhoto> photos) {
        return group(photos, System.currentTimeMillis(), TimeZone.getDefault());
    }

    /** Valid dates descend; missing dates are last. Equal timestamps retain their input order. */
    public static List<Group> group(List<DownloadedPhoto> photos, long nowMillis, TimeZone timeZone) {
        Objects.requireNonNull(photos, "photos");
        ZoneId zone = Objects.requireNonNull(timeZone, "timeZone").toZoneId();
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        List<DownloadedPhoto> sorted = new ArrayList<>(photos);
        for (DownloadedPhoto photo : sorted) Objects.requireNonNull(photo, "photo");
        sorted.sort((first, second) -> Long.compare(second.photoDate(), first.photoDate()));
        List<Group> result = new ArrayList<>(); List<DownloadedPhoto> members = new ArrayList<>();
        LocalDate current = null;
        for (DownloadedPhoto photo : sorted) {
            LocalDate date = photo.photoDate() <= 0 ? null : Instant.ofEpochMilli(photo.photoDate()).atZone(zone).toLocalDate();
            if (!members.isEmpty() && !Objects.equals(current, date)) {
                result.add(new Group(current, label(current, today), members)); members.clear();
            }
            current = date; members.add(photo);
        }
        if (!members.isEmpty()) result.add(new Group(current, label(current, today), members));
        return Collections.unmodifiableList(result);
    }

    private static String label(LocalDate date, LocalDate today) {
        if (date == null) return "日期未知";
        if (date.equals(today)) return "今天";
        if (date.equals(today.minusDays(1))) return "昨天";
        return (date.getYear() == today.getYear() ? "" : date.getYear() + "年") + date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }

    /** Lazy row metadata supports a virtualized ListView without materializing every photo row. */
    public static List<Row> rows(List<Group> groups, int columns) {
        Objects.requireNonNull(groups, "groups");
        if (columns < 1) throw new IllegalArgumentException("columns must be positive");
        return new Rows(groups, columns);
    }

    private static final class Rows extends AbstractList<Row> implements RandomAccess {
        final List<Group> groups; final int[] starts; final int columns, count;
        Rows(List<Group> source, int columns) {
            this.groups = Collections.unmodifiableList(new ArrayList<>(source)); this.columns = columns;
            starts = new int[groups.size()]; long total = 0;
            for (int i = 0; i < groups.size(); i++) {
                Group group = Objects.requireNonNull(groups.get(i), "group"); starts[i] = (int) total;
                total += 1L + (group.photoCount + (long) columns - 1) / columns;
                if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("too many photo rows");
            }
            count = (int) total;
        }
        @Override public int size() { return count; }
        @Override public Row get(int index) {
            if (index < 0 || index >= count) throw new IndexOutOfBoundsException(String.valueOf(index));
            int low = 0, high = starts.length - 1;
            while (low < high) { int mid = (low + high + 1) >>> 1; if (starts[mid] <= index) low = mid; else high = mid - 1; }
            int row = index - starts[low]; return new Row(groups.get(low), row == 0 ? -1 : (row - 1) * columns, columns);
        }
    }
}
