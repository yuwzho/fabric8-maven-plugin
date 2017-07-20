package io.fabric8.maven.enricher.standard;

import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.model.KubernetesListBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.maven.core.util.Base64Util;
import io.fabric8.maven.enricher.api.BaseEnricher;
import io.fabric8.maven.enricher.api.EnricherContext;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.util.Map;

public abstract class SecretEncricher extends BaseEnricher {

    public SecretEncricher(EnricherContext buildContext, String name) {
        super(buildContext, name);
    }

    protected String encode(String raw) {
        return Base64Util.encodeToString(raw);
    }

    @Override
    public void addMissingResources(KubernetesListBuilder builder) {
        // update builder
        builder.accept(new TypedVisitor<SecretBuilder>() {
            @Override
            public void visit(SecretBuilder secretBuilder) {
                Map<String, String> annotation = secretBuilder.buildMetadata().getAnnotations();
                if (!annotation.containsKey(getAnnotationKey())) { return; }
                String dockerId = annotation.get(getAnnotationKey());
                Map<String, String> data = generateData(dockerId);
                secretBuilder.addToData(data);
            }
        });
    }

    abstract protected String getAnnotationKey();

    abstract protected Map<String, String> generateData(String key);
}
