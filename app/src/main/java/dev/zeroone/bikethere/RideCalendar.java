package dev.zeroone.bikethere;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RideCalendar {
    record Total(int rides, double metres) {
        private static final Total EMPTY = new Total(0, 0);

        Total plus(Total other) {
            return new Total(rides + other.rides, metres + other.metres);
        }
    }

    private final YearMonth current;
    private final Map<LocalDate, Total> days = new HashMap<>();
    private final Map<YearMonth, Total> months = new HashMap<>();
    private final Map<Integer, Total> years = new HashMap<>();
    private YearMonth earliest;
    private YearMonth latest;

    RideCalendar(YearMonth current, List<RideStore.Summary> rides) {
        this.current = current;
        earliest = current;
        latest = current;
        for (RideStore.Summary ride : rides) {
            LocalDate date = Instant.ofEpochMilli(ride.start()).atZone(ZoneId.of(ride.zone())).toLocalDate();
            YearMonth month = YearMonth.from(date);
            var total = new Total(1, ride.distance());
            days.merge(date, total, Total::plus);
            months.merge(month, total, Total::plus);
            years.merge(month.getYear(), total, Total::plus);
            if (month.isBefore(earliest)) {
                earliest = month;
            }
            if (month.isAfter(latest)) {
                latest = month;
            }
        }
    }

    YearMonth visibleMonth(YearMonth selection) {
        if (selection == null) {
            return current;
        }
        if (selection.isBefore(earliest)) {
            return earliest;
        }
        return selection.isAfter(latest) ? latest : selection;
    }

    boolean canGoBack(YearMonth month) {
        return month.isAfter(earliest);
    }

    boolean canGoForward(YearMonth month) {
        return month.isBefore(latest);
    }

    Total day(LocalDate date) {
        return days.getOrDefault(date, Total.EMPTY);
    }

    Total month(YearMonth month) {
        return months.getOrDefault(month, Total.EMPTY);
    }

    Total year(int year) {
        return years.getOrDefault(year, Total.EMPTY);
    }

    static int leadingDays(YearMonth month, DayOfWeek firstDay) {
        return Math.floorMod(month.atDay(1).getDayOfWeek().getValue() - firstDay.getValue(), 7);
    }
}
