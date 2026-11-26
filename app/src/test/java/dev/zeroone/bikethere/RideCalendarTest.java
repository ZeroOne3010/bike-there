package dev.zeroone.bikethere;

import org.junit.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.Assert.*;

public class RideCalendarTest {
    @Test
    public void totalsUseTheRideLocalDateAcrossMidnightAndYearEnd() {
        var calendar = new RideCalendar(YearMonth.of(2026, 2), List.of(
                ride("2025-12-31T23:30:00Z", "Europe/Helsinki", 750),
                ride("2026-01-01T12:00:00Z", "Europe/Helsinki", 2250),
                ride("2025-12-31T23:30:00Z", "UTC", 3000)));
        assertEquals(new RideCalendar.Total(2, 3000), calendar.day(LocalDate.of(2026, 1, 1)));
        assertEquals(new RideCalendar.Total(1, 3000), calendar.day(LocalDate.of(2025, 12, 31)));
        assertEquals(new RideCalendar.Total(2, 3000), calendar.month(YearMonth.of(2026, 1)));
        assertEquals(new RideCalendar.Total(2, 3000), calendar.year(2026));
    }

    @Test
    public void onlyNavigatesBetweenTheFirstRideMonthAndTheCurrentMonth() {
        YearMonth current = YearMonth.of(2026, 4);
        YearMonth earliest = YearMonth.of(2026, 1);
        var calendar = new RideCalendar(current, List.of(ride("2026-01-10T09:00:00Z", "UTC", 750)));
        assertEquals(current, calendar.visibleMonth(null));
        assertEquals(earliest, calendar.visibleMonth(earliest.minusMonths(1)));
        assertEquals(current, calendar.visibleMonth(current.plusMonths(1)));
        assertFalse(calendar.canGoBack(earliest));
        assertTrue(calendar.canGoBack(current.minusMonths(1)));
        assertTrue(calendar.canGoForward(earliest));
        assertFalse(calendar.canGoForward(current));
        assertEquals(0, calendar.month(current.minusMonths(1)).rides());
    }

    @Test
    public void emptyHistoryStopsAtTheCurrentMonthAndGridUsesLocaleWeekStart() {
        YearMonth current = YearMonth.of(2026, 11);
        var calendar = new RideCalendar(current, java.util.Collections.emptyList());
        assertFalse(calendar.canGoBack(current));
        assertFalse(calendar.canGoForward(current));
        assertEquals(6, RideCalendar.leadingDays(current, DayOfWeek.MONDAY));
        assertEquals(0, RideCalendar.leadingDays(current, DayOfWeek.SUNDAY));
    }

    private static RideStore.Summary ride(String time, String zone, double metres) {
        return new RideStore.Summary(time, "Ride", zone, Instant.parse(time).toEpochMilli(),
                0, 0, metres, null);
    }
}
