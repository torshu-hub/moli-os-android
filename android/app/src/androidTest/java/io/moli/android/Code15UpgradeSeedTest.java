package io.moli.android;

import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Seed the real old code14 with a disabled home and synthetic encrypted OAuth pair. */
@RunWith(AndroidJUnit4.class)
public final class Code15UpgradeSeedTest {
    @Test public void seedRealCode14() throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Code12UpgradeSeedTest.seed(app,Integer.parseInt(InstrumentationRegistry.getArguments().getString("expectedSeed","14")));
        EngineHost.maintain(app,()->{
            ConfigStore store=new ConfigStore(app);
            store.netatmo(new JSONObject().put("operation","provision").put("consent",true)
                .put("application",new JSONObject().put("client_id","app-test").put("client_secret","synthetic-secret")
                    .put("experimental",true).put("write_thermostat",true)));
            String before=store.read();
            store.transaction.commitExpected(before,before+"\n[[driver]]\nid = \"netatmo-energy\"\nkind = \"netatmo\"\nenabled = false\n[driver.options]\nhome_id = \"home-test\"\npoll_seconds = 30\ntopology_seconds = 300\n");
            // Use the existing documented encrypted vault format, never a real account.
            File file=new File(store.directory,"secrets.enc");
            byte[] old=Files.readAllBytes(file.toPath()),key=new byte[32];
            String hex=ProtectedKey.open(app).getString("master");
            for(int n=0;n<32;n++)key[n]=(byte)Integer.parseInt(hex.substring(n*2,n*2+2),16);
            assertEquals("MOLISEC1",new String(old,0,8,StandardCharsets.US_ASCII));
            SecretKeySpec secret=new SecretKeySpec(key,"ChaCha20");
            Cipher cipher=Cipher.getInstance("ChaCha20/Poly1305/NoPadding");
            byte[] aad="moli-os secrets v1".getBytes(StandardCharsets.UTF_8);
            cipher.init(Cipher.DECRYPT_MODE,secret,new IvParameterSpec(Arrays.copyOfRange(old,8,20)));cipher.updateAAD(aad);
            JSONObject values=new JSONObject(new String(cipher.doFinal(Arrays.copyOfRange(old,20,old.length)),StandardCharsets.UTF_8));
            values.put("netatmo-energy/oauth_tokens",new JSONObject().put("access_token","synthetic-access")
                .put("refresh_token","synthetic-refresh").put("expires_at",System.currentTimeMillis()/1000+86400)
                .put("write",true).toString());
            byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);
            cipher.init(Cipher.ENCRYPT_MODE,secret,new IvParameterSpec(nonce));cipher.updateAAD(aad);
            ByteArrayOutputStream output=new ByteArrayOutputStream();output.write(Arrays.copyOfRange(old,0,8));output.write(nonce);
            output.write(cipher.doFinal(values.toString().getBytes(StandardCharsets.UTF_8)));Files.write(file.toPath(),output.toByteArray());
            JSONObject status=store.netatmo(new JSONObject().put("operation","status"));
            assertTrue(status.getBoolean("credential_present"));assertTrue(status.getBoolean("write_allowed"));
            assertEquals("home-test",status.getString("selected_home"));
            return null;
        }).get(30,TimeUnit.SECONDS);
        ConfigStore store=new ConfigStore(app);
        assertTrue(app.getSharedPreferences(Code12UpgradeSeedTest.PREFS,Context.MODE_PRIVATE).edit()
            .putString("files",Code12UpgradeSeedTest.hashes(store.directory).toString()).commit());
    }
}
