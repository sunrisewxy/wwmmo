
package au.com.codeka.warworlds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import warworlds.Warworlds.Hello;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import au.com.codeka.warworlds.api.ApiClient;
import au.com.codeka.warworlds.api.ApiException;
import au.com.codeka.warworlds.ctrl.TransparentWebView;
import au.com.codeka.warworlds.game.StarfieldActivity;
import au.com.codeka.warworlds.model.BuildQueueManager;
import au.com.codeka.warworlds.model.BuildingDesignManager;
import au.com.codeka.warworlds.model.ChatManager;
import au.com.codeka.warworlds.model.Empire;
import au.com.codeka.warworlds.model.EmpireManager;

/**
 * Main activity. Displays the message of the day and lets you select "Start Game", "Options", etc.
 */
public class WarWorldsActivity extends Activity {
    private static Logger log = LoggerFactory.getLogger(WarWorldsActivity.class);
    private Context mContext = this;
    private Button mStartGameButton;
    private TextView mConnectionStatus;
    private Handler mHandler;

    private static final int OPTIONS_DIALOG = 1000;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        log.info("WarWorlds activity starting...");
        super.onCreate(savedInstanceState);

        Util.loadProperties(mContext, this);
        Authenticator.configure(mContext);

        mHandler = new Handler();

        requestWindowFeature(Window.FEATURE_NO_TITLE); // remove the title bar
        setHomeScreenContent();
    }

    @Override
    public void onResume() {
        super.onResume();

        SharedPreferences prefs = Util.getSharedPreferences(mContext);
        if (prefs.getString("AccountName", null) == null) {
            startActivity(new Intent(this, AccountsActivity.class));
            return;
        }
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        if (id == OPTIONS_DIALOG) {
            GlobalOptionsDialog dialog = new GlobalOptionsDialog(mContext);
            return dialog;
        }

        return super.onCreateDialog(id);
    }

    /**
     * Says "hello" to the server. Lets it know who we are, fetches the MOTD and if there's
     * no empire registered yet, switches over to the \c EmpireSetupActivity.
     * 
     * @param motd The \c WebView we'll install the MOTD to.
     */
    private void sayHello(final TransparentWebView motdView, final int retries) {

        mStartGameButton.setEnabled(false);
        if (retries == 0) {
            mConnectionStatus.setText("Connecting...");
        } else {
            mConnectionStatus.setText(String.format("Retrying (#%d)...", retries+1));
        }

        // if we've saved off the authentication cookie, cool!
        SharedPreferences prefs = Util.getSharedPreferences(mContext);
        final String accountName = prefs.getString("AccountName", null);
        if (accountName == null) {
            // You're not logged in... how did we get this far anyway?
            startActivity(new Intent(this, AccountsActivity.class));
            return;
        }

        new AsyncTask<Void, Void, String>() {
            private boolean mNeedsEmpireSetup;
            private boolean mErrorOccured;

            @Override
            protected String doInBackground(Void... arg0) {
                // re-authenticate and get a new cookie
                String authCookie = Authenticator.authenticate(WarWorldsActivity.this, accountName);
                ApiClient.getCookies().add(authCookie);

                // say hello to the server
                String message;
                try {
                    String deviceRegistrationKey = DeviceRegistrar.getDeviceRegistrationKey(mContext);
                    if (deviceRegistrationKey.length() == 0) {
                        mErrorOccured = true;
                        message = "<p>Your device was not registered... for some reason.</p>";
                        return message;
                    }
                    String url = "hello/"+deviceRegistrationKey;
                    Hello hello = ApiClient.putProtoBuf(url, null, Hello.class);
                    if (hello == null) {
                        // Usually this happens on the dev server when we've just cleared the
                        // data store. Not good :-)
                        throw new ApiException("Server Error");
                    }

                    if (hello.hasEmpire()) {
                        mNeedsEmpireSetup = false;
                        EmpireManager.getInstance().setup(
                                Empire.fromProtocolBuffer(hello.getEmpire()));
                    } else {
                        mNeedsEmpireSetup = true;
                    }

                    ChatManager.getInstance().setup(hello.getChannelToken());

                    message = hello.getMotd().getMessage();
                    mErrorOccured = false;
                } catch(ApiException e) {
                    message = "<p class=\"error\">An error occured talking to the server, check " +
                              "data connection.</p>";
                    mErrorOccured = true;
                }

                return message;
            }

            @Override
            protected void onPostExecute(String result) {
                BuildingDesignManager.getInstance().setup();
                BuildQueueManager.getInstance().setup();

                mConnectionStatus.setText("Connected");
                mStartGameButton.setEnabled(true);
                if (mNeedsEmpireSetup) {
                    startActivity(new Intent(mContext, EmpireSetupActivity.class));
                } else {
                    motdView.loadHtml("html/motd-template.html", result);
                }

                if (mErrorOccured) {
                    mConnectionStatus.setText("Connection Failed");
                    mStartGameButton.setEnabled(false);

                    // if there's an error, try again in a few seconds
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            sayHello(motdView, retries+1);
                        }
                    }, 3000);
                }
            }
        }.execute();
    }

    private void setHomeScreenContent() {
        setContentView(R.layout.home);

        mStartGameButton = (Button) findViewById(R.id.start_game_btn);
        mConnectionStatus = (TextView) findViewById(R.id.connection_status);
        final Button logOutButton = (Button) findViewById(R.id.log_out_btn);
        final Button optionsButton = (Button) findViewById(R.id.options_btn);

        final TransparentWebView motd = (TransparentWebView) findViewById(R.id.home_motd);

        logOutButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(mContext, AccountsActivity.class));
            }
        });

        optionsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showDialog(OPTIONS_DIALOG);
            }
        });

        mStartGameButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(mContext, StarfieldActivity.class));
            }
        });

        sayHello(motd, 0);
    }
}
