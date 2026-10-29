package com.freeflows.android.test;

import android.test.ActivityInstrumentationTestCase2;
import android.widget.*;

@SuppressWarnings("deprecation")
public class StartupTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public StartupTest() { super(MainActivity.class); }
    public void testEntryAndConsentStartWithoutMicrophone() throws Throwable {
        MainActivity activity = getActivity();
        runTestOnUiThread(() -> {
            assertNotNull(activity.findViewById(101));
            assertNotNull(activity.findViewById(102));
            assertFalse(((CheckBox)activity.findViewById(104)).isChecked());
            assertTrue(((Button)activity.findViewById(105)).isEnabled());
        });
    }
    public void testInvalidInviteDoesNotEnterRoom() throws Throwable {
        MainActivity activity = getActivity();
        runTestOnUiThread(() -> {
            ((EditText)activity.findViewById(101)).setText("http://invalid.example/?invite=x");
            ((EditText)activity.findViewById(102)).setText("Teste");
            ((CheckBox)activity.findViewById(104)).setChecked(true);
            activity.findViewById(105).performClick();
            assertNotNull(activity.findViewById(105));
            assertNull(activity.findViewById(106));
        });
    }
}
