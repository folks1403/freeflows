package com.freeflows.android.test;

import android.test.ActivityInstrumentationTestCase2;
import android.test.InstrumentationTestRunner;
import android.widget.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Live-room opt-in test: connects as spectator and never activates microphone or AI. */
@SuppressWarnings("deprecation")
public class ConnectionTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public ConnectionTest() { super(MainActivity.class); }
    public void testJoinRoomAsSpectator() throws Throwable {
        String invitation = ((InstrumentationTestRunner)getInstrumentation()).getArguments().getString("invite");
        assertNotNull("Supply the current HTTPS invitation with -e invite", invitation);
        MainActivity activity = getActivity();
        runTestOnUiThread(() -> {
            ((EditText)activity.findViewById(101)).setText(invitation);
            ((EditText)activity.findViewById(102)).setText("Teste Galaxy A32");
            ((CheckBox)activity.findViewById(103)).setChecked(false);
            ((CheckBox)activity.findViewById(104)).setChecked(false);
            activity.findViewById(105).performClick();
        });
        AtomicBoolean joined = new AtomicBoolean();
        long deadline = android.os.SystemClock.elapsedRealtime() + 45000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            getInstrumentation().runOnMainSync(() -> joined.set(activity.findViewById(106) != null));
            if (joined.get()) break;
            Thread.sleep(250);
        }
        assertTrue("The app did not reach the room", joined.get());
        runTestOnUiThread(() -> {
            assertFalse(((CheckBox)activity.findViewById(104)).isChecked());
            activity.findViewById(110).performClick();
            assertNotNull(activity.findViewById(105));
        });
    }
}
