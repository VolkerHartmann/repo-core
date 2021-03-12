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
package edu.kit.datamanager.test;

import edu.kit.datamanager.entities.RepoServiceRole;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 * @author jejkal
 */
public class RepoServiceRoleTest{

  @Test
  public void testFromValue(){
    Assert.assertEquals(RepoServiceRole.fromValue("ROLE_SERVICE_READ"), RepoServiceRole.SERVICE_READ);
    Assert.assertEquals(RepoServiceRole.fromValue("ROLE_SERVICE_WRITE"), RepoServiceRole.SERVICE_WRITE);
    Assert.assertEquals(RepoServiceRole.fromValue("ROLE_SERVICE_ADMINISTRATE"), RepoServiceRole.SERVICE_ADMINISTRATOR);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testFromIllegalValue(){
    RepoServiceRole.fromValue("ROLE_INVALID_TEST");
  }

  @Test
  public void testGetValueMatchesToString(){
    Assert.assertEquals(RepoServiceRole.SERVICE_READ.getValue(), RepoServiceRole.SERVICE_READ.toString());
  }
}
