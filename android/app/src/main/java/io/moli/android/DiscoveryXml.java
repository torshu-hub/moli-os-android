package io.moli.android;

import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.StringReader;
import java.util.Locale;

/** Bounded device-description reading; never follows XML links or entities. */
final class DiscoveryXml {
    static NetworkEvidence.Entry describe(NetworkEvidence.Entry announcement,String xml)throws Exception {
        if(xml==null||xml.length()>32768||xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")||xml.toUpperCase(Locale.ROOT).contains("<!ENTITY"))throw new IllegalArgumentException("description");
        XmlPullParser parser=Xml.newPullParser();parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);
        parser.setInput(new StringReader(xml));String name="";boolean root=false,closedRoot=false,gateway=false,wan=false;int events=0;
        for(int event=parser.next();event!=XmlPullParser.END_DOCUMENT;event=parser.next()){
            if(++events>8192||parser.getDepth()>32)throw new IllegalArgumentException("description");
            if(event==XmlPullParser.END_TAG&&parser.getDepth()==1&&parser.getName().equals("root"))closedRoot=true;
            if(event!=XmlPullParser.START_TAG)continue;
            if(parser.getDepth()==1){if(root||!parser.getName().equals("root"))throw new IllegalArgumentException("description");root=true;}
            switch(parser.getName()){
                case "friendlyName": if(name.isEmpty())name=NetworkEvidence.safeName(parser.nextText());break;
                case "deviceType": gateway|=parser.nextText().startsWith("urn:schemas-upnp-org:device:InternetGatewayDevice:");break;
                case "serviceType": wan|=parser.nextText().startsWith("urn:schemas-upnp-org:service:WANIPConnection:");break;
                default: break;
            }
        }
        if(!root||!closedRoot)throw new IllegalArgumentException("description");
        return new NetworkEvidence.Entry(announcement.ip,announcement.port,name,gateway&&wan?NetworkEvidence.Kind.IGD:NetworkEvidence.Kind.UPNP,NetworkEvidence.Proof.DESCRIPTION,announcement.path,announcement.location);
    }
    private DiscoveryXml(){}
}
