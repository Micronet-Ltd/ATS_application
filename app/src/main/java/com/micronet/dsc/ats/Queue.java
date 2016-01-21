/////////////////////////////////////////////////////////////
// Queue: Message/Event Queue Direct Access Object
//  use this class to manipulate the event queue
/////////////////////////////////////////////////////////////

package com.micronet.dsc.ats;

import java.util.ArrayList;
import java.util.List;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


public class Queue {

    public static final String TAG = "ATS-Queue";

    public static final String TABLE_NAME = "queue";
    public static final String COLUMN_ID = "_id"; // the record ID
    public static final String COLUMN_SEQUENCE_ID = "sequence_id";
    public static final String COLUMN_EVENT_TYPE_ID = "event_type_id";
    public static final String COLUMN_TRIGGER_DT = "trigger_dt";

    public static final String COLUMN_INPUTS = "inputs"; // bitfield
    public static final String COLUMN_BATTERY_LEVEL = "battery";

    public static final String COLUMN_LATITUDE = "latitude";
    public static final String COLUMN_LONGITUDE = "longitude";
    public static final String COLUMN_SPEED = "speed";
    public static final String COLUMN_HEADING = "heading";
    public static final String COLUMN_FIX_TYPE = "fix_type";
    public static final String COLUMN_FIX_ACCURACY = "fix_accuracy";
    public static final String COLUMN_SAT_COUNT = "sat_count";
    public static final String COLUMN_ODOMETER = "odometer"; // distance (meters)
    public static final String COLUMN_CONTINUOUS_IDLE = "idle"; // last time idle (seconds)
    public static final String COLUMN_EXTRA = "extra"; // an extra byte of information
    public static final String COLUMN_FIX_HISTORIC = "fix_historic"; // 1 = historic, 0 = current


    public static final String COLUMN_CARRIER_ID = "carrier_id";
    public static final String COLUMN_NETWORK_TYPE = "network_type";
    public static final String COLUMN_SIGNAL_STRENGTH = "signal_strength";
    public static final String COLUMN_IS_ROAMING = "is_roaming";



	// SQL_CREATE:
    // 	This is the database creation sql statement . .be sure to increment the database version in MySQLLiteHelper
    //  if this changes
    public static final String SQL_CREATE = "create table "
            + TABLE_NAME + "(" +
            COLUMN_ID + " integer primary key autoincrement, " +
            COLUMN_SEQUENCE_ID + " integer," +
            COLUMN_EVENT_TYPE_ID + " integer," +
            COLUMN_TRIGGER_DT + " integer," +
            COLUMN_INPUTS + " integer," +
            COLUMN_BATTERY_LEVEL + " integer," + // tenths of volt
            COLUMN_LATITUDE + " real," +
            COLUMN_LONGITUDE + " real," +
            COLUMN_SPEED + " integer," +
            COLUMN_HEADING + " integer," +
            COLUMN_FIX_TYPE + " integer," +
            COLUMN_FIX_ACCURACY + " integer," +
            COLUMN_SAT_COUNT + " integer," +
            COLUMN_ODOMETER + " integer," +
            COLUMN_CONTINUOUS_IDLE + " integer," +
            COLUMN_EXTRA + " integer," +
            COLUMN_FIX_HISTORIC + " integer," +

            COLUMN_CARRIER_ID + " integer," +
            COLUMN_NETWORK_TYPE + " integer," +
            COLUMN_SIGNAL_STRENGTH + " integer," +
            COLUMN_IS_ROAMING + " integer" +

    ");";


    // Database fields
    private SQLiteDatabase database;
    private MySQLiteHelper dbHelper;

    MainService service; // this stores the context

    //ScheduledThreadPoolExecutor exec = null; // in case we want to run recurring tasks like purging old records

    /////////////////////////////////////////////////////////////
    // Queue()
    // Parameters:
    //  service: this is really only used to get the application's context
    //		so we can access the application's database
    /////////////////////////////////////////////////////////////
    public Queue(final MainService service) {

		// just remember this passed variable
        this.service = service;
        // open a database in the application's context
        dbHelper = new MySQLiteHelper(service.context);


		/*
        // implement a max TTL for the queue:
        class PurgeOldItemsTask implements Runnable {

            @Override
            public void run() {
                if (service.config != null) {
                    // if config exists, then go ahead and purge items older than the value given
                    //int seconds_ago = config.readParameterInt(Config.SETTING_QUEUE_TTL, 0);
                    //deleteOldItems(seconds_ago);
                }
            }
        }

        exec = new ScheduledThreadPoolExecutor(1);
        exec.scheduleAtFixedRate(new PurgeOldItemsTask(), 10, 10, TimeUnit.SECONDS); // 10 seconds
        */

    }

	////////////////////////////////////////
	//	open()
	//		Opens the Database, must be called before any data-changes are made
	////////////////////////////////////////
    public void open() throws SQLException {
        Log.v(TAG, "open()");
        database = dbHelper.getWritableDatabase();
    }

	////////////////////////////////////////
	//	close()
	//		Closes the Database, must be called when database no longer needed
	////////////////////////////////////////
    public void close() {
        Log.v(TAG, "close()");
        dbHelper.close();
        //if (exec != null)
        //    exec.shutdown();
    }


    ////////////////////////////////////
    // addItem() : 
    //		Adds an item to the end of the queue
    ////////////////////////////////////
    public QueueItem addItem(QueueItem item) {
        try {
            ContentValues values = itemToContentValues(item);

            long insertId = database.insertOrThrow(TABLE_NAME, null,
                    values);

            Log.i(TAG, "Queued Event: type: " + item.event_type_id + " , seqnum: " + item.sequence_id + " , id: " + insertId);

            item.setId(insertId);
        } catch (Exception e) {
            Log.e(TAG, "Exception addItem(): " + e.toString(), e);
        }
        return item;
    }


    ////////////////////////////////////
    // deleteOldItems() :
    //		removes items in queue older than a certain time
    ////////////////////////////////////
    public void deleteOldItems(long seconds_ago) {
        Log.v(TAG,"Queue: deleting items older than " + seconds_ago + " seconds");

        if (seconds_ago ==0) return; // this means dont purge

        long ago_dt = QueueItem.getSystemDT() - seconds_ago;

        database.delete(TABLE_NAME,
                COLUMN_TRIGGER_DT + " < " + ago_dt,
                null);
    }


    ////////////////////////////////////
    // deleteItem() : 
    //		To be called when ACK is received, removes an item from queue    
    ////////////////////////////////////
    public void deleteItemByID(long id) {
        Log.v(TAG, "Queue Item deleting item w/ id " + id);
        database.delete(TABLE_NAME,
                COLUMN_ID + " = " + id,
                null);
    }

    ////////////////////////////////////
    // deleteTopItem() : 
    //		To be called when ACK is received, removes the first item from queue
    //      always deletes the top item in the queue no matter the sequence ID
    //		used during testing when we don't know or care about the sequence ID
    ////////////////////////////////////
    public void deleteTopItem() {
        QueueItem item = getFirstItem();
        if (item != null) {
            Log.v(TAG, "Found First Queue Item: seqnum: " + item.sequence_id + " , id: " + item.getId());
            deleteItemByID(item.getId());
        } else {
            Log.v(TAG, "No Items in Queue");
        }
    }

    ////////////////////////////////////
    // deleteItemBySequenceId() : 
    //		To be called when ACK is received, removes the item from queue
    //  mask determines what portion of the sequence ID to treat as significant when making the comparison
    ////////////////////////////////////
    public void deleteItemBySequenceId(long sequence_id, long mask) {

        // we must only delete this item if it is the first item in the queue
        //  b/c there may be many messages in the queue with the same sequence ID (since sequence ID may reset)

        QueueItem item = getFirstItem();
        if ((item != null) &&
            ((item.sequence_id & mask) == (sequence_id & mask))) { // check if the first item has this sequence ID
            Log.v(TAG, "Found Queue Item: seqnum: " + item.sequence_id + ", id: " + item.getId());
            deleteItemByID(item.getId());
        } else if (item != null) {
            Log.v(TAG, "Queue Item seqnum no match, expected " + item.sequence_id + " received " + sequence_id );
        } else {
            Log.v(TAG, "No Items in Queue");
        }

    }



    ////////////////////////////////////
    // getFirstItem()
    //   get the first item in the queue (does not remove it)
    //	 used to determine if there is an item in queue
    // Returns: null if no items in queue
    //          the first item in queue if one exists
    ////////////////////////////////////
    public QueueItem getFirstItem() {

        QueueItem item = null;

        Cursor cursor = database.rawQuery(
                "select * from " + TABLE_NAME + " order by " + COLUMN_ID + " limit 1",
                null);

        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            if (!cursor.isAfterLast()) {
                item = cursorToItem(cursor);
            }
        }
        // make sure to close the cursor
        cursor.close();
        return item;
    }


	////////////////////////////////////
	// getAllItems()
	//	 returns an array of all items in the queue .. used mainly during testing
	////////////////////////////////////
    public List<QueueItem> getAllItems() {
        List<QueueItem> items = new ArrayList<QueueItem>();

        Cursor cursor = database.rawQuery(
                "select * from " + TABLE_NAME + " order by " + COLUMN_ID,
                null);

        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            QueueItem item = cursorToItem(cursor);
            items.add(item);
            cursor.moveToNext();
        }
        // make sure to close the cursor
        cursor.close();
        return items;
    }


    ////////////////////////////////////
    // clearAll()
    //   removes all items from the queue .. mainly used during testing
    ////////////////////////////////////
    public void clearAll() {
        database.execSQL("delete from " + TABLE_NAME);
    } // clearAll()


	////////////////////////////////////////////////
    ////////////////////////////////////////////////
    // PRIVATES
    

	///////////////////////////////////////////////
	// cursorToItem()
	//	populates a QueueItem object from the DB record cursor
	///////////////////////////////////////////////
    private QueueItem cursorToItem(Cursor cursor) { // for querying
        QueueItem item = new QueueItem();
        item.setId(cursor.getLong(0));
        item.sequence_id = cursor.getInt(1);
        item.event_type_id = cursor.getInt(2);
        item.trigger_dt = cursor.getLong(3);
        item.input_bitfield = cursor.getShort(4);
        item.battery_voltage = cursor.getShort(5);
        item.latitude = cursor.getDouble(6);
        item.longitude = cursor.getDouble(7);
        item.speed = cursor.getInt(8);
        item.heading = cursor.getShort(9);
        item.fix_type = cursor.getInt(10);
        item.fix_accuracy = cursor.getInt(11);
        item.sat_count = cursor.getInt(12);
        item.odometer = cursor.getLong(13);
        item.continuous_idle = cursor.getInt(14);
        item.extra = cursor.getInt(15);
        item.is_fix_historic = (cursor.getInt(16) == 1 ? true : false);
        item.carrier_id = cursor.getInt(17);
        item.network_type = (byte) cursor.getShort(18);
        item.signal_strength = (byte) cursor.getShort(19);
        item.is_roaming = (cursor.getInt(20) == 1 ? true : false);


        return item;
    } // cursorToItem()


	///////////////////////////////////////////////
	// itemToContentValues()
	//	populates a DB record from a QueueItem object
	///////////////////////////////////////////////
    private ContentValues itemToContentValues(QueueItem item) { // for inserting

        // Does not contain the ID field (as this is generated by DB)

        ContentValues values = new ContentValues();
        values.put(COLUMN_SEQUENCE_ID, item.sequence_id);
        values.put(COLUMN_EVENT_TYPE_ID, item.event_type_id);
        values.put(COLUMN_TRIGGER_DT, item.trigger_dt);
        values.put(COLUMN_INPUTS, item.input_bitfield);
        values.put(COLUMN_BATTERY_LEVEL, item.battery_voltage);
        values.put(COLUMN_LATITUDE, item.latitude);
        values.put(COLUMN_LONGITUDE , item.longitude);
        values.put(COLUMN_SPEED , item.speed);
        values.put(COLUMN_HEADING  , item.heading);
        values.put(COLUMN_FIX_TYPE, item.fix_type);
        values.put(COLUMN_FIX_ACCURACY, item.fix_accuracy);
        values.put(COLUMN_SAT_COUNT , item.sat_count);
        values.put(COLUMN_ODOMETER , item.odometer);
        values.put(COLUMN_CONTINUOUS_IDLE , item.continuous_idle);
        values.put(COLUMN_EXTRA , item.extra);
        values.put(COLUMN_FIX_HISTORIC , (item.is_fix_historic ? 1 : 0));
        values.put(COLUMN_CARRIER_ID , item.carrier_id);
        values.put(COLUMN_NETWORK_TYPE , item.network_type);
        values.put(COLUMN_SIGNAL_STRENGTH , item.signal_strength);
        values.put(COLUMN_IS_ROAMING , (item.is_roaming ? 1 : 0));

        return values;
    } // itemToContentValues


} // class
