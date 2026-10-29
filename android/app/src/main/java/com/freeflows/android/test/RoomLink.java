package com.freeflows.android.test;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class RoomLink {
    final String base, invite, control;
    RoomLink(String text) {
        try {
            URI u = new URI(text.trim());
            if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getRawUserInfo() != null)
                throw new IllegalArgumentException();
            invite = param(u.getRawQuery(), "invite");
            if (invite.trim().isEmpty()) throw new IllegalArgumentException();
            control = param(u.getRawFragment(), "control");
            base = new URI("https", null, u.getHost(), u.getPort(), null, null, null).toString();
        } catch (Exception e) { throw new IllegalArgumentException("Cole o convite HTTPS completo da sala."); }
    }
    String invitation() {
        try { return base + "/?invite=" + URLEncoder.encode(invite, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }
    private static String param(String query, String key) {
        if (query != null) for (String item : query.split("&")) {
            String[] p = item.split("=", 2);
            try {
                if (URLDecoder.decode(p[0], "UTF-8").equals(key))
                    return p.length > 1 ? URLDecoder.decode(p[1], "UTF-8") : "";
            } catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
        }
        return "";
    }
}
