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


    static int NUM_POPULATED_ITEMS = 6; // number of items populated by the populateQueue() function here:
    private void populateQueue(long now) {


        QueueItem item1 = new QueueItem();

        item1.sequence_id = 0x1234;
        item1.event_type_id  = EventType.EVENT_TYPE_REBOOT;
        item1.trigger_dt = now;
        item1.latitude = 36.05145;
        assertEquals(now, item1.trigger_dt);
        item1.trigger_dt = item1.trigger_dt-3;
        item1 = q.addItem(item1);


        assertEquals(EventType.EVENT_TYPE_REBOOT, item1.event_type_id);
        assertEquals(1, item1.getId());


        QueueItem item2 = item1.clone();
        item2.sequence_id = 65536;
        item2.event_type_id = EventType.EVENT_TYPE_HEARTBEAT;
        item2.trigger_dt = now;
        item2 = q.addItem(item2);


        assertEquals(3, item2.getId());

        QueueItem item3 = item2.clone();
        item3.sequence_id = 0x7FFFFFFF;
        item3.sequence_id++; // roll over from 4 bytes unsigned
        item3.event_type_id  = EventType.EVENT_TYPE_RESTART;
        item3.trigger_dt = item2.trigger_dt+3;

        item3 = q.addItem(item3);
        assertEquals(5, item3.getId());


    } // populateQueue()



    public void testEmptyQueue(){

        QueueItem firstitem;
        firstitem = q.getFirstItem(Queue.SERVER_ID_PRIMARY);
        assertNull(firstitem);
        firstitem = q.getFirstItem(Queue.SERVER_ID_SECONDARY);
        assertNull(firstitem);
    }


    public void testAddEntry() {

        long now = QueueItem.getSystemDT();
        populateQueue(now);

        // test the first entry is accurate
        QueueItem firstitem;

        firstitem = q.getFirstItem(Queue.SERVER_ID_PRIMARY);

        assertNotNull(firstitem);
        assertEquals(1, firstitem.getId());
        assertEquals(EventType.EVENT_TYPE_REBOOT, firstitem.event_type_id);
        assertEquals(now-3, firstitem.trigger_dt);

        // Check that the reals are also being inserted and retrieved
        assertEquals(36.05145, firstitem.latitude);


        // Also verify an entry was created for secondary server

        firstitem = q.getFirstItem(Queue.SERVER_ID_SECONDARY);
        assertNotNull(firstitem);
        assertEquals(EventType.EVENT_TYPE_REBOOT, firstitem.event_type_id);
        assertEquals(now-3, firstitem.trigger_dt);
        assertEquals(36.05145, firstitem.latitude);


    } // testAddEntry()



    public void testRemoveEntryById(){

        long now = QueueItem.getSystemDT();
        populateQueue(now);

        q.deleteItemByID(3);

        QueueItem firstitem;


        // doesn't effect first entyr on  primary server

        firstitem = q.getFirstItem(Queue.SERVER_ID_PRIMARY);

        assertNotNull(firstitem);
        assertEquals(1, firstitem.getId());
        assertEquals(EventType.EVENT_TYPE_REBOOT, firstitem.event_type_id);
        assertEquals(now-3, firstitem.trigger_dt);

        // or secondary server

        firstitem = q.getFirstItem(Queue.SERVER_ID_SECONDARY);

        assertNotNull(firstitem);
        assertEquals(2, firstitem.getId());
        assertEquals(EventType.EVENT_TYPE_REBOOT, firstitem.event_type_id);
        assertEquals(now-3, firstitem.trigger_dt);



        // Delete first entry

        q.deleteItemByID(1);


        // Does effect primary
        firstitem = q.getFirstItem(Queue.SERVER_ID_PRIMARY);

        assertNotNull(firstitem);
        assertEquals(5, firstitem.getId());
        assertEquals(EventType.EVENT_TYPE_RESTART, firstitem.event_type_id);
        assertEquals(now + 3, firstitem.trigger_dt);


        // Does not effect secondary
        firstitem = q.getFirstItem(Queue.SERVER_ID_SECONDARY);

        assertNotNull(firstitem);
        assertEquals(2, firstitem.getId());
        assertEquals(EventType.EVENT_TYPE_REBOOT, firstitem.event_type_id);
        assertEquals(now-3, firstitem.trigger_dt);


    }

    public void testRemoveEntryBySequence(){

        long now = QueueItem.getSystemDT();
        populateQueue(now);




        List<QueueItem> allitems;

        assertEquals(NUM_POPULATED_ITEMS, q.getAllItems().size()); // 3 primary and 3 secondary

        // trying to delete something not in the queue doesn't work
        q.deleteItemBySequenceId(Queue.SERVER_ID_PRIMARY, 2, Codec.SEQUENCE_ID_RECEIVE_MASK);
        q.deleteItemBySequenceId(Queue.SERVER_ID_SECONDARY, 2, Codec.SEQUENCE_ID_RECEIVE_MASK);

        QueueItem firstitem;
        firstitem = q.getFirstItem(Queue.SERVER_ID_PRIMARY);

        assertNotNull(firstitem);
        assertEquals(1, firstitem.getId());
        assertEquals(EventType.EVENT_TYPE_REBOOT, firstitem.event_type_id);
        assertEquals(now-3, firstitem.trigger_dt);
        assertEquals(NUM_POPULATED_ITEMS , q.getAllItems().size());


        // trying to delete something not FIRST in the queue doesn't work
        q.deleteItemBySequenceId(Queue.SERVER_ID_PRIMARY, 0x12348765, Codec.SEQUENCE_ID_RECEIVE_MASK);
        q.deleteItemBySequenceId(Queue.SERVER_ID_SECONDARY, 65536, Codec.SEQUENCE_ID_RECEIVE_MASK);

        assertEquals(NUM_POPULATED_ITEMS , q.getAllItems().size());
        firstitem = q.getFirstItem(Queue.SERVER_ID_PRIMARY);

        assertNotNull(firstitem);
        assertEquals(1, firstitem.getId());
        assertEquals(0x1234, firstitem.sequence_id);
        assertEquals(EventType.EVENT_TYPE_REBOOT, firstitem.event_type_id);
        assertEquals(now-3, firstitem.trigger_dt);


        //allitems = q.getAllItems();

        // trying to delete first item works
        q.deleteItemBySequenceId(Queue.SERVER_ID_PRIMARY, 0x1234, Codec.SEQUENCE_ID_RECEIVE_MASK);

        allitems = q.getAllItems();
        assertEquals(NUM_POPULATED_ITEMS - 1, allitems.size());

        firstitem = q.getFirstItem(Queue.SERVER_ID_PRIMARY);

        assertNotNull(firstitem);
        assertEquals(65536, firstitem.sequence_id);
        assertEquals(3, firstitem.getId());
        assertEquals(EventType.EVENT_TYPE_HEARTBEAT, firstitem.event_type_id);
        assertEquals(now, firstitem.trigger_dt);

        // delete item with larger (31 bit) id works
        q.deleteItemBySequenceId(Queue.SERVER_ID_PRIMARY, 65536, Codec.SEQUENCE_ID_RECEIVE_MASK);

        assertEquals(NUM_POPULATED_ITEMS - 2, q.getAllItems().size());
        firstitem = q.getFirstItem(Queue.SERVER_ID_PRIMARY);

        assertNotNull(firstitem);
        assertEquals(5, firstitem.getId());
        assertEquals(0x80000000, firstitem.sequence_id);
        assertEquals(EventType.EVENT_TYPE_RESTART, firstitem.event_type_id);
        assertEquals(now+3, firstitem.trigger_dt);

        // delete last item with larger (32 bit) id works
        q.deleteItemBySequenceId(Queue.SERVER_ID_PRIMARY, 0x0, Codec.SEQUENCE_ID_RECEIVE_MASK);

        assertEquals(NUM_POPULATED_ITEMS -3, q.getAllItems().size());


        // Secondary server is un-affected

        firstitem = q.getFirstItem(Queue.SERVER_ID_SECONDARY);

        assertNotNull(firstitem);
        assertEquals(2, firstitem.getId());
        assertEquals(EventType.EVENT_TYPE_REBOOT, firstitem.event_type_id);
        assertEquals(now-3, firstitem.trigger_dt);

        // Remove Secondary server top-of-queue
        q.deleteItemBySequenceId(Queue.SERVER_ID_SECONDARY, 0x1234, Codec.SEQUENCE_ID_RECEIVE_MASK);

        firstitem = q.getFirstItem(Queue.SERVER_ID_SECONDARY);

        assertNotNull(firstitem);
        assertEquals(4, firstitem.getId());
        assertEquals(NUM_POPULATED_ITEMS - 4, q.getAllItems().size());

    } // testRemoveEntryBySequence()

    public void testClearAllEntry(){

        long now = QueueItem.getSystemDT();
        populateQueue(now);

        QueueItem firstitem;
        firstitem = q.getFirstItem(Queue.SERVER_ID_PRIMARY);
        assertNotNull(firstitem);

        firstitem = q.getFirstItem(Queue.SERVER_ID_SECONDARY);
        assertNotNull(firstitem);

        q.clearAll();
        firstitem = q.getFirstItem(Queue.SERVER_ID_PRIMARY);
        assertNull(firstitem);

        firstitem = q.getFirstItem(Queue.SERVER_ID_SECONDARY);
        assertNull(firstitem);

    }


    public void testDeleteOldItems() {
        long now = QueueItem.getSystemDT();
        populateQueue(now);


        // too long ago, should not delete anything
        q.deleteOldItems(Queue.SERVER_ID_SECONDARY, 10);

        assertEquals(NUM_POPULATED_ITEMS, q.getAllItems().size());


        QueueItem firstitem;
        firstitem = q.getFirstItem(Queue.SERVER_ID_PRIMARY);
        assertNotNull(firstitem);
        assertEquals(1, firstitem.getId());

        firstitem = q.getFirstItem(Queue.SERVER_ID_SECONDARY);
        assertNotNull(firstitem);
        assertEquals(2, firstitem.getId());


        // now just delete the earliest
        q.deleteOldItems(Queue.SERVER_ID_SECONDARY, 2); // more than 2 seconds ago
        assertEquals(NUM_POPULATED_ITEMS - 1, q.getAllItems().size());

        firstitem = q.getFirstItem(Queue.SERVER_ID_PRIMARY);
        assertNotNull(firstitem);
        assertEquals(1, firstitem.getId());

        firstitem = q.getFirstItem(Queue.SERVER_ID_SECONDARY);
        assertNotNull(firstitem);
        assertEquals(4, firstitem.getId());

    } // testDeleteOldItems()


} // class
