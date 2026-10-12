package com.selene.onestepflight;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONObject;

public class JourneyMessageTest {
    private JSONObject message(String key,long version,long revision) throws Exception {
        return new JSONObject().put("journeyKey",key).put("snapshotVersion",version).put("clientRevision",revision).put("expiresAt",1000);
    }
    @Test public void rejectsLateOldJourneyRevisionVersionAndExpiredMessages() throws Exception {
        assertTrue(JourneyMessage.accepts("new",5,7,false,message("new",8,5),900));
        assertFalse(JourneyMessage.accepts("new",5,7,false,message("old",8,5),900));
        assertFalse(JourneyMessage.accepts("new",5,7,false,message("new",8,4),900));
        assertFalse(JourneyMessage.accepts("new",5,7,false,message("new",6,5),900));
        assertFalse(JourneyMessage.accepts("new",5,7,true,message("new",8,5),900));
        assertFalse(JourneyMessage.accepts("new",5,7,false,message("new",8,5),1000));
    }
    @Test public void missingAndStaleValuesRetainLastKnownGateAndTimes() throws Exception {
        var previous=new JSONObject().put("gate","B32").put("boardingTime","10:45").put("stale",false);
        var incoming=new JSONObject().put("gate","待公布").put("missingLabel","待公布").put("boardingTime",JSONObject.NULL).put("stale",true);
        var result=JourneyMessage.retain(previous,incoming);
        assertEquals("B32",result.getString("gate")); assertEquals("10:45",result.getString("boardingTime")); assertTrue(result.getBoolean("stale"));
    }
    @Test public void partialRawFactsKeepKnownOperationalFields() throws Exception {
        var previous=new JSONObject("{\"facts\":{\"gate\":\"B40\",\"terminal\":\"T5\",\"boardingTime\":\"2026-10-11T14:30:00Z\"}}");
        var result=JourneyMessage.retain(previous,new JSONObject("{\"facts\":{\"gate\":\"UNKNOWN\",\"terminal\":\"T6\"}}"));
        assertEquals("B40",result.getJSONObject("facts").getString("gate"));
        assertEquals("T6",result.getJSONObject("facts").getString("terminal"));
        assertEquals("2026-10-11T14:30:00Z",result.getJSONObject("facts").getString("boardingTime"));
    }
}
