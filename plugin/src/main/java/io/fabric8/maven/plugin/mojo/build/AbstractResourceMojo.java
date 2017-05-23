/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.fabric8.maven.plugin.mojo.build;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.maven.core.util.ResourceUtil;
import io.fabric8.maven.core.util.kubernetes.KubernetesHelper;
import io.fabric8.maven.core.util.kubernetes.KubernetesResourceUtil;
import io.fabric8.maven.core.util.ResourceClassifier;
import io.fabric8.maven.core.util.ResourceFileType;
import io.fabric8.maven.docker.util.Logger;
import io.fabric8.maven.plugin.mojo.AbstractFabric8Mojo;
import io.fabric8.openshift.api.model.Template;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProjectHelper;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static io.fabric8.maven.core.util.ResourceFileType.yaml;

/**
 */
public abstract class AbstractResourceMojo extends AbstractFabric8Mojo {
    /**
     * The generated kubernetes and openshift manifests
     */
    @Parameter(property = "fabric8.targetDir", defaultValue = "${project.build.outputDirectory}/META-INF/fabric8")
    protected File targetDir;
    /**
     * The artifact type for attaching the generated resource file to the project.
     * Can be either 'json' or 'yaml'
     */
    @Parameter(property = "fabric8.resourceType")
    private ResourceFileType resourceFileType = yaml;
    @Component
    private MavenProjectHelper projectHelper;

    /**
     * Returns the Template if the list contains a single Template only otherwise returns null
     */
    protected static Template getSingletonTemplate(KubernetesList resources) {
        // if the list contains a single Template lets unwrap it
        if (resources != null) {
            List<HasMetadata> items = resources.getItems();
            if (items != null && items.size() == 1) {
                HasMetadata singleEntity = items.get(0);
                if (singleEntity instanceof Template) {
                    return (Template) singleEntity;
                }
            }
        }
        return null;
    }

    protected void writeResources(KubernetesList resources, ResourceClassifier classifier) throws MojoExecutionException {
        // write kubernetes.yml / openshift.yml
        File resourceFileBase = new File(this.targetDir, classifier.getValue());

        writeResourcesIndividualAndComposite(resources, resourceFileBase, this.resourceFileType, log);

        // Attach it to the Maven reactor so that it will also get deployed
        projectHelper.attachArtifact(project, this.resourceFileType.getArtifactType(), classifier.getValue(), resourceFileType.addExtensionIfMissing(resourceFileBase));
    }

    private static void writeResourcesIndividualAndComposite(KubernetesList resources, File resourceFileBase, ResourceFileType resourceFileType, Logger log) throws MojoExecutionException {
        Object entity = resources;
        // if the list contains a single Template lets unwrap it
        // TODO: Check this, actually this kind of 'unwrapping' should happend somewhere else where the resources are created.
        Template template = getSingletonTemplate(resources);
        if (template != null) {
            entity = template;
        }
        try {
            ResourceUtil.save(resourceFileBase, entity, resourceFileType);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write resource to " + resourceFileType.addExtensionIfMissing(resourceFileBase) + ". " + e, e);
        }

        // write separate files, one for each resource item
        writeIndividualResources(resources, resourceFileBase, resourceFileType, log);
    }

    private static void writeIndividualResources(KubernetesList resources, File targetDir, ResourceFileType resourceFileType, Logger log) throws MojoExecutionException {
        for (HasMetadata item : resources.getItems()) {
            String name = KubernetesHelper.getName(item);
            if (StringUtils.isBlank(name)) {
                log.error("No name for generated item %s", item);
                continue;
            }

            File targetFile = null;
            try {
                String itemFile = KubernetesResourceUtil.getNameWithSuffix(name, item.getKind());
                targetFile = resourceFileType.addExtensionIfMissing(new File(targetDir, itemFile));
                ResourceUtil.save(targetFile, item);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to write resource to " + targetFile + ": " + e, e);
            }
        }
    }

}
