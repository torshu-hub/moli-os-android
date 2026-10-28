package io.moli.android;
import org.junit.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
public final class ConfigurationTransactionTest {
    static final class Crash extends Error {}
    static final class Faults extends ConfigurationTransaction.Disk {
        int calls, failAt; boolean error;
        Faults(File dir,int at,boolean error){super(dir);failAt=at;this.error=error;}
        void after() throws IOException {if(++calls==failAt){if(error)throw new IOException("disk full");throw new Crash();}}
        @Override public void write(String n,String t)throws Exception{super.write(n,t);after();}
        @Override public void remove(String n)throws Exception{super.remove(n);after();}
    }
    @Test public void interruptedReplacementRecoversOldOrCommittedFile()throws Exception {
        for(int at=1;at<=3;at++){
            Path dir=Files.createTempDirectory("moli-j1-");
            try{writeText(dir.resolve("moli.toml"),"old");writeText(dir.resolve("history.db"),"history");
                try{new ConfigurationTransaction(new Faults(dir.toFile(),at,false)).commitExpected("old","new");fail();}catch(Crash expected){}
                ConfigurationTransaction.Disk disk=new ConfigurationTransaction.Disk(dir.toFile());
                ConfigurationTransaction tx=new ConfigurationTransaction(disk);tx.recover();tx.recover();
                assertEquals(at==3?"new":"old",disk.read("moli.toml",100));assertEquals("history",readText(dir.resolve("history.db")));
            }finally{cleanup(dir);}
        }
    }
    @Test public void diskFailureAndStaleFormsCannotReplaceTheLastValidFile()throws Exception {
        Path dir=Files.createTempDirectory("moli-j1-");
        try{writeText(dir.resolve("moli.toml"),"old");
            ConfigurationTransaction.Disk disk=new ConfigurationTransaction.Disk(dir.toFile());
            ConfigurationTransaction tx=new ConfigurationTransaction(disk);
            try{tx.commitExpected("stale","new");fail();}catch(IOException expected){}
            assertEquals("old",disk.read("moli.toml",100));
            try{new ConfigurationTransaction(new Faults(dir.toFile(),2,true)).commitExpected("old","new");fail();}catch(IOException expected){}
            tx.recover();assertEquals("old",disk.read("moli.toml",100));
            try{tx.commit(Map.of("home.json","{}"));fail();}catch(IOException expected){}
        }finally{cleanup(dir);}
    }
    @Test public void recoveryRefusesAnUnrelatedPostCrashEdit()throws Exception {
        Path dir=Files.createTempDirectory("moli-j1-");
        try{writeText(dir.resolve("moli.toml"),"old");
            try{new ConfigurationTransaction(new Faults(dir.toFile(),2,false)).commitExpected("old","new");}catch(Crash expected){}
            writeText(dir.resolve("moli.toml"),"unrelated");
            try{new ConfigurationTransaction(new ConfigurationTransaction.Disk(dir.toFile())).recover();fail();}catch(IOException expected){}
            assertEquals("unrelated",readText(dir.resolve("moli.toml")));
        }finally{cleanup(dir);}
    }
    static void writeText(Path p,String s)throws Exception{Files.write(p,s.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static String readText(Path p)throws Exception{return new String(Files.readAllBytes(p),java.nio.charset.StandardCharsets.UTF_8);}
    static void cleanup(Path dir)throws Exception{try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
}
