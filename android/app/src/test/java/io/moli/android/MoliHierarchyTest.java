package io.moli.android;
import org.junit.Test;
import static org.junit.Assert.*;
public final class MoliHierarchyTest {
    @Test public void backUsesParentsAndNeverVisitHistory(){
        assertEquals("/automatismes",MainActivity.parentRoute("#/automatismes/demand-link"));
        assertEquals("/energie",MainActivity.parentRoute("#/energie/solaire"));
        assertEquals("/automatismes/rule",MainActivity.parentRoute("#/automatismes/rule/detail"));
        for(String root:new String[]{"pieces","automatismes","energie","moli","infra","systeme"})assertEquals("/",MainActivity.parentRoute("#/"+root));
        assertNull(MainActivity.parentRoute("#/"));assertNull(MainActivity.parentRoute(""));
    }
    @Test public void unknownOrMalformedRoutesSafelyReturnHome(){
        for(String route:new String[]{"#/unknown/detail","https://invalid.test","#/energie/../pieces","#/pieces//detail","#/energie?redirect=outside"})assertEquals("/",MainActivity.parentRoute(route));
    }
}
