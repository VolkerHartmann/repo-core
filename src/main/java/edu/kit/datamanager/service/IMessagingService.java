/*
 * Copyright 2018 Karlsruhe Institute of Technology.
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
 */
package edu.kit.datamanager.service;

import edu.kit.datamanager.entities.messaging.IAMQPSubmittable;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 *
 * @author jejkal
 */
public interface IMessagingService extends HealthIndicator{

  /**
   * Send a message via the underlaying messaging system. The message itself
   * provides routing information as well as the possibility to be serialized
   * into JSON.
   *
   * @param msg The message to send.
   */
  void send(IAMQPSubmittable msg);

}
