package dev.zeroone.bikethere;

import android.content.Context;
import android.content.res.Resources;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;

import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.function.Consumer;

final class StatsPanel extends LinearLayout {
    private final RideUi ui;
    private final RideColors colours;
    private final RideCalendar calendar;
    private final YearMonth month;
    private final boolean distanceMode;
    private final Locale locale;
    private final NumberFormat distance;
    private final NumberFormat counts;

    StatsPanel(Context context, RideUi ui, RideCalendar calendar, YearMonth month, boolean distanceMode,
               Consumer<YearMonth> changeMonth, Consumer<Boolean> changeMode) {
        super(context);
        this.ui = ui;
        this.colours = ui.colours;
        this.calendar = calendar;
        this.month = month;
        this.distanceMode = distanceMode;
        locale = getResources().getConfiguration().getLocales().get(0);
        distance = NumberFormat.getNumberInstance(locale);
        distance.setMinimumFractionDigits(1);
        distance.setMaximumFractionDigits(1);
        counts = NumberFormat.getIntegerInstance(locale);
        counts.setGroupingUsed(false);
        setOrientation(VERTICAL);

        addPanel(totals(), 18);
        addPanel(pager(changeMonth), 12);
        addPanel(modeSwitcher(changeMode), 14);
        addPanel(grid(), 12);
        if (calendar.month(month).rides() == 0) {
            addPanel(ui.text("No rides recorded this month.", 14, false, colours.muted()), 0);
        }
    }

    private View totals() {
        LinearLayout row = ui.row();
        row.setBaselineAligned(false);
        row.setPadding(ui.dp(14), ui.dp(13), ui.dp(14), ui.dp(13));
        row.setBackground(ui.shape(colours.panel(), 0, colours.line()));
        row.addView(total("MONTH", calendar.month(month)), RideUi.weight());
        View divider = new View(getContext());
        divider.setBackgroundColor(colours.line());
        LayoutParams line = new LayoutParams(ui.dp(1), ui.dp(58));
        line.setMargins(ui.dp(6), 0, ui.dp(13), 0);
        row.addView(divider, line);
        String year = getString(R.string.stats_year, counts.format(month.getYear()));
        row.addView(total(year, calendar.year(month.getYear())), RideUi.weight());
        return row;
    }

    private View total(String label, RideCalendar.Total total) {
        LinearLayout column = ui.column();
        TextView caption = ui.code(label, 11, true, colours.muted());
        caption.setLetterSpacing(.075f);
        column.addView(caption);
        TextView kilometres = ui.title(getString(R.string.stats_km, distance.format(total.metres() / 1000)),
                30, colours.ink());
        kilometres.setMaxLines(1);
        kilometres.setAutoSizeTextTypeUniformWithConfiguration(14, 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        column.addView(kilometres, new LayoutParams(-1, ui.dp(42)));
        column.addView(ui.text(rideCount(total.rides()), 13, false, colours.muted()));
        return column;
    }

    private View pager(Consumer<YearMonth> changeMonth) {
        LinearLayout row = ui.row();
        row.addView(monthButton("‹", "Previous month", calendar.canGoBack(month),
                () -> changeMonth.accept(month.minusMonths(1))), new LayoutParams(ui.dp(48), -2));
        String label = month.format(DateTimeFormatter.ofPattern("LLLL uuuu", locale));
        TextView title = ui.text(label, 19, true, colours.ink());
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        ViewCompat.setAccessibilityHeading(title, true);
        row.addView(title, RideUi.weight());
        row.addView(monthButton("›", "Next month", calendar.canGoForward(month),
                () -> changeMonth.accept(month.plusMonths(1))), new LayoutParams(ui.dp(48), -2));
        return row;
    }

    private Button monthButton(String arrow, String description, boolean available, Runnable click) {
        Button button = ui.secondary(arrow, click);
        button.setPadding(0, button.getPaddingTop(), 0, button.getPaddingBottom());
        button.setTextSize(25);
        button.setContentDescription(description);
        button.setEnabled(available);
        if (!available) {
            button.setAlpha(.4f);
        }
        return button;
    }

    private View modeSwitcher(Consumer<Boolean> changeMode) {
        LinearLayout row = ui.row();
        Button rides = modeButton("Rides", !distanceMode, () -> changeMode.accept(false));
        LayoutParams first = RideUi.weight();
        first.rightMargin = ui.dp(8);
        row.addView(rides, first);
        row.addView(modeButton("Kilometres", distanceMode, () -> changeMode.accept(true)), RideUi.weight());
        return row;
    }

    private Button modeButton(String text, boolean selected, Runnable click) {
        Button button = selected ? ui.button(text, colours.ink(), colours.paper(), null, click)
                : ui.secondary(text, click);
        button.setSelected(selected);
        return button;
    }

    private View grid() {
        LinearLayout grid = ui.column();
        DayOfWeek first = WeekFields.of(locale).getFirstDayOfWeek();
        LinearLayout weekdays = ui.row();
        for (int column = 0; column < 7; column++) {
            DayOfWeek weekday = first.plus(column);
            TextView label = ui.text(weekday.getDisplayName(TextStyle.SHORT_STANDALONE, locale), 11,
                    true, colours.muted());
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            label.setContentDescription(weekday.getDisplayName(TextStyle.FULL, locale));
            weekdays.addView(label, cellSize(23));
        }
        grid.addView(weekdays);
        int padding = RideCalendar.leadingDays(month, first);
        int weeks = (padding + month.lengthOfMonth() + 6) / 7;
        for (int week = 0; week < weeks; week++) {
            LinearLayout row = ui.row();
            for (int column = 0; column < 7; column++) {
                int number = week * 7 + column - padding + 1;
                View cell = number >= 1 && number <= month.lengthOfMonth()
                        ? dayCell(month.atDay(number)) : new View(getContext());
                row.addView(cell, cellSize(51));
            }
            grid.addView(ui.hairline());
            grid.addView(row);
        }
        grid.addView(ui.hairline());
        return grid;
    }

    private View dayCell(LocalDate day) {
        RideCalendar.Total total = calendar.day(day);
        boolean recorded = total.rides() > 0;
        LinearLayout cell = ui.column();
        cell.setGravity(Gravity.CENTER);
        if (recorded) {
            cell.setBackground(ui.shape(colours.panel(), 0, colours.line()));
        }
        cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        int ink = recorded ? colours.accent() : colours.muted();
        TextView date = ui.code(counts.format(day.getDayOfMonth()), 11, false, colours.muted());
        date.setGravity(Gravity.CENTER);
        date.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cell.addView(date);
        String metric = distanceMode ? distance.format(total.metres() / 1000) : counts.format(total.rides());
        if (recorded) {
            TextView value = ui.title(metric, 17, ink);
            value.setMaxLines(1);
            value.setGravity(Gravity.CENTER);
            value.setAutoSizeTextTypeUniformWithConfiguration(9, 17, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cell.addView(value, new LayoutParams(-1, ui.dp(22)));
        }
        String caption = distanceMode ? getString(R.string.stats_km, metric) : rideCount(total.rides());
        String dateText = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale));
        cell.setContentDescription(getString(R.string.stats_day, dateText, caption));
        return cell;
    }

    private LayoutParams cellSize(int height) {
        LayoutParams size = new LayoutParams(0, ui.dp(height), 1);
        size.setMargins(ui.dp(2), 0, ui.dp(2), ui.dp(4));
        return size;
    }

    private void addPanel(View view, int bottomMargin) {
        LayoutParams size = new LayoutParams(-1, -2);
        size.bottomMargin = ui.dp(bottomMargin);
        addView(view, size);
    }

    private String getString(int id, Object... args) {
        return getContext().getString(id, args);
    }

    private String rideCount(int number) {
        Resources resources = getResources();
        return resources.getQuantityString(R.plurals.stats_ride_count, number, counts.format(number));
    }
}
