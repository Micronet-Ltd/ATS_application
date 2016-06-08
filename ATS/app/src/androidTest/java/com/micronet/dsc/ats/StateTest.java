package com.micronet.dsc.ats;

import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;

public class StateTest extends AndroidTestCase {

    State st;

    public void setUp(){

        RenamingDelegatingContext context
                = new RenamingDelegatingContext(getContext(), "test_");
        st = new State(context);
        st.clearAll();

    }

    public void tearDown() throws Exception{

        super.tearDown();
    }



    public void testReadDefault() {

        // Read Entire State Value

        int engine_status = st.readState(State.FLAG_ENGINE_STATUS);

        assertEquals(0, engine_status);

    } // testReadDefault()


    public void testWrite() {

        st.writeState(State.FLAG_ENGINE_STATUS, 1);

        int engine_status = st.readState(State.FLAG_ENGINE_STATUS);
        //assertEquals(Config.SETTING_DEFAULTS[SETTING_SERVER_ADDRESS], res);
        assertEquals(1, engine_status);


    } // testWrite()


    public void testReadString() {


        String vin = st.readStateString(State.STRING_VIN);

        assertEquals("", vin);

    } // testReadDefault()

    public void testWriteString() {

        // Read Entire State Value
        String write_vin = "TEST123456";
        st.writeStateString(State.STRING_VIN, write_vin );

        String read_vin = st.readStateString(State.STRING_VIN);
        assertEquals(write_vin, read_vin);

    }


    } // class StateTest
