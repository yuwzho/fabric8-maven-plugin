/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.maven.fabric8.project.gitrepo;

import java.util.List;

import org.apache.cxf.jaxrs.client.JAXRSClientFactory;
import org.apache.cxf.jaxrs.client.WebClient;

/**
 * A client API for working with git hosted repositories using back ends like
 * <a href="http://gogs.io/">gogs</a> or <a href="http://github.com/">github</a>
 */
public class GitRepoClient {

    private final String address;
    private final String username;
    private final String password;
    private GitApi api;

    public GitRepoClient(String address, String username, String password) {
        this.address = address;
        this.password = password;
        this.username = username;
    }

    /**
     * Creates a JAXRS web client for the given JAXRS client
     */
    protected <T> T createWebClient(Class<T> clientType) {
        List<Object> providers = WebClients.createProviders();
        WebClient webClient = WebClient.create(address, providers);
        WebClients.disableSslChecks(webClient);
        WebClients.configureUserAndPassword(webClient, username, password);
        return JAXRSClientFactory.fromClient(webClient, clientType);
    }

    public RepositoryDTO createRepository(CreateRepositoryDTO createRepository) {
        return getApi().createRepository(createRepository);
    }

    protected GitApi getApi() {
        if (api == null) {
            api = createWebClient(GitApi.class);
        }
        return api;
    }
}
