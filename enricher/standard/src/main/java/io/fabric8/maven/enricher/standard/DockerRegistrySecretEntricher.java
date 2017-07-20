package io.fabric8.maven.enricher.standard;

import io.fabric8.maven.core.util.DockerUtil;
import io.fabric8.maven.enricher.api.EnricherContext;
import io.fabric8.utils.Strings;
import org.apache.maven.settings.Settings;

import java.util.HashMap;
import java.util.Map;

public class DockerRegistrySecretEntricher extends SecretEncricher {
    final private static String ANNOTATION_KEY = "maven.fabric8.io/dockerId";
    final private static String DOCKER_KEY = ".dockerconfigjson";
    final private static String ENCRICHER_NAME = "fmp-docker-registry-secret";


    public DockerRegistrySecretEntricher(EnricherContext buildContext) {
        super(buildContext, ENCRICHER_NAME);
    }

    @Override
    protected String getAnnotationKey() {
        return ANNOTATION_KEY;
    }

    @Override
    protected Map<String, String> generateData(String dockerId) {
        String dockerSecret = DockerUtil.getDockerJsonConfigString(SETTINGS, dockerId);
        if (Strings.isNullOrBlank(dockerSecret)) {
            return null;
        }

        Map<String, String> data = new HashMap();
        data.put(DOCKER_KEY, encode(dockerSecret));
        return data;
    }
}
