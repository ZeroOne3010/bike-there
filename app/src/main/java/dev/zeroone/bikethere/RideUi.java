package dev.zeroone.bikethere;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

final class RideUi {
    static final String APPEARANCE = "appearance";
    private static final Typeface DISPLAY = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Context context;
    final RideColors colours;

    RideUi(Context context) {
        this.context = context;
        colours = RideColors.forDark(isDark(context));
    }

    static boolean isDark(Context context) {
        return switch (TrackerService.preferences(context).getString(APPEARANCE, "system")) {
            case "dark" -> true;
            case "light" -> false;
            default -> (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        };
    }

    void edgeToEdge(Activity activity, View root) {
        Window window = activity.getWindow();
        // Android's insets controller needs the decor view, even before setContentView.
        View decor = window.getDecorView();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        if (Build.VERSION.SDK_INT < 35) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
        }
        var bars = WindowCompat.getInsetsController(window, decor);
        bars.setAppearanceLightStatusBars(!colours.dark());
        bars.setAppearanceLightNavigationBars(!colours.dark());
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            root.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
    }

    int dp(float size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    TextView text(String text, int size, boolean bold, int color) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setIncludeFontPadding(false);
        if (bold) {
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return label;
    }

    TextView title(String text, int size, int color) {
        TextView label = text(text, size, true, color);
        label.setTypeface(DISPLAY);
        label.setLetterSpacing(-.035f);
        return label;
    }

    GradientDrawable shape(int fill, int radius, Integer stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke != null) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
    }

    Button button(String text, int fill, int ink, Integer edge, Runnable click) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(ink);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setIncludeFontPadding(false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2e8d719e),
                shape(fill, 24, edge), shape(Color.WHITE, 24, null)));
        button.setOnClickListener(view -> click.run());
        return button;
    }

    Button primary(String text, Runnable click) {
        return button(text, colours.accent(), colours.onAccent(), null, click);
    }

    Button secondary(String text, Runnable click) {
        return button(text, Color.TRANSPARENT, colours.ink(), colours.line(), click);
    }

    View hairline() {
        View view = new View(context);
        view.setBackgroundColor(colours.line());
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return view;
    }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -2, 1);
    }
}
