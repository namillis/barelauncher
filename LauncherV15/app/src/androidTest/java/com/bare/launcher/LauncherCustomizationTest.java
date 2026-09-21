package com.bare.launcher;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Device-level regressions for context-menu and drawer focus ownership. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class LauncherCustomizationTest {

    private static final long WAIT_TIMEOUT_MS = 8_000L;

    /** ActivityScenario closes the activity but intentionally keeps app data. */
    @Before
    public void clearCustomNamesBeforeTest() {
        clearPersistedCustomNames();
    }

    @After
    public void clearCustomNamesAfterTest() {
        clearPersistedCustomNames();
    }

    @Test
    public void contextMenu_forApp_exposesRename() {
        try (ActivityScenario<LauncherActivity> scenario =
                     ActivityScenario.launch(LauncherActivity.class)) {
            View homeCell = focusHomeCell(scenario);
            scenario.onActivity(activity -> assertTrue(homeCell.performLongClick()));

            View rename = awaitVisibleViewField(scenario, "menuRename");
            assertNotNull(rename);
        }
    }

    @Test
    public void renameDialog_hasFocusedInputAndWorkingSaveResetButtons() {
        try (ActivityScenario<LauncherActivity> scenario =
                     ActivityScenario.launch(LauncherActivity.class)) {
            View homeCell = focusHomeCell(scenario);
            AppInfo originalApp = (AppInfo) field(homeCell, "boundApp");
            String identity = originalApp.packageName;
            String sourceLabel = originalApp.sourceLabel;

            AlertDialog saveDialog = openRenameDialog(scenario, homeCell, false);
            scenario.onActivity(activity -> {
                assertSame(saveDialog, field(activity, "renameDialog"));
                EditText input = (EditText) field(activity, "renameInput");
                input.setText("Game Console");
                assertTrue(saveDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            });

            View renamedCell = awaitFocusedApp(scenario, "CellView", identity,
                    "Game Console");
            AlertDialog resetDialog = openRenameDialog(scenario, renamedCell, true);
            scenario.onActivity(activity -> {
                assertSame(resetDialog, field(activity, "renameDialog"));
                assertTrue(resetDialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
            });

            awaitFocusedApp(scenario, "CellView", identity, sourceLabel);
        }
    }

    @Test
    public void renameDialog_imeDoneHidesKeyboardAndFocusesSave() {
        try (ActivityScenario<LauncherActivity> scenario =
                     ActivityScenario.launch(LauncherActivity.class)) {
            AlertDialog dialog = openRenameDialog(scenario, focusHomeCell(scenario), false);
            scenario.onActivity(activity -> {
                EditText input = (EditText) field(activity, "renameInput");
                EditorInfo editorInfo = new EditorInfo();
                InputConnection connection = input.onCreateInputConnection(editorInfo);
                assertNotNull(connection);
                assertEquals(EditorInfo.IME_ACTION_DONE,
                        editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION);
                assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_DONE));
            });

            awaitValue(scenario, "hidden rename keyboard and focused Save", activity -> {
                EditText input = (EditText) field(activity, "renameInput");
                View save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                int softInputState = dialog.getWindow().getAttributes().softInputMode
                        & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE;
                return dialog.isShowing()
                        && !input.hasFocus()
                        && save.hasFocus()
                        && softInputState
                        == WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                        ? dialog : null;
            });
            scenario.onActivity(activity ->
                    assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()));
        }
    }

    @Test
    public void renameDialog_inputHasBalancedHorizontalSpacing() {
        try (ActivityScenario<LauncherActivity> scenario =
                     ActivityScenario.launch(LauncherActivity.class)) {
            AlertDialog dialog = openRenameDialog(
                    scenario, focusHomeCell(scenario), false);
            scenario.onActivity(activity -> {
                EditText input = (EditText) field(activity, "renameInput");
                ViewGroup inputContainer = (ViewGroup) input.getParent();
                int expectedSpacing = Math.round(24 * activity.getResources()
                        .getDisplayMetrics().density);

                assertEquals(expectedSpacing, inputContainer.getPaddingLeft());
                assertEquals(expectedSpacing, inputContainer.getPaddingRight());
                assertTrue(inputContainer.getWidth() > input.getWidth());
                assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            });
        }
    }

    @Test
    public void layoutSettings_exposesColumnsAndRoundnessControls() {
        Context context = getInstrumentation().getTargetContext();
        String prefsName = (String) staticField(LauncherActivity.class, "PREFS");
        String columnsKey = (String) staticField(LauncherActivity.class, "KEY_LAYOUT_COLUMNS");
        String cornerKey = (String) staticField(
                LauncherActivity.class, "KEY_CARD_CORNER_PERCENT");
        SharedPreferences preferences = context.getSharedPreferences(
                prefsName, Context.MODE_PRIVATE);
        boolean hadColumns = preferences.contains(columnsKey);
        boolean hadCorner = preferences.contains(cornerKey);
        int oldColumns = preferences.getInt(columnsKey, LayoutOptions.DEFAULT_COLUMNS);
        int oldCorner = preferences.getInt(cornerKey, LayoutOptions.DEFAULT_CORNER_PERCENT);
        assertTrue(preferences.edit().putInt(columnsKey, 6).putInt(cornerKey, 20).commit());

        try (ActivityScenario<LauncherActivity> scenario =
                     ActivityScenario.launch(LauncherActivity.class)) {
            scenario.onActivity(activity -> invoke(activity, "showSettingsPanel"));
            awaitVisibleViewField(scenario, "settingsOverlay");

            scenario.onActivity(activity -> invoke(activity, "activateSettingsRowId",
                    new Class<?>[] {int.class},
                    staticField(LauncherActivity.class, "SR_LAYOUT_MENU")));
            awaitValue(scenario, "Layout settings rows", activity -> {
                android.widget.LinearLayout column = (android.widget.LinearLayout)
                        field(activity, "settingsColumn");
                return column != null && column.getChildCount() == 2 ? column : null;
            });

            scenario.onActivity(activity -> {
                invoke(activity, "stepLayoutColumns", new Class<?>[] {int.class}, -1);
                invoke(activity, "stepCardCorner", new Class<?>[] {int.class}, -1);
            });
            android.widget.LinearLayout column = awaitValue(
                    scenario, "updated Layout settings", activity -> {
                        android.widget.LinearLayout current = (android.widget.LinearLayout)
                                field(activity, "settingsColumn");
                        if (current == null || current.getChildCount() != 2) return null;
                        String columnsText = ((android.widget.TextView)
                                ((ViewGroup) current.getChildAt(0)).getChildAt(1))
                                .getText().toString();
                        String cornerText = ((android.widget.TextView)
                                ((ViewGroup) current.getChildAt(1)).getChildAt(1))
                                .getText().toString();
                        return "< 5 >".equals(columnsText)
                                && "< 18% >".equals(cornerText)
                                && preferences.getInt(columnsKey, -1) == 5
                                && preferences.getInt(cornerKey, -1) == 18
                                && (Boolean) field(activity, "layoutApplyPending")
                                ? current : null;
                    });

            scenario.onActivity(activity -> {
                assertSame(column, field(activity, "settingsColumn"));
                assertEquals(activity.getString(R.string.settings_row_layout_columns),
                        ((android.widget.TextView) ((ViewGroup) column.getChildAt(0))
                                .getChildAt(0)).getText().toString());
                assertEquals("< 5 >", ((android.widget.TextView)
                        ((ViewGroup) column.getChildAt(0)).getChildAt(1)).getText().toString());
                assertEquals("< 18% >", ((android.widget.TextView)
                        ((ViewGroup) column.getChildAt(1)).getChildAt(1)).getText().toString());
                assertEquals(5, preferences.getInt(columnsKey, -1));
                assertEquals(18, preferences.getInt(cornerKey, -1));
                assertTrue((Boolean) field(activity, "layoutApplyPending"));
            });
        } finally {
            SharedPreferences.Editor restore = preferences.edit();
            if (hadColumns) restore.putInt(columnsKey, oldColumns);
            else restore.remove(columnsKey);
            if (hadCorner) restore.putInt(cornerKey, oldCorner);
            else restore.remove(cornerKey);
            assertTrue(restore.commit());
        }
    }

    @Test
    public void fourColumnLayout_demotesOverflowFavoritesAndUsesFiveByThreeCards() {
        Context context = getInstrumentation().getTargetContext();
        String prefsName = (String) staticField(LauncherActivity.class, "PREFS");
        String columnsKey = (String) staticField(LauncherActivity.class, "KEY_LAYOUT_COLUMNS");
        String cornerKey = (String) staticField(
                LauncherActivity.class, "KEY_CARD_CORNER_PERCENT");
        String homeCountKey = (String) staticField(LauncherActivity.class, "KEY_HOME_COUNT");
        String hiddenKey = (String) staticField(LauncherActivity.class, "KEY_HIDDEN");
        SharedPreferences preferences = context.getSharedPreferences(
                prefsName, Context.MODE_PRIVATE);
        boolean hadColumns = preferences.contains(columnsKey);
        boolean hadCorner = preferences.contains(cornerKey);
        boolean hadHomeCount = preferences.contains(homeCountKey);
        boolean hadHidden = preferences.contains(hiddenKey);
        int oldColumns = preferences.getInt(columnsKey, LayoutOptions.DEFAULT_COLUMNS);
        int oldCorner = preferences.getInt(cornerKey, LayoutOptions.DEFAULT_CORNER_PERCENT);
        int oldHomeCount = preferences.getInt(homeCountKey, -1);
        String oldHidden = preferences.getString(hiddenKey, "");
        assertTrue(preferences.edit().putInt(columnsKey, 4).putInt(cornerKey, 0)
                .putInt(homeCountKey, 6).putString(hiddenKey, "").commit());

        try (ActivityScenario<LauncherActivity> scenario =
                     ActivityScenario.launch(LauncherActivity.class)) {
            awaitValue(scenario, "loaded four-column layout", activity -> {
                Object shelf = field(activity, "shelf");
                @SuppressWarnings("unchecked")
                java.util.List<AppInfo> displayed = (java.util.List<AppInfo>)
                        field(shelf, "displayed");
                return ((Integer) field(activity, "homeCount")) >= 0
                        && !displayed.isEmpty() ? shelf : null;
            });
            scenario.onActivity(activity -> {
                Object shelf = field(activity, "shelf");
                @SuppressWarnings("unchecked")
                java.util.List<AppInfo> displayed = (java.util.List<AppInfo>)
                        field(shelf, "displayed");
                assertEquals("selected columns", 4,
                        ((Integer) field(activity, "layoutColumns")).intValue());
                assertEquals("overflow favorites demoted", 4,
                        ((Integer) field(activity, "homeCount")).intValue());
                assertEquals("shelf renders four favorites", 4, displayed.size());
                int tileWidth = (Integer) field(activity, "tileWpx");
                int tileHeight = (Integer) field(activity, "bannerHpx");
                assertEquals("cards use 5:3 geometry", tileWidth * 3,
                        tileHeight * 5, 2);
                assertEquals("zero percent gives square corners", 0,
                        ((Integer) field(activity, "tileCornerPx")).intValue());
            });
        } finally {
            SharedPreferences.Editor restore = preferences.edit();
            if (hadColumns) restore.putInt(columnsKey, oldColumns);
            else restore.remove(columnsKey);
            if (hadCorner) restore.putInt(cornerKey, oldCorner);
            else restore.remove(cornerKey);
            if (hadHomeCount) restore.putInt(homeCountKey, oldHomeCount);
            else restore.remove(homeCountKey);
            if (hadHidden) restore.putString(hiddenKey, oldHidden);
            else restore.remove(hiddenKey);
            assertTrue(restore.commit());
        }
    }

    @Test
    public void dpadUp_fromFirstGridRow_returnsDirectlyToMatchingHomeFavorite() {
        try (ActivityScenario<LauncherActivity> scenario =
                     ActivityScenario.launch(LauncherActivity.class)) {
            View originalHomeCell = focusHomeCell(scenario);
            AppInfo expectedFavorite = (AppInfo) field(originalHomeCell, "boundApp");

            scenario.onActivity(activity -> invoke(activity, "openDrawer"));
            View drawer = awaitVisibleViewField(scenario, "drawer");
            View gridCell = awaitFocusedCell(scenario, "DrawerCell");
            scenario.onActivity(activity -> {
                assertTrue(gridCell.dispatchKeyEvent(
                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)));
                gridCell.dispatchKeyEvent(
                        new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));
            });

            awaitValue(scenario, "closed drawer", activity ->
                    drawer.getVisibility() != View.VISIBLE ? drawer : null);
            View returnedHomeCell = awaitFocusedCell(scenario, "CellView");
            scenario.onActivity(activity -> {
                AppInfo returnedFavorite = (AppInfo) field(returnedHomeCell, "boundApp");
                assertNotNull(returnedFavorite);
                assertEquals(expectedFavorite.packageName, returnedFavorite.packageName);
                assertEquals(View.VISIBLE,
                        ((View) field(activity, "shelf")).getVisibility());
            });
        }
    }

    @Test
    public void refreshArtwork_whileDrawerVisible_keepsFocusInDrawer() {
        try (ActivityScenario<LauncherActivity> scenario =
                     ActivityScenario.launch(LauncherActivity.class)) {
            focusHomeCell(scenario);
            scenario.onActivity(activity -> invoke(activity, "openDrawer"));
            View drawer = awaitVisibleViewField(scenario, "drawer");
            scenario.onActivity(activity -> invoke(drawer, "requestFocusOnIndex",
                    new Class<?>[] {int.class, boolean.class}, 0, true));
            View drawerCell = awaitFocusedCell(scenario, "DrawerCell");

            scenario.onActivity(activity -> {
                AppInfo app = (AppInfo) field(drawerCell, "boundApp");
                assertNotNull(app);
                invoke(activity, "refreshAppArtwork", new Class<?>[] {AppInfo.class}, app);

                View focusedAfter = activity.getWindow().getDecorView().findFocus();
                assertSame(drawerCell, focusedAfter);
                assertTrue(isDescendant(drawer, focusedAfter));
                assertEquals(View.VISIBLE, drawer.getVisibility());
            });
        }
    }

    @Test
    public void focusedHomeAndGridTiles_castShadowAndClearItOnBlur() {
        try (ActivityScenario<LauncherActivity> scenario =
                     ActivityScenario.launch(LauncherActivity.class)) {
            View homeCell = focusHomeCell(scenario);
            float expectedShadow = awaitValue(scenario, "configured focus shadow", activity ->
                    Math.round(((Integer) staticField(LauncherActivity.class,
                            "FOCUS_SHADOW_Z_DP"))
                            * activity.getResources().getDisplayMetrics().density * 10f) / 10f);

            awaitValue(scenario, "home tile focus shadow", activity ->
                    Math.abs(homeCell.getTranslationZ() - expectedShadow) < 0.6f
                            ? homeCell : null);

            scenario.onActivity(activity -> invoke(activity, "openDrawer"));
            View gridCell = awaitFocusedCell(scenario, "DrawerCell");
            awaitValue(scenario, "grid tile focus shadow", activity ->
                    Math.abs(gridCell.getTranslationZ() - expectedShadow) < 0.6f
                            ? gridCell : null);

            scenario.onActivity(activity -> gridCell.setFocusable(false));
            awaitValue(scenario, "shadow removed from blurred grid tile", activity ->
                    Math.abs(gridCell.getTranslationZ()) < 0.1f ? gridCell : null);
        }
    }

    private static AlertDialog openRenameDialog(
            ActivityScenario<LauncherActivity> scenario, View cell, boolean expectReset) {
        AppInfo app = (AppInfo) field(cell, "boundApp");
        int focusHint = (Integer) field(cell, "boundIndex");
        assertNotNull(app);
        scenario.onActivity(activity -> invoke(activity, "showRenameDialog",
                new Class<?>[] {AppInfo.class, boolean.class, int.class},
                app, false, focusHint));
        AlertDialog dialog = awaitRenameDialogReady(scenario, expectReset);
        focusRenameInput(scenario);
        return dialog;
    }

    private static AlertDialog awaitRenameDialogReady(
            ActivityScenario<LauncherActivity> scenario, boolean expectReset) {
        return awaitValue(scenario, "visible Rename dialog with ready actions", activity -> {
            AlertDialog dialog = (AlertDialog) field(activity, "renameDialog");
            EditText input = (EditText) field(activity, "renameInput");
            if (dialog == null || !dialog.isShowing() || input == null) {
                return null;
            }
            if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) == null
                    || !dialog.getButton(AlertDialog.BUTTON_POSITIVE).isFocusable()
                    || dialog.getButton(AlertDialog.BUTTON_NEGATIVE) == null
                    || !dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isFocusable()) {
                return null;
            }
            if (expectReset && (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) == null
                    || !dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isFocusable())) {
                return null;
            }
            return dialog;
        });
    }

    private static void focusRenameInput(ActivityScenario<LauncherActivity> scenario) {
        scenario.onActivity(activity -> {
            EditText input = (EditText) field(activity, "renameInput");
            if (!input.hasFocus()) {
                input.setFocusableInTouchMode(true);
                input.requestFocus();
            }
        });
        awaitValue(scenario, "focused Rename input", activity -> {
            EditText input = (EditText) field(activity, "renameInput");
            return input != null && input.hasFocus() ? input : null;
        });
    }

    private static View awaitVisibleViewField(
            ActivityScenario<LauncherActivity> scenario, String fieldName) {
        return awaitValue(scenario, "visible " + fieldName, activity -> {
            View view = (View) field(activity, fieldName);
            return view != null && view.getVisibility() == View.VISIBLE && view.isShown()
                    ? view : null;
        });
    }

    private static View focusHomeCell(ActivityScenario<LauncherActivity> scenario) {
        View shelf = awaitVisibleViewField(scenario, "shelf");
        scenario.onActivity(activity -> invoke(shelf, "requestFocusOnIndex",
                new Class<?>[] {int.class, boolean.class}, 0, true));
        return awaitFocusedCell(scenario, "CellView");
    }

    private static View awaitFocusedApp(ActivityScenario<LauncherActivity> scenario,
                                        String classSuffix, String identity,
                                        String expectedLabel) {
        return awaitValue(scenario, "focused " + classSuffix + " bound to " + identity
                + " with label " + expectedLabel, activity -> {
            View focused = activity.getWindow().getDecorView().findFocus();
            if (focused == null
                    || !focused.getClass().getSimpleName().equals(classSuffix)) {
                return null;
            }
            AppInfo app = (AppInfo) field(focused, "boundApp");
            return app != null && identity.equals(app.packageName)
                    && expectedLabel.equals(app.label) ? focused : null;
        });
    }

    private static View awaitFocusedCell(ActivityScenario<LauncherActivity> scenario,
                                         String classSuffix) {
        return awaitValue(scenario, "focused " + classSuffix, activity -> {
            View focused = activity.getWindow().getDecorView().findFocus();
            return focused != null && focused.getClass().getSimpleName().equals(classSuffix)
                    ? focused : null;
        });
    }

    /** Wait for an observable UI postcondition; never guess animation or looper timing. */
    private static <T> T awaitValue(ActivityScenario<LauncherActivity> scenario,
                                    String description,
                                    Function<LauncherActivity, T> probe) {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS;
        do {
            getInstrumentation().waitForIdleSync();
            AtomicReference<T> result = new AtomicReference<>();
            scenario.onActivity(activity -> result.set(probe.apply(activity)));
            if (result.get() != null) return result.get();
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Timed out waiting for " + description);
        return null;
    }

    private static void clearPersistedCustomNames() {
        Context context = getInstrumentation().getTargetContext();
        String prefsName = (String) staticField(LauncherActivity.class, "PREFS");
        String key = (String) staticField(LauncherActivity.class, "KEY_CUSTOM_NAMES");
        boolean committed = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit().remove(key).commit();
        if (!committed) throw new AssertionError("Failed to clear custom-name test state");
    }

    private static boolean isDescendant(View ancestor, View candidate) {
        if (candidate == ancestor) return true;
        android.view.ViewParent current = candidate != null ? candidate.getParent() : null;
        while (current != null) {
            if (current == ancestor) return true;
            current = current.getParent();
        }
        return false;
    }

    private static Object staticField(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void invoke(Object target, String name) {
        invoke(target, name, new Class<?>[0]);
    }

    private static void invoke(Object target, String name, Class<?>[] parameterTypes,
                               Object... args) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
