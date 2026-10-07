package fr.wafforme.signage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * Relance l'appli au démarrage du boîtier (et après une mise à jour de l'APK).
 * Sur Android 10+, le système n'autorise ce lancement que si l'appli a la
 * permission « Afficher par-dessus les autres applis » — l'écran de
 * configuration de l'appli propose de l'activer.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        // Petit délai : certains boîtiers ne sont pas encore prêts à l'instant du boot
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent i = new Intent(app, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                i.putExtra("fromBoot", true);
                app.startActivity(i);
            } catch (Throwable ignored) {
            } finally {
                pending.finish();
            }
        }, 2500);
    }
}
