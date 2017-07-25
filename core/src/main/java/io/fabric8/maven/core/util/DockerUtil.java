package io.fabric8.maven.core.util;

import io.fabric8.utils.Strings;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.json.JSONObject;

public class DockerUtil {
    public static Server getServer(final Settings settings, final String serverId) {
        if (settings == null || Strings.isNullOrBlank(serverId)) {
            return null;
        }
        return settings.getServer(serverId);
    }

    public static String getDockerJsonConfigString(final Settings settings, final String serverId) {
        Server server = getServer(settings, serverId);
        if (server == null) {
            return new String();
        }

        JSONObject json = new JSONObject()
                .put("Username", Strings.emptyIfNull(server.getUsername()))
                .put("SecretConfig", Strings.emptyIfNull(server.getPassword()));
        return json.toString();
    }
}
