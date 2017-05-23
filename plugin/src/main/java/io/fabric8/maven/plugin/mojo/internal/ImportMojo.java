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
package io.fabric8.maven.plugin.mojo.internal;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import com.google.common.base.Objects;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.utils.IOHelpers;
import io.fabric8.maven.core.access.ClusterAccess;
import io.fabric8.maven.core.service.openshift.JenkinShiftClient;
import io.fabric8.maven.core.util.FileUtil;
import io.fabric8.maven.core.util.kubernetes.*;
import io.fabric8.maven.docker.util.Logger;
import io.fabric8.maven.fabric8.project.BuildConfigHelper;
import io.fabric8.maven.fabric8.project.GitUtils;
import io.fabric8.maven.fabric8.project.UserDetails;
import io.fabric8.maven.plugin.mojo.AbstractFabric8Mojo;
import io.fabric8.openshift.api.model.*;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.OpenShiftNotAvailableException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.components.interactivity.Prompter;
import org.codehaus.plexus.components.interactivity.PrompterException;
import org.eclipse.jgit.lib.Repository;

import static io.fabric8.maven.core.util.kubernetes.KubernetesHelper.*;

/**
 * Imports the current project into fabric8 so that it can be automatically built via Jenkins and appears in the
 * <a href="http://fabric8.io/guide/console.html">fabric8 developer console</a>
 */
@Mojo(name = "import", requiresProject = true)
public class ImportMojo extends AbstractFabric8Mojo {

    public static final String FABRIC8_GIT_APP_SECRETS_CONFIGMAP = "fabric8-git-app-secrets";
    public static final String PROPERTY_PRIVATE_KEY = "ssh-key";
    public static final String PROPERTY_PUBLIC_KEY = "ssh-key.pub";
    public static final String GOGS_REPO_HOST = "gogs";

    @Parameter(defaultValue = "${basedir}")
    private File basedir;

    @Parameter(property = "fabric8.project.name")
    private String projectName;

    @Parameter(property = "fabric8.origin.branchName", defaultValue = "origin")
    private String originBranchName;

    @Parameter(property = "fabric8.passsword.retry")
    private boolean retryPassword;

    /**
     * Namespace under which to operate
     */
    @Parameter(property = "fabric8.namespace")
    private String namespace;

    @Parameter(property = "fabric8.secret.namespace")
    private String secretNamespace;

    @Parameter(property = "fabric8.secret.name")
    private String gogsSecretName;

    @Component
    private Prompter prompter;

    private ClusterAccess clusterAccess;
    private String gitUserName;
    private String gitPassword;
    private String gitEmail;
    private boolean gitSecretUpdated;

    private BuildConfigHelper buildConfigHelper;

    private static String getQualifiedName(HasMetadata hasMetadata, String defaultNamespace) {
        return StringUtils.defaultIfEmpty(getNamespace(hasMetadata), defaultNamespace) +
               "/" + getName(hasMetadata);
    }

    @Override
    public void executeInternal() throws MojoExecutionException, MojoFailureException {
        if (!basedir.isDirectory() || !basedir.exists()) {
            throw new MojoExecutionException("No directory for base directory: " + basedir);
        }

        buildConfigHelper = new BuildConfigHelper(log);

        // lets check for a git repo
        String gitRemoteURL;
        Repository repository;
        try {
            repository = GitUtils.findRepository(basedir);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to find local git repository in current directory: " + e, e);
        }
        try {
            gitRemoteURL = GitUtils.getRemoteURL(repository);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to get the current git branch: " + e, e);
        }

        try {
            clusterAccess = new ClusterAccess(this.namespace);
            if (StringUtils.isBlank(projectName)) {
                projectName = basedir.getName();
            }

            KubernetesClient kubernetes = clusterAccess.createDefaultClient(log);
            KubernetesResourceUtil.validateKubernetesMasterUrl(kubernetes.getMasterUrl());

            String namespace = clusterAccess.getNamespace();
            OpenShiftClient openShiftClient = getOpenShiftClientOrJenkinsShift(kubernetes, namespace);

            if (gitRemoteURL != null) {
                // lets check we don't already have this project imported
                String branch = repository.getBranch();
                BuildConfig buildConfig = findBuildConfigForGitRepo(openShiftClient, namespace, gitRemoteURL, branch);
                if (buildConfig != null) {
                    logBuildConfigLink(kubernetes, namespace, buildConfig, log);
                    throw new MojoExecutionException("Project already imported into build " +
                            getName(buildConfig) + " for URI: " + gitRemoteURL + " and branch: " + branch);
                } else {
                    Map<String, String> annotations = new HashMap<>();
                    annotations.put(Fabric8Annotations.GIT_CLONE_URL.value(), gitRemoteURL);
                    buildConfig = buildConfigHelper.createBuildConfig(kubernetes, namespace, projectName, gitRemoteURL, annotations);

                    openShiftClient.buildConfigs().inNamespace(namespace).create(buildConfig);

                    ensureExternalGitSecretsAreSetupFor(kubernetes, namespace, gitRemoteURL);

                    logBuildConfigLink(kubernetes, namespace, buildConfig, log);
                }
            } else {
                // lets create an import a new project
                UserDetails userDetails = createGogsUserDetails(kubernetes, namespace);
                BuildConfigHelper.CreateGitProjectResults createGitProjectResults;
                try {
                    createGitProjectResults = buildConfigHelper.importNewGitProject(kubernetes, userDetails, basedir,
                                                                                    namespace, projectName, originBranchName, "Importing project from mvn fabric8:import", false);
                } catch (WebApplicationException e) {
                    Response response = e.getResponse();
                    if (response.getStatus() > 400) {
                        String message = getEntityMessage(response);
                        log.warn("Could not create the git repository: %s %s", e, message);
                        log.warn("Are your username and password correct in the Secret %s/%s?", secretNamespace, gogsSecretName);
                        log.warn("To re-enter your password rerun this command with -Dfabric8.passsword.retry=true");

                        throw new MojoExecutionException("Could not create the git repository. " +
                                "Are your username and password correct in the Secret " +
                                secretNamespace + "/" + gogsSecretName + "?" + e + message, e);
                    } else {
                        throw e;
                    }
                }

                BuildConfig buildConfig = createGitProjectResults.getBuildConfig();
                openShiftClient.buildConfigs().inNamespace(namespace).create(buildConfig);
                logBuildConfigLink(kubernetes, namespace, buildConfig, log);
            }
        } catch (KubernetesClientException e) {
            KubernetesResourceUtil.handleKubernetesClientException(e, this.log);
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    protected void ensureExternalGitSecretsAreSetupFor(KubernetesClient kubernetes, String namespace, String gitRemoteURL) throws MojoExecutionException {
        String secretNamespace = getSecretNamespace();
        ensureNamespaceExists(kubernetes, secretNamespace);

        ConfigMap configMap = getSecretGitConfigMap(kubernetes, namespace, secretNamespace);
        String host = GitUtils.getGitHostName(gitRemoteURL);
        if (host == null) {
            host = "default";
        }
        String protocol = GitUtils.getGitProtocol(gitRemoteURL);
        boolean isSsh = Objects.equal("ssh", protocol);

        String currentSecretName = configMap.getData().get(host);
        if (currentSecretName == null) {
            currentSecretName = createGitSecretName(namespace, host);
        }

        Secret secret = findOrCreateGitSecret(kubernetes, currentSecretName, host);
        if (isSsh) {
            // lets see if we need to import ssh keys
            Map<String, String> secretData = secret.getData();
            if (secretData == null) {
                secretData = new HashMap<>();
            }
            if (!secretData.containsKey(PROPERTY_PRIVATE_KEY) || !secretData.containsKey(PROPERTY_PUBLIC_KEY)) {
                String answer = null;
                try {
                    answer = prompter.prompt("Would you like to import your local SSH public/private key pair from your ~/.ssh folder? (Y/n)");
                } catch (PrompterException e) {
                    log.warn("Failed to get prompt: %s", e);
                }
                if (answer != null && answer.trim().isEmpty() || answer.trim().toUpperCase().startsWith("Y")) {
                    chooseSshKeyPairs(secretData, host);
                    secret.setData(secretData);
                }
            }
        } else {
            // if empty or retrying lets re-enter the user/pwd
            getGogsSecretField(kubernetes, secret, host, "username");
            getGogsSecretField(kubernetes, secret, host, "password");
        }
        createOrUpdateSecret(kubernetes, secret);

        updateSecretGitConfigMap(kubernetes, secretNamespace, configMap, host, currentSecretName);
    }

    private ConfigMap getSecretGitConfigMap(KubernetesClient kubernetes, String namespace, String secretNamespace) {
        ConfigMap configMap = kubernetes.configMaps().inNamespace(secretNamespace).withName(FABRIC8_GIT_APP_SECRETS_CONFIGMAP).get();
        if (configMap == null) {
            Map<String, String> labels = new HashMap<String, String>();
            labels.put("provider", "fabric8");
            Map<String, String> data = new HashMap<>();
            data.put(GOGS_REPO_HOST, createGitSecretName(namespace, GOGS_REPO_HOST));
            configMap = new ConfigMapBuilder().
                    withNewMetadata().withName(FABRIC8_GIT_APP_SECRETS_CONFIGMAP).
                    withLabels(labels).endMetadata().withData(data).build();
            log.info("Creating ConfigMap " + FABRIC8_GIT_APP_SECRETS_CONFIGMAP + " in namespace " + secretNamespace);
            kubernetes.configMaps().inNamespace(secretNamespace).withName(FABRIC8_GIT_APP_SECRETS_CONFIGMAP).create(configMap);
        }
        if (configMap.getData() == null) {
            configMap.setData(new HashMap<String, String>());
        }
        return configMap;
    }

    private void updateSecretGitConfigMap(KubernetesClient kubernetes, String secretNamespace, ConfigMap configMap, String host, String currentSecretName) {
        Map<String, String> data = configMap.getData();
        if (data == null) {
            data = new HashMap<>();
        }
        if (!Objects.equal(data.put(host, currentSecretName), currentSecretName)) {
            configMap.setData(data);
            log.info("Updating ConfigMap " + getQualifiedName(configMap, secretNamespace));
            kubernetes.configMaps().inNamespace(secretNamespace).withName(FABRIC8_GIT_APP_SECRETS_CONFIGMAP).replace(configMap);
        }
    }

    private void chooseSshKeyPairs(Map<String, String> secretData, String host) throws MojoExecutionException {
        String homeDir = System.getProperty("user.home", ".");
        File sshDir = new File(homeDir, ".ssh");
        SortedMap<String, String> keyPairs = new TreeMap<>();
        if (sshDir.isDirectory() && sshDir.exists()) {
            File[] files = sshDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String publicName = file.getName();
                    if (file.isFile() && publicName.endsWith(".pub")) {
                        String privateName = FileUtil.stripPostfix(publicName, ".pub");
                        if (new File(sshDir, privateName).isFile()) {
                            keyPairs.put(privateName, publicName);
                        }
                    }
                }
            }
        }

        if (keyPairs.isEmpty()) {
            log.warn("No SSH key pairs could be found in %s to choose from!", sshDir);
            log.warn("You may want to clone the git repository over https:// instead to avoid ssh key pairs?");
        } else {
            if (keyPairs.size() == 0) {
                String privateName = keyPairs.firstKey();
                importSshKeys(secretData, sshDir, privateName, keyPairs.get(privateName));
            } else {
                List<String> privateKeys = new ArrayList<>(keyPairs.keySet());
                String privateKey = null;
                try {
                    privateKey = prompter.prompt("Which public / private key pair do you wish to use for SSH authentication with host: " + host, privateKeys);
                } catch (PrompterException e) {
                    log.warn("Failed to get user input: %s", e);
                }
                if (StringUtils.isNotBlank(privateKey)) {
                    String publicKey = keyPairs.get(privateKey);
                    if (StringUtils.isBlank(publicKey)) {
                        log.warn("Invalid answer: %s when available values are: %s", privateKey, privateKeys);
                    } else {
                        importSshKeys(secretData, sshDir, privateKey, publicKey);
                    }
                }
            }
        }
    }

    protected void importSshKeys(Map<String, String> secretData, File sshDir, String privateKeyFileName, String publicKeyFileName) throws MojoExecutionException {
        String privKey = loadKey(sshDir, privateKeyFileName);
        String pubKey = loadKey(sshDir, publicKeyFileName);
        secretData.put(PROPERTY_PRIVATE_KEY, privKey);
        secretData.put(PROPERTY_PUBLIC_KEY, pubKey);
        gitSecretUpdated = true;
    }

    private String loadKey(File dir, String name) throws MojoExecutionException {
        File file = new File(dir, name);
        if (!file.isFile() || !file.exists()) {
            throw new MojoExecutionException("SSH key file " + file + " is not a file!");
        }
        String key = null;
        try {
            key = FileUtils.readFileToString(file, Charset.defaultCharset());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to load SSH key file " + file + ": " + e, e);
        }
        if (StringUtils.isBlank(key)) {
            throw new MojoExecutionException("Empty SSH key file " + file);
        }
        return Base64.encodeBase64String(key.getBytes());
    }

    protected UserDetails createGogsUserDetails(KubernetesClient kubernetes, String namespace) throws MojoExecutionException {
        String gogsURL = getGogsURL(kubernetes, namespace);
        log.debug("Got gogs URL: " + gogsURL);
        if (StringUtils.isBlank(gogsURL)) {
            throw new MojoExecutionException("Could not find the external URL to service 'gogs' in namespace " + namespace + ". Are you sure you are running gogs in this kubernetes namespace?");
        }
        String gogsSecretName = getGogsSecretName(namespace);
        Secret gogsSecret = null;
        if (StringUtils.isBlank(gitUserName) || StringUtils.isBlank(gitPassword)) {
            gogsSecret = findOrCreateGitSecret(kubernetes, gogsSecretName, GOGS_REPO_HOST);
        }
        if (StringUtils.isBlank(gitUserName)) {
            gitUserName = getGogsSecretField(kubernetes, gogsSecret, GOGS_REPO_HOST, "username");
        }
        if (StringUtils.isBlank(gitPassword)) {
            gitPassword = getGogsSecretField(kubernetes, gogsSecret, GOGS_REPO_HOST, "password");
        }
        if (StringUtils.isBlank(gitEmail)) {
            gitEmail = findEmailFromDotGitConfig();
        }
        createOrUpdateSecret(kubernetes, gogsSecret);

        ConfigMap configMap = getSecretGitConfigMap(kubernetes, namespace, secretNamespace);
        updateSecretGitConfigMap(kubernetes, secretNamespace, configMap, GOGS_REPO_HOST, gogsSecretName);

        log.info("git username: " + gitUserName + " password: " + hidePassword(gitPassword) + " email: " + gitEmail);
        return new UserDetails(gogsURL, gogsURL, gitUserName, gitPassword, gitEmail);
    }

    private void createOrUpdateSecret(KubernetesClient kubernetes, Secret secret) {
        if (gitSecretUpdated) {
            String name = getName(secret);
            if (StringUtils.isNotBlank(secret.getMetadata().getResourceVersion())) {
                log.info("Updating Secret " + getQualifiedName(secret, secretNamespace));
                kubernetes.secrets().inNamespace(secretNamespace).withName(name).replace(secret);
            } else {
                log.info("Creating Secret " + getQualifiedName(secret, secretNamespace));
                kubernetes.secrets().inNamespace(secretNamespace).withName(name).create(secret);
            }
        }
    }

    private String getGogsSecretField(KubernetesClient kubernetes, Secret gogsSecret, String gitRepoHost, String propertyName) throws MojoExecutionException {
        Map<String, String> data = gogsSecret.getData();
        if (data == null) {
            data = new HashMap<>();
            gogsSecret.setData(data);
        }
        String value = data.get(propertyName);
        if (StringUtils.isBlank(value) || retryPassword) {
            try {
                if (propertyName.equals("password")) {
                    value = prompter.promptForPassword("Please enter your password/access token for git repo " + gitRepoHost);
                } else {
                    value = prompter.prompt("Please enter your username for git repo " + gitRepoHost);
                }
            } catch (PrompterException e) {
                throw new MojoExecutionException("Failed to input required data: " + e, e);
            }
            data.put(propertyName, Base64.encodeBase64String(value.getBytes()));
            gitSecretUpdated = true;
            return value;
        }
        return new String(org.apache.commons.codec.binary.Base64.decodeBase64(value));
    }

    private String hidePassword(String password) {
        if (StringUtils.isBlank(password)) {
            return "";
        }
        StringBuilder buffer = new StringBuilder();
        for (int i = 0, size = password.length(); i < size; i++) {
            buffer.append("*");
        }
        return buffer.toString();
    }

    private String findEmailFromDotGitConfig() {
        Map<String, Properties> map = null;
        try {
            map = GitUtils.parseGitConfig();
        } catch (IOException e) {
            log.warn("Failed to parse ~/.gitconfig file. %s", e);
        }
        if (map != null) {
            Properties user = map.get("user");
            if (user != null) {
                return user.getProperty("email");
            }
        }
        return null;
    }

    private Secret findOrCreateGitSecret(KubernetesClient kubernetes, String secretName, String repositoryHost) {
        String secretNamespace = getSecretNamespace();
        ensureNamespaceExists(kubernetes, secretNamespace);


        Secret gogsSecret = kubernetes.secrets().inNamespace(secretNamespace).withName(secretName).get();
        if (gogsSecret == null) {
            // lets create a new secret!
            Map<String, String> labels = new HashMap<>();
            labels.put("provider", "fabric8");
            labels.put("repository", repositoryHost);
            labels.put("scm", "git");
            gogsSecret = new SecretBuilder().withNewMetadata().withName(secretName).withLabels(labels).endMetadata().withData(new HashMap<String, String>()).build();
        }
        return gogsSecret;
    }

    private void ensureNamespaceExists(KubernetesClient kubernetes, String name) {
        // lets check namespace exists
        Namespace namespace = kubernetes.namespaces().withName(name).get();
        if (namespace == null) {
            Map<String, String> labels = new HashMap<>();
            labels.put("provider", "fabric8");
            labels.put("kind", "secrets");
            namespace = new NamespaceBuilder().withNewMetadata().withName(name).withLabels(labels).endMetadata().build();
            if (OpenshiftHelper.isOpenShift(kubernetes)) {
                ProjectRequest projectRequest = new ProjectRequestBuilder().withMetadata(namespace.getMetadata()).build();
                OpenShiftClient openShiftClient = asOpenShiftClient(kubernetes);
                log.info("Creating ProjectRequest " + name + " with labels: " + labels);
                openShiftClient.projectrequests().create(projectRequest);
            } else {
                log.info("Creating Namespace " + name + " with labels: " + labels);
                kubernetes.namespaces().withName(name).create(namespace);
            }
        }
    }

    private OpenShiftClient asOpenShiftClient(KubernetesClient kubernetes) {
        return kubernetes.adapt(OpenShiftClient.class);
    }

    protected BuildConfig findBuildConfigForGitRepo(OpenShiftClient openShiftClient, String namespace, String gitRepoUrl, String gitRef) throws MojoExecutionException {
        BuildConfigList buildConfigList = openShiftClient.buildConfigs().inNamespace(namespace).list();
        if (buildConfigList != null) {
            List<BuildConfig> items = buildConfigList.getItems();
            if (items != null) {
                for (BuildConfig item : items) {
                    BuildConfigSpec spec = item.getSpec();
                    if (spec != null) {
                        BuildSource source = spec.getSource();
                        if (source != null) {
                            GitBuildSource git = source.getGit();
                            if (git != null) {
                                String uri = git.getUri();
                                String ref = git.getRef();
                                if (Objects.equal(gitRepoUrl, uri)) {
                                    if (StringUtils.isBlank(gitRef) && StringUtils.isBlank(ref)) {
                                        return item;
                                    }
                                    if (Objects.equal(gitRef, ref)) {
                                        return item;
                                    }
                                }

                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    public String getSecretNamespace() {
        if (StringUtils.isBlank(secretNamespace)) {
            secretNamespace = "user-secrets-source-" + KubernetesHelper.currentUserName();
        }
        return secretNamespace;
    }

    public String getGogsSecretName(String currentNamespace) {
        if (StringUtils.isBlank(gogsSecretName)) {
            gogsSecretName = createGitSecretName(currentNamespace, GOGS_REPO_HOST);
        }
        return gogsSecretName;
    }

    private String createGitSecretName(String namespace, String host) {
        return namespace + "-" + host + "-git";
    }

    protected void logBuildConfigLink(KubernetesClient kubernetes, String namespace, BuildConfig buildConfig, Logger log) {
        String url = buildConfigHelper.getBuildConfigConsoleURL(kubernetes, namespace, buildConfig);
        if (url != null) {
            log.info("You can view the project dashboard at: " + url);
            File jenkinsfile = new File(basedir, "Jenkinsfile");
            if (!jenkinsfile.exists() || !jenkinsfile.isFile()) {
                log.info("To configure a CD Pipeline go to: " + String.format("%s/forge/command/devops-edit", url));
            }
        }
    }

    private String getGogsURL(KubernetesClient kubernetes, String namespace) throws MojoExecutionException {
        Endpoints endpoints = kubernetes.endpoints().inNamespace(namespace).withName("gogs").get();
        int runningEndpoints = 0;
        if (endpoints != null) {
            List<EndpointSubset> subsets = endpoints.getSubsets();
            for (EndpointSubset subset : subsets) {
                List<EndpointAddress> addresses = subset.getAddresses();
                if (addresses != null) {
                    runningEndpoints += addresses.size();
                }
            }
        }
        if (runningEndpoints == 0) {
            log.warn("No running endpoints for service %s in namespace %s. " +
                     "Please run the `gogs` or the `cd-pipeline` application in the fabric8 console.",
                    "gogs", namespace);
            throw new MojoExecutionException("No service " + "gogs" + " running in namespace " + namespace);
        }
        log.info("Running %s endpoints of %s in namespace %s", runningEndpoints, "gogs", namespace);
        return ServiceUrlUtil.getServiceURL(kubernetes, "gogs", namespace, "http", true);
    }

    protected String getEntityMessage(Response response) throws IOException {
        Object entity = response.getEntity();
        String message = "";
        if (entity != null) {
            if (entity instanceof InputStream) {
                InputStream is = (InputStream) entity;
                entity = IOHelpers.readFully(is);
            } else if (entity instanceof Reader) {
                Reader r = (Reader) entity;
                entity = IOHelpers.readFully(r);
            }
            message = " " + entity;
        }
        return message;
    }

    private OpenShiftClient getOpenShiftClientOrJenkinsShift(KubernetesClient kubernetes, String namespace) throws MojoExecutionException {
        OpenShiftClient openShiftClient = getOpenShiftClientOrNull(kubernetes);
        if (openShiftClient == null) {
            String jenkinshiftUrl = getJenkinShiftUrl(kubernetes, namespace);
            log.debug("Using jenkinshift URL: " + jenkinshiftUrl);
            if (jenkinshiftUrl == null) {
                throw new MojoExecutionException("Could not find the service `jenkinshift` in namespace `" + namespace + "` on this kubernetes cluster " + kubernetes.getMasterUrl());
            }
            return new JenkinShiftClient(jenkinshiftUrl);
        }
        return openShiftClient;
    }

    private String getJenkinShiftUrl(KubernetesClient kubernetes, String namespace) {
        String jenkinshiftUrl = ServiceUrlUtil.getServiceURL(kubernetes, "jenkinshift", namespace, "http", true);
        if (jenkinshiftUrl == null) {
            // the jenkinsshift URL is not external so lets use the fabric8 console
            String fabric8ConsoleURL = getFabric8ConsoleServiceUrl(kubernetes, namespace);
            if (StringUtils.isNotBlank(fabric8ConsoleURL)) {
                jenkinshiftUrl = String.format("%s/k8s",fabric8ConsoleURL);
            }
        }
        return jenkinshiftUrl;
    }

    private static String getFabric8ConsoleServiceUrl(KubernetesClient kubernetes, String namespace) {
        return ServiceUrlUtil.getServiceURL(kubernetes, "fabric8", namespace, "http", true);
    }

    private OpenShiftClient getOpenShiftClientOrNull(KubernetesClient kubernetesClient) {
        try {
            return kubernetesClient.adapt(OpenShiftClient.class);
        } catch (OpenShiftNotAvailableException e) {
            // ignore
        }
        return null;
    }
}
