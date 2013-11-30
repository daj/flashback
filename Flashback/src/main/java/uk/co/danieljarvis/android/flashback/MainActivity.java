package uk.co.danieljarvis.android.flashback;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.KeyguardManager.KeyguardLock;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.PhoneLookup;
import android.telephony.PhoneNumberUtils;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    final static String mTAG = "Flashback";

    long mLastTimeShown = 0;

    LayoutInflater mLayoutInflater;

    private int mMaxNumberOfToasts = 5;
    private int mTextMsgLengthChars = 200;

    private int mPaddingLeft;
    private int mPaddingTop;
    private int mPaddingTopICS;

    private int mToastLineHeight;
    private int mDesiredHeight;

    private KeyguardManager mKeyguardMgr;
    private KeyguardLock mKeyguardLock = null;

    boolean mDidDisableKeyguard = false;

    // Allow room for the Accept and Decline buttons on the dialer.  The
    // user should always be able to handle the call without having to
    // close Flashback first.
    //
    // They mostly appear at the bottom of the screen, but on some devices
    // they are slightly up from the bottom.
    private int mPaddingBottom;

    private int mRowHeight;

    // Used for displaying call times in an nice format like "5 minutes ago"
    long mCurrentTime;

    TelephonyManager mTelephonyManager = null;
    PhoneStateListener mListener;

    /**
     * Created by the CustomPhoneStateListener
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(mTAG, "MainActivity being created");
        super.onCreate(savedInstanceState);

        Resources res = getResources();

        mPaddingBottom = res.getDimensionPixelSize(R.dimen.padding_bottom);
        mPaddingLeft = res.getDimensionPixelSize(R.dimen.padding_left);
        mPaddingTop = res.getDimensionPixelSize(R.dimen.padding_top);
        mPaddingTopICS = res.getDimensionPixelSize(R.dimen.padding_top_ICS);

        mToastLineHeight = res.getDimensionPixelSize(R.dimen.toast_line_height);

        mRowHeight = res.getDimensionPixelSize(R.dimen.row_height);

        Intent intent = getIntent();
        final String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

        if ((number != null) && (number.length() > 0)) {
            Log.d(mTAG, "MainActivity launched by CustomPhoneStateListener");
        } else {
            Log.i(mTAG, "Flashback was launched from the apps screen - finish");
            showHelpToast();
            finish();
        }
    }

    protected void showHelpToast() {
        Toast.makeText(getApplicationContext(),
                getString(R.string.help_text),
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onStart() {
        Log.i(mTAG, "MainActivity being started");
        super.onStart();
    }

    @Override
    protected void onResume() {
        Log.i(mTAG, "MainActivity being resumed");

        Intent intent = getIntent();
        final String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

        if ((number != null) && (number.length() > 0)) {
            Log.d(mTAG, "MainActivity resumed by CustomPhoneStateListener");
        } else {
            Log.i(mTAG, "Flashback was resumed from the apps screen - finish");
            showHelpToast();
            finish();
        }

        super.onResume();
    }

    private void commonStart(Intent intent) {
        String number = "";

        if (intent != null) {
            // Save the correlator from the start intent.  This must be included on
            // all subsequent messages.
            number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            Log.i(mTAG, "Phone number is " + number);
        }

        if ((number == null) || (number.length() == 0)) {
            // MainActivity should never have been launched in this situation
            Log.w(mTAG, "Caller number withheld, ignoring");
        } else {
            Log.i(mTAG, "Common start code");

            // Check if the phone is currently locked
            mKeyguardMgr = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            boolean isKeyguardUp = mKeyguardMgr.inKeyguardRestrictedInputMode();

            if (isKeyguardUp) {
                Log.i(mTAG, "Keyguard is up - disable it");

                mKeyguardLock = mKeyguardMgr.newKeyguardLock("uk.co.danieljarvis.android.Flashback");
                mKeyguardLock.disableKeyguard();

                Log.i(mTAG, "Add FLAG_SHOW_WHEN_LOCKED to window");
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

                mDidDisableKeyguard = true;
            }

            try {
                // Create a new PhoneStateListener
                mListener = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String incomingNumber) {
                        switch (state) {
                            case TelephonyManager.CALL_STATE_RINGING: {
                                Log.i(mTAG, "MainActivity - Ringing");
                                break;
                            }

                            case TelephonyManager.CALL_STATE_IDLE: {
                                Log.i(mTAG, "MainActivity - Idle, shutting flashback popup");
                                finish();
                                break;
                            }

                            case TelephonyManager.CALL_STATE_OFFHOOK: {
                                Log.i(mTAG, "MainActivity - OffHook");
                                //finish();
                                break;
                            }
                        }
                    }
                };

                // Get the telephony manager
                mTelephonyManager =
                        (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

                // Register the listener with the telephony manager
                mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_CALL_STATE);
            } catch (Exception e) {
                Log.e(mTAG, "Exception when trying to listen for call state events " + e);
            }

            handleRinging(this, number);
        }
    }

    @Override
    protected void onPause() {
        Log.d(mTAG, "onPause");
        super.onPause();
    }

    @Override
    protected void onStop() {
        Log.d(mTAG, "onStop");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.d(mTAG, "onDestroy");

        if (mDidDisableKeyguard && (mKeyguardLock != null)) {
            Log.i(mTAG, "Reenable keyguard");
            mKeyguardLock.reenableKeyguard();
        }

        if (mTelephonyManager != null) {
            Log.d(mTAG, "Deregister phone state listener");
            mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_NONE);
        }

        // Call the superclass method for general onDestroy processing.
        super.onDestroy();
    }

    /**
     * If the user manually clicks close
     * <p/>
     * The activity will also finish if the call ends
     */
    public void onCloseClick(View v) {
        Log.i(mTAG, "Close clicked");
        finish();
    }

    public void handleRinging(Context context, String remotePhone) {
        if (remotePhone == null) {
            Log.w(mTAG, "Null remote phone, setting to empty string");
            remotePhone = "";
        }

        boolean contactFound = false;
        String contactName = "";

        try {
            // Initialize the contact name to be the phone number as it is
            // better to display that than nothing at all!
            contactName = PhoneNumberUtils.formatNumber(remotePhone);
        } catch (Exception ex) {
            Log.e(mTAG, "ERROR: " + ex.toString());
            ex.printStackTrace();
        }

        Cursor cursor = null;
        try {
            // Only try and look up the contact name if the remote phone
            // number is available
            if ((remotePhone != null) && (remotePhone.length() > 0)) {
                Uri contactUri =
                        Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                                Uri.encode(remotePhone));

                String[] projection = new String[]{PhoneLookup.DISPLAY_NAME};
                cursor = context.getContentResolver().query(contactUri,
                        projection,
                        null,
                        null,
                        null);

                if (cursor.moveToFirst()) {
                    contactName =
                            cursor.getString(cursor.getColumnIndexOrThrow(PhoneLookup.DISPLAY_NAME));
                    contactFound = true;
                    Log.i(mTAG, "Contact matches: " + contactName);
                }
            }
        } catch (Exception ex) {
            Log.e(mTAG, "ERROR: " + ex.toString());
            ex.printStackTrace();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        Log.d(mTAG, "Remote phone: " + remotePhone);

        mCurrentTime = System.currentTimeMillis();

        List<ToastListItem> toastList = getSmsDetails(context, remotePhone);

        final String[] projection = null;
        final String sortOrder = android.provider.CallLog.Calls.DATE + " DESC";

        cursor = null;
        try {
            if ((remotePhone != null) && (remotePhone.length() > 0)) {
                // Only get the call logs associated with the incoming call number
                Uri contactUri =
                        Uri.withAppendedPath(Uri.parse("content://call_log/calls/filter"),
                                Uri.encode(remotePhone));

                cursor = context.getContentResolver().query(contactUri,
                        projection,
                        null,
                        null,
                        sortOrder);

                int count = 0;

                Log.d(mTAG, "Querying call logs for " + remotePhone);

                mLayoutInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

                while (cursor.moveToNext()) {
                    long callDate =
                            cursor.getLong(cursor.getColumnIndex(android.provider.CallLog.Calls.DATE));

                    final int callType =
                            cursor.getInt(cursor.getColumnIndex(android.provider.CallLog.Calls.TYPE));

                    count++;

                    if (count <= mMaxNumberOfToasts) {
                        ToastListItem cl = new ToastListItem(callDate, null, false, callType);
                        toastList.add(cl);
                    }
                }

                // We only try to display Flashback toast when the number isn't withheld
                displayToast(context, toastList, contactName, contactFound);
            }
        } catch (Exception ex) {
            Log.e(mTAG, "ERROR: " + ex.toString());
            ex.printStackTrace();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void displayToast(Context context,
                              List<ToastListItem> xiList,
                              String contactName,
                              boolean contactFound) {
        // Create the layout.  Use null as a workaround due to running in a service
        //   http://www.cloud208.com/blogica/Android+Custom+Toast+from+a+Service
        View layout = mLayoutInflater.inflate(R.layout.toast_layout, null);

        TextView contact = (TextView) layout.findViewById(R.id.contact_name);
        contact.setText(contactName);

        LinearLayout toastRows = (LinearLayout) layout.findViewById(R.id.toast_rows);

        this.getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        this.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        this.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        this.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Make us non-modal, so that others can receive touch events.
        this.getWindow().setFlags(LayoutParams.FLAG_NOT_TOUCH_MODAL,
                LayoutParams.FLAG_NOT_TOUCH_MODAL);

        // ...but notify us that it happened.
        getWindow().setFlags(LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);

        this.getWindow().setGravity(Gravity.TOP | Gravity.LEFT);

        // Work out how big our popup is going to be
        int titleBarHeight = getStatusBarHeight();

        // The width is always the same
        int desiredWidth = context.getResources().getDimensionPixelSize(R.dimen.desired_width);

        // Start out allowing only for the header thickness (with the Close
        // button in it)
        int desiredHeight = context.getResources().getDimensionPixelSize(R.dimen.desired_height);

        if (xiList.isEmpty()) {
            Log.i(mTAG, "No records");

            // No records requires a slightly different layout
            View toastRow = mLayoutInflater.inflate(R.layout.toast_no_history, null);

            desiredHeight += mRowHeight;

            ImageView image = (ImageView) toastRow.findViewById(R.id.image);
            TextView text = (TextView) toastRow.findViewById(R.id.text);

            image.setImageResource(R.drawable.icon);

            if (contactFound) {
                text.setText(getString(R.string.no_records_person));
            } else {
                text.setText(getString(R.string.no_records_number));
            }

            toastRows.addView(toastRow);
        } else {
            Log.i(mTAG, xiList.size() + " records found");

            Collections.sort(xiList, new ToastListItem(0, null, false, -1));

            int count = 1;

            ImageView image = null;
            TextView date = null;
            TextView text = null;
            View toastRow = null;

            for (ToastListItem cl : xiList) {
                // Only show the latest 5 lines
                if (count <= mMaxNumberOfToasts) {
                    toastRow = mLayoutInflater.inflate(R.layout.toast_row, null);

                    image = (ImageView) toastRow.findViewById(R.id.image);
                    date = (TextView) toastRow.findViewById(R.id.date);
                    text = (TextView) toastRow.findViewById(R.id.text);

                    image.setImageResource(getImgResForCallType(cl));

                    Log.d(mTAG, count + ": " + formatTime(cl.mTimeOfArrival));
                    date.setText(formatTime(cl.mTimeOfArrival));

                    if (cl.mMsgText != null) {
                        String msg = cl.mMsgText;

                        if (msg.length() > mTextMsgLengthChars) {
                            // Defensively truncate really long text messages (this code
                            // was originally to manually add the ellipsis at the end, but
                            // now I do that through the layout file).
                            msg = msg.substring(0, mTextMsgLengthChars);
                        }

                        Log.d(mTAG, count + ": " + msg);
                        text.setText(msg);
                    }

                    desiredHeight += mRowHeight;

                    Log.d(mTAG, "Set toast " + count + ": " + text);
                    toastRows.addView(toastRow);
                } else {
                    Log.d(mTAG, "Reached count number: " + count);
                    break;
                }

                count++;
            }
        }

        mDesiredHeight = desiredHeight;
        Log.d(mTAG, "Desired dimensions: " + desiredWidth + " x " + desiredHeight);

        // Now adjust the activity size based on the actual screen size
        Display display = getWindowManager().getDefaultDisplay();
        Point screenSize = getSize(display);
        int screenWidth = screenSize.x;
        int screenHeight = screenSize.y;
        Log.i(mTAG, "Screen size : " + screenWidth + " x " + screenHeight);

        // Allow for the padding
        screenWidth = screenWidth - (2 * mPaddingLeft);
        screenHeight = screenHeight - titleBarHeight - mPaddingTop - mPaddingBottom;

        if (desiredWidth > screenWidth) {
            desiredWidth = screenWidth;
        }

        if (desiredHeight > screenHeight) {
            desiredHeight = screenHeight;
        }

        Log.i(mTAG, "Flashback size : " + desiredWidth + " x " + desiredHeight);
        this.getWindow().setLayout(desiredWidth, desiredHeight);

        this.setContentView(layout);
    }

    /**
     * @return an estimate of status bar height in pixels.
     * <p/>
     * Code based on http://stackoverflow.com/a/14213035/112705
     */
    public int getStatusBarHeight() {
        // We can't use getDecorView().getWindowVisibleDisplayFrame() with
        // window.findViewById(Window.ID_ANDROID_CONTENT).getTop() as it only
        // works after the window has actually been displayed!

        int result = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = getResources().getDimensionPixelSize(resourceId);
            Log.d(mTAG, "Status bar height: " + result);
        }
        return result;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // If we've received a touch notification that the user has touched
        // outside the app, finish the activity.
        if (MotionEvent.ACTION_OUTSIDE == event.getAction()) {
            Log.i(mTAG, "Touched outside, close Flashback");
            finish();
            return true;
        }

        // Delegate everything else to Activity.
        return super.onTouchEvent(event);
    }

    private int getImgResForCallType(ToastListItem xiTLI) {
        int callType = xiTLI.mCallType;

        if (callType == android.provider.CallLog.Calls.MISSED_TYPE) {
            return R.drawable.missed;
        } else if (callType == android.provider.CallLog.Calls.INCOMING_TYPE) {
            return R.drawable.incoming;
        } else if (callType == android.provider.CallLog.Calls.OUTGOING_TYPE) {
            return R.drawable.outgoing;
        } else {
            // This must be an SMS, find out if incoming or outgoing
            if (xiTLI.mIsInboxMsg) {
                return R.drawable.sms_incoming;
            } else {
                return R.drawable.sms_outgoing;
            }
        }
    }

    /**
     * Get all the SMS messages sent to the specified number.
     */
    public ArrayList<ToastListItem> getSmsDetails(Context context, String xiNumber) {
        ArrayList<ToastListItem> smsList = new ArrayList<ToastListItem>();

        Uri uri;
        String[] body;
        String[] number;
        long[] date;

        if ((xiNumber != null) && (xiNumber.length() > 0)) {
            Cursor cursorInbox = null;

            try {
                // Read inbox messages first
                uri = Uri.parse("content://sms/inbox");
                cursorInbox = context.getContentResolver().query(uri, null, null, null, null);

                if (cursorInbox == null) {
                    Log.w(mTAG, "Failed to query inbox SMS messages");
                } else {
                    body = new String[cursorInbox.getCount()];
                    number = new String[cursorInbox.getCount()];
                    date = new long[cursorInbox.getCount()];

                    if (cursorInbox.moveToFirst()) {
                        for (int i = 0; i < cursorInbox.getCount(); i++) {
                            if (cursorInbox.getColumnIndex("body") == -1) {
                                Log.w(mTAG, "Inbox query - body column not available");
                                continue;
                            }

                            if (cursorInbox.getColumnIndex("address") == -1) {
                                Log.w(mTAG, "Inbox query - address column not available");
                                continue;
                            }

                            if (cursorInbox.getColumnIndex("date") == -1) {
                                Log.w(mTAG, "Inbox query - date column not available");
                                continue;
                            }

                            String bodyText = cursorInbox.getString(cursorInbox.getColumnIndexOrThrow("body"));

                            if (bodyText == null) {
                                body[i] = "";
                            } else {
                                body[i] = bodyText;
                            }

                            number[i] = cursorInbox.getString(cursorInbox.getColumnIndexOrThrow("address")) + "";
                            date[i] = cursorInbox.getLong(cursorInbox.getColumnIndexOrThrow("date"));

                            cursorInbox.moveToNext();

                            //Log.d(mTAG, "SMS from: " + number[i]);

                            if (PhoneNumberUtils.compare(number[i], xiNumber)) {
                                //Log.d(mTAG, "SMS body: " + body[i]);

                                ToastListItem sms = new ToastListItem(date[i], body[i], true, -1);
                                smsList.add(sms);
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                Log.w(mTAG, "Exception querying SMS inbox: " + ex);
                ex.printStackTrace();
            } finally {
                if ((cursorInbox != null) && !cursorInbox.isClosed()) {
                    cursorInbox.close();
                }
            }

            Cursor cursorSent = null;

            try {
                // Now do the sent messages too
                uri = Uri.parse("content://sms/sent");
                cursorSent = context.getContentResolver().query(uri, null, null, null, null);

                body = new String[cursorSent.getCount()];
                number = new String[cursorSent.getCount()];
                date = new long[cursorSent.getCount()];

                if (cursorSent.moveToFirst()) {
                    for (int i = 0; i < cursorSent.getCount(); i++) {
                        if (cursorSent.getColumnIndex("body") == -1) {
                            Log.w(mTAG, "Sent messages query - body column not available");
                            continue;
                        }

                        if (cursorSent.getColumnIndex("address") == -1) {
                            Log.w(mTAG, "Sent messages query - address column not available");
                            continue;
                        }

                        if (cursorSent.getColumnIndex("date") == -1) {
                            Log.w(mTAG, "Sent messages query - date column not available");
                            continue;
                        }

                        String bodyText = cursorSent.getString(cursorSent.getColumnIndexOrThrow("body"));

                        if (bodyText == null) {
                            body[i] = "";
                        } else {
                            body[i] = bodyText;
                        }

                        number[i] = cursorSent.getString(cursorSent.getColumnIndexOrThrow("address")) + "";
                        date[i] = cursorSent.getLong(cursorSent.getColumnIndexOrThrow("date"));

                        cursorSent.moveToNext();

                        //Log.d(mTAG, "SMS from: " + number[i]);

                        if (PhoneNumberUtils.compare(number[i], xiNumber)) {
                            //Log.d(mTAG, "SMS body: " + body[i]);

                            ToastListItem sms = new ToastListItem(date[i], body[i], false, -1);
                            smsList.add(sms);
                        }
                    }
                }
            } catch (Exception ex) {
                Log.w(mTAG, "Exception querying SMS sent messages: " + ex);
                ex.printStackTrace();
            } finally {
                if ((cursorSent != null) && !cursorSent.isClosed()) {
                    cursorSent.close();
                }
            }
        }

        return smsList;
    }

    private final CharSequence formatTime(long xiItemTime) {
        final CharSequence relativeTime =
                DateUtils.getRelativeTimeSpanString(xiItemTime,
                        mCurrentTime,
                        0); // Ensure it reports "seconds ago"

        return relativeTime;
    }

    // Cope with deprecated getWidth() and getHeight() methods
    Point getSize(Display xiDisplay) {
        Point outSize = new Point();
        boolean sizeFound = false;

        try {
            // Test if the new getSize() method is available
            Method newGetSize =
                    Display.class.getMethod("getSize", new Class[]{Point.class});

            // No exception, so the new method is available
            Log.d(mTAG, "Use getSize to find screen size");
            newGetSize.invoke(xiDisplay, outSize);
            sizeFound = true;
            Log.d(mTAG, "Screen size is " + outSize.x + " x " + outSize.y);
        } catch (NoSuchMethodException ex) {
            // This is the failure I expect when the deprecated APIs are not available
            Log.d(mTAG, "getSize not available - NoSuchMethodException");
        } catch (InvocationTargetException e) {
            Log.w(mTAG, "getSize not available - InvocationTargetException");
        } catch (IllegalArgumentException e) {
            Log.w(mTAG, "getSize not available - IllegalArgumentException");
        } catch (IllegalAccessException e) {
            Log.w(mTAG, "getSize not available - IllegalAccessException");
        }

        if (!sizeFound) {
            Log.i(mTAG, "Used deprecated methods as getSize not available");
            outSize = new Point(xiDisplay.getWidth(), xiDisplay.getHeight());
        }

        return outSize;
    }

    /**
     * Position the Flashback popup!
     */
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        Log.d(mTAG, "onAttachedToWindow()");

        commonStart(getIntent());

        View view = getWindow().getDecorView();
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) view.getLayoutParams();

        Log.d(mTAG, "Original offset: " + lp.x + " x " + lp.y);

        lp.x = mPaddingLeft;
        lp.y = mPaddingTop + getStatusBarHeight();

        Display display = getWindowManager().getDefaultDisplay();
        Point screenSize = getSize(display);
        int screenHeight = screenSize.y;

        // On ICS devices we want to position Flashback so it doesn't cover
        // the contact number at the top, or the blue bar
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) &&
                (screenHeight > (mDesiredHeight + mPaddingTopICS + mPaddingBottom))) {
            Log.d(mTAG, "Adjust offset for ICS");
            lp.y += mPaddingTopICS;
        }

        Log.d(mTAG, "Altered offset: " + lp.x + " x " + lp.y);

        getWindowManager().updateViewLayout(view, lp);
    }
}
