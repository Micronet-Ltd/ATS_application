package com.micronet.dsc.ats;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;

import java.util.List;

public class QueueTest extends AndroidTestCase {

    private Queue q;
    private MainService service;

    public void setUp(){
        RenamingDelegatingContext context
                = new RenamingDelegatingContext(getContext(), "test_");

/*
        MySQLiteHelper dbHelper = new MySQLiteHelper(context);
        SQLiteDatabase db;
        db = dbHelper.getWritableDatabase();

        db.execSQL("DROP TABLE IF EXISTS " + Queue.TABLE_NAME);
        db.execSQL(Queue.SQL_CREATE );

        Cursor cursor = db.rawQuery(
                "select * from " + Queue.TABLE_NAME + " limit 1",
                null);
*/

        service = new MainService(context);
        q = service.queue;
        //q = new Queue(service);
        //q.open();
    }

    public void tearDown() throws Exception{
        q.close();
        super.tearDown();
    }


    private void populateQueue(long now) {


        QueueItem item1 = new QueueItem();

        item1.sequence_id = 0x1234;
        item1.event_type_id  = QueueItem.EVENT_TYPE_REBOOT;
        item1.trigger_dt = now;
        item1.latitude = 36.05145;

        item1 = q.addItem(item1);

        assertEquals(now, item1.trigger_dt);
        assertEquals(QueueItem.EVENT_TYPE_REBOOT, item1.event_type_id);
        assertEquals(1, item1.getId());


        QueueItem item2 = item1.clone();
        item2.sequence_id = 0x12348765;
        item2.event_type_id = QueueItem.EVENT_TYPE_HEARTBEAT;
        item2.trigger_dt = item2.trigger_dt+3;

        item2 = q.addItem(item2);

        assertEquals(2, item2.getId());

        QueueItem item3 = item2.clone();
        item3.sequence_id = 0x7FFFFFFF;
        item3.sequence_id++; // roll over from 4 bytes unsigned
        item3.event_type_id  = QueueItem.EVENT_TYPE_RESTART;
        item3.trigger_dt = item2.trigger_dt+3;

        item3 = q.addItem(item3);
        assertEquals(3, item3.getId());


    } // populateQueue()



    public void testEmptyQueue(){

        QueueItem firstitem;
        firstitem = q.getFirstItem();
        assertNull(firstitem);
    }


    public void testAddEntry(){

        long now = QueueItem.getSystemDT();
        populateQueue(now);

        // test the first entry is accurate
        QueueItem firstitem;

        firstitem = q.getFirstItem();

        assertNotNull(firstitem);
        assertEquals(1, firstitem.getId());
        assertEquals(QueueItem.EVENT_TYPE_REBOOT, firstitem.event_type_id);
        assertEquals(now, firstitem.trigger_dt);

        // Check that the reals are also being inserted and retrieved
        assertEquals(36.05145, firstitem.latitude);


    }


    public void testRemoveEntryById(){

        long now = QueueItem.getSystemDT();
        populateQueue(now);

        q.deleteItemByID(2);

        QueueItem firstitem;
        firstitem = q.getFirstItem();

        assertNotNull(firstitem);
        assertEquals(1, firstitem.getId());
        assertEquals(QueueItem.EVENT_TYPE_REBOOT, firstitem.event_type_id);
        assertEquals(now, firstitem.trigger_dt);


        q.deleteItemByID(1);

        firstitem = q.getFirstItem();

        assertNotNull(firstitem);
        assertEquals(3, firstitem.getId());
        assertEquals(QueueItem.EVENT_TYPE_RESTART, firstitem.event_type_id);
        assertEquals(now+6, firstitem.trigger_dt);

    }

    public void testRemoveEntryBySequence(){

        long now = QueueItem.getSystemDT();
        populateQueue(now);

        List<QueueItem> allitems;

        assertEquals(3, q.getAllItems().size());

        // trying to delete something not in the queue doesn't work
        q.deleteItemBySequenceId(2, 0xFFFF);

        QueueItem firstitem;
        firstitem = q.getFirstItem();

        assertNotNull(firstitem);
        assertEquals(1, firstitem.getId());
        assertEquals(QueueItem.EVENT_TYPE_REBOOT, firstitem.event_type_id);
        assertEquals(now, firstitem.trigger_dt);
        assertEquals(3, q.getAllItems().size());


        // trying to delete something not FIRST in the queue doesn't work
        q.deleteItemBySequenceId(0x12348765, 0xFFFF);

        assertEquals(3, q.getAllItems().size());
        firstitem = q.getFirstItem();

        assertNotNull(firstitem);
        assertEquals(1, firstitem.getId());
        assertEquals(0x1234, firstitem.sequence_id);
        assertEquals(QueueItem.EVENT_TYPE_REBOOT, firstitem.event_type_id);
        assertEquals(now, firstitem.trigger_dt);


        //allitems = q.getAllItems();

        // trying to delete first item works
        q.deleteItemBySequenceId(0x1234, 0xFFFF);

        allitems = q.getAllItems();
        assertEquals(2, allitems.size());

        firstitem = q.getFirstItem();

        assertNotNull(firstitem);
        assertEquals(0x12348765, firstitem.sequence_id);
        assertEquals(2, firstitem.getId());
        assertEquals(QueueItem.EVENT_TYPE_HEARTBEAT, firstitem.event_type_id);
        assertEquals(now + 3, firstitem.trigger_dt);

        // delete item with larger (31 bit) id works
        q.deleteItemBySequenceId(0x8765, 0xFFFF);

        assertEquals(1, q.getAllItems().size());
        firstitem = q.getFirstItem();

        assertNotNull(firstitem);
        assertEquals(3, firstitem.getId());
        assertEquals(0x80000000, firstitem.sequence_id);
        assertEquals(QueueItem.EVENT_TYPE_RESTART, firstitem.event_type_id);
        assertEquals(now+6, firstitem.trigger_dt);

        // delete last item with larger (32 bit) id works
        q.deleteItemBySequenceId(0x0, 0xFFFF);

        assertEquals(0, q.getAllItems().size());



    } // testRemoveEntryBySequence()

    public void testClearAllEntry(){

        long now = QueueItem.getSystemDT();
        populateQueue(now);

        QueueItem firstitem;
        firstitem = q.getFirstItem();
        assertNotNull(firstitem);

        q.clearAll();
        firstitem = q.getFirstItem();
        assertNull(firstitem);

    }

}
