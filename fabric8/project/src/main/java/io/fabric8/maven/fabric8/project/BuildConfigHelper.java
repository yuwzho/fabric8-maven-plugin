/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.maven.fabric8.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.maven.core.service.ApplyService;
import io.fabric8.maven.core.util.ResourceUtil;
import io.fabric8.maven.core.util.kubernetes.Fabric8Annotations;
import io.fabric8.maven.core.util.kubernetes.KubernetesHelper;
import io.fabric8.maven.core.util.kubernetes.ServiceUrlUtil;
import io.fabric8.maven.docker.util.Logger;
import io.fabric8.maven.fabric8.project.gitrepo.CreateRepositoryDTO;
import io.fabric8.maven.fabric8.project.gitrepo.GitRepoClient;
import io.fabric8.maven.fabric8.project.gitrepo.RepositoryDTO;
import io.fabric8.openshift.api.model.BuildConfig;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.InitCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static io.fabric8.maven.core.util.kubernetes.KubernetesHelper.*;

/**
 */
public class BuildConfigHelper {

    private Logger log;

    public BuildConfigHelper(Logger log) {
        this.log = log;
    }

    /**
     * Returns the created BuildConfig for the given project name and git repository
     */
    private BuildConfig createAndApplyBuildConfig(KubernetesClient kubernetesClient, String namespace, String projectName, String cloneUrl, Map<String, String> annotations) {
        BuildConfig buildConfig = createBuildConfig(kubernetesClient, namespace, projectName, cloneUrl, annotations);
        ApplyService controller = new ApplyService(kubernetesClient, log);
        controller.setNamespace(namespace);
        controller.applyBuildConfig(buildConfig, "from project " + projectName);
        return buildConfig;
    }

    public BuildConfig createBuildConfig(KubernetesClient kubernetesClient, String namespace, String projectName, String cloneUrl, Map<String, String> annotations) {
        log.info("Creating a BuildConfig for namespace: " + namespace + " project: " + projectName);
        String jenkinsUrl = null;
        try {
            jenkinsUrl = getJenkinsServiceUrl(kubernetesClient, namespace);
        } catch (Exception e) {
            // ignore missing Jenkins service issue
        }
        BuildConfig buildConfig = Builds.createDefaultBuildConfig(projectName, cloneUrl, jenkinsUrl);
        Map<String, String> currentAnnotations = KubernetesHelper.getOrCreateAnnotations(buildConfig);
        currentAnnotations.putAll(annotations);
        return buildConfig;
    }

    /**
     * Returns the URL to the fabric8 console
     */
    public String getBuildConfigConsoleURL(KubernetesClient kubernetes, String consoleNamespace, BuildConfig buildConfig) {
        String name = getName(buildConfig);
        String namespace = getNamespace(buildConfig);
        if (StringUtils.isBlank(namespace)) {
            namespace = consoleNamespace;
        }
        String consoleURL = getFabric8ConsoleServiceUrl(kubernetes, namespace);
        if (StringUtils.isNotBlank(consoleURL)) {
            if (StringUtils.isNotBlank(name)) {
                return String.format("%s/workspaces/%s/projects/%s", consoleURL, namespace, name);
            }
            return String.format("%s/workspaces/%s", consoleURL, namespace);
        }
        return null;
    }

    private  String getJenkinsServiceUrl(KubernetesClient kubernetes, String namespace) {
        return ServiceUrlUtil.getServiceURL(kubernetes, "jenkins", namespace, "http", true);
    }

    private  String getFabric8ConsoleServiceUrl(KubernetesClient kubernetes, String namespace) {
        return ServiceUrlUtil.getServiceURL(kubernetes, "fabric8", namespace, "http", true);
    }

    public  CreateGitProjectResults importNewGitProject(KubernetesClient kubernetesClient, UserDetails userDetails, File basedir, String namespace, String projectName, String origin, String message, boolean apply) throws GitAPIException, JsonProcessingException {
        GitUtils.disableSslCertificateChecks();

        InitCommand initCommand = Git.init();
        initCommand.setDirectory(basedir);
        Git git = initCommand.call();
        log.info("Initialised an empty git configuration repo at {}", basedir.getAbsolutePath());

        PersonIdent personIdent = userDetails.createPersonIdent();

        String user = userDetails.getUser();
        String address = userDetails.getAddress();
        String internalAddress = userDetails.getInternalAddress();
        String branch = userDetails.getBranch();

        // lets create the repository
        GitRepoClient repoClient = userDetails.createRepoClient();
        CreateRepositoryDTO createRepository = new CreateRepositoryDTO();
        createRepository.setName(projectName);

        String fullName = null;
        RepositoryDTO repository = repoClient.createRepository(createRepository);
        if (repository != null) {
            if (log.isDebugEnabled()) {
                log.debug("Got repository: " + ResourceUtil.toJson(repository));
            }
            fullName = repository.getFullName();
        }
        if (StringUtils.isBlank(fullName)) {
            fullName = user + "/" + projectName;
        }

        String htmlUrl = String.format("%s/%s/%s", address, user, projectName);
        String localCloneUrl = String.format("%s/%s/%s.git", internalAddress, user, projectName);
        String cloneUrl = htmlUrl + ".git";

        String defaultCloneUrl = cloneUrl;
        // lets default to using the local git clone URL
        if (StringUtils.isNotBlank(internalAddress)) {
            defaultCloneUrl = localCloneUrl;
        }

        // now lets import the code and publish
        GitUtils.configureBranch(git, branch, origin, defaultCloneUrl);

        GitUtils.addDummyFileToEmptyFolders(basedir);
        log.info("About to git commit and push to: " + defaultCloneUrl + " and remote name " + origin);
        GitUtils.doAddCommitAndPushFiles(git, userDetails, personIdent, branch, origin, message, true);

        Map<String, String> annotations = new HashMap<>();
        annotations.put(Fabric8Annotations.GIT_CLONE_URL.value(), cloneUrl);
        annotations.put(Fabric8Annotations.GIT_LOCAL_CLONE_URL.value(), localCloneUrl);

        BuildConfig buildConfig;
        if (apply) {
            buildConfig = createAndApplyBuildConfig(kubernetesClient, namespace, projectName, defaultCloneUrl, annotations);
        } else {
            buildConfig = createBuildConfig(kubernetesClient, namespace, projectName, defaultCloneUrl, annotations);
        }

        return new CreateGitProjectResults(buildConfig, fullName, htmlUrl, localCloneUrl, cloneUrl);
    }

    public static class CreateGitProjectResults {
        private final BuildConfig buildConfig;
        private final String fullName;
        private final String htmlUrl;
        private final String remoteUrl;
        private final String cloneUrl;

        public CreateGitProjectResults(BuildConfig buildConfig, String fullName, String htmlUrl, String remoteUrl, String cloneUrl) {
            this.buildConfig = buildConfig;
            this.fullName = fullName;
            this.htmlUrl = htmlUrl;
            this.remoteUrl = remoteUrl;
            this.cloneUrl = cloneUrl;
        }

        @Override
        public String toString() {
            return "CreateGitProjectResults{" +
                    "fullName='" + fullName + '\'' +
                    ", htmlUrl='" + htmlUrl + '\'' +
                    ", remoteUrl='" + remoteUrl + '\'' +
                    ", cloneUrl='" + cloneUrl + '\'' +
                    '}';
        }

        public BuildConfig getBuildConfig() {
            return buildConfig;
        }

        public String getFullName() {
            return fullName;
        }

        public String getHtmlUrl() {
            return htmlUrl;
        }

        public String getRemoteUrl() {
            return remoteUrl;
        }

        public String getCloneUrl() {
            return cloneUrl;
        }
    }
}
