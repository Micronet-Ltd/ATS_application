package com.micronet.dsc.resetrb;

import static com.micronet.dsc.resetrb.InstallationLocksReceiver.CLEANING_SHARED_PREF;
import static com.micronet.dsc.resetrb.InstallationLocksReceiver.LAST_CLEANING_KEY;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.internal.verification.VerificationModeFactory.times;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.verifyStatic;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest({RBCClearer.class, InstallationLocks.class})
public class InstallationLocksReceiverTest {

    @Test
    public void testOnReceiveTimeToCheck() {
        // Variables
        mockStatic(RBCClearer.class);
        mockStatic(InstallationLocks.class);
        Context context = PowerMockito.mock(Context.class);
        SharedPreferences sharedPreferences = PowerMockito.mock(SharedPreferences.class);
        SharedPreferences.Editor editor = PowerMockito.mock(SharedPreferences.Editor.class);
        Intent intent = mock(Intent.class);
        InstallationLocksReceiver installationLocksReceiver = new InstallationLocksReceiver();

        // Behavior
        PowerMockito.when(RBCClearer.isTimeForPeriodicCleaning(anyLong(), anyLong())).thenReturn(true);
        PowerMockito.when(context.getSharedPreferences(CLEANING_SHARED_PREF, Context.MODE_PRIVATE)).thenReturn(sharedPreferences);
        PowerMockito.when(sharedPreferences.getLong(LAST_CLEANING_KEY, 0)).thenReturn(0L);
        PowerMockito.when(sharedPreferences.edit()).thenReturn(editor);
        PowerMockito.when(editor.putLong(eq(LAST_CLEANING_KEY), anyLong())).thenReturn(editor);
        PowerMockito.when(intent.getAction()).thenReturn(Intent.ACTION_BOOT_COMPLETED);

        // Execute
        installationLocksReceiver.onReceive(context, intent);

        // Verify
        verifyStatic(RBCClearer.class);
        RBCClearer.isTimeForPeriodicCleaning(anyLong(), anyLong());
        RBCClearer.clearRedbendFiles(anyBoolean());
    }

    @Test
    public void testOnReceiveNotTimeToCheck() {
        // Variables
        mockStatic(RBCClearer.class);
        mockStatic(InstallationLocks.class);
        Context context = PowerMockito.mock(Context.class);
        SharedPreferences sharedPreferences = PowerMockito.mock(SharedPreferences.class);
        SharedPreferences.Editor editor = PowerMockito.mock(SharedPreferences.Editor.class);
        Intent intent = mock(Intent.class);
        InstallationLocksReceiver installationLocksReceiver = new InstallationLocksReceiver();

        // Behavior
        PowerMockito.when(RBCClearer.isTimeForPeriodicCleaning(anyLong(), anyLong())).thenReturn(false);
        PowerMockito.when(context.getSharedPreferences(CLEANING_SHARED_PREF, Context.MODE_PRIVATE)).thenReturn(sharedPreferences);
        PowerMockito.when(sharedPreferences.getLong(LAST_CLEANING_KEY, 0)).thenReturn(0L);
        PowerMockito.when(sharedPreferences.edit()).thenReturn(editor);
        PowerMockito.when(editor.putLong(eq(LAST_CLEANING_KEY), anyLong())).thenReturn(editor);
        PowerMockito.when(intent.getAction()).thenReturn(Intent.ACTION_BOOT_COMPLETED);

        // Execute
        installationLocksReceiver.onReceive(context, intent);

        // Verify
        verifyStatic(RBCClearer.class, times(1));
        RBCClearer.isTimeForPeriodicCleaning(anyLong(), anyLong());
        verifyStatic(RBCClearer.class, times(0));
        RBCClearer.clearRedbendFiles(anyBoolean());
    }
}