package com.mj.simplekeyboard;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A plain QWERTY keyboard: letters, a number/symbol page, shift, backspace, space, enter,
 * with quiet autocorrect (no suggestion bar): typos are fixed when a word ends, backspace
 * always stays on, and a quick double space types ". ".
 */
public class KeyboardService extends InputMethodService {

    private static final String TAG = "SimpleKeyboard";
    /** Keys that end a word and so trigger autocorrect. */
    private static final String SEPARATORS = ",.!?:;\")";
    private static final long DOUBLE_SPACE_MS = 600;

    private static final String SHIFT = "SHIFT";
    private static final String BACK = "BACK";
    private static final String ENTER = "ENTER";
    private static final String SPACE = "SPACE";
    private static final String TO_SYMBOLS = "TO_SYM";
    private static final String TO_LETTERS = "TO_ABC";

    private static final String[][] LETTERS = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
            {SHIFT, "z", "x", "c", "v", "b", "n", "m", BACK},
            {TO_SYMBOLS, ",", SPACE, ENTER},
    };

    private static final String[][] SYMBOLS = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"@", "#", "$", "&", "-", "+", "(", ")", "/"},
            {"*", "\"", "'", ":", ";", "!", "?", "_", BACK},
            {TO_LETTERS, ",", SPACE, ".", ENTER},
    };

    private static final int BG = Color.rgb(43, 44, 48);
    private static final int KEY = Color.rgb(70, 71, 76);
    private static final int KEY_PRESSED = Color.rgb(100, 101, 107);
    /** Shift, backspace, comma, ?123 and enter. */
    private static final int KEY_ACCENT = Color.rgb(61, 70, 94);
    private static final int KEY_ACCENT_PRESSED = Color.rgb(88, 99, 128);
    private static final int TEXT = Color.rgb(227, 227, 227);

    private LinearLayout root;
    private final java.util.List<TextView> letterKeys = new java.util.ArrayList<>();
    private TextView shiftKey;
    private boolean symbols = false;
    private boolean shift = false;
    private boolean capsLock = false;
    private long lastShiftTap = 0;

    private final Autocorrect autocorrect = new Autocorrect();
    private boolean correctThisField;
    private long lastSpaceTime;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable repeatDelete = new Runnable() {
        @Override public void run() {
            deleteOne();
            handler.postDelayed(this, 50);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Thread loader = new Thread(() -> {
            try (BufferedReader words = asset("words.txt");
                 BufferedReader shortcuts = asset("shortcuts.txt")) {
                autocorrect.load(words, shortcuts);
            } catch (IOException | RuntimeException e) {
                // Typing still works without the word list; only autocorrect is off.
                Log.e(TAG, "Could not load the word list; autocorrect is off", e);
            }
        }, "word-list");
        loader.setPriority(Thread.MIN_PRIORITY);
        loader.start();
    }

    private BufferedReader asset(String name) throws IOException {
        return new BufferedReader(new InputStreamReader(
                getAssets().open(name), StandardCharsets.UTF_8));
    }

    @Override
    public View onCreateInputView() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(BG);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(3);
        root.setPadding(pad, pad, pad, dp(6));
        outer.addView(root);

        // Bottom strip. On Android 16+ we hide the system's strip (globe + down arrow) and draw
        // this one instead, with our own hide arrow at the right. On older versions the system
        // strip is drawn over this space, so it stays empty there.
        final FrameLayout bottomBar = new FrameLayout(this);
        if (ownsNavStrip()) {
            ImageView hide = new ImageView(this);
            hide.setImageResource(R.drawable.ic_hide);
            hide.setScaleType(ImageView.ScaleType.CENTER);
            hide.setContentDescription("Hide keyboard");
            hide.setBackground(pressedOnly());
            hide.setOnClickListener(v -> requestHideSelf(0));
            bottomBar.addView(hide, new FrameLayout.LayoutParams(
                    dp(64), LinearLayout.LayoutParams.MATCH_PARENT, Gravity.END));
        }
        outer.addView(bottomBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0));

        outer.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            int height = ownsNavStrip() ? Math.max(bottom, dp(44)) : bottom;
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) bottomBar.getLayoutParams();
            if (lp.height != height) {
                lp.height = height;
                bottomBar.setLayoutParams(lp);
            }
            return insets;
        });
        build();
        return outer;
    }

    /** Android 16+ lets a keyboard hide the system strip under it (where the globe lives). */
    private static boolean ownsNavStrip() {
        return Build.VERSION.SDK_INT >= 36;
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        if (ownsNavStrip()) {
            Window w = getWindow().getWindow();
            if (w != null) w.getInsetsController().hide(WindowInsets.Type.captionBar());
        }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        symbols = false;
        capsLock = false;
        shift = false;
        correctThisField = wantsCorrection(info);
        lastSpaceTime = 0;
        build();
        updateAutoShift();
    }

    /** Autocorrect only plain text: not passwords, emails, web addresses or numbers. */
    private static boolean wantsCorrection(EditorInfo info) {
        if (info == null) return false;
        int type = info.inputType;
        if ((type & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false;
        if ((type & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false;
        switch (type & InputType.TYPE_MASK_VARIATION) {
            case InputType.TYPE_TEXT_VARIATION_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
            case InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
            case InputType.TYPE_TEXT_VARIATION_URI:
            case InputType.TYPE_TEXT_VARIATION_FILTER:
                return false;
            default:
                return true;
        }
    }

    /** Turns shift on at the start of a sentence, when the text box asks for that. */
    private void updateAutoShift() {
        if (capsLock || symbols) return;
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        boolean want = ic != null && info != null && info.inputType != 0
                && ic.getCursorCapsMode(info.inputType) != 0;
        if (want != shift) {
            shift = want;
            refreshShift();
        }
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        handler.removeCallbacks(repeatDelete);
        super.onFinishInputView(finishingInput);
    }

    private void build() {
        if (root == null) return;
        root.removeAllViews();
        letterKeys.clear();
        shiftKey = null;
        String[][] rows = symbols ? SYMBOLS : LETTERS;
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            // The second letter row has 9 keys; pad its ends so keys line up like a real QWERTY.
            boolean indent = !symbols && r == 1;
            if (indent) row.addView(spacer(0.5f));
            for (String k : rows[r]) row.addView(makeKey(k));
            if (indent) row.addView(spacer(0.5f));
            root.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));
        }
        refreshShift();
    }

    /** Update letter case and the shift icon in place, so quick taps aren't lost to a rebuild. */
    private void refreshShift() {
        boolean up = upper();
        for (TextView k : letterKeys) {
            String c = (String) k.getTag();
            k.setText(up ? c.toUpperCase() : c);
        }
        if (shiftKey != null) {
            shiftKey.setBackground(withIcon(keyBackground(true, false), capsLock
                    ? R.drawable.ic_caps_lock : up ? R.drawable.ic_shift_on : R.drawable.ic_shift));
        }
    }

    private View spacer(float weight) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, weight));
        return v;
    }

    private TextView makeKey(final String code) {
        TextView key = new TextView(this);
        key.setGravity(Gravity.CENTER);
        key.setTextColor(TEXT);
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        key.setSingleLine(true);

        float weight = 1f;
        boolean accent = true;
        boolean pill = false;
        int icon = 0;
        switch (code) {
            case SHIFT:
                shiftKey = key;
                weight = 1.5f;
                break;
            case BACK:
                icon = R.drawable.ic_backspace;
                key.setContentDescription("Delete");
                weight = 1.5f;
                break;
            case ENTER:
                icon = R.drawable.ic_enter;
                key.setContentDescription("Enter");
                weight = 1.5f;
                pill = true;
                break;
            case SPACE:
                key.setContentDescription("Space");
                weight = symbols ? 5f : 6f; // letters page has no period key; space takes its spot
                accent = false;
                break;
            case TO_SYMBOLS:
            case TO_LETTERS:
                key.setText(TO_SYMBOLS.equals(code) ? "?123" : "ABC");
                key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                weight = 1.5f;
                pill = true;
                break;
            default:
                key.setTag(code);
                if (Character.isLetter(code.charAt(0))) letterKeys.add(key);
                key.setText(code);
                // Comma and period sit beside the space bar in the accent colour.
                accent = ",".equals(code) || ".".equals(code);
        }

        key.setBackground(icon != 0
                ? withIcon(keyBackground(accent, pill), icon) : keyBackground(accent, pill));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(3), dp(4), dp(3), dp(4));
        key.setLayoutParams(lp);

        if (BACK.equals(code)) {
            key.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        lastSpaceTime = 0;
                        deleteOne();
                        handler.postDelayed(repeatDelete, 400);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        handler.removeCallbacks(repeatDelete);
                        updateAutoShift();
                        return true;
                }
                return false;
            });
        } else {
            key.setOnClickListener(v -> press(code));
        }
        return key;
    }

    private boolean upper() {
        return shift || capsLock;
    }

    private void press(String code) {
        InputConnection ic = getCurrentInputConnection();
        switch (code) {
            case SHIFT: {
                long now = System.currentTimeMillis();
                if (capsLock) {
                    capsLock = false;
                    shift = false;
                } else if (shift && now - lastShiftTap < 400) {
                    capsLock = true; // double tap
                } else {
                    shift = !shift;
                }
                lastShiftTap = now;
                refreshShift();
                return;
            }
            case TO_SYMBOLS:
                symbols = true;
                build();
                return;
            case TO_LETTERS:
                symbols = false;
                build();
                return;
            case SPACE: {
                if (ic == null) return;
                long now = System.currentTimeMillis();
                if (now - lastSpaceTime < DOUBLE_SPACE_MS && doubleSpacePeriod(ic)) {
                    lastSpaceTime = 0;
                } else {
                    endWord(ic, " ");
                    lastSpaceTime = now;
                }
                updateAutoShift();
                return;
            }
            case ENTER:
                lastSpaceTime = 0;
                enter(ic);
                updateAutoShift();
                return;
            default:
                if (ic == null) return;
                lastSpaceTime = 0;
                if (SEPARATORS.contains(code)) {
                    endWord(ic, code);
                    updateAutoShift();
                    return;
                }
                ic.commitText(upper() ? code.toUpperCase() : code, 1);
                if (shift && !capsLock) {
                    shift = false;
                    refreshShift();
                }
        }
    }

    /** Ends the word before the cursor: autocorrects it, then types {@code separator}. */
    private void endWord(InputConnection ic, String separator) {
        ic.beginBatchEdit();
        correctWordBeforeCursor(ic);
        ic.commitText(separator, 1);
        ic.endBatchEdit();
    }

    /** Replaces the word right before the cursor with its correction, if it has one. */
    private void correctWordBeforeCursor(InputConnection ic) {
        if (!correctThisField || !autocorrect.isReady()) return;
        if (!TextUtils.isEmpty(ic.getSelectedText(0))) return;
        CharSequence after = ic.getTextAfterCursor(1, 0);
        if (after != null && after.length() > 0 && isWordChar(after.charAt(0))) {
            return; // cursor is inside a word
        }
        final int window = 40;
        CharSequence before = ic.getTextBeforeCursor(window, 0);
        if (before == null) return;
        int end = before.length();
        int i = end;
        while (i > 0 && isWordChar(before.charAt(i - 1))) i--;
        if (i == 0 && end == window) return; // word may be longer than we can see
        if (i > 0) {
            char prev = before.charAt(i - 1);
            // @names, #tags, paths, numbers and words joined by - _ or . : leave alone.
            if (Character.isLetterOrDigit(prev) || "@#/_-.\\".indexOf(prev) >= 0) return;
        }
        while (i < end && before.charAt(i) == '\'') i++;
        if (i >= end || before.charAt(end - 1) == '\'') return;
        String typed = before.subSequence(i, end).toString();
        String fixed = autocorrect.correct(typed);
        if (fixed == null) return;
        ic.deleteSurroundingText(typed.length(), 0);
        ic.commitText(fixed, 1);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetter(c) || c == '\'';
    }

    /** Quick double space after a word or number: "word  " becomes "word. ". */
    private boolean doubleSpacePeriod(InputConnection ic) {
        if (!correctThisField) return false;
        CharSequence b = ic.getTextBeforeCursor(2, 0);
        if (b == null || b.length() < 2 || b.charAt(1) != ' '
                || !Character.isLetterOrDigit(b.charAt(0))) {
            return false;
        }
        ic.beginBatchEdit();
        ic.deleteSurroundingText(1, 0);
        ic.commitText(". ", 1);
        ic.endBatchEdit();
        return true;
    }

    private void enter(InputConnection ic) {
        if (ic == null) return;
        ic.beginBatchEdit();
        correctWordBeforeCursor(ic);
        ic.endBatchEdit();
        EditorInfo info = getCurrentInputEditorInfo();
        int action = info == null ? EditorInfo.IME_ACTION_NONE
                : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noAction = info == null
                || (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                || action == EditorInfo.IME_ACTION_NONE
                || action == EditorInfo.IME_ACTION_UNSPECIFIED;
        if (noAction) {
            ic.commitText("\n", 1);
        } else {
            ic.performEditorAction(action);
        }
    }

    private void deleteOne() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence selected = ic.getSelectedText(0);
        if (!TextUtils.isEmpty(selected)) {
            ic.commitText("", 1);
            return;
        }
        // Delete a whole surrogate pair (e.g. a pasted emoji) instead of half of it.
        CharSequence before = ic.getTextBeforeCursor(2, 0);
        int n = 1;
        if (before != null && before.length() == 2
                && Character.isSurrogatePair(before.charAt(0), before.charAt(1))) {
            n = 2;
        }
        ic.deleteSurroundingText(n, 0);
    }

    private StateListDrawable keyBackground(boolean accent, boolean pill) {
        float radius = pill ? dp(100) : dp(8);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed},
                rounded(accent ? KEY_ACCENT_PRESSED : KEY_PRESSED, radius));
        s.addState(new int[]{}, rounded(accent ? KEY_ACCENT : KEY, radius));
        return s;
    }

    /** Transparent until pressed (the hide arrow under the keys). */
    private StateListDrawable pressedOnly() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(KEY_PRESSED, dp(100)));
        s.addState(new int[]{}, rounded(Color.TRANSPARENT, dp(100)));
        return s;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** A key background with an icon drawn centred on top of it. */
    private Drawable withIcon(Drawable background, int icon) {
        LayerDrawable layers = new LayerDrawable(new Drawable[]{background, getDrawable(icon)});
        layers.setLayerGravity(1, Gravity.CENTER);
        return layers;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
