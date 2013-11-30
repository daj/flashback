package uk.co.danieljarvis.android.flashback;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.util.Log;

public class CustomPhoneStateListener extends BroadcastReceiver {
    final static String mTAG = "Flashback";

    // Retry interval for launching Flashback, in milliseconds
    final static int LAUNCH_RETRY_PERIOD_MILLIS = 250;

    // Set the maximum number of times we should wait for the
    // incoming call screen to appear
    final static int MAX_LAUNCH_ATTEMPTS = 10;

    public void onReceive(Context context, Intent intent) {
        final String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        final String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

        final Context finalContext = context;

        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            if ((number == null) || (number.length() == 0)) {
                Log.i(mTAG, "Listener - Ringing, caller number withheld, do not launch Flashback");
            } else {
                Log.i(mTAG, "Listener - Ringing, call from " + number);
                launchFlashback(finalContext, number, 1);
            }
        }
        if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            Log.i(mTAG, "Listener - Ignore Idle event");
        }
        if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            Log.i(mTAG, "Listener - Ignore OffHook event");
        }
    }

    private static String getTopActivity(final Context context) {
        ActivityManager lAM = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        RunningTaskInfo topmostTask = lAM.getRunningTasks(1).get(0);
        return topmostTask.topActivity.getClassName();
    }

    private static void launchFlashback(final Context context,
                                        final String callingNumber,
                                        final int attemptCount) {
        Log.i(mTAG, "launchFlashback attempt: " + attemptCount);

        if (attemptCount >= MAX_LAUNCH_ATTEMPTS) {
            Log.i(mTAG, "Giving up on trying to launch Flashback");
            return;
        }

        String topmostActivityClass =
                CustomPhoneStateListener.getTopActivity(context);

        Log.d(mTAG, "Class on top of UI is: " + topmostActivityClass);

        boolean isIncomingCallScreenVisible = false;

        if (topmostActivityClass.contains("com.android.phone.") &&
                topmostActivityClass.contains("InCallScreen")) {
            Log.i(mTAG, "Incoming call screen is on top");
            isIncomingCallScreenVisible = true;
        } else if (topmostActivityClass.contains("com.android.") &&
                topmostActivityClass.contains("InCallActivity")) {
            Log.i(mTAG, "Incoming call screen is on top (OS 4.4)");
            isIncomingCallScreenVisible = true;
        }

        if (isIncomingCallScreenVisible) {
            Log.i(mTAG, "Launch MainActivity for call from " + callingNumber);

            Intent launchMainActivity = new Intent(context, MainActivity.class);
            launchMainActivity.putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, callingNumber);

            launchMainActivity.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

            // Launching an Activity from outside an Activity context requires the
            // FLAG_ACTIVITY_NEW_TASK flag.  Without it you get an AndroidRuntimeException.
            launchMainActivity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // As the API docs say:
            //
            //   "If an activity is ever started via any non-user-driven events
            //    such as phone-call receipt or an alarm handler, this flag
            //    should be passed to Context.startActivity, ensuring that the
            //    pausing activity does not think the user has acknowledged its
            //    notification."
            launchMainActivity.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION);

            context.startActivity(launchMainActivity);
        } else {
            Log.i(mTAG, "Incoming call screen is NOT on top - try again in " +
                    LAUNCH_RETRY_PERIOD_MILLIS + "ms");

            final int newAttemptCount = attemptCount + 1;

            final Runnable launchTask = new Runnable() {
                public void run() {
                    CustomPhoneStateListener.launchFlashback(context,
                            callingNumber,
                            newAttemptCount);
                }
            };

            Handler handler = new Handler();
            handler.postDelayed(launchTask, LAUNCH_RETRY_PERIOD_MILLIS);
        }
    }
}