package dev.voidwager.wallhunt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** Receives the installer session's status: shows Android's confirm screen, or records why it failed. */
public class InstallReceiver extends BroadcastReceiver {
    static final String ACTION = "dev.voidwager.wallhunt.INSTALL_STATUS";

    @Override
    public void onReceive(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            // STATUS_SUCCESS never reaches here in practice: the update replaces this process.
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            c.getSharedPreferences(MainActivity.PREFS, 0).edit()
                    .putString("upd_err", status == PackageInstaller.STATUS_FAILURE_ABORTED
                            ? "update cancelled" : msg == null ? "install failed (" + status + ")" : msg)
                    .apply();
        }
    }
}
