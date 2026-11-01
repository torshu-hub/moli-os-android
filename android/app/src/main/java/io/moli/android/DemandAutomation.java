package io.moli.android;

import org.json.*;

/** A standard Moli graph, never an Android execution engine. */
final class DemandAutomation {
    static JSONObject graph(String source,String target)throws JSONException {
        JSONArray nodes=new JSONArray(),edges=new JSONArray();
        for(String value:new String[]{"on","off"}) {
            nodes.put(new JSONObject().put("id",value).put("type","when_state").put("point",source).put("to",value).put("for_s",0).put("x",0).put("y",value.equals("on")?0:200));
            nodes.put(new JSONObject().put("id","set-"+value).put("type","set").put("point",target).put("value",value.equals("on")).put("x",300).put("y",value.equals("on")?0:200));
            edges.put(new JSONObject().put("from",value).put("port","out").put("to","set-"+value));
        }
        return new JSONObject().put("nodes",nodes).put("edges",edges);
    }
    static boolean point(JSONObject device,String key,boolean writable) {
        JSONArray points=device.optJSONArray("points");
        for(int i=0;points!=null&&i<points.length();i++) {
            JSONObject p=points.optJSONObject(i);
            if(p!=null&&key.equals(p.optString("key"))&&(!writable||(p.optJSONObject("access")!=null&&p.optJSONObject("access").optBoolean("write"))))return true;
        }
        return false;
    }
    static String[] wiring(JSONObject graph)throws JSONException {
        JSONArray nodes=graph.getJSONArray("nodes"),edges=graph.getJSONArray("edges");
        if(nodes.length()!=4||edges.length()!=2)return null;
        String source=null,target=null;
        for(int i=0;i<4;i++){JSONObject n=nodes.getJSONObject(i);if("on".equals(n.optString("id")))source=n.optString("point");if("set-on".equals(n.optString("id")))target=n.optString("point");}
        if(source==null||target==null)return null;
        JSONObject expected=graph(source,target);
        for(String collection:new String[]{"nodes","edges"}){
            JSONArray actual=graph.getJSONArray(collection),want=expected.getJSONArray(collection);
            java.util.Set<String> used=new java.util.HashSet<>();
            for(int i=0;i<actual.length();i++){
                JSONObject item=actual.getJSONObject(i);String key=item.optString(collection.equals("nodes")?"id":"from");
                if(!used.add(key))return null;JSONObject match=null;
                for(int j=0;j<want.length();j++){JSONObject candidate=want.getJSONObject(j);if(key.equals(candidate.optString(collection.equals("nodes")?"id":"from")))match=candidate;}
                if(match==null)return null;
                java.util.Iterator<String> keys=item.keys();
                while(keys.hasNext()){String field=keys.next();if(field.equals("x")||field.equals("y"))continue;if(!match.has(field)||!item.get(field).equals(match.get(field)))return null;}
                keys=match.keys();while(keys.hasNext()){String field=keys.next();if(!field.equals("x")&&!field.equals("y")&&!item.has(field)&&!(field.equals("for_s")||field.equals("port")))return null;}
            }
        }
        return new String[]{source,target};
    }
    static String name(JSONObject device) {
        JSONObject label=device.optJSONObject("label");
        String custom=label==null||label.isNull("name")?"":label.optString("name");
        return custom.isEmpty()?device.optString("native_name"):custom;
    }
    static JSONObject retarget(JSONObject original,String source,String target)throws JSONException {
        if(wiring(original)==null)throw new JSONException("unsupported_graph");
        JSONObject graph=new JSONObject(original.toString());
        JSONArray nodes=graph.getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++){
            JSONObject node=nodes.getJSONObject(i);
            node.put("point","when_state".equals(node.getString("type"))?source:target);
        }
        return graph;
    }
    private DemandAutomation(){}
}
