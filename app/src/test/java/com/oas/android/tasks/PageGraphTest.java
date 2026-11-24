package com.oas.android.tasks;

import org.junit.Test;
import static org.junit.Assert.*;

public class PageGraphTest {
    @Test public void findsShortestKnownPathAndHandlesCycles(){
        PageGraph graph=new PageGraph().connect("main","exploration","explore").connect("exploration","main","back")
            .connect("exploration","souls","souls").connect("souls","orochi","orochi").connect("orochi","exploration","back");
        assertTrue(graph.route("main","main").isEmpty());
        assertEquals(3,graph.route("main","orochi").size());
        assertEquals("explore",graph.route("main","orochi").get(0).click);
        assertEquals(2,graph.route("orochi","main").size());
    }
    @Test(expected=IllegalStateException.class)public void unknownCannotProduceAnAction(){new PageGraph().connect("main","souls","souls").route("unknown","main");}
}
