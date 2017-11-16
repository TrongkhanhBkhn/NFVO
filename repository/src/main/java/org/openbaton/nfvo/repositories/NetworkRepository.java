/*
 * Copyright (c) 2016 Open Baton (http://www.openbaton.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.openbaton.nfvo.repositories;

import org.openbaton.catalogue.nfvo.networks.BaseNetwork;
import org.springframework.data.repository.CrudRepository;

/** Created by lto on 06/05/15. */
public interface NetworkRepository extends CrudRepository<BaseNetwork, String> {
  //    List<Network> findByProjectId(String projectId);
}
