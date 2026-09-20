package com.bare.launcher;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.AlertDialog;
import android.content.Context;
import android.os.SystemClock;
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

    private static AlertDialog openRenameDialog(
            ActivityScenario<LauncherActivity> scenario, View cell, boolean expectReset) {
        scenario.onActivity(activity -> assertTrue(cell.performLongClick()));
        View rename = awaitVisibleViewField(scenario, "menuRename");
        scenario.onActivity(activity -> assertTrue(rename.performClick()));
        return awaitRenameDialogReady(scenario, expectReset);
    }

    private static AlertDialog awaitRenameDialogReady(
            ActivityScenario<LauncherActivity> scenario, boolean expectReset) {
        return awaitValue(scenario, "ready Rename dialog", activity -> {
            AlertDialog dialog = (AlertDialog) field(activity, "renameDialog");
            EditText input = (EditText) field(activity, "renameInput");
            if (dialog == null || !dialog.isShowing() || input == null || !input.hasFocus()) {
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
