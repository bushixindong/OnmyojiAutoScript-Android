package com.oas.android.tasks;

import org.junit.Test;
import static org.junit.Assert.*;

public class DungeonConfigTest {
    @Test public void acceptsSupportedChoices(){
        assertEquals(13,new DungeonConfig("orochi",0,13,100,120,true,true,false).layer);
        assertTrue(new DungeonConfig("evozone",3,1,1,1,true,true,true).inspect);
        new DungeonConfig("goryou",3,1,1,10,true,true,false);
        new DungeonConfig("sougenbi",2,1,1,10,true,true,false);
    }
    @Test public void rejectsInvalidConfigurationBeforeAnyNavigation(){
        invalid("orochi",0,9,1,10);invalid("orochi",0,14,1,10);invalid("evozone",4,10,1,10);
        invalid("evozone",0,11,1,10);invalid("goryou",-1,1,1,10);invalid("sougenbi",3,1,1,10);
        invalid("unknown",0,1,1,10);invalid("orochi",0,10,0,10);invalid("orochi",0,10,101,10);
        invalid("orochi",0,10,1,0);invalid("orochi",0,10,1,121);
    }
    private void invalid(String kind,int variant,int layer,int rounds,int minutes){
        try{new DungeonConfig(kind,variant,layer,rounds,minutes,true,true,false);fail("Invalid config was accepted");}
        catch(IllegalArgumentException expected){}
    }
}
