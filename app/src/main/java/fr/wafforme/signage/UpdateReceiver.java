package fr.wafforme.signage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Résultat de l'installation lancée par Updater. */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        boolean interactive = intent.getBooleanExtra("interactive", false);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (interactive) {
                // Quelqu'un est devant l'écran (menu) : on affiche la demande d'Android
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { ctx.startActivity(confirm); } catch (Throwable ignored) {}
                }
            } else {
                // Pendant l'affichage : on n'interrompt pas, on attend le menu
                Updater.prefs(ctx).edit().putBoolean("update_pending", true).apply();
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Updater.prefs(ctx).edit().putBoolean("update_pending", false).remove("update_error").apply();
            // L'appli redémarre toute seule (BootReceiver, MY_PACKAGE_REPLACED)
        } else {
            Updater.prefs(ctx).edit().putString("update_error",
                    "Échec installation (" + status + ") " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)).apply();
        }
    }
}
