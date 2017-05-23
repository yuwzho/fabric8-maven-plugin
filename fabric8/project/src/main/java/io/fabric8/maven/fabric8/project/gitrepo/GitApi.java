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

import javax.ws.rs.*;

/**
 * REST API for working with git hosted repositories using back ends like
 * <a href="http://gogs.io/">gogs</a> or <a href="http://github.com/">github</a>
 */
@Path("api/v1")
@Produces("application/json")
@Consumes("application/json")
public interface GitApi {

    @POST
    @Path("user/repos")
    RepositoryDTO createRepository(CreateRepositoryDTO dto);
}
