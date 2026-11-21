package os.nyro.launcher;

import static os.nyro.launcher.Visuals.*;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

final class NyRoSheet {
    record Action(String title, String glyph, Runnable perform, boolean destructive) {
        Action(String title, String glyph, Runnable perform) { this(title, glyph, perform, false); }
    }

    static void show(Activity activity, String title, View icon, List<Action> actions) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(activity, 20), dp(activity, 11), dp(activity, 20), dp(activity, 16));
        GradientDrawable shape = surface(activity, PANEL, 0, LINE);
        float corner = dp(activity, 30);
        shape.setCornerRadii(new float[]{corner, corner, corner, corner, 0, 0, 0, 0});
        sheet.setBackground(shape);

        View handle = new View(activity);
        handle.setBackground(surface(activity, 0xff605874, 5, Color.TRANSPARENT));
        LinearLayout.LayoutParams grip = new LinearLayout.LayoutParams(dp(activity, 35), dp(activity, 4));
        grip.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(handle, grip);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (icon != null) {
            var iconSize = new LinearLayout.LayoutParams(dp(activity, 49), dp(activity, 49));
            iconSize.rightMargin = dp(activity, 13);
            header.addView(icon, iconSize);
        }
        TextView heading = text(activity, title, 23, TEXT);
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setAccessibilityHeading(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        View close = new Glyph(activity, "close", MUTED, 19);
        close.setBackground(pressable(activity, CARD, 16, LINE));
        close.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        close.setContentDescription("Fechar menu");
        close.setOnClickListener(view -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        sheet.addView(header, new LinearLayout.LayoutParams(-1, dp(activity, 75)));

        ListView list = new ListView(activity);
        list.setDivider(new ColorDrawable(PANEL));
        list.setDividerHeight(dp(activity, 6));
        list.setPadding(0, 0, 0, 0);
        list.setVerticalScrollBarEnabled(actions.size() > 5);
        list.setSelector(android.R.color.transparent);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return actions.size(); }
            @Override public Action getItem(int position) { return actions.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                ActionRow row = recycled instanceof ActionRow existing ? existing : new ActionRow(activity);
                row.bind(getItem(position));
                return row;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            actions.get(position).perform().run();
        });
        int height = Math.max(dp(activity, 61), dp(activity, actions.size() * 61 - 6));
        int available = activity.getWindowManager().getCurrentWindowMetrics().getBounds().height();
        sheet.addView(list, new LinearLayout.LayoutParams(-1, Math.min(height, Math.round(available * .57f))));
        dialog.setContentView(sheet);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.BOTTOM);
        window.setDimAmount(.56f);
        window.setNavigationBarContrastEnforced(false);
        dialog.show();
        window.setLayout(-1, -2);
    }

    private static TextView text(Context context, String title, float size, int color) {
        TextView label = new TextView(context);
        label.setText(title);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

    private static final class ActionRow extends LinearLayout {
        private final android.widget.FrameLayout badge;
        private final TextView label;
        private String icon;
        private int tint;

        ActionRow(Context context) {
            super(context);
            setGravity(Gravity.CENTER_VERTICAL);
            setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(context, 55)));
            setPadding(dp(context, 10), 0, dp(context, 14), 0);
            setBackground(pressable(context, CARD, 16, Color.TRANSPARENT));
            badge = new android.widget.FrameLayout(context);
            addView(badge, new LayoutParams(dp(context, 37), dp(context, 37)));
            label = text(context, "", 15, TEXT);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(dp(context, 12), 0, 0, 0);
            addView(label, new LayoutParams(0, -1, 1));
        }

        void bind(Action action) {
            int color = action.destructive() ? 0xffffaeba : ACCENT;
            if (!action.glyph().equals(icon) || color != tint) {
                icon = action.glyph();
                tint = color;
                badge.removeAllViews();
                badge.setBackground(surface(getContext(), action.destructive() ? 0xff392433 : 0xff252139,
                        12, Color.TRANSPARENT));
                badge.addView(new Glyph(getContext(), icon, color, 19), new android.widget.FrameLayout.LayoutParams(-1, -1));
            }
            label.setText(action.title());
            label.setTextColor(action.destructive() ? color : TEXT);
        }
    }

    private NyRoSheet() {}
}
