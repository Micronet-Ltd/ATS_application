package com.micronet.dsc.ats;
import android.os.SystemClock;
import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;

public class CrashTest extends AndroidTestCase {

    Crash cr;

    public void setUp(){

        RenamingDelegatingContext context
                = new RenamingDelegatingContext(getContext(), "test_");
        cr = new Crash(context);
        cr.clearAll();

    }

    public void tearDown() throws Exception{

        super.tearDown();
    }



    public void testRestorable() {

        // Since we were cleared, we are not restorable

        assertFalse(cr.isRestoreable());

        // After committing, we are restorable

        cr.edit();
        cr.commit();
        assertTrue(cr.isRestoreable());


        cr.edit();
        cr.editor.putLong("SaveTime", SystemClock.elapsedRealtime() - Crash.MAX_ELAPSED_RESTORE_TIME_MS); // to far in past
        cr.editor.commit();

        assertFalse(cr.isRestoreable());

        cr.edit();
        cr.editor.putLong("SaveTime", SystemClock.elapsedRealtime() + 1000); // one second in future
        cr.editor.commit();

        assertFalse(cr.isRestoreable());

        cr.edit();
        cr.editor.putLong("SaveTime", SystemClock.elapsedRealtime()); // just right
        cr.editor.commit();

        assertTrue(cr.isRestoreable());

        cr.edit();
        cr.editor.putString("Version", "XXX"); // wrong version
        cr.editor.commit();

        assertFalse(cr.isRestoreable());

        cr.edit();
        cr.editor.putString("Version", BuildConfig.VERSION_NAME); // right version
        cr.editor.commit();


        assertTrue(cr.isRestoreable());

        cr.clearAll();
        assertFalse(cr.isRestoreable());



    } // testRestorable()


    public void testWriteArrayInt() {


        // test writing the int array

        int[] iarr = {20,30,40};

        cr.edit();
        cr.writeStateArrayInt(1, iarr);
        cr.commit();


        String sarr[] = cr.readStateArray(1);

        assertEquals(sarr.length, 3);
        assertTrue(sarr[0].equals("20"));
        assertTrue(sarr[1].equals("30"));
        assertTrue(sarr[2].equals("40"));


    } // testWriteArrayInt()

    public void testWriteArrayLong() {


        // test writing the int array

        long[] larr = {20,30, 5000000000L};

        cr.edit();
        cr.writeStateArrayLong(1, larr);
        cr.commit();


        String sarr[] = cr.readStateArray(1);

        assertEquals(sarr.length, 3);
        assertTrue(sarr[0].equals("20"));
        assertTrue(sarr[1].equals("30"));
        assertTrue(sarr[2].equals("5000000000"));


    } // testWriteArrayLong()



} // class
