package com.bare.launcher;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.AlertDialog;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Device-level regressions for context-menu and drawer focus ownership. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class LauncherCustomizationTest {

    @Test
    public void contextMenu_forApp_exposesRename() {
        try (ActivityScenario<LauncherActivity> scenario =
                     ActivityScenario.launch(LauncherActivity.class)) {
            View homeCell = awaitFocusedCell(scenario, "CellView");
            scenario.onActivity(activity -> assertTrue(homeCell.performLongClick()));
            getInstrumentation().waitForIdleSync();

            scenario.onActivity(activity -> {
                View rename = (View) field(activity, "menuRename");
                assertNotNull(rename);
                assertEquals(View.VISIBLE, rename.getVisibility());
            });
        }
    }

    @Test
    public void renameDialog_hasFocusedInputAndWorkingSaveResetButtons() {
        try (ActivityScenario<LauncherActivity> scenario =
                     ActivityScenario.launch(LauncherActivity.class)) {
            View homeCell = awaitFocusedCell(scenario, "CellView");
            AppInfo app = (AppInfo) field(homeCell, "boundApp");
            String sourceLabel = app.sourceLabel;

            openRenameDialog(scenario, homeCell);
            scenario.onActivity(activity -> {
                AlertDialog dialog = (AlertDialog) field(activity, "renameDialog");
                EditText input = (EditText) field(activity, "renameInput");
                assertNotNull(dialog);
                assertTrue(dialog.isShowing());
                assertTrue(input.hasFocus());
                assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isFocusable());
                assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isFocusable());
                input.setText("Game Console");
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertEquals("Game Console", app.label));

            View renamedCell = awaitFocusedCell(scenario, "CellView");
            openRenameDialog(scenario, renamedCell);
            scenario.onActivity(activity -> {
                AlertDialog dialog = (AlertDialog) field(activity, "renameDialog");
                assertNotNull(dialog.getButton(AlertDialog.BUTTON_NEUTRAL));
                assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isFocusable());
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            });
            getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertEquals(sourceLabel, app.label));
        }
    }

    @Test
    public void refreshArtwork_whileDrawerVisible_keepsFocusInDrawer() {
        try (ActivityScenario<LauncherActivity> scenario =
                     ActivityScenario.launch(LauncherActivity.class)) {
            awaitFocusedCell(scenario, "CellView");
            scenario.onActivity(activity -> invoke(activity, "openDrawer"));
            View drawerCell = awaitFocusedCell(scenario, "DrawerCell");

            scenario.onActivity(activity -> {
                View drawer = (View) field(activity, "drawer");
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

    private static void openRenameDialog(ActivityScenario<LauncherActivity> scenario,
                                         View cell) {
        scenario.onActivity(activity -> {
            assertTrue(cell.performLongClick());
            View rename = (View) field(activity, "menuRename");
            assertEquals(View.VISIBLE, rename.getVisibility());
            assertTrue(rename.performClick());
        });
        getInstrumentation().waitForIdleSync();
    }

    private static View awaitFocusedCell(ActivityScenario<LauncherActivity> scenario,
                                         String classSuffix) {
        for (int attempt = 0; attempt < 80; attempt++) {
            final View[] result = new View[1];
            scenario.onActivity(activity -> {
                View focused = activity.getWindow().getDecorView().findFocus();
                if (focused != null && focused.getClass().getSimpleName().equals(classSuffix)) {
                    result[0] = focused;
                }
            });
            if (result[0] != null) return result[0];
            SystemClock.sleep(100);
            getInstrumentation().waitForIdleSync();
        }
        fail("Timed out waiting for focused " + classSuffix);
        return null;
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
