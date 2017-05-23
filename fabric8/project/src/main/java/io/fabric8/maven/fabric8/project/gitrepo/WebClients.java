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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.jaxrs.cfg.Annotations;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import io.fabric8.maven.fabric8.project.TrustEverythingSSLTrustManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.cxf.configuration.jsse.TLSClientParameters;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.transport.http.HTTPConduit;

import javax.net.ssl.TrustManager;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 */
public class WebClients {

    public static void disableSslChecks(WebClient webClient) {
        HTTPConduit conduit = WebClient.getConfig(webClient)
                .getHttpConduit();

        TLSClientParameters params = conduit.getTlsClientParameters();

        if (params == null) {
            params = new TLSClientParameters();
            conduit.setTlsClientParameters(params);
        }

        params.setTrustManagers(new TrustManager[]{new TrustEverythingSSLTrustManager()});
        params.setDisableCNCheck(true);
    }

    public static void configureUserAndPassword(WebClient webClient, String username, String password) {
        if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)) {
            HTTPConduit conduit = WebClient.getConfig(webClient).getHttpConduit();
            conduit.getAuthorization().setUserName(username);
            conduit.getAuthorization().setPassword(password);
        }
    }

    public static ClientRequestFilter createPrivateTokenFilter(final String privateToken) {
        ClientRequestFilter interceptor = new ClientRequestFilter() {

            @Override
            public void filter(ClientRequestContext requestContext) throws IOException {
                requestContext.getHeaders().add("PRIVATE-TOKEN", privateToken);
            }
        };
        return interceptor;
    }

    public static void configureAuthorization(WebClient webClient, String username, String authorizationType, String authorization) {
        HTTPConduit conduit = WebClient.getConfig(webClient).getHttpConduit();
        if (StringUtils.isNotBlank(username)) {
            conduit.getAuthorization().setUserName(username);
        }
        if (StringUtils.isNotBlank(authorizationType) && StringUtils.isNotBlank(authorization)) {
            conduit.getAuthorization().setUserName(username);
            conduit.getAuthorization().setAuthorizationType(authorizationType);
            conduit.getAuthorization().setAuthorization(authorization);
        }
    }

    public static List<Object> createProviders() {
        List<Object> providers = new ArrayList<>();
        Annotations[] annotationsToUse = JacksonJaxbJsonProvider.DEFAULT_ANNOTATIONS;
        ObjectMapper objectMapper = createObjectMapper();
        providers.add(new JacksonJaxbJsonProvider(objectMapper, annotationsToUse));
        providers.add(new ExceptionResponseMapper());
        return providers;
    }

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }
}
