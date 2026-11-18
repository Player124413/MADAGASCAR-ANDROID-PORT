package com.player124413.madagascar;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** A launcher-accessible, landscape editor for the exact layout.toml consumed by the native touch host. */
public final class TouchEditorActivity extends Activity {
    private File appDirectory;
    private TouchLayoutStore.Layout layout;
    private LayoutEditorView editor;
    private TextView selectedLabel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(16, 19, 15));
        window.setNavigationBarColor(Color.rgb(16, 19, 15));
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        File files = getExternalFilesDir(null);
        appDirectory = files == null ? getFilesDir() : files;
        layout = TouchLayoutStore.load(appDirectory);
        buildEditor();
    }

    private void buildEditor() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF10130F);
        editor = new LayoutEditorView();
        root.addView(editor, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(dp(10), dp(7), dp(10), dp(7));
        tools.setBackground(roundRect(0xE91C211B, 16, 0xFF6B684D, 1));

        selectedLabel = new TextView(this);
        selectedLabel.setTextColor(0xFFF4F0E7);
        selectedLabel.setTextSize(12);
        selectedLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        selectedLabel.setGravity(Gravity.CENTER);
        selectedLabel.setText(R.string.touch_editor_hint);
        tools.addView(selectedLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(25)));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addTool(row, R.string.touch_editor_smaller, v -> changeSize(-1));
        addTool(row, R.string.touch_editor_bigger, v -> changeSize(1));
        addTool(row, R.string.touch_editor_hide_show, v -> toggleHidden());
        addTool(row, R.string.touch_editor_reset, v -> {
            layout.reset();
            editor.clearSelection();
            selectedLabel.setText(R.string.touch_editor_reset_done);
            editor.invalidate();
        });
        addTool(row, R.string.touch_editor_cancel, v -> finish());
        addTool(row, R.string.touch_editor_save, v -> saveAndFinish());
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT, HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        tools.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)));

        FrameLayout.LayoutParams toolParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        // Preserve the top Xbox Start/Back controls: unlike a conventional toolbar, this sits below them.
        toolParams.topMargin = dp(72);
        root.addView(tools, toolParams);
        setContentView(root);
    }

    private void addTool(LinearLayout row, int title, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(title);
        button.setTextSize(12);
        button.setTextColor(0xFFF4F0E7);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setBackground(roundRect(0xFF30362A, 12, 0xFF59604C, 1));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
        params.rightMargin = dp(7);
        row.addView(button, params);
    }

    private void changeSize(int direction) {
        int selected = editor.selected();
        if (selected < 0) {
            Toast.makeText(this, R.string.touch_editor_select_control, Toast.LENGTH_SHORT).show();
            return;
        }
        float next = layout.radius[selected] * (direction > 0 ? 1.10f : 1f / 1.10f);
        layout.radius[selected] = Math.max(0.02f, Math.min(0.25f, next));
        editor.invalidate();
    }

    private void toggleHidden() {
        int selected = editor.selected();
        if (selected < 0) {
            Toast.makeText(this, R.string.touch_editor_select_control, Toast.LENGTH_SHORT).show();
            return;
        }
        // The left stick is the only non-hideable control, matching the native editor.
        if (selected == TouchLayoutStore.IDS.length - 1) {
            Toast.makeText(this, R.string.touch_editor_stick_required, Toast.LENGTH_SHORT).show();
            return;
        }
        layout.hidden[selected] = !layout.hidden[selected];
        updateSelected(selected);
        editor.invalidate();
    }

    private void saveAndFinish() {
        try {
            TouchLayoutStore.save(appDirectory, layout);
            Toast.makeText(this, R.string.touch_editor_saved, Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception error) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.touch_editor_save_failed_title)
                    .setMessage(getString(R.string.touch_editor_save_failed, error.getMessage()))
                    .setPositiveButton(R.string.dialog_ok, null)
                    .show();
        }
    }

    private void updateSelected(int index) {
        if (index < 0) {
            selectedLabel.setText(R.string.touch_editor_hint);
            return;
        }
        String state = layout.hidden[index] ? getString(R.string.touch_editor_hidden) : getString(R.string.touch_editor_visible);
        selectedLabel.setText(getString(R.string.touch_editor_selected, TouchLayoutStore.xboxLabel(index), state));
    }

    private GradientDrawableAdapter roundRect(int fill, int radius, int stroke, int width) {
        return new GradientDrawableAdapter(fill, dp(radius), stroke, dp(width));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** A tiny drawable avoids depending on AppCompat while retaining the launcher's rounded visual language. */
    private static final class GradientDrawableAdapter extends android.graphics.drawable.GradientDrawable {
        GradientDrawableAdapter(int fill, int radius, int stroke, int width) {
            setColor(fill);
            setCornerRadius(radius);
            if (width > 0) setStroke(width, stroke);
        }
    }

    private final class LayoutEditorView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] editable = TouchLayoutStore.editableControls();
        private int selected = -1;
        private boolean dragging;
        private float grabX;
        private float grabY;

        LayoutEditorView() {
            super(TouchEditorActivity.this);
            setFocusable(true);
            setContentDescription(getString(R.string.touch_editor_content_description));
        }

        int selected() {
            return selected;
        }

        void clearSelection() {
            selected = -1;
            dragging = false;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[] {0xFF111811, 0xFF263020, 0xFF10130F}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0x604D5943);
            canvas.drawRect(dp(12), dp(12), width - dp(12), height - dp(12), paint);
            paint.setStyle(Paint.Style.FILL);

            for (int index : editable) drawControl(canvas, index, width, height);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setTextSize(dp(12));
            paint.setColor(0xD0E0E0D8);
            canvas.drawText(getString(R.string.touch_editor_footer), width / 2f, height - dp(18), paint);
        }

        private void drawControl(Canvas canvas, int index, float width, float height) {
            float cx = layout.x[index] * width;
            float cy = layout.y[index] * height;
            float radius = Math.max(dp(24), layout.radius[index] * layout.scale * height);
            boolean hidden = layout.hidden[index];
            boolean active = index == selected;
            String label = TouchLayoutStore.xboxLabel(index);

            int fill = xboxColor(label);
            int alpha = hidden ? 65 : 205;
            paint.setColor((fill & 0x00FFFFFF) | (alpha << 24));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(active ? dp(3) : dp(1.5f));
            paint.setColor(active ? 0xFFFFD05B : 0xFFD8E1D3);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.FILL);

            if (label.startsWith("D-PAD")) {
                drawDpad(canvas, cx, cy, radius, label.substring(label.length() - 1), hidden);
            } else if ("LEFT STICK".equals(label)) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(hidden ? 0x607E8A78 : 0xFFCBD5C7);
                canvas.drawCircle(cx, cy, radius * .64f, paint);
                canvas.drawCircle(cx, cy, radius * .34f, paint);
                paint.setStyle(Paint.Style.FILL);
                drawLabel(canvas, "LS", cx, cy + radius * .18f, radius, hidden);
            } else {
                drawLabel(canvas, label, cx, cy + radius * .18f, radius, hidden);
            }
            if (hidden) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(0xD9FF6B57);
                float line = radius * .55f;
                canvas.drawLine(cx - line, cy - line, cx + line, cy + line, paint);
                canvas.drawLine(cx - line, cy + line, cx + line, cy - line, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }

        private void drawDpad(Canvas canvas, float cx, float cy, float radius, String arrow, boolean hidden) {
            paint.setColor(hidden ? 0x607E8A78 : 0xFFD2D8D0);
            float arm = radius * .28f;
            canvas.drawRoundRect(new RectF(cx - arm, cy - radius * .62f, cx + arm, cy + radius * .62f), arm * .25f, arm * .25f, paint);
            canvas.drawRoundRect(new RectF(cx - radius * .62f, cy - arm, cx + radius * .62f, cy + arm), arm * .25f, arm * .25f, paint);
            paint.setColor(hidden ? 0x60303A2D : 0xFF303A2D);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            paint.setTextSize(radius * .72f);
            canvas.drawText(arrow, cx, cy + radius * .25f, paint);
        }

        private void drawLabel(Canvas canvas, String label, float cx, float baseline, float radius, boolean hidden) {
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            float size = label.length() > 5 ? radius * .31f : radius * .56f;
            paint.setTextSize(Math.max(dp(11), size));
            paint.setColor(hidden ? 0x70FFFFFF : 0xFFF8FBF6);
            canvas.drawText(label, cx, baseline, paint);
        }

        private int xboxColor(String label) {
            if ("A".equals(label)) return 0xFF107C10;
            if ("B".equals(label)) return 0xFFC50F1F;
            if ("X".equals(label)) return 0xFF107CDB;
            if ("Y".equals(label)) return 0xFFFFB900;
            if (label.startsWith("D-PAD")) return 0xFF59655A;
            if ("START".equals(label) || "BACK".equals(label)) return 0xFF4B564A;
            return 0xFF596A60;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float width = Math.max(1f, getWidth());
            float height = Math.max(1f, getHeight());
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    int hit = hit(event.getX(), event.getY(), width, height);
                    selected = hit;
                    dragging = hit >= 0;
                    if (hit >= 0) {
                        grabX = event.getX() - layout.x[hit] * width;
                        grabY = event.getY() - layout.y[hit] * height;
                    }
                    updateSelected(selected);
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    if (dragging && selected >= 0) {
                        layout.x[selected] = clamp((event.getX() - grabX) / width, 0f, 1f);
                        layout.y[selected] = clamp((event.getY() - grabY) / height, 0f, 1f);
                        layout.hidden[selected] = false;
                        updateSelected(selected);
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }

        private int hit(float x, float y, float width, float height) {
            int result = -1;
            float nearest = Float.MAX_VALUE;
            for (int index : editable) {
                float dx = x - layout.x[index] * width;
                float dy = y - layout.y[index] * height;
                float distance = (float) Math.hypot(dx, dy);
                float radius = Math.max(dp(24), layout.radius[index] * layout.scale * height) * 1.2f;
                if (distance <= radius && distance < nearest) {
                    result = index;
                    nearest = distance;
                }
            }
            return result;
        }

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
