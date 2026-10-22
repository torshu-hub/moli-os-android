package io.moli.android;

import org.json.*;

/** Only fixed codes and numbers are recordable; never arbitrary strings or payloads. */
final class TrialJournal {
    static final int MAX_EVENTS = 256;
    enum Event { SESSION_START, SESSION_END, PROFILE_REVIEWED, PROFILE_APPROVED, PROFILE_REJECTED,
        SELECTION_CHANGED, ENGINE_STARTED, ENGINE_STOPPED, ENGINE_FAILED, ENGINE_READ_OK, ENGINE_READ_FAILED,
        SYNTHETIC_RECEIVED, DEMO_RECEIVED, WEATHER_RECEIVED, PROBE_START, PROBE_OK, PROBE_FAILED,
        PROBE_LIMITED, HEALTH_OK, HEALTH_FAILED, CHECK_GAP, NETWORK_SEARCH, NETWORK_END, IP_COPY, IP_CHOSEN, EQUIPMENT_PROPOSED, EQUIPMENT_SAVED,
        ACCOUNT_STATUS, ACCOUNT_SIGNIN, ACCOUNT_INVENTORY, ACCOUNT_FORGET, UUID_CHECK, CANDIDATE_CHECK, CANDIDATE_RESULT, EQUIPMENT_READ }
    enum Source { MOTEUR, RESEAU, COMPTE, EQUIPEMENTS, DIAGNOSTICS }
    enum Outcome { DEBUT, REUSSI, REFUSE, INDISPONIBLE, EXPIRE, ANNULE, CACHE, ENREGISTRE, FRAIS, ABSENT }
    enum Error { AUCUNE, RESEAU, IDENTITE, AUTHENTIFICATION, MFA, LIMITE, AUTORISATION, COFFRE, REPONSE, MOTEUR_ACTIF, SAISIE, INVENTAIRE_ABSENT, UUID_DIFFERENT, CLE_REFUSEE, INCOMPATIBLE }
    String salt="";
    boolean active,incomplete;
    int dropped;
    final JSONArray events = new JSONArray();
    static TrialJournal parse(String text) throws JSONException {
        TrialJournal j = new TrialJournal(); JSONObject root = new JSONObject(text);
        j.salt=root.optString("salt",""); if(!j.salt.matches("[0-9a-f]{64}"))j.salt="";
        j.incomplete=root.optBoolean("incomplete");j.active = root.optBoolean("active"); j.dropped = Math.max(0,root.optInt("dropped"));
        JSONArray entries = root.optJSONArray("events");
        if (entries != null) for (int i=Math.max(0,entries.length()-MAX_EVENTS);i<entries.length();i++) {
            JSONObject e=entries.optJSONObject(i); if (e==null) continue;
            try {
                Event event=Event.valueOf(e.optString("event"));
                if(e.has("source")) j.action(e.optLong("time"),event,Source.valueOf(e.getString("source")),Outcome.valueOf(e.getString("result")),Error.valueOf(e.getString("error")),e.optLong("a"),e.optLong("b"),e.optString("subject"),e.optString("candidate"),e.optLong("receipt"));
                else j.add(e.optLong("time"),event,e.optLong("a"),e.optLong("b"));
            }
            catch (IllegalArgumentException ignored) { /* Unrecognized text is never exported. */ }
        }
        return j;
    }
    void add(long now, Event event, long a, long b) throws JSONException {
        if(event==Event.SYNTHETIC_RECEIVED || event==Event.DEMO_RECEIVED || event==Event.WEATHER_RECEIVED) {
            for(int i=events.length()-1;i>=0;i--) { JSONObject previous=events.getJSONObject(i);
                if(event.name().equals(previous.getString("event"))) { if(previous.getLong("a")==a && previous.getLong("b")==b) return; break; }
            }
        }
        if (events.length()>=MAX_EVENTS) { events.remove(0); dropped++; }
        events.put(new JSONObject().put("time",now).put("event",event.name()).put("a",a).put("b",b));
    }
    void action(long time,Event event,Source source,Outcome outcome,Error error,long a,long b,String subject) throws JSONException {
        action(time,event,source,outcome,error,a,b,subject,"",0);
    }
    void action(long time,Event event,Source source,Outcome outcome,Error error,long a,long b,String subject,String candidate,long receipt) throws JSONException {
        if(events.length()>=MAX_EVENTS){events.remove(0);dropped++;}
        events.put(new JSONObject().put("time",time).put("event",event.name()).put("source",source.name())
            .put("result",outcome.name()).put("error",error.name()).put("a",a).put("b",b)
            .put("subject",subject!=null && subject.matches("[0-9a-f]{16}")?subject:"").put("candidate",candidate!=null && candidate.matches("[0-9a-f]{16}")?candidate:"").put("receipt",Math.max(0,receipt)));
    }
    String json() throws JSONException { return new JSONObject().put("incomplete",incomplete).put("salt",salt).put("active",active).put("dropped",dropped).put("events",events).toString(); }
    String text() throws JSONException {
        StringBuilder s=new StringBuilder("Rapport d’essai Moli OS\nÉtat : ").append(active?"actif":"terminé").append(" ; événements omis : ").append(dropped).append(" ; incomplet : ").append(incomplete).append('\n');
        s.append("a/b : compteurs ou durée en ms ; EQUIPMENT_READ : a=type (1 IPP, 2 IGD, 3 MSS310), b=nombre de mesures fraîches. NETWORK_END : a=appareils par IP, b=masque services (1 IPP, 2 HTTP, 4 Cast, 8 UPnP, 16 IGD) ; ACCOUNT_FORGET b=1 révocation confirmée, b=0 non confirmée ; UUID_CHECK a=1 lectures autorisées, a=0 désactivées. Les pseudonymes appareil# désignent les cibles, candidate# les adresses (même HMAC de session que la copie/choix d’adresse). UUID_CHECK début/refus ne prouve aucun enregistrement. EQUIPMENT_READ ABSENT au premier cycle ne prouve pas une prise introuvable : attendre les lectures suivantes. CACHE ne prouve pas la validité distante du jeton.\n");
        java.time.format.DateTimeFormatter local=java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(java.time.ZoneId.systemDefault());
        for(int i=0;i<events.length();i++) { JSONObject e=events.getJSONObject(i);
            java.time.Instant time=java.time.Instant.ofEpochMilli(e.getLong("time"));
            s.append(local.format(time)).append(" [").append(time).append("] ");
            s.append(e.optString("source","DIAGNOSTICS")).append(" | ").append(label(Event.valueOf(e.getString("event")))).append(" [").append(e.getString("event")).append("]");
            if(e.has("result"))s.append(" | ").append(e.getString("result")).append(" | catégorie=").append(e.getString("error"));
            if(!e.optString("subject").isEmpty())s.append(" | appareil#").append(e.getString("subject"));
            if(!e.optString("candidate").isEmpty())s.append(" | candidate#").append(e.getString("candidate"));
            if(e.optLong("receipt")>0)s.append(" | dernière_réception=").append(local.format(java.time.Instant.ofEpochMilli(e.getLong("receipt"))));
            s.append(" | a=").append(e.getLong("a")).append(" b=").append(e.getLong("b")).append('\n');
        }
        return s.toString();
    }
    private static String label(Event e) {
        return switch(e) {
            case SESSION_START -> "Début de session"; case SESSION_END -> "Fin de session";
            case ENGINE_STARTED -> "Moteur démarré";case ENGINE_STOPPED -> "Moteur arrêté";case ENGINE_FAILED -> "Échec moteur";
            case NETWORK_SEARCH -> "Recherche réseau démarrée";case NETWORK_END -> "Recherche réseau terminée";
            case IP_COPY -> "Adresse copiée";case IP_CHOSEN -> "Candidate choisie";
            case EQUIPMENT_PROPOSED -> "Configuration préremplie, non enregistrée";case EQUIPMENT_SAVED -> "Configuration enregistrée";
            case ACCOUNT_STATUS -> "Session et inventaire en cache";case ACCOUNT_SIGNIN -> "Connexion Meross";
            case ACCOUNT_INVENTORY -> "Actualisation inventaire Meross";case ACCOUNT_FORGET -> "Oubli du compte Meross";
            case UUID_CHECK -> "Identité UUID et association";case CANDIDATE_CHECK -> "Lecture des identités candidates";
            case CANDIDATE_RESULT -> "Identité candidate / prise correspondante, sinon cible choisie";case EQUIPMENT_READ -> "Lecture équipement";default -> e.name();
        };
    }
}
