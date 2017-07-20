package io.fabric8.maven.plugin.mojo.build;

import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.kubernetes.api.model.KubernetesListBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.maven.core.config.SecretConfig;
import io.fabric8.maven.core.util.Base64Util;
import io.fabric8.maven.core.util.DockerUtil;
import io.fabric8.maven.core.util.ResourceClassifier;
import io.fabric8.utils.Strings;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates or copies the Kubernetes JSON secrets file and attaches it to the build so its
 * installed and released to maven repositories like other build artifacts.
 */
@Mojo(name = "secret", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, requiresDependencyResolution = ResolutionScope.COMPILE)
public class SecretsMojo extends AbstractResourceMojo {

    @Parameter(property = "fabric8.secrets")
    private List<SecretConfig> secrets;

    @Override
    public void executeInternal() throws MojoExecutionException, MojoFailureException {
        KubernetesList secretResource = resolveSecrets();

        this.targetDir = new File(this.targetDir, "secrets");
        writeResources(secretResource, ResourceClassifier.KUBERNETES);

    }

    final private static String API_VERSION = "v1";
    final private static String DOCKER_TYPE = "kubernetes.io/dockerconfigjson";
    final private static String DOCKER_KEY = ".dockerconfigjson";
    final private static String KIND = "Secret";

    private KubernetesList resolveSecrets() {
        KubernetesListBuilder builder = new KubernetesListBuilder();
        for (int i = 0; i < secrets.size(); i++) {
            SecretConfig secretConfig = secrets.get(i);
            if (Strings.isNullOrBlank(secretConfig.name)) {
                continue;
            }

            Map<String, String> data = new HashMap();
            ObjectMeta metadata = new ObjectMeta();
            String type = "";
            metadata.setNamespace(secretConfig.namespace == null ? "default" : secretConfig.namespace);
            metadata.setName(secretConfig.name);

            // docker-registry
            if (secretConfig.dockerId != null) {
                String dockerSecret = DockerUtil.getDockerJsonConfigString(settings, secretConfig.dockerId);
                if (Strings.isNullOrBlank(dockerSecret)) {
                    continue;
                }
                data.put(DOCKER_KEY, Base64Util.encodeToString(dockerSecret));
                type = DOCKER_TYPE;
            }
            // TODO: generic secret

            if (Strings.isNullOrBlank(type) || data.isEmpty()) {
                continue;
            }

            Secret secret = new Secret(API_VERSION, data, KIND, metadata, null, type);
            builder.addToSecretItems(i, secret);
        }
        return builder.build();
    }
}

